package tricatch.oehostsapp.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A short device name for a User-Agent, for the list of devices on the proxy service page:
 * "SM-S918N (Android 14) · Chrome", "iPhone (iOS 18.1) · Safari", "Mac · Firefox", "curl 8.7.1".
 * Only a hint for the user; the full User-Agent stays available as the row's tooltip.
 */
final class UaSummary {

    private static final Pattern ANDROID = Pattern.compile("Android\\s*([\\d.]*)([^)]*)\\)");
    private static final Pattern IOS = Pattern.compile("(?:iPhone )?OS (\\d+(?:_\\d+)*) like Mac OS X");
    private static final Pattern PRODUCT = Pattern.compile("^([A-Za-z][\\w.-]*)(?:/([\\w.-]+))?");

    private UaSummary() {}

    static String of(String ua) {
        if (ua == null || ua.isBlank()) return "-";
        String device = device(ua);
        String client = browser(ua);
        if (client == null && device == null) client = product(ua);
        if (device == null) return client;
        return client == null ? device : device + " · " + client;
    }

    /** The phone model or OS, or null for a client that names neither (curl, an app's own UA). */
    private static String device(String ua) {
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) {
            String kind = ua.contains("iPad") ? "iPad" : ua.contains("iPod") ? "iPod" : "iPhone";
            Matcher m = IOS.matcher(ua);
            return m.find() ? kind + " (iOS " + m.group(1).replace('_', '.') + ")" : kind;
        }
        Matcher a = ANDROID.matcher(ua);
        if (a.find()) {
            String version = a.group(1).isEmpty() ? "Android" : "Android " + a.group(1);
            String model = null;
            for (String part : a.group(2).split(";")) {
                String p = part.trim();
                int build = p.indexOf(" Build/");
                if (build >= 0) p = p.substring(0, build).trim();
                // Chrome's reduced User-Agent sends "K" instead of the model; "wv" marks a WebView.
                if (!p.isEmpty() && !p.equals("K") && !p.equals("wv") && !p.startsWith("Linux")) model = p;
            }
            return model == null ? version : model + " (" + version + ")";
        }
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) return "Mac";
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("CrOS")) return "ChromeOS";
        if (ua.contains("Linux") || ua.contains("X11")) return "Linux";
        return null;
    }

    /** The browser, or null when the User-Agent is not a browser's. */
    private static String browser(String ua) {
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) return "Edge";
        if (ua.contains("SamsungBrowser/")) return "Samsung Internet";
        if (ua.contains("Whale/")) return "Whale";
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) return "Firefox";
        if (ua.contains("CriOS/") || ua.contains("Chrome/")) return "Chrome";
        if (ua.contains("Safari/") || ua.contains("AppleWebKit/")) return "Safari";
        return null;
    }

    /** The first product token ("curl/8.7.1" -> "curl 8.7.1"), or the start of the User-Agent. */
    private static String product(String ua) {
        Matcher m = PRODUCT.matcher(ua.strip());
        if (!m.find()) return ua.length() > 40 ? ua.substring(0, 40) + "…" : ua;
        return m.group(2) == null ? m.group(1) : m.group(1) + " " + m.group(2);
    }
}
