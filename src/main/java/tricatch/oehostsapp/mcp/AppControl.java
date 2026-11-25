package tricatch.oehostsapp.mcp;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * How the MCP tools that change something reach the window. The screens keep their own state (an
 * open editor with an edit its autosave has not written yet, the launcher's switches, the settings
 * pages) and do not watch the store, so a change made behind their back would be shown stale or
 * overwritten by the next autosave. The window registers an implementation here; a tool hands it a
 * change, which runs on the event thread after the editors' pending edits are saved, and the screens
 * of {@link Area the areas it touched} then read the store again.
 *
 * <p>Without a window (tests, or before the window is built) a change runs directly on the store.
 */
public interface AppControl {

    /** What a change touched, so the window reloads only those screens. */
    enum Area {
        /** oeHosts profiles and their folders. */
        HOSTS,
        /** oeProxy vhosts and their folders. */
        VHOSTS,
        /** The Chrome launcher's options. */
        LAUNCHER,
        /** App settings (presets, ports, SSL proxy, ...). */
        SETTINGS
    }

    /** Runs {@code change} on the event thread between saving the editors and reloading {@code areas}. */
    <T> T apply(List<Area> areas, Supplier<T> change);

    /** The launcher's flags (and start URL) as its screen would launch Chrome now; {@code uddOnly}: only --user-data-dir. */
    List<String> chromeWords(boolean uddOnly);

    /** Starts or stops the Network tab's capture. */
    void setCapturing(boolean on);

    /** Clears the Network tab's rows. */
    void clearTraffic();

    /** Thrown when the event thread does not answer in time. */
    final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message) {
            super(message);
        }
    }

    // ── Registration ─────────────────────────────────────────

    /** Sets the window's implementation; its methods may be called from any thread. */
    static void set(AppControl c) {
        Holder.current = c;
    }

    /** Drops {@code c} if it is still the registered one (a rebuilt window has registered its own already). */
    static void clear(AppControl c) {
        if (Holder.current == c) Holder.current = null;
    }

    /** The registered window, or null. */
    static AppControl current() {
        return Holder.current;
    }

    /**
     * Runs {@code s} on the event thread and waits for its result (at once when already on it).
     * Exceptions thrown by {@code s} reach the caller unchanged.
     */
    static <T> T onEventThread(Supplier<T> s) {
        if (SwingUtilities.isEventDispatchThread()) return s.get();
        FutureTask<T> task = new FutureTask<>(s::get);
        SwingUtilities.invokeLater(task);
        try {
            return task.get(Holder.TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while waiting for the app's window.");
        } catch (TimeoutException e) {
            task.cancel(false);
            throw new Unavailable("The app's window did not answer in time; try again.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Static state of the interface. */
    final class Holder {
        static final long TIMEOUT_MS = 15_000;
        static volatile AppControl current;

        private Holder() {}
    }
}
