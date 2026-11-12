package tricatch.oehostsapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chrome launch flags, ported from oeHub's static/js/chrome-launch-args.js and the helpers in
 * util.js (resolveUDD, wrapDelayedUrl, winArgQuote, macArgQuote), so it builds the same strings
 * as oeHub; {@link ChromeLauncher} starts Chrome with them.
 */
public final class ChromeArgs {

    /** Default --user-data-dir before ${USERHOME} is resolved (hosts.html). */
    public static final String DEFAULT_USER_DATA_DIR = "${USERHOME}/oe-chrome";

    /** DENIED_CHROME_FLAGS; a trailing * matches any suffix. Keep in step with chrome-launch-args.js. */
    static final List<String> DENIED = List.of(
            "renderer-cmd-prefix", "gpu-launcher", "utility-cmd-prefix", "zygote-cmd-prefix",
            "plugin-launcher", "ppapi-plugin-launcher", "nacl-gdb", "nacl-gdb-script", "browser-subprocess-path",
            "load-extension", "disable-extensions-except", "load-component-extension",
            "remote-debugging-*", "remote-allow-origins", "enable-automation",
            "no-sandbox", "disable-gpu-sandbox", "disable-setuid-sandbox", "disable-web-security",
            "disable-site-isolation-trials", "allow-file-access-from-files", "allow-running-insecure-content",
            "ignore-certificate-errors*", "proxy-pac-url", "proxy-auto-detect", "js-flags",
            "utility-and-browser-sandbox-cmd-prefix", "ppapi-flash-path", "enable-logging", "log-file");

    private static final Pattern FLAG = Pattern.compile("^--([A-Za-z0-9][A-Za-z0-9-]*)(?:=|$)");
    private static final Pattern UNSAFE = Pattern.compile("[\"\\x00-\\x1f]");

    /** Everything the launcher panel decides; a plain value holder. */
    public static final class Options {
        public boolean devTools;
        public boolean incognito;
        public boolean extraArgsEnabled;
        public String extraArgs = "";
        public boolean hrrEnabled;
        /** {@code MAP host ip} entries joined by commas (already resolved). */
        public String rulesStr = "";
        public boolean proxyServerEnabled;
        public String proxyServer = "";
        public boolean uddEnabled;
        /** Already resolved for the target OS (see {@link #resolveUserDataDir}). */
        public String userDataDir = "";
        public boolean uaEnabled;
        public String userAgent = "";
        public boolean urlEnabled;
        public String url = "";
    }

    private ChromeArgs() {}

    public static boolean isDenied(String line) {
        Matcher m = FLAG.matcher(line);
        if (!m.find()) return false;
        String name = m.group(1).toLowerCase(Locale.ROOT);
        for (String p : DENIED) {
            if (p.endsWith("*") ? name.startsWith(p.substring(0, p.length() - 1)) : name.equals(p)) return true;
        }
        return false;
    }

    /** A quote or control character would end the quoted value early. */
    public static String clean(String v) {
        return UNSAFE.matcher(v == null ? "" : v).replaceAll("");
    }

    public static List<String> extraArgLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : (text == null ? "" : text).split("\n", -1)) {
            String c = clean(l.strip());
            if (!c.isEmpty() && !c.startsWith("//") && !isDenied(c)) out.add(c);
        }
        return out;
    }

    public static String resolveUserDataDir(String path, boolean win) {
        String p = path.replace("${USERHOME}", win ? "%USERPROFILE%" : "$HOME");
        return win ? p.replace('/', '\\') : p.replace('\\', '/');
    }

    /** buildChromeArgParts: sorted flags without the trailing URL. */
    public static List<String> parts(Options o) {
        List<String> parts = new ArrayList<>();
        if (o.devTools) parts.add("--auto-open-devtools-for-tabs");
        if (o.extraArgsEnabled) parts.addAll(extraArgLines(o.extraArgs));
        if (o.hrrEnabled && !o.rulesStr.isEmpty()) parts.add("--host-resolver-rules=\"" + clean(o.rulesStr) + "\"");
        if (o.proxyServerEnabled && !o.proxyServer.isEmpty()) parts.add("--proxy-server=\"" + clean(o.proxyServer) + "\"");
        if (o.incognito) parts.add("--incognito");
        if (o.uddEnabled && !o.userDataDir.isEmpty()) parts.add("--user-data-dir=\"" + clean(o.userDataDir) + "\"");
        if (o.uaEnabled && !o.userAgent.isEmpty()) parts.add("--user-agent=\"" + clean(o.userAgent) + "\"");
        sortLikeJs(parts);
        return parts;
    }

    /** buildChromeArgLines: the per-line preview, with the resolver rules masked. */
    public static List<String> previewLines(Options o, boolean hasRules) {
        List<String> lines = new ArrayList<>();
        if (o.devTools) lines.add("--auto-open-devtools-for-tabs");
        if (o.extraArgsEnabled) lines.addAll(extraArgLines(o.extraArgs));
        if (o.hrrEnabled && hasRules) lines.add("--host-resolver-rules \"MAP ...\"");
        if (o.proxyServerEnabled && !o.proxyServer.isEmpty()) lines.add("--proxy-server=\"" + clean(o.proxyServer) + "\"");
        if (o.incognito) lines.add("--incognito");
        if (o.uddEnabled && !o.userDataDir.isEmpty()) lines.add("--user-data-dir=\"" + o.userDataDir + "\"");
        if (o.uaEnabled && !o.userAgent.isEmpty()) lines.add("--user-agent=\"" + o.userAgent + "\"");
        sortLikeJs(lines);
        if (o.urlEnabled && !o.url.isEmpty()) lines.add("  " + o.url);
        return lines;
    }

    /** JS Array.prototype.sort() default order: by UTF-16 code units. */
    private static void sortLikeJs(List<String> l) {
        Collections.sort(l);
    }

    /**
     * True for a start URL Chrome may be given directly: http(s), about: or chrome:. The URL is passed
     * as it is (no delayed-load wrapper; that only served the oeHub browser extension), so anything
     * else is refused, in particular a value starting with "-" that Chrome would read as a flag.
     */
    public static boolean isStartUrl(String url) {
        return url.matches("(?s)^(https?://|about:|chrome://).*");
    }

    /** Single-quotes a word for a POSIX shell (a URL's ?, &, # and ' stay literal). */
    private static String shQuote(String p) {
        return "'" + p.replace("'", "'\\''") + "'";
    }

    public static String winQuote(String p) {
        return "'" + p.replace("'", "''") + "'";
    }

    public static String macQuote(String p) {
        return p.matches("(?s).*[<>()'].*") ? "\"" + p.replaceAll("([\\\\$`\"])", "\\\\$1") + "\"" : p;
    }

    private static String winLaunchArg(String p) {
        String q = winQuote(p);
        return p.contains("%") ? "([Environment]::ExpandEnvironmentVariables(" + q + "))" : q;
    }

    /** buildChromeCommandLine: the launch as a command to paste (o.userDataDir resolved for that OS). */
    public static String commandLine(Options o, boolean win) {
        List<String> parts = parts(o);
        String url = o.urlEnabled && !o.url.isEmpty() ? o.url : null;
        if (win) {
            String exe = "Start-Process \"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\"";
            List<String> q = new ArrayList<>();
            for (String p : parts) q.add(winLaunchArg(p));
            // Plain quoting: a URL's %xx escapes must not go through ExpandEnvironmentVariables.
            if (url != null) q.add(winQuote(url));
            if (q.isEmpty()) return exe;
            return exe + " -ArgumentList " + String.join(", ", q);
        }
        List<String> q = new ArrayList<>();
        for (String p : parts) q.add(macQuote(p));
        if (url != null) q.add(shQuote(url));
        if (q.isEmpty()) return "open -na \"Google Chrome\"";
        return "open -na \"Google Chrome\" --args " + String.join(" ", q);
    }

    private static String regexLiteral(String s) {
        return s.replaceAll("[.*+?^${}()|\\[\\]\\\\]", "\\\\$0");
    }

    /**
     * buildChromeKillLaunchCommand: closes the Chrome a fresh launch would collide with (asking
     * first), then launches. One line on purpose, see the JS original.
     */
    public static String killAndLaunch(boolean win, String launchCmd, String userDataDir, String prompt) {
        String fullPrompt = prompt + " [y/N] ";
        if (win) {
            String filter = "$_.CommandLine -notmatch '--type='";
            if (userDataDir != null && !userDataDir.isEmpty()) {
                String dir = "[regex]::Escape(([Environment]::ExpandEnvironmentVariables(" + winQuote(userDataDir) + ")))";
                filter += " -and $_.CommandLine -match ('--user-data-dir=\"?' + " + dir + " + '(?:\"|\\s|$)')";
            }
            return "$p = @(Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" | Where-Object { " + filter + " }); "
                    + "if ($p.Count -eq 0 -or (Read-Host " + winQuote(fullPrompt.stripTrailing()) + ") -eq 'y') { "
                    + "if ($p) { $p | ForEach-Object { taskkill /PID $_.ProcessId *> $null }; "
                    + "Wait-Process -Id $p.ProcessId -Timeout 10 -ErrorAction SilentlyContinue; "
                    + "Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1 }; "
                    + launchCmd + " }";
        }
        String sel, pattern;
        if (userDataDir != null && !userDataDir.isEmpty()) {
            List<String> segs = new ArrayList<>();
            for (String seg : userDataDir.split(Pattern.quote("$HOME"), -1)) {
                segs.add(regexLiteral(seg).replaceAll("([\"`$])", "\\\\$1"));
            }
            sel = "-f --";
            pattern = "\"MacOS/Google Chrome .*--user-data-dir=" + String.join("$HOME", segs) + "( |\\$)\"";
        } else {
            sel = "-x";
            pattern = "\"Google Chrome\"";
        }
        String quoted = "'" + fullPrompt.replace("'", "'\\''") + "'";
        return "p=" + pattern + "; "
                + "if ! pgrep " + sel + " \"$p\" >/dev/null || { printf '%s' " + quoted + "; read a; [ \"$a\" = y ] || [ \"$a\" = Y ]; }; then "
                + "if pkill " + sel + " \"$p\"; then i=0; while pgrep " + sel + " \"$p\" >/dev/null && [ $i -lt 20 ]; do sleep 0.5; i=$((i+1)); done; "
                + "pkill -9 " + sel + " \"$p\"; sleep 1; fi; "
                + launchCmd + "; fi";
    }
}
