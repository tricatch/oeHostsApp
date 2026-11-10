package tricatch.oehubapp;

import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventConsumer;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.event.HttpEventType;
import tricatch.oe.proxy.http.io.HeaderLines;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Subscribes to the embedded proxy's traffic events ({@code HttpEventManager}) and hands each one,
 * converted to plain strings, to a sink. The proxy only builds events while someone is
 * subscribed, so {@link #stop} makes monitoring free again.
 *
 * <p>Events arrive on several worker threads in no guaranteed order (a response may come before
 * its request header); the sink must cope with that.
 */
public final class ProxyMonitor implements HttpEventConsumer {

    /** One proxy event; {@code headers} are the raw lines (first line = request/status line). */
    public record Event(String rid, HttpEventType type, long timestamp, List<String> headers, byte[] body,
                        String stream, Integer opcode, String wsDirection) {}

    private static final int MAX_DECODED_BYTES = 8 * 1024 * 1024;

    private final Consumer<Event> sink;
    private volatile boolean subscribed;

    public ProxyMonitor(Consumer<Event> sink) {
        this.sink = sink;
    }

    public synchronized void start() {
        if (subscribed) return;
        HttpEventManager.getInstance().addEventConsumer(this);
        subscribed = true;
    }

    public synchronized void stop() {
        if (!subscribed) return;
        HttpEventManager.getInstance().removeEventConsumer(this);
        subscribed = false;
    }

    @Override
    public String getClientId() {
        // The identifier every request resolves to (the single default owner, codec-encoded).
        return ReverseProxyServer.getIdentifierCodec().encode(ProxyRuntime.OWNER);
    }

    @Override
    public String getChannelId() {
        return "oehubapp-monitor";
    }

    @Override
    public void process(HttpEvent e) {
        try {
            if (e.getType() == null || e.getRid() == null) return;
            sink.accept(new Event(e.getRid(), e.getType(), e.getTimestamp(), lines(e.getHeaders()), e.getBody(),
                    e.getHttpStream() == null ? null : e.getHttpStream().name(), e.getOpcode(), e.getWsDirection()));
        } catch (RuntimeException ignored) {
            // A monitor must never break event delivery (an exception would unsubscribe it).
        }
    }

    private static List<String> lines(HeaderLines h) {
        List<String> out = new ArrayList<>();
        if (h == null) return out;
        for (int i = 0; i < h.size(); i++) {
            if (h.get(i) != null) out.add(h.get(i).toString().strip());
        }
        return out;
    }

    // ── Display helpers ─────────────────────────────────────────

    /** Value of header {@code name} in raw header lines (the first line is skipped), or null. */
    public static String header(List<String> headers, String name) {
        for (int i = 1; i < headers.size(); i++) {
            String l = headers.get(i);
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).strip().equalsIgnoreCase(name)) return l.substring(c + 1).strip();
        }
        return null;
    }

    /**
     * A body as text for display: decompressed (gzip/deflate) and decoded with its charset, or
     * null when it is not text (the caller shows its size instead).
     */
    public static String bodyText(byte[] body, List<String> headers) {
        if (body == null || body.length == 0) return null;
        String enc = header(headers, "Content-Encoding");
        enc = enc == null ? "" : enc.toLowerCase(Locale.ROOT);
        byte[] data = body;
        if (!enc.isEmpty() && !"identity".equals(enc)) {
            if (enc.contains("gzip")) data = inflate(body, true, false);
            else if (enc.contains("deflate")) {
                data = inflate(body, false, false);
                if (data == null) data = inflate(body, false, true); // some servers send raw deflate
            } else return null;
            if (data == null) return null;
        }
        String type = header(headers, "Content-Type");
        if (!isText(type, data)) return null;
        return new String(data, charsetOf(type));
    }

    private static byte[] inflate(byte[] body, boolean gzip, boolean raw) {
        try (InputStream in = gzip ? new GZIPInputStream(new ByteArrayInputStream(body))
                : new InflaterInputStream(new ByteArrayInputStream(body), new Inflater(raw))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) {
                if (out.size() + n > MAX_DECODED_BYTES) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.strip();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        return Charset.forName(p.substring(8).strip().replace("\"", ""));
                    } catch (RuntimeException e) {
                        break;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean isText(String contentType, byte[] data) {
        if (contentType != null && !contentType.isBlank()) {
            int semi = contentType.indexOf(';');
            String t = (semi >= 0 ? contentType.substring(0, semi) : contentType).strip().toLowerCase(Locale.ROOT);
            return t.startsWith("text/") || t.endsWith("/json") || t.endsWith("+json") || t.endsWith("/xml")
                    || t.endsWith("+xml") || t.contains("javascript") || t.equals("application/x-www-form-urlencoded");
        }
        // No Content-Type: valid UTF-8 without control characters other than tab/CR/LF.
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 && c != '\t' && c != '\r' && c != '\n' || c == 0x7f) return false;
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
