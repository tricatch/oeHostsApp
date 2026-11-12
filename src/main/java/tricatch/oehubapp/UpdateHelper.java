package tricatch.oehubapp;

import java.io.File;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * Second half of a self-update ({@link Update}), run from the downloaded jar in its own JVM:
 * <pre>java -cp oehubapp.jar.new tricatch.oehubapp.UpdateHelper --apply PID TARGET -- COMMAND...</pre>
 * Waits for process {@code PID} (the old app) to end, replaces {@code TARGET} with the jar this
 * class was loaded from, and starts {@code COMMAND} (the app again). If the jar cannot be replaced
 * the old version is started anyway, so the user is never left without the app.
 * <p>
 * The OLD version builds this command line and the NEW version's class runs it, so the argument
 * format is a contract between releases: keep it unchanged. Uses only the JDK (no logging library,
 * no AWT); its output goes to {@code oehubapp-update.log} next to the jar.
 */
public final class UpdateHelper {

    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String UNINSTALL_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\oeHubApp";

    private UpdateHelper() {}

    public static void main(String[] args) throws Exception {
        int sep = Arrays.asList(args).indexOf("--");
        if (args.length < 4 || !"--apply".equals(args[0]) || sep != 3 || sep == args.length - 1) {
            System.err.println("usage: UpdateHelper --apply PID TARGET -- COMMAND...");
            System.exit(2);
        }
        long pid = Long.parseLong(args[1]);
        Path target = Path.of(args[2]).toAbsolutePath();
        List<String> command = List.of(Arrays.copyOfRange(args, sep + 1, args.length));
        Path source = Path.of(UpdateHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        log("update " + target + " from " + source);
        var old = ProcessHandle.of(pid);
        if (old.isPresent()) {
            try {
                old.get().onExit().get(60, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                // The old app is still up: replacing its jar or starting a second one would fail.
                log("process " + pid + " did not exit; update not applied");
                System.exit(1);
            }
        }

        if (replace(source, target)) {
            log("installed " + versionOf(target));
            if (WIN) updateUninstallEntry(target);
        }
        start(command, target.getParent());
    }

    /**
     * Copies {@code source} beside {@code target}, then moves it over {@code target}. Copying, not
     * moving, because this JVM holds {@code source} open (Windows refuses to rename it). Retries
     * for a few seconds: Windows may keep the old jar locked briefly after its process ends.
     */
    private static boolean replace(Path source, Path target) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            for (int attempt = 1; ; attempt++) {
                try {
                    try {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return true;
                } catch (FileSystemException e) {
                    if (attempt >= 40) throw e;
                    Thread.sleep(250);
                }
            }
        } catch (Exception e) {
            log("could not replace " + target + ": " + e);
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // left behind; harmless
            }
            return false;
        }
    }

    /** Starts the app again, its output where the launcher would put it. */
    private static void start(List<String> command, Path dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
            // install.sh's launcher writes the app's output to oehubapp.log next to the jar;
            // Windows runs javaw.exe, which has no console.
            File appLog = dir.resolve("oehubapp.log").toFile();
            if (!WIN && appLog.isFile()) {
                pb.redirectErrorStream(true).redirectOutput(appLog);
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            }
            pb.start();
            log("started " + String.join(" ", command));
        } catch (Exception e) {
            log("could not start the app: " + e);
        }
    }

    /**
     * Sets the version shown in Settings > Apps, when install.ps1 registered this install folder
     * there. Uses reg.exe (signed, part of Windows), not a script.
     */
    private static void updateUninstallEntry(Path target) {
        String version = versionOf(target);
        if (version == null) return;
        try {
            Process q = new ProcessBuilder("reg", "query", UNINSTALL_KEY, "/v", "InstallLocation")
                    .redirectErrorStream(true).start();
            String out = new String(q.getInputStream().readAllBytes());
            q.waitFor(10, TimeUnit.SECONDS);
            String dir = target.getParent().toString();
            if (q.exitValue() != 0 || !out.toLowerCase(Locale.ROOT).contains(dir.toLowerCase(Locale.ROOT))) return;
            Process a = new ProcessBuilder("reg", "add", UNINSTALL_KEY, "/v", "DisplayVersion", "/t", "REG_SZ",
                    "/d", version, "/f").redirectErrorStream(true).start();
            a.getInputStream().readAllBytes();
            a.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log("could not update the Settings > Apps entry: " + e);
        }
    }

    private static String versionOf(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            var mf = jf.getManifest();
            return mf == null ? null : mf.getMainAttributes().getValue("Implementation-Version");
        } catch (Exception e) {
            return null;
        }
    }

    private static void log(String s) {
        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + s);
    }
}
