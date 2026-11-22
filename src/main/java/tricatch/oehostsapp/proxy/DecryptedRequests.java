package tricatch.oehostsapp.proxy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether a tunnel that ended at the reverse proxy needs a row of its own. Normally not:
 * the requests inside it are the rows. But a tunnel that carried bytes and left no request would
 * otherwise vanish without a trace, and that is how an app that refuses the CA (pinning) looks.
 * Not thread-safe: the Network tab uses it on the event thread.
 */
public final class DecryptedRequests {

    private static final int MAX_HOSTS = 4000;

    /** Domain -> when its latest request was seen. */
    private final Map<String, Long> latest = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_HOSTS;
        }
    };

    /** The reverse proxy reported a request with this Host header. */
    public void saw(String host, long at) {
        String key = key(host);
        if (key != null) latest.merge(key, at, Math::max);
    }

    /** Whether a tunnel to {@code host}, open since {@code openedAt}, ended without any request of it being seen. */
    public boolean leftNoRequest(String host, long openedAt, long sentBytes) {
        if (sentBytes == 0) return false; // opened and never used
        String key = key(host);
        Long at = key == null ? null : latest.get(key);
        return at == null || at < openedAt;
    }

    private static String key(String host) {
        if (host == null) return null;
        return SslRules.normalize(ForwardProxy.HostPort.parse(host, 443).host());
    }
}
