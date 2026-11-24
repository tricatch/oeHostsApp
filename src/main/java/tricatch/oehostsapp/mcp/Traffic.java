package tricatch.oehostsapp.mcp;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * What the Network tab holds, for the MCP traffic tools. The rows live on the event thread in
 * {@code ui.MonitorTab}, which registers itself here as the source; a tool asks for a copy, built on
 * the event thread and handed over as immutable records. Nothing is kept here and nothing is written
 * to disk: no Network tab (or a stopped capture) means nothing to offer.
 */
public final class Traffic {

    /** One request or tunnel as the Network tab shows it. Bodies are as captured (possibly compressed). */
    public record Entry(String id, boolean reverseProxy, boolean tunnel, boolean decryptedWithoutRequest,
                        long startedAt, long answeredAt, long endedAt,
                        String method, String host, String path, String url, String client,
                        Integer status, String failure,
                        List<String> requestHeaders, List<String> responseHeaders,
                        byte[] requestBody, byte[] responseBody,
                        boolean requestBodyTooLarge, boolean responseBodyTooLarge, boolean bodiesDropped,
                        long sent, long received, List<String> websocket) {}

    /** The rows, oldest first, and whether the capture is running. */
    public record Snapshot(boolean capturing, List<Entry> entries) {
        static final Snapshot NONE = new Snapshot(false, List.of());
    }

    private static final long TIMEOUT_MS = 5000;
    private static volatile Supplier<Snapshot> source;

    private Traffic() {}

    /** Sets the source; {@code s} runs on the event thread. */
    public static void setSource(Supplier<Snapshot> s) {
        source = s;
    }

    /** Drops {@code s} if it is still the source (a rebuilt window has registered its own already). */
    public static void clearSource(Supplier<Snapshot> s) {
        if (source == s) source = null;
    }

    /** A copy of the rows; {@link Snapshot#NONE} when there is no source or the event thread does not answer. */
    static Snapshot snapshot() {
        Supplier<Snapshot> s = source;
        if (s == null) return Snapshot.NONE;
        if (SwingUtilities.isEventDispatchThread()) return s.get();
        FutureTask<Snapshot> task = new FutureTask<>(s::get);
        SwingUtilities.invokeLater(task);
        try {
            return task.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Snapshot.NONE;
        } catch (ExecutionException | TimeoutException e) {
            task.cancel(false);
            return Snapshot.NONE;
        }
    }
}
