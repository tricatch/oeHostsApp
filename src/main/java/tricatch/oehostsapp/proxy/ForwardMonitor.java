package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.Listeners;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * What passes through the proxy service, for the Network tab:
 * <ul>
 *   <li>a plain HTTP request: URL, headers as they were sent, status, both bodies;</li>
 *   <li>a CONNECT tunnel: target, time and byte counts. Its content is never looked at;</li>
 *   <li>a failure of either: the proxy's status and its reason ({@link Failure}).</li>
 * </ul>
 * A tunnel that ends at the embedded reverse proxy is reported only when it has ended: its requests
 * are that proxy's events ({@link ProxyMonitor}), see {@link DecryptedRequests}. What the service
 * answers itself and what {@link ClientAccess} refuses is not traffic and is not reported.
 *
 * <p>It only observes: nothing is changed, held back or aggregated. While nobody captures
 * ({@link #start}) nothing is recorded at all.
 */
public final class ForwardMonitor {

    /** A body is kept up to this size, as oe-proxy does for its events. */
    public static final int BODY_LIMIT = 1024 * 1024;

    public enum Failure { DNS, CONNECT, TIMEOUT }

    private static final Logger log = LoggerFactory.getLogger(ForwardMonitor.class);
    private static final AtomicLong IDS = new AtomicLong();
    private static final String HANDLER = "oehostsapp-monitor";
    private static volatile Capture capture;

    private ForwardMonitor() {}

    /** One run of capturing: exchanges of an earlier run report and collect nothing. */
    private record Capture(Consumer<Exchange> sink) {}

    /**
     * Starts capturing for {@code sink}, which is called on the proxy's threads with the exchange
     * every time it changed (the same object each time), so it must be quick. Closing stops it.
     */
    public static Listeners.Subscription start(Consumer<Exchange> sink) {
        Capture c = new Capture(sink);
        capture = c;
        return () -> {
            if (capture == c) capture = null;
        };
    }

    /**
     * One request or tunnel as observed. Readable from any thread while it changes; the lists and
     * arrays it returns are not to be modified.
     */
    public static final class Exchange {
        private final String id = "f-" + IDS.incrementAndGet();
        private final long startedAt = System.currentTimeMillis();
        private final boolean tunnel;
        private final String method, host, path, url, client;
        private final int port;
        private final List<String> requestHeaders;

        private volatile long answeredAt, endedAt;
        private volatile List<String> responseHeaders = List.of();
        private volatile Integer status;
        private volatile Failure failure;
        private volatile byte[] requestBody, responseBody;
        private volatile boolean requestBodyTooLarge, responseBodyTooLarge;
        private volatile boolean endsAtReverseProxy;
        private volatile long sent, received;

        private Exchange(HttpRequest req, String client) {
            this.client = client;
            method = req.method().name();
            tunnel = req.method() == HttpMethod.CONNECT;
            String uri = req.uri();
            if (tunnel) {
                ForwardProxy.HostPort to = ForwardProxy.HostPort.parse(uri, 443);
                host = to.host();
                port = to.port();
                path = ":" + port;
                url = uri;
            } else {
                int scheme = uri.indexOf("://");
                String rest = scheme < 0 ? uri : uri.substring(scheme + 3);
                int end = 0;
                while (end < rest.length() && "/?#".indexOf(rest.charAt(end)) < 0) end++;
                String authority = rest.substring(0, end);
                String withoutUser = authority.substring(authority.lastIndexOf('@') + 1);
                boolean https = uri.regionMatches(true, 0, "https://", 0, 8);
                ForwardProxy.HostPort to = ForwardProxy.HostPort.parse(withoutUser, https ? 443 : 80);
                host = to.host();
                port = to.port();
                String p = rest.substring(end);
                path = p.startsWith("/") ? p : "/" + p;
                url = uri;
            }
            List<String> head = new ArrayList<>();
            head.add(method + " " + uri + " " + req.protocolVersion().text());
            addHeaders(head, req.headers());
            requestHeaders = List.copyOf(head);
        }

        public String id() { return id; }

        /** A CONNECT: no content, only counts. */
        public boolean tunnel() { return tunnel; }

        /** A tunnel into the embedded reverse proxy, which shows the requests inside. */
        public boolean endsAtReverseProxy() { return endsAtReverseProxy; }

        public String method() { return method; }

        public String host() { return host; }

        public int port() { return port; }

        /** Path and query; {@code :port} for a tunnel. */
        public String path() { return path; }

        /** The target as the client named it. */
        public String url() { return url; }

        /** The client device's address. */
        public String client() { return client; }

        public long startedAt() { return startedAt; }

        /** When the response started, or 0. */
        public long answeredAt() { return answeredAt; }

        /** When it ended, or 0. */
        public long endedAt() { return endedAt; }

        public boolean ended() { return endedAt != 0; }

        /** The raw lines, the first one the request line. */
        public List<String> requestHeaders() { return requestHeaders; }

        public List<String> responseHeaders() { return responseHeaders; }

        /** The status, or null: not answered, or a tunnel that was established. */
        public Integer status() { return status; }

        public Failure failure() { return failure; }

        public byte[] requestBody() { return requestBody; }

        public byte[] responseBody() { return responseBody; }

        public boolean requestBodyTooLarge() { return requestBodyTooLarge; }

        public boolean responseBodyTooLarge() { return responseBodyTooLarge; }

        /** Bytes from the client: the body, or everything inside a tunnel. */
        public long sent() { return sent; }

        /** Bytes to the client, likewise. */
        public long received() { return received; }
    }

    private static void addHeaders(List<String> to, HttpHeaders headers) {
        headers.iteratorCharSequence().forEachRemaining(h -> to.add(h.getKey() + ": " + h.getValue()));
    }

    /**
     * Starts observing {@code req}, which the service is about to proxy; null when nobody captures.
     * Runs on the client connection's event loop, before the proxy rewrites the request.
     */
    static Observation observe(HttpRequest req, ChannelHandlerContext ctx) {
        Capture c = capture;
        if (c == null || ctx == null) return null;
        try {
            Channel channel = ctx.channel();
            String client = channel.remoteAddress() instanceof InetSocketAddress a && a.getAddress() != null
                    ? a.getAddress().getHostAddress() : "?";
            Connection connection;
            if (ctx.pipeline().get(HANDLER) instanceof Connection existing) {
                connection = existing;
            } else {
                connection = new Connection();
                // First: it counts the bytes as they are on the wire.
                ctx.pipeline().addFirst(HANDLER, connection);
                Connection added = connection;
                channel.closeFuture().addListener(f -> added.closed());
            }
            Observation o = new Observation(c, new Exchange(req, client), connection);
            connection.add(o);
            if (!o.x.tunnel) o.report(); // a tunnel waits until it is known where it ends
            return o;
        } catch (RuntimeException e) {
            log.debug("not recording {}: {}", req.uri(), e.toString());
            return null; // the monitor must never break the traffic
        }
    }

    /** The observed state of one client connection: what is open on it, and the tunnel's counters. */
    private static final class Connection extends ChannelDuplexHandler {
        private final Set<Observation> open = ConcurrentHashMap.newKeySet();
        private volatile Observation tunnel;
        /** The first bytes written to a tunnel's client are the proxy's own "200 Connection established". */
        private boolean establishedWritten;

        void add(Observation o) {
            open.add(o);
            if (o.x.tunnel) {
                establishedWritten = false;
                tunnel = o;
            }
        }

        void remove(Observation o) {
            open.remove(o);
            if (tunnel == o) tunnel = null;
        }

        void closed() {
            for (Observation o : open) o.end();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            Observation t = tunnel;
            if (t != null && t.capturing() && msg instanceof ByteBuf b) t.x.sent += b.readableBytes();
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Observation t = tunnel;
            if (t != null && msg instanceof ByteBuf b && b.isReadable()) {
                if (!establishedWritten) establishedWritten = true;
                else if (t.capturing()) t.x.received += b.readableBytes();
            }
            super.write(ctx, msg, promise);
        }
    }

    /** A body as it passes, up to {@link #BODY_LIMIT}. */
    private static final class Body {
        private ByteArrayOutputStream kept = new ByteArrayOutputStream();

        void add(ByteBuf b) {
            if (kept == null) return;
            int n = b.readableBytes();
            if (kept.size() + n > BODY_LIMIT) {
                kept = null;
                return;
            }
            byte[] copy = new byte[n];
            b.getBytes(b.readerIndex(), copy); // without moving the buffer's indexes
            kept.write(copy, 0, n);
        }

        boolean tooLarge() {
            return kept == null;
        }

        void release() {
            if (kept != null) kept = new ByteArrayOutputStream(0);
        }

        byte[] bytes() {
            return kept == null || kept.size() == 0 ? null : kept.toByteArray();
        }
    }

    /**
     * The proxy's hooks for one request. The request, the response and the connection's end come
     * from different event loops, hence synchronized; nothing in here blocks.
     */
    static final class Observation {
        private final Capture capturedBy;
        private final Exchange x;
        private final Connection connection;
        private final Body request = new Body(), response = new Body();
        private boolean interim;

        private Observation(Capture capturedBy, Exchange x, Connection connection) {
            this.capturedBy = capturedBy;
            this.x = x;
            this.connection = connection;
        }

        private boolean capturing() {
            return capture == capturedBy;
        }

        private void report() {
            if (!capturing()) return;
            try {
                capturedBy.sink().accept(x);
            } catch (RuntimeException e) {
                log.debug("Network tab: {}", e.toString());
            }
        }

        synchronized void requestPart(HttpObject part) {
            if (x.tunnel || !capturing() || !(part instanceof HttpContent c)) return;
            if (c.content().isReadable()) {
                x.sent += c.content().readableBytes();
                request.add(c.content());
            }
            if (c instanceof LastHttpContent) {
                keepRequest();
                if (x.requestBody != null || x.requestBodyTooLarge) report();
            }
        }

        private void keepRequest() {
            x.requestBody = request.bytes();
            x.requestBodyTooLarge = request.tooLarge();
        }

        /** A part of the server's answer, before the proxy changes it. */
        synchronized void responsePart(HttpObject part) {
            if (x.tunnel || x.ended() || !capturing()) return;
            if (part instanceof HttpResponse res) {
                int code = res.status().code();
                // 100, 103...: passed on, not the answer. Its empty end is not the answer's end either.
                interim = code < 200 && code != 101;
                if (interim) return;
                answered(res);
            }
            if (part instanceof HttpContent c) {
                if (interim) {
                    if (c instanceof LastHttpContent) interim = false;
                    return;
                }
                if (c.content().isReadable()) {
                    x.received += c.content().readableBytes();
                    response.add(c.content());
                }
                if (c instanceof LastHttpContent) end();
            }
        }

        private void answered(HttpResponse res) {
            List<String> head = new ArrayList<>();
            head.add(res.protocolVersion().text() + " " + res.status());
            addHeaders(head, res.headers());
            x.responseHeaders = List.copyOf(head);
            x.status = res.status().code();
            x.answeredAt = System.currentTimeMillis();
            if (res.status().code() < 500) x.failure = null;
            report();
        }

        /** An answer of the proxy's own (502, 504, 400): those do not pass {@link #responsePart}. */
        synchronized void proxyAnswered(HttpObject part) {
            if (x.ended() || !x.responseHeaders.isEmpty() || !capturing()) return;
            if (!(part instanceof HttpResponse res && part instanceof HttpContent c) || res.status().code() < 200) return;
            x.endsAtReverseProxy = false; // there is no request inside to show instead
            answered(res);
            if (!x.tunnel && c.content().isReadable()) {
                x.received += c.content().readableBytes();
                response.add(c.content());
            }
            end();
        }

        synchronized void resolved(InetSocketAddress address) {
            if (!x.tunnel) return;
            x.endsAtReverseProxy = SslProxy.endsAtReverseProxy(address);
            if (!x.endsAtReverseProxy) report();
        }

        synchronized void connected() {
            x.failure = null;
        }

        synchronized void failed(Failure why) {
            x.failure = why;
        }

        synchronized void end() {
            connection.remove(this);
            if (x.ended()) return;
            boolean capturing = capturing();
            if (capturing && !x.tunnel) {
                keepRequest();
                x.responseBody = response.bytes();
                x.responseBodyTooLarge = response.tooLarge();
                // An idle keep-alive connection holds on to this object.
                response.release();
            }
            // Last: readers on other threads take ended() to mean the bodies are there.
            x.endedAt = System.currentTimeMillis();
            if (capturing) report();
        }
    }
}
