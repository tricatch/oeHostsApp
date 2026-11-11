package tricatch.oehubapp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Expected strings follow static/js/chrome-launch-args.js and util.js. */
class ChromeArgsTest {

    private static ChromeArgs.Options base() {
        ChromeArgs.Options o = new ChromeArgs.Options();
        o.hrrEnabled = true;
        o.rulesStr = "MAP foo.oe 127.0.0.1,MAP bar.oe 10.0.0.1";
        return o;
    }

    @Test
    void deniedFlagsAndCleaning() {
        assertTrue(ChromeArgs.isDenied("--no-sandbox"));
        assertTrue(ChromeArgs.isDenied("--remote-debugging-port=9222"));
        assertTrue(ChromeArgs.isDenied("--IGNORE-CERTIFICATE-ERRORS-spki-list=x"));
        assertFalse(ChromeArgs.isDenied("--disable-sync"));
        assertEquals(List.of("--disable-sync", "--lang=ko"),
                ChromeArgs.extraArgLines("--disable-sync\n// comment\n--no-sandbox\n\n  --lang=\"ko\"  "));
        assertEquals("abc", ChromeArgs.clean("a\"b\u0001c"));
    }

    @Test
    void launchWordsOrderAndUrl() {
        ChromeArgs.Options o = base();
        o.incognito = true;
        o.uaEnabled = true;
        o.userAgent = "UA \"x\"";
        o.uddEnabled = true;
        o.userDataDir = ChromeArgs.resolveUserDataDir(ChromeArgs.DEFAULT_USER_DATA_DIR, true);
        assertEquals("%USERPROFILE%\\oe-chrome", o.userDataDir);
        assertEquals("$HOME/oe-chrome", ChromeArgs.resolveUserDataDir(ChromeArgs.DEFAULT_USER_DATA_DIR, false));
        List<String> words = ChromeLauncher.words(ChromeArgs.parts(o), "https://foo.oe/a'b");
        assertEquals(List.of("--host-resolver-rules=MAP foo.oe 127.0.0.1,MAP bar.oe 10.0.0.1", "--incognito",
                "--user-agent=UA x", "--user-data-dir=%USERPROFILE%\\oe-chrome",
                "https://foo.oe/a'b"), words);
        assertTrue(words.stream().allMatch(ChromeLauncher::allowed));
    }

    @Test
    void launcherRefusesDangerousWords() {
        assertNull(ChromeLauncher.words(List.of("--user-data-dir=\"abc"), null));
        for (String w : List.of("--renderer-cmd-prefix=/bin/sh", "--Remote-Debugging-Port=9222", "--no-sandbox",
                "file:///etc/passwd", "javascript:alert(1)", "$(touch x)", "--a\u0001b")) {
            assertFalse(ChromeLauncher.allowed(w), w);
        }
        assertTrue(ChromeLauncher.allowed("about:blank"));
        assertNull(ChromeLauncher.words(List.of(), "--remote-debugging-port=9222"));
        assertNull(ChromeLauncher.words(List.of(), "foo.oe"));
        assertTrue(ChromeLauncher.allowed("--disable-sync"));
    }

    @Test
    void previewMasksTheRules() {
        ChromeArgs.Options o = base();
        o.urlEnabled = true;
        o.url = "https://foo.oe";
        assertEquals(List.of("--host-resolver-rules \"MAP ...\"", "  https://foo.oe"), ChromeArgs.previewLines(o, true));
    }

    @Test
    void directCommandLines() {
        ChromeArgs.Options o = new ChromeArgs.Options();
        o.uddEnabled = true;
        o.userDataDir = "%USERPROFILE%\\oe-chrome";
        o.incognito = true;
        assertEquals("Start-Process \"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" -ArgumentList '--incognito', "
                + "([Environment]::ExpandEnvironmentVariables('--user-data-dir=\"%USERPROFILE%\\oe-chrome\"'))",
                ChromeArgs.commandLine(o, true));
        o.userDataDir = "$HOME/oe-chrome";
        assertEquals("open -na \"Google Chrome\" --args --incognito --user-data-dir=\"$HOME/oe-chrome\"", ChromeArgs.commandLine(o, false));
        assertEquals("open -na \"Google Chrome\"", ChromeArgs.commandLine(new ChromeArgs.Options(), false));
        ChromeArgs.Options u = new ChromeArgs.Options();
        u.urlEnabled = true;
        u.url = "https://foo.oe/a?x=1&y='2'%20";
        assertEquals("Start-Process \"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" -ArgumentList "
                + "'https://foo.oe/a?x=1&y=''2''%20'", ChromeArgs.commandLine(u, true));
        assertEquals("open -na \"Google Chrome\" --args 'https://foo.oe/a?x=1&y='\\''2'\\''%20'",
                ChromeArgs.commandLine(u, false));
    }

    @Test
    void killAndLaunchIsOneLine() {
        String win = ChromeArgs.killAndLaunch(true, "LAUNCH", "%USERPROFILE%\\oe-chrome", "Close it?");
        assertFalse(win.contains("\n"));
        assertTrue(win.startsWith("$p = @(Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\""));
        assertTrue(win.endsWith("LAUNCH }"));
        String mac = ChromeArgs.killAndLaunch(false, "LAUNCH", "$HOME/oe-chrome", "Close it?");
        assertTrue(mac.startsWith("p=\"MacOS/Google Chrome .*--user-data-dir=$HOME/oe-chrome( |\\$)\"; "));
        assertTrue(mac.endsWith("LAUNCH; fi"));
    }
}
