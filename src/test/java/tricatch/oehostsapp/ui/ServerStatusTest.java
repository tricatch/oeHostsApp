package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.store.Store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerStatusTest {

    @TempDir
    Path dir;

    @org.junit.jupiter.api.BeforeAll
    static void lookAndFeel() {
        // The app's look and feel provides the fonts Ui.font derives from (no native library, as in the app).
        System.setProperty("flatlaf.useNativeLibrary", "false");
        com.formdev.flatlaf.FlatLightLaf.setup();
    }

    private static final ServerStatus.Snapshot RUNNING = new ServerStatus.Snapshot(true, false, null, "127.0.0.1", 1);
    private static final ServerStatus.Snapshot STOPPED = new ServerStatus.Snapshot(false, false, null, "127.0.0.1", 1);
    private static final ServerStatus.Snapshot FAILED = new ServerStatus.Snapshot(false, true, "boom", "127.0.0.1", 1);

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) throws Exception {
        AtomicReference<T> r = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.set(c.call());
            } catch (Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) throw err.get();
        return r.get();
    }

    private void restartEndsIn(ServerStatus.Snapshot end, String messageKey) throws Exception {
        AppContext ctx = new AppContext(Store.open(dir.resolve(messageKey + ".json")));
        Listeners service = new Listeners();
        AtomicReference<ServerStatus.Snapshot> state = new AtomicReference<>(RUNNING);
        JButton button = new JButton();
        JLabel status = new JLabel();
        ServerStatus s = onEdt(() -> new ServerStatus(ctx, service::add, state::get));
        onEdt(() -> {
            s.restart(button, status, () -> {
                state.set(STOPPED);
                service.fire();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    // test thread
                }
                state.set(end);
                service.fire();
            });
            return null;
        });
        // In progress: the button waits and the bottom bar says so, even after a fast restart.
        Thread.sleep(300);
        assertFalse(onEdt(button::isEnabled));
        assertEquals(ctx.m("app.settings.server.restarting"), onEdt(status::getText));
        // Settled: the button is back and the bar tells how it ended.
        Thread.sleep(1200);
        assertTrue(onEdt(button::isEnabled));
        assertEquals(ctx.m(messageKey), onEdt(status::getText));
        ctx.dispose();
    }

    @Test
    void showsProgressThenSuccess() throws Exception {
        restartEndsIn(RUNNING, "app.settings.server.restarted");
    }

    @Test
    void showsProgressThenFailure() throws Exception {
        restartEndsIn(FAILED, "app.settings.server.restart.failed");
    }
}
