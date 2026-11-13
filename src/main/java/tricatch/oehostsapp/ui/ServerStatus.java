package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.Listeners;

import javax.swing.*;
import java.awt.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The right half of a server's settings page (forward proxy, web service, reverse proxy): its
 * state, every address it answers on, and the error of a failed start, kept up to date; and the
 * progress of a restart started from the page ({@link #restart}).
 */
final class ServerStatus {

    /** What the table shows: running, failed (with {@code error}) or neither (starting / stopped). */
    record Snapshot(boolean running, boolean failed, String error, String bind, int port) {
        boolean settled() {
            return running || failed;
        }
    }

    /** "Restarting" stays up at least this long, so a fast restart is still seen to happen. */
    private static final int MIN_PROGRESS_MS = 700;
    /** A restart that has not settled by then is reported as not answering. */
    private static final int TIMEOUT_MS = 30_000;

    private final AppContext ctx;
    private final Function<Runnable, Listeners.Subscription> source;
    private final Supplier<Snapshot> snapshot;
    /** The table: state, addresses, error. */
    final JPanel panel;
    private final JTextField[] v = new JTextField[3];
    private final JTextArea addresses;
    /** Set while a restart started here has not settled. */
    private boolean restarting;

    ServerStatus(AppContext ctx, Function<Runnable, Listeners.Subscription> source, Supplier<Snapshot> snapshot) {
        this.ctx = ctx;
        this.source = source;
        this.snapshot = snapshot;
        panel = SettingsKit.table(new String[][]{
                {ctx.m("app.settings.server.state"), "-"},
                {ctx.m("app.settings.fwdproxy.address"), "-"},
                {ctx.m("app.settings.server.error"), "-"}}, v);
        // The state is a word in the UI language: Korean in the mono font falls back to a taller
        // glyph that the row cuts off at the top.
        v[0].setFont(Ui.font(Font.BOLD, 12.5f));
        v[2].setFont(Ui.font(Font.PLAIN, 12.5f));
        addresses = SettingsKit.multiLineCell(panel, v[1]);
        ctx.listen(source, this::refresh);
        refresh();
    }

    private void refresh() {
        Snapshot s = snapshot.get();
        String key = restarting ? "app.settings.server.restarting"
                : s.running() ? "app.settings.server.running" : s.failed() ? "app.settings.server.failed" : "app.settings.server.starting";
        v[0].setText(ctx.m(key));
        v[0].setForeground(restarting ? Ui.MUTED : s.running() ? Ui.BC700 : s.failed() ? Ui.DANGER : Ui.MUTED);
        addresses.setText(s.port() > 0 ? String.join("\n", SettingsKit.reachableAt(ctx, s.bind(), s.port())) : "-");
        SettingsKit.errorRow(panel, v[2], !restarting && s.failed() ? s.error() : null);
    }

    /**
     * Runs {@code action} (the server's restart) off the event thread and shows its progress: the
     * button is disabled and the state row and {@code status} say "restarting" until the server
     * reports running or failed, then {@code status} tells how it ended.
     */
    void restart(JButton button, JLabel status, Runnable action) {
        if (restarting) return;
        restarting = true;
        button.setEnabled(false);
        status.setForeground(Ui.MUTED);
        status.setText(ctx.m("app.settings.server.restarting"));
        if (status.getClientProperty("flash") instanceof Timer old) old.stop();
        refresh();
        long started = System.currentTimeMillis();
        Listeners.Subscription[] sub = {null};
        Timer[] timeout = {null};
        Runnable finish = () -> {
            if (!restarting) return;
            if (sub[0] != null) sub[0].close();
            timeout[0].stop();
            Snapshot s = snapshot.get();
            restarting = false;
            button.setEnabled(true);
            refresh();
            if (s.running()) SettingsKit.ok(status, ctx.m("app.settings.server.restarted"));
            else if (s.failed()) SettingsKit.error(status, ctx.m("app.settings.server.restart.failed"));
            else SettingsKit.error(status, ctx.m("app.settings.server.restart.timeout"));
        };
        // Settled: finish, but not before "restarting" has been up for a moment.
        Runnable settled = () -> {
            if (!restarting || !snapshot.get().settled()) return;
            int wait = (int) Math.max(0, MIN_PROGRESS_MS - (System.currentTimeMillis() - started));
            Timer t = new Timer(wait, e -> finish.run());
            t.setRepeats(false);
            t.start();
        };
        timeout[0] = new Timer(TIMEOUT_MS, e -> finish.run());
        timeout[0].setRepeats(false);
        timeout[0].start();
        // Subscribed before the restart starts, so its first change cannot be missed.
        sub[0] = source.apply(() -> SwingUtilities.invokeLater(settled));
        Thread.ofVirtual().name("server-restart").start(action);
    }
}
