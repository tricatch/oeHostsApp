package tricatch.oehostsapp.update;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.OeHostsApp;
import tricatch.oehostsapp.UpdateHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Update check and self-update from the GitHub releases of the app.
 * <p>
 * The latest version is the tag that {@code <repo>/releases/latest} redirects to (no GitHub API,
 * so no rate limit or JSON). An update downloads that release's {@code oehostsapp.jar} next to the
 * installed jar as {@code oehostsapp.jar.new}, checks it against the release's
 * {@code SHA256SUMS.txt}, then starts {@link UpdateHelper} from the new jar and quits: the helper
 * waits for this process to end (Windows cannot replace a jar in use), puts the new jar in place
 * and starts the app again with the same Java, JVM options and arguments.
 * <p>
 * Only a jar run with {@code java -jar} from a writable folder (the installers' layout) can update
 * itself; an IDE or Gradle run only reports the latest version.
 */
public final class Update {

    private static final Logger log = LoggerFactory.getLogger(Update.class);

    /** The releases come from here; {@code -Doehostsapp.update.repo=<url>} points at a test server. */
    static volatile String repo = System.getProperty("oehostsapp.update.repo", "https://github.com/tricatch/oeHostsApp");
    static final String JAR_NAME = "oehostsapp.jar";
    static final String NEW_SUFFIX = ".new";
    private static final Pattern TAG = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.\\-]*");

    public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, RESTARTING, FAILED }

    /**
     * @param latest the latest release's version, once known
     * @param percent download progress, 0-100
     * @param error why the last check (FAILED) or download (AVAILABLE) failed; null otherwise
     */
    public record Status(State state, String latest, int percent, String error) {}

    private static volatile Status status = new Status(State.IDLE, null, 0, null);
    private static final Listeners listeners = new Listeners();
    private static volatile String[] appArgs = new String[0];

    private Update() {}

    /** The app's command-line arguments, passed again when it restarts after an update. */
    public static void setAppArgs(String[] args) {
        appArgs = args.clone();
    }

    public static Status status() {
        return status;
    }

    /** Runs {@code r} (on the thread that changed it) whenever the status changes: the About page and the navbar's gear. */
    public static Listeners.Subscription onChange(Runnable r) {
        return listeners.add(r);
    }

    private static void set(Status s) {
        status = s;
        listeners.fire();
    }

    /** True when this run can replace its own jar (see the class comment). */
    public static boolean canSelfUpdate() {
        return installedJar() != null;
    }

    /** The jar this app runs from, when it is a file in a writable folder; null otherwise. */
    public static Path installedJar() {
        try {
            Path p = Path.of(Update.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(p) || !p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return null;
            Path dir = p.toAbsolutePath().getParent();
            return dir != null && Files.isWritable(dir) ? p.toAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes a downloaded jar left from an earlier update (applied or not). */
    public static void cleanUp() {
        Path jar = installedJar();
        if (jar == null) return;
        try {
            Files.deleteIfExists(jar.resolveSibling(JAR_NAME + NEW_SUFFIX));
        } catch (IOException ignored) {
            // still held by the helper that is exiting; the next start or update removes it
        }
    }

    /** Looks for a newer release in the background; does nothing while a check or update runs. */
    public static synchronized void checkInBackground() {
        State s = status.state();
        if (s == State.CHECKING || s == State.DOWNLOADING || s == State.RESTARTING) return;
        set(new Status(State.CHECKING, status.latest(), 0, null));
        Thread.ofVirtual().name("update-check").start(() -> {
            try {
                String latest = latestVersion();
                boolean newer = isNewer(latest, OeHostsApp.version());
                log.info("latest release {} (running {})", latest, OeHostsApp.version());
                set(new Status(newer ? State.AVAILABLE : State.UP_TO_DATE, latest, 0, null));
            } catch (Exception e) {
                log.warn("update check failed: {}", reason(e));
                set(new Status(State.FAILED, null, 0, reason(e)));
            }
        });
    }

    /**
     * Downloads the latest release in the background, then starts the helper and calls
     * {@code quit} (which must end the process). On failure the status goes back to AVAILABLE
     * with the error.
     */
    public static synchronized void updateInBackground(Runnable quit) {
        Status s = status;
        Path jar = installedJar();
        if (s.state() != State.AVAILABLE || s.latest() == null || jar == null) return;
        String tag = s.latest();
        set(new Status(State.DOWNLOADING, tag, 0, null));
        Thread.ofVirtual().name("update-download").start(() -> {
            try {
                int[] last = {-1};
                Path fresh = download(tag, jar.getParent(), pct -> {
                    if (pct != last[0]) {
                        last[0] = pct;
                        set(new Status(State.DOWNLOADING, tag, pct, null));
                    }
                });
                startHelper(fresh, jar);
                set(new Status(State.RESTARTING, tag, 100, null));
                log.info("restarting into {}", tag);
                quit.run();
            } catch (Exception e) {
                log.warn("update to {} failed: {}", tag, reason(e));
                try {
                    Files.deleteIfExists(jar.resolveSibling(JAR_NAME + NEW_SUFFIX));
                } catch (IOException ignored) {
                    // removed on the next start
                }
                set(new Status(State.AVAILABLE, tag, 0, reason(e)));
            }
        });
    }

    /** The release page of {@code version}. */
    public static String releaseUrl(String version) {
        return repo + "/releases/tag/" + version;
    }

    // ── Check ───────────────────────────────────────────────────

    /** The tag this app's {@code releases/latest} redirects to. */
    static String latestVersion() throws IOException, InterruptedException {
        return latestVersion(repo);
    }

    /** The tag {@code <repoUrl>/releases/latest} redirects to (a GitHub repository URL). */
    public static String latestVersion(String repoUrl) throws IOException, InterruptedException {
        HttpResponse<Void> r;
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            r = http.send(request(repoUrl + "/releases/latest"), HttpResponse.BodyHandlers.discarding());
        }
        String loc = r.headers().firstValue("location").orElse(null);
        if (r.statusCode() / 100 != 3 || loc == null) throw new IOException("HTTP " + r.statusCode());
        int at = loc.lastIndexOf("/tag/");
        if (at < 0) throw new IOException("no release");
        String tag = URLDecoder.decode(loc.substring(at + 5).replaceAll("[?#].*", ""), StandardCharsets.UTF_8);
        if (!TAG.matcher(tag).matches()) throw new IOException("bad tag " + tag);
        return tag;
    }

    /**
     * True when {@code latest} is a higher version than {@code current}: dot-separated parts,
     * numbers compared as numbers, anything else as text. A pre-release ({@code 1.2.0-rc1}) comes
     * before its release ({@code 1.2.0}). A version that is not a release ({@code dev}) is never older.
     */
    public static boolean isNewer(String latest, String current) {
        if (latest == null || current == null || current.isEmpty() || !Character.isDigit(current.charAt(0))) return false;
        int dl = latest.indexOf('-'), dc = current.indexOf('-');
        int c = compareParts(dl < 0 ? latest : latest.substring(0, dl), dc < 0 ? current : current.substring(0, dc));
        if (c != 0) return c > 0;
        if (dl < 0 || dc < 0) return dl < 0 && dc >= 0; // only the release beats its pre-release
        return compareParts(latest.substring(dl + 1), current.substring(dc + 1)) > 0;
    }

    private static int compareParts(String latest, String current) {
        String[] a = latest.split("\\.");
        String[] b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "0";
            String y = i < b.length ? b[i] : "0";
            int c = x.matches("\\d+") && y.matches("\\d+")
                    ? new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y))
                    : x.compareTo(y);
            if (c != 0) return c;
        }
        return 0;
    }

    // ── Download ────────────────────────────────────────────────

    /**
     * Downloads release {@code tag}'s jar to {@code dir/oehostsapp.jar.new} and checks it: the
     * SHA-256 listed in the release's {@code SHA256SUMS.txt}, the version in its manifest, and that
     * it carries the helper. Returns the file; on any failure nothing is left behind.
     */
    static Path download(String tag, Path dir, IntConsumer progress) throws IOException, InterruptedException {
        Path out = dir.resolve(JAR_NAME + NEW_SUFFIX);
        fetchAsset(repo, tag, JAR_NAME, out, progress);
        try (JarFile jf = new JarFile(out.toFile())) {
            var mf = jf.getManifest();
            String v = mf == null ? null : mf.getMainAttributes().getValue("Implementation-Version");
            if (!tag.equals(v)) throw new IOException("jar version " + v + " is not " + tag);
            if (jf.getEntry(UpdateHelper.class.getName().replace('.', '/') + ".class") == null) {
                throw new IOException("jar has no update helper");
            }
        } catch (IOException e) {
            Files.deleteIfExists(out);
            throw e;
        }
        return out;
    }

    /**
     * Downloads asset {@code asset} of release {@code tag} of {@code repoUrl} to {@code out} and
     * checks its SHA-256 against the release's {@code SHA256SUMS.txt}. On any failure nothing is
     * left at {@code out}.
     */
    public static void fetchAsset(String repoUrl, String tag, String asset, Path out, IntConsumer progress)
            throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            fetchAsset(http, repoUrl, tag, asset, out, progress);
        }
    }

    private static void fetchAsset(HttpClient http, String repoUrl, String tag, String asset, Path out, IntConsumer progress)
            throws IOException, InterruptedException {
        String base = repoUrl + "/releases/download/" + tag + "/";
        HttpResponse<String> sums = http.send(request(base + "SHA256SUMS.txt"), HttpResponse.BodyHandlers.ofString());
        if (sums.statusCode() != 200) throw new IOException("SHA256SUMS.txt: HTTP " + sums.statusCode());
        String expected = expectedSha256(sums.body(), asset);
        if (expected == null) throw new IOException("no checksum for " + asset);

        boolean ok = false;
        try {
            HttpResponse<InputStream> r = http.send(request(base + asset), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                r.body().close();
                throw new IOException(asset + ": HTTP " + r.statusCode());
            }
            long total = r.headers().firstValueAsLong("content-length").orElse(-1);
            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
            try (InputStream in = r.body(); OutputStream os = Files.newOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    os.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    if (total > 0) progress.accept((int) Math.min(99, done * 100 / total));
                }
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equals(expected)) throw new IOException("checksum mismatch");
            progress.accept(100);
            ok = true;
        } finally {
            if (!ok) Files.deleteIfExists(out);
        }
    }

    /** The lower-case SHA-256 of {@code asset} in a {@code sha256sum}-format list. */
    static String expectedSha256(String sums, String asset) {
        for (String line : sums.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[1].replaceFirst("^\\*", "").replaceFirst("^.*/", "").equals(asset)
                    && parts[0].matches("[0-9a-fA-F]{64}")) {
                return parts[0].toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    // ── Restart ─────────────────────────────────────────────────

    /**
     * Starts {@link UpdateHelper} from {@code fresh} with the command that starts this app again:
     * the same Java executable (javaw.exe stays javaw.exe), the same JVM options, {@code -jar}
     * the installed jar, the same app arguments.
     */
    private static void startHelper(Path fresh, Path installed) throws IOException {
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> relaunch = new ArrayList<>();
        relaunch.add(java);
        relaunch.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        relaunch.add("-jar");
        relaunch.add(installed.toString());
        relaunch.addAll(List.of(appArgs));

        List<String> cmd = new ArrayList<>(List.of(java, "-cp", fresh.toString(), UpdateHelper.class.getName(),
                "--apply", String.valueOf(ProcessHandle.current().pid()), installed.toString(), "--"));
        cmd.addAll(relaunch);
        Path logFile = installed.resolveSibling("oehostsapp-update.log");
        new ProcessBuilder(cmd)
                .directory(installed.getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "oeHostsApp/" + OeHostsApp.version()).GET().build();
    }

    public static String reason(Exception e) {
        if (e instanceof java.net.http.HttpTimeoutException) return "timeout";
        if (e instanceof java.net.ConnectException || e instanceof java.nio.channels.UnresolvedAddressException
                || e.getCause() instanceof java.nio.channels.UnresolvedAddressException) {
            return "cannot connect";
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
