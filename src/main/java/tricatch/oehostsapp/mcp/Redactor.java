package tricatch.oehostsapp.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides secrets in captured traffic before an MCP client sees it. A hidden value becomes
 * {@code hmac:<12 hex>}: HMAC-SHA256 under a key made at random when the app starts and never saved
 * or shown, so a tag cannot be turned back into its value (not even a short one like {@code role=admin}
 * by trying candidates), while the same value keeps the same tag for the life of the process (the
 * client can still tell whether two requests carry the same session).
 *
 * <p>Hidden: every cookie value ({@code Cookie}, {@code Set-Cookie}), the credentials of
 * {@code Authorization}/{@code Proxy-Authorization}, any header, query parameter, form field or JSON
 * member whose name looks secret ({@link #sensitiveName}; everything under such a JSON member), and
 * anything shaped like a JWT wherever it appears. Matching by name is a heuristic: a secret under
 * an innocent name passes.
 */
final class Redactor {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(token|secret|passw|pwd|auth|session|csrf|xsrf|api[-_]?key|apikey|credential|signature|cookie|jwt|bearer|private[-_]?key|otp|nonce).*");
    private static final Pattern SENSITIVE_EXACT = Pattern.compile("(?i)(sid|code|key|sig|pin|ssn|pass)");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    /** A redactor with a fresh random key. */
    Redactor() {
        this(randomKey());
    }

    Redactor(byte[] key) {
        this.key = key.clone();
    }

    private static byte[] randomKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return k;
    }

    /** Whether a header, parameter or JSON member called {@code name} holds a secret by its name. */
    static boolean sensitiveName(String name) {
        if (name == null) return false;
        String n = name.strip();
        return SENSITIVE.matcher(n).matches() || SENSITIVE_EXACT.matcher(n).matches();
    }

    /** The tag that stands for {@code value}; an empty value stays empty. */
    String tag(String value) {
        if (value == null || value.isEmpty()) return value;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder("hmac:");
            for (int i = 0; i < 6; i++) b.append(String.format("%02x", h[i] & 0xff));
            return b.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Free text: every JWT-shaped run replaced by its tag. */
    String text(String s) {
        if (s == null || s.indexOf("eyJ") < 0) return s;
        Matcher m = JWT.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(tag(m.group())));
        m.appendTail(b);
        return b.toString();
    }

    /** Raw header lines: the first is the request or status line, the rest {@code Name: value}. */
    List<String> headers(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) out.add(i == 0 ? startLine(lines.get(0)) : header(lines.get(i)));
        return out;
    }

    /** {@code GET /path?x=1 HTTP/1.1} with the target's query hidden; a status line passes. */
    String startLine(String line) {
        String[] p = line.split(" ", 3);
        if (p.length == 3 && !p[0].startsWith("HTTP/")) return p[0] + " " + url(p[1]) + " " + p[2];
        return text(line);
    }

    String header(String line) {
        int c = line.indexOf(':');
        if (c <= 0) return text(line);
        String name = line.substring(0, c).strip();
        String value = line.substring(c + 1).strip();
        String n = name.toLowerCase(Locale.ROOT);
        String v = switch (n) {
            case "cookie" -> cookie(value);
            case "set-cookie" -> setCookie(value);
            case "authorization", "proxy-authorization" -> authorization(value);
            case "referer", "location", "content-location", "origin" -> url(value);
            default -> sensitiveName(name) ? tag(value) : text(value);
        };
        return name + ": " + v;
    }

    /** {@code a=1; b=2}: every value hidden, the names kept. */
    String cookie(String value) {
        StringBuilder b = new StringBuilder();
        for (String part : value.split(";")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            if (!b.isEmpty()) b.append("; ");
            int eq = p.indexOf('=');
            b.append(eq < 0 ? tag(p) : p.substring(0, eq + 1) + tag(p.substring(eq + 1)));
        }
        return b.toString();
    }

    /** {@code name=value; Path=/; HttpOnly}: the value hidden, the attributes kept. */
    String setCookie(String value) {
        int semi = value.indexOf(';');
        String first = semi < 0 ? value : value.substring(0, semi);
        int eq = first.indexOf('=');
        String masked = eq < 0 ? tag(first.strip()) : first.substring(0, eq + 1) + tag(first.substring(eq + 1).strip());
        return semi < 0 ? masked : masked + value.substring(semi);
    }

    /** {@code Bearer abc}: the scheme kept, the credentials hidden. */
    String authorization(String value) {
        int sp = value.indexOf(' ');
        return sp < 0 ? tag(value) : value.substring(0, sp) + " " + tag(value.substring(sp + 1).strip());
    }

    /** A URL or path: secret-named query parameters hidden, JWTs anywhere hidden. */
    String url(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return text(url);
        int hash = url.indexOf('#', q);
        String query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
        String tail = hash < 0 ? "" : text(url.substring(hash));
        return text(url.substring(0, q)) + "?" + form(query) + tail;
    }

    /** {@code a=1&password=x}: secret-named values hidden (by their decoded names). */
    String form(String s) {
        StringBuilder b = new StringBuilder();
        String[] pairs = s.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            if (i > 0) b.append('&');
            int eq = pair.indexOf('=');
            if (eq < 0) {
                b.append(text(pair));
                continue;
            }
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            b.append(name).append('=').append(sensitiveName(decode(name)) ? tag(decode(value)) : text(value));
        }
        return b.toString();
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** A body already decoded to text, by its Content-Type: form fields, JSON members, or free text. */
    String body(String text, String contentType) {
        if (text == null) return null;
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (t.startsWith("application/x-www-form-urlencoded")) return form(text.strip());
        String s = text.strip();
        if (t.contains("json") || s.startsWith("{") || s.startsWith("[")) {
            try {
                JsonNode n = JSON.readTree(s);
                if (n != null && n.isContainerNode()) return JSON.writeValueAsString(json(n, false));
            } catch (JsonProcessingException ignored) {
                // not JSON after all: free text
            }
        }
        return text(text);
    }

    private JsonNode json(JsonNode n, boolean secret) {
        if (n.isObject()) {
            ObjectNode o = (ObjectNode) n;
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = o.fieldNames(); it.hasNext(); ) names.add(it.next());
            for (String name : names) o.set(name, json(o.get(name), secret || sensitiveName(name)));
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = (ArrayNode) n;
            for (int i = 0; i < a.size(); i++) a.set(i, json(a.get(i), secret));
            return a;
        }
        if (n.isNull()) return n;
        if (secret) return TextNode.valueOf(tag(n.asText()));
        return n.isTextual() ? TextNode.valueOf(text(n.asText())) : n;
    }
}
