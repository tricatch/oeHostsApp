package tricatch.oehostsapp.chrome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts Chrome on this machine with the launcher panel's flags. A Chrome that is already running
 * on the same profile would take the new window over and ignore every flag, so it is found first
 * and, once the user agrees, closed.
 *
 * <p>"Same profile" is the {@code --user-data-dir} value: a launch with one collides with the
 * Chrome started with the same directory, a launch without one with every Chrome started without
 * one (the default profile). Chrome's own child processes ({@code --type=...}) are left out.
 *
 * <p>The flags can come from a profile someone else wrote (an oeHub import), so every word is
 * checked against {@link ChromeArgs#DENIED} and the allowed start URLs before anything starts.
 */
public final class ChromeLauncher {

    public enum Result {
        /** Chrome was started. */
        LAUNCHED,
        /** A Chrome on the same profile is running; launch again with {@code closeRunning} to replace it. */
        RUNNING,
        NOT_FOUND,
        /** A word is not an allowed flag or start URL; nothing was started. */
        REFUSED,
        FAILED
    }

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean WIN = OS.contains("win");
    private static final boolean MAC = OS.contains("mac");
    /** How long listing the running Chrome processes may take before it is given up. */
    private static final long PROCESS_LIST_TIMEOUT_S = 20;
    /** Chrome's main executable on Linux (the packages' install folder), as {@code ps} shows it. */
    private static final String LINUX_CHROME = "/opt/google/chrome/chrome";
    private static final Pattern UDD = Pattern.compile("--user-data-dir=(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern ENV = Pattern.compile("%([A-Za-z0-9_()]+)%");

    private ChromeLauncher() {}

    /**
     * The launch words for {@link ChromeArgs#parts} (whose values carry the quotes of a command
     * line) and an optional start URL, passed as it is. Null when a part does not form exactly one
     * word (an open quote) or the URL is not a permitted start URL ({@link ChromeArgs#isStartUrl}).
     */
    public static List<String> words(List<String> parts, String url) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            List<String> w = split(p);
            if (w == null || w.size() != 1) return null;
            out.add(w.get(0));
        }
        if (url != null && !url.isBlank()) {
            String u = url.strip();
            if (!ChromeArgs.isStartUrl(u)) return null;
            out.add(u);
        }
        return out;
    }

    /**
     * Command-line splitting without a shell: double quotes group, nothing is expanded or escaped.
     * Null when a quote is left open.
     */
    static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false, have = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                inQuote = !inQuote;
                have = true;
            } else if (!inQuote && Character.isWhitespace(ch)) {
                if (have) {
                    words.add(sb.toString());
                    sb.setLength(0);
                    have = false;
                }
            } else {
                sb.append(ch);
                have = true;
            }
        }
        if (inQuote) return null;
        if (have) words.add(sb.toString());
        return words;
    }

    /** True when the word is a permitted flag or a permitted start URL. */
    static boolean allowed(String word) {
        if (word.chars().anyMatch(c -> c < 0x20)) return false;
        if (word.startsWith("--")) return word.matches("--[A-Za-z0-9][A-Za-z0-9-]*(=.*)?") && !ChromeArgs.isDenied(word);
        return ChromeArgs.isStartUrl(word);
    }

    /**
     * Starts Chrome with {@code words}. When a Chrome on the same profile is running, returns
     * {@link Result#RUNNING} unless {@code closeRunning}, in which case that Chrome is closed first.
     * Blocks (finding processes takes about a second on Windows): call it off the UI thread.
     */
    public static Result launch(List<String> words, boolean closeRunning) {
        if (words == null) return Result.REFUSED;
        List<String> args = new ArrayList<>();
        for (String w : words) {
            if (!allowed(w)) return Result.REFUSED;
            args.add(expand(w));
        }
        Optional<String> exe = chrome();
        if (exe.isEmpty()) return Result.NOT_FOUND;

        String udd = null;
        for (String a : args) if (a.startsWith("--user-data-dir=")) udd = a.substring("--user-data-dir=".length());
        List<Long> running = running(udd);
        if (!running.isEmpty()) {
            if (!closeRunning) return Result.RUNNING;
            close(running);
        }

        List<String> cmd = new ArrayList<>();
        if (!MAC) {
            cmd.add(exe.get());
            cmd.addAll(args);
        } else {
            // A new instance through Launch Services, as a Dock click would start it.
            cmd.addAll(List.of("open", "-na", exe.get()));
            if (!args.isEmpty()) {
                cmd.add("--args");
                cmd.addAll(args);
            }
        }
        try {
            new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return Result.LAUNCHED;
        } catch (IOException e) {
            return Result.FAILED;
        }
    }

    /** %VAR% on Windows; $HOME and a leading ~ on macOS (the forms resolveUserDataDir writes). */
    static String expand(String word) {
        if (WIN) {
            Matcher m = ENV.matcher(word);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = System.getenv(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        String home = System.getProperty("user.home");
        String w = word.replace("$HOME", home);
        return w.replace("=~/", "=" + home + "/");
    }

    /** The Chrome executable (Windows, Linux) or app bundle (macOS). */
    static Optional<String> chrome() {
        List<Path> candidates = new ArrayList<>();
        if (WIN) {
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
                String base = System.getenv(env);
                if (base != null) candidates.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe"));
            }
        } else if (MAC) {
            candidates.add(Path.of("/Applications/Google Chrome.app"));
            candidates.add(Path.of(System.getProperty("user.home"), "Applications", "Google Chrome.app"));
        } else {
            candidates.add(Path.of("/usr/bin/google-chrome-stable"));
            candidates.add(Path.of("/usr/bin/google-chrome"));
            candidates.add(Path.of(LINUX_CHROME));
        }
        return candidates.stream().filter(Files::exists).map(Path::toString).findFirst();
    }

    /** Main Chrome processes on the profile {@code udd} (null: the default profile). */
    static List<Long> running(String udd) {
        List<Long> out = new ArrayList<>();
        for (String[] p : processes()) {
            String cmd = p[1];
            if (cmd.contains("--type=")) continue;
            Matcher m = UDD.matcher(cmd);
            String theirs = m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
            if (udd == null ? theirs == null : theirs != null && samePath(udd, theirs)) out.add(Long.parseLong(p[0]));
        }
        return out;
    }

    private static boolean samePath(String a, String b) {
        String x = normalize(a), y = normalize(b);
        return WIN ? x.equalsIgnoreCase(y) : x.equals(y);
    }

    private static String normalize(String p) {
        String s = WIN ? p.replace('/', '\\') : p;
        while (s.length() > 1 && (s.endsWith("\\") || s.endsWith("/"))) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** {pid, command line} of every Chrome process. */
    private static List<String[]> processes() {
        List<String> cmd = WIN
                // Java cannot read another process's command line on Windows; CIM can. The script
                // goes in encoded: Java does not escape the quotes inside a Windows argument.
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", java.util.Base64.getEncoder().encodeToString(
                        ("[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\""
                                + " | ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }").getBytes(StandardCharsets.UTF_16LE)))
                : List.of("ps", "axwwo", "pid=,args=");
        List<String[]> out = new ArrayList<>();
        for (String line : run(cmd).split("\\R")) {
            String l = line.strip();
            if (WIN) {
                int tab = l.indexOf('\t');
                if (tab > 0 && l.substring(0, tab).matches("\\d+")) out.add(new String[]{l.substring(0, tab), l.substring(tab + 1)});
            } else if (MAC ? l.contains("Google Chrome.app/Contents/MacOS/Google Chrome") : l.contains(LINUX_CHROME)) {
                int sp = l.indexOf(' ');
                if (sp > 0 && l.substring(0, sp).matches("\\d+")) out.add(new String[]{l.substring(0, sp), l.substring(sp + 1)});
            }
        }
        return out;
    }

    /**
     * The output of {@code cmd}, or what it printed before {@link #PROCESS_LIST_TIMEOUT_S} ran out
     * (the process is then killed); empty when it cannot start. The output is read on another
     * thread, so a process that hangs cannot block the caller past the timeout.
     */
    private static String run(List<String> cmd) {
        Process proc;
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            return ""; // nothing found: launch as if no Chrome were running
        }
        java.util.concurrent.CompletableFuture<String> text = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }, r -> Thread.ofVirtual().name("chrome-ps").start(r));
        try {
            if (!proc.waitFor(PROCESS_LIST_TIMEOUT_S, TimeUnit.SECONDS)) proc.destroyForcibly();
            return text.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Asks Chrome to close (so it does not report a crash on its next start), then forces what is
     * still running after a few seconds.
     */
    private static void close(List<Long> pids) {
        List<ProcessHandle> handles = new ArrayList<>();
        for (long pid : pids) ProcessHandle.of(pid).ifPresent(handles::add);
        for (ProcessHandle h : handles) {
            if (WIN) {
                // taskkill without /F sends WM_CLOSE; ProcessHandle.destroy would terminate outright.
                try {
                    new ProcessBuilder("taskkill", "/PID", String.valueOf(h.pid())).redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(5, TimeUnit.SECONDS);
                } catch (IOException e) {
                    // forced below
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                h.destroy(); // SIGTERM
            }
        }
        long deadline = System.currentTimeMillis() + 5000;
        for (ProcessHandle h : handles) {
            try {
                h.onExit().get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                h.destroyForcibly();
            }
        }
        for (ProcessHandle h : handles) {
            try {
                h.onExit().get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // gone or stuck: launch anyway
            }
        }
    }
}
