package tricatch.oehostsapp.web;

import tricatch.oehostsapp.Messages;
import tricatch.oehostsapp.proxy.Ca;
import tricatch.oehostsapp.proxy.ProxyRuntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The page the web service ({@link ShareServer}) serves so a phone can download the root CA from
 * the QR code. Only the public certificate is served, never the private key.
 */
public final class PhoneSetup {

    private static final Logger log = LoggerFactory.getLogger(PhoneSetup.class);

    /** The setup page's path (the QR code opens it). */
    public static final String PAGE_PATH = "/ssl";

    /** The download path; the {@code .crt} name and the content type make iOS and Android offer to install it. */
    public static final String CA_PATH = "/oehostsapp-ca.crt";

    /** Where the CA certificate is; tests point it at their own file. */
    static volatile Supplier<Path> caCert = ProxyRuntime::caCert;

    private PhoneSetup() {}

    /** The URL the QR code carries: the web service's setup page. */
    public static String pageUrl(String ip, int port) {
        return "http://" + ip + ":" + port + PAGE_PATH;
    }

    /** An HTTP answer, independent of the server that sends it. */
    public record Reply(int status, String contentType, byte[] body, String fileName) {}

    /**
     * The answer to {@code GET path}: the setup page at {@link #PAGE_PATH}, the CA certificate at
     * {@link #CA_PATH}, or null for any other path (the caller answers 404).
     */
    public static Reply respond(String path, String acceptLanguage) {
        Map<String, String> m = Messages.of(lang(acceptLanguage));
        if (path.equals(PAGE_PATH) || path.equals(PAGE_PATH + "/")) {
            return new Reply(200, "text/html; charset=utf-8", page(m).getBytes(StandardCharsets.UTF_8), null);
        }
        if (path.equals(CA_PATH)) {
            byte[] der = caDer();
            if (der == null) return new Reply(404, "text/plain; charset=utf-8", m.get("app.phone.page.missing").getBytes(StandardCharsets.UTF_8), null);
            return new Reply(200, "application/x-x509-ca-cert", der, "oehostsapp-ca.crt");
        }
        return null;
    }

    /** "ko" when the phone prefers Korean over English, else "en". */
    static String lang(String acceptLanguage) {
        if (acceptLanguage == null) return "en";
        for (String part : acceptLanguage.split(",")) {
            String l = part.strip().toLowerCase(Locale.ROOT);
            if (l.startsWith("ko")) return "ko";
            if (l.startsWith("en")) return "en";
        }
        return "en";
    }

    /** The CA certificate in DER (the form both iOS and Android install), or null when there is none. */
    static byte[] caDer() {
        Path cert = caCert.get();
        X509Certificate x = Ca.read(cert);
        if (x == null) return null;
        try {
            return x.getEncoded();
        } catch (Exception e) {
            log.warn("phone setup: cannot read {}: {}", cert, e.getMessage());
            return null;
        }
    }

    /** A small self-contained page for a phone screen (no external resources). */
    static String page(Map<String, String> m) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(m.get("app.phone.page.title")) + "</title><style>"
                + "body{margin:0;background:#f8f9fa;color:#212529;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;line-height:1.55}"
                + "header{background:#a8d878;padding:14px 18px;font-weight:700;font-size:17px;color:#2d4a14}"
                + "main{padding:18px;max-width:560px;margin:0 auto}"
                + ".btn{display:block;text-align:center;background:#dff2b8;border:1px solid #a8d878;border-radius:6px;"
                + "padding:12px;font-weight:600;color:#2d4a14;text-decoration:none;margin:16px 0 22px}"
                + "h2{font-size:15px;margin:18px 0 6px}ol{padding-left:20px;margin:0}li{margin:4px 0}"
                + "p{margin:0 0 8px}.muted{color:#6c757d;font-size:13px}"
                + "</style></head><body><header>oeHostsApp</header><main>"
                + "<p>" + esc(m.get("app.phone.page.desc")) + "</p>"
                + "<a class=\"btn\" href=\"" + CA_PATH + "\">" + esc(m.get("app.phone.page.download")) + "</a>"
                + "<h2>iPhone · iPad</h2>" + steps(m.get("app.phone.steps.ios"))
                + "<h2>Android</h2>" + steps(m.get("app.phone.steps.android"))
                + "<p class=\"muted\" style=\"margin-top:18px\">" + esc(m.get("app.phone.page.note")).replace("\n", "<br>") + "</p>"
                + "</main></body></html>";
    }

    /** "a|b|c" as an ordered list. */
    private static String steps(String s) {
        StringBuilder b = new StringBuilder("<ol>");
        for (String step : s.split("\\|")) b.append("<li>").append(esc(step.strip())).append("</li>");
        return b.append("</ol>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
