package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.proxy.ForwardProxy;
import tricatch.oehostsapp.proxy.ProxyRuntime;
import tricatch.oehostsapp.proxy.SslProxy;
import tricatch.oehostsapp.proxy.SslRules;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Network → SSL proxy settings, a dialog: the domain list on the left, put in force by Apply
 * rather than autosave (every change closes the proxy service's connections); on the right what
 * the SSL proxy depends on and the domains that lately went through the proxy service, each with
 * a button that adds it to the list. The on/off switch is not here but in the navbar
 * ({@link SslProxyControls}).
 *
 * <p>The body has one size, so warnings and arriving domains never move the buttons: the editor
 * and the recent list take what height is left.
 */
final class SslProxyDialog {

    private static final int WIDTH = 920;
    private static final int HEIGHT = 480;
    /** What the dialog's header and footer and the OS's bars need besides the body. */
    private static final int CHROME = 150;
    /** Between the halves, and the right half's padding after its dividing line ({@code SettingsKit.halves}). */
    private static final int HALVES_GAP = 24, RIGHT_INSET = 21;
    /** Recent connections arrive in bursts (one page is dozens): the list is redrawn this often at most. */
    private static final int RECENT_REFRESH_MS = 400;

    /** Edits not applied yet, kept when the dialog closes for as long as the stored list is the same. */
    private record Draft(String base, String text) {}

    /** One line of the recent list. */
    private record Row(String host, boolean decrypted, boolean excluded, boolean addable) {}

    private static Draft draft;

    private final AppContext ctx;
    private final OeDialog dialog;
    private final int halfWidth;
    private final CodeEditor editor = new CodeEditor(null);
    private final JLabel status = SettingsKit.status();
    private final JTextPane warning = desc("");
    private final JPanel openCaRow;
    private final JTextField[] state = new JTextField[4];
    private final JPanel recent = new WidthTracking();
    private final JPanel recentCards = new JPanel(new CardLayout());
    private final JButton clear;
    private final Timer recentTimer;
    private JButton apply;

    private List<Row> shown;
    private String saved;
    private boolean applying;
    private Boolean trusted;

    static void show(AppContext ctx, Runnable openCaSettings) {
        OeDialog.open(ctx, ctx.m("app.ssl.dialog.title"), d -> new SslProxyDialog(ctx, d, openCaSettings).build());
    }

    /** A domain was added to the stored list from outside (a tunnel's menu): unapplied edits keep it too. */
    static void domainAdded(String stored, String next, String host) {
        if (draft != null && draft.base().equals(stored)) draft = new Draft(next, SslRules.withDomain(draft.text(), host));
    }

    private SslProxyDialog(AppContext ctx, OeDialog dialog, Runnable openCaSettings) {
        this.ctx = ctx;
        this.dialog = dialog;
        halfWidth = (bodyWidth() - HALVES_GAP) / 2;
        saved = ctx.settings(st -> st.sslProxy.domains);
        editor.lineHighlighting(SslRules::valid);
        editor.setText(draft != null && draft.base().equals(saved) ? draft.text() : saved);
        warning.setForeground(Ui.DANGER);
        openCaRow = buttons(Ui.secondary(ctx.m("app.ssl.open.ca"), "shield-check", () -> {
            dialog.dispose();
            openCaSettings.run();
        }));
        clear = Ui.secondary(ctx.m("app.ssl.recent.clear"), "trash", SslProxy::clearRecent);
        recentTimer = new Timer(RECENT_REFRESH_MS, e -> fillRecent());
        recentTimer.setRepeats(false);
    }

    /** The body's width at 100%, smaller on a screen the zoomed dialog would not fit. */
    private int bodyWidth() {
        return Math.min(WIDTH, (screen().width * 100 / Ui.px(100)) - 60);
    }

    private int bodyHeight() {
        return Math.min(HEIGHT, (screen().height * 100 / Ui.px(100)) - CHROME);
    }

    private Rectangle screen() {
        GraphicsConfiguration gc = ctx.frame().getGraphicsConfiguration();
        Rectangle r = new Rectangle(gc.getBounds());
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        r.width -= in.left + in.right;
        r.height -= in.top + in.bottom;
        return r;
    }

    private void build() {
        JPanel body = (JPanel) halves(left(), right());
        body.setPreferredSize(Ui.dim(bodyWidth(), bodyHeight()));
        dialog.body(body);
        dialog.footerLeft(status);
        apply = dialog.button(ctx.m("app.ssl.apply"), true, this::apply);
        dialog.button(ctx.m("btn.close"), false, dialog::dispose);

        dialog.getRootPane().registerKeyboardAction(e -> apply(),
                KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                editor.area.requestFocusInWindow();
            }

            @Override
            public void windowActivated(WindowEvent e) {
                // The CA may have been registered outside the app meanwhile.
                trusted = SslProxyControls.trusted();
                refreshState();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                recentTimer.stop();
            }
        });
        editor.onEdit(this::edited);
        ctx.listen(dialog, ForwardProxy::onChange, this::refreshState);
        ctx.listen(dialog, ProxyRuntime::onChange, this::refreshState);
        ctx.listen(dialog, SslProxy::onRecentChange, () -> {
            if (!recentTimer.isRunning()) recentTimer.start();
        });
        trusted = SslProxyControls.trusted();
        refreshState();
        fillRecent();
        showDirty();
    }

    private JComponent left() {
        JTextPane about = desc(ctx.m("app.ssl.desc"));
        Dialogs.fixWidth(about, halfWidth);
        JTextPane syntax = desc(ctx.m("app.ssl.syntax"));
        Dialogs.fixWidth(syntax, halfWidth);
        JPanel p = new JPanel(new BorderLayout(0, Ui.px(8)));
        p.setOpaque(false);
        p.add(OeDialog.stack(14, about, label(ctx.m("app.ssl.domains"))), BorderLayout.NORTH);
        p.add(framed(editor.scroll), BorderLayout.CENTER);
        p.add(column(syntax, warning), BorderLayout.SOUTH);
        return p;
    }

    private JComponent right() {
        JPanel table = table(new String[][]{
                {ctx.m("app.ssl.title"), "-"},
                {ctx.m("app.settings.fwdproxy.title"), "-"},
                {ctx.m("app.settings.server.title"), "-"},
                {ctx.m("app.settings.ca.title"), "-"}}, state);
        // Words in the UI language: the mono font's Hangul fallback is taller and gets cut off.
        for (JTextField f : state) f.setFont(Ui.font(Font.BOLD, 12.5f));
        int width = halfWidth - RIGHT_INSET;
        JTextPane recentDesc = desc(ctx.m("app.ssl.recent.desc"));
        Dialogs.fixWidth(recentDesc, width);
        JComponent top = column(heading(ctx.m("app.settings.server.status.title")), table, openCaRow, line(),
                heading(ctx.m("app.ssl.recent.title")), recentDesc);

        recent.setLayout(new GridBagLayout());
        recent.setBackground(Color.WHITE);
        JScrollPane scroll = Ui.plainScroll(recent);
        scroll.getViewport().setBackground(Color.WHITE);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel none = new JPanel(new BorderLayout());
        none.setOpaque(false);
        JTextPane noneText = desc(ctx.m("app.ssl.recent.none"));
        Dialogs.fixWidth(noneText, width);
        none.add(noneText, BorderLayout.NORTH);
        recentCards.setOpaque(false);
        recentCards.add(scroll, "list");
        recentCards.add(none, "none");

        JPanel p = new JPanel(new BorderLayout(0, Ui.px(10)));
        p.setOpaque(false);
        p.add(top, BorderLayout.NORTH);
        p.add(recentCards, BorderLayout.CENTER);
        p.add(rightAligned(clear), BorderLayout.SOUTH);
        return p;
    }

    // ── Edit and apply ──────────────────────────────────────────

    private void edited() {
        String now = editor.getText();
        draft = now.equals(saved) ? null : new Draft(saved, now);
        showDirty();
        refreshWarning();
        if (!recentTimer.isRunning()) recentTimer.start();
    }

    private void showDirty() {
        if (applying) return;
        if (status.getClientProperty("flash") instanceof Timer t) t.stop();
        status.setForeground(Ui.MUTED);
        status.setText(editor.getText().equals(saved) ? " " : ctx.m("app.ssl.unapplied"));
    }

    private void apply() {
        if (applying) return;
        String next = editor.getText();
        List<Integer> bad = SslRules.problems(next);
        if (!bad.isEmpty()) {
            editor.goTo(bad.get(0) - 1, 0);
            error(status, ctx.m("app.ssl.invalid", bad.get(0)));
            return;
        }
        SslRules before = SslProxy.effective();
        if (!ctx.saveSettings(s -> s.sslProxy.domains = next)) return;
        saved = next;
        draft = null;
        ctx.fireSettingsChanged();
        applying = true;
        apply.setEnabled(false);
        if (status.getClientProperty("flash") instanceof Timer t) t.stop();
        status.setForeground(Ui.MUTED);
        status.setText(ctx.m("app.ssl.applying"));
        Thread.ofVirtual().name("ssl-proxy-apply").start(() -> {
            try {
                SslProxy.apply(before);
            } finally {
                SwingUtilities.invokeLater(this::applied);
            }
        });
    }

    private void applied() {
        applying = false;
        apply.setEnabled(true);
        refreshState();
        if (ForwardProxy.state() == ForwardProxy.State.FAILED) error(status, ctx.m("app.ssl.apply.failed"));
        else if (!editor.getText().equals(saved)) showDirty(); // edited while it was being applied
        else ok(status, ctx.m("app.ssl.applied"));
    }

    // ── State ───────────────────────────────────────────────────

    private void refreshState() {
        boolean on = ctx.settings(st -> st.sslProxy.enabled);
        state[0].setText(ctx.m(on ? "app.ssl.state.on" : "app.ssl.state.off"));
        state[0].setForeground(on ? Ui.BC700 : Ui.MUTED);
        ForwardProxy.State f = ForwardProxy.state();
        serverState(state[1], f == ForwardProxy.State.RUNNING, f == ForwardProxy.State.FAILED);
        ProxyRuntime.State r = ProxyRuntime.state();
        serverState(state[2], r == ProxyRuntime.State.RUNNING, r == ProxyRuntime.State.FAILED);
        state[3].setText(ctx.m(trusted == null ? "app.settings.ca.trust.unknown"
                : trusted ? "app.settings.ca.trust.yes" : "app.ssl.ca.untrusted"));
        state[3].setForeground(trusted == null ? Ui.MUTED : trusted ? Ui.BC700 : Ui.DANGER);
        openCaRow.setVisible(Boolean.FALSE.equals(trusted));
        refreshWarning();
    }

    private void serverState(JTextField field, boolean running, boolean failed) {
        field.setText(ctx.m(running ? "app.settings.server.running"
                : failed ? "app.settings.server.failed" : "app.settings.server.starting"));
        field.setForeground(running ? Ui.BC700 : failed ? Ui.DANGER : Ui.MUTED);
    }

    /** What would keep the SSL proxy, while it is on, from working with the list on the screen. */
    private void refreshWarning() {
        List<String> lines = ctx.settings(st -> st.sslProxy.enabled)
                ? SslProxyControls.problems(ctx, trusted, editor.getText()) : List.of();
        String text = String.join("\n", lines);
        if (!text.equals(warning.getText())) {
            warning.setText(text);
            warning.setPreferredSize(null); // measured again for the new text
            Dialogs.fixWidth(warning, halfWidth);
        }
        warning.setVisible(!lines.isEmpty());
        warning.revalidate();
    }

    // ── Recent domains ──────────────────────────────────────────

    private void fillRecent() {
        SslRules listed = SslRules.parse(editor.getText());
        List<Row> rows = new ArrayList<>();
        for (SslProxy.Recent r : SslProxy.recent()) {
            boolean excluded = listed.excludes(r.host());
            rows.add(new Row(r.host(), r.decrypted(), excluded, !excluded && !listed.matches(r.host())));
        }
        if (rows.equals(shown)) return;
        shown = rows;
        recent.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        for (int y = 0; y < rows.size(); y++) {
            Row r = rows.get(y);
            c.gridy = y;
            JLabel host = new JLabel(r.host());
            host.setFont(Ui.mono(12f));
            host.setForeground(Ui.TEXT);
            host.setToolTipText(r.host());
            JButton add = Ui.iconButton("square-plus", ctx.m("app.ssl.recent.add"), false, () -> add(r.host()));
            Dimension size = add.getPreferredSize();
            add.setMinimumSize(size);
            // As tall as the button, so the text sits at its middle; cut with an ellipsis rather
            // than pushing the state and the button out of the dialog.
            host.setPreferredSize(new Dimension(0, size.height));
            host.setMinimumSize(new Dimension(0, size.height));
            c.gridx = 0;
            c.anchor = GridBagConstraints.BASELINE_LEADING;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, 0, Ui.px(12));
            recent.add(host, c);

            JLabel how = new JLabel(ctx.m(r.decrypted() ? "app.ssl.recent.decrypted"
                    : r.excluded() ? "app.ssl.recent.excluded" : "app.ssl.recent.tunnel"));
            how.setFont(Ui.font(Font.BOLD, 12f));
            how.setForeground(r.decrypted() ? Ui.BC700 : Ui.MUTED);
            how.setPreferredSize(new Dimension(how.getPreferredSize().width, size.height));
            how.setMinimumSize(how.getPreferredSize());
            c.gridx = 1;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            recent.add(how, c);

            c.gridx = 2;
            c.anchor = GridBagConstraints.CENTER;
            c.insets = new Insets(0, 0, 0, Ui.px(4));
            // An empty cell of the same size keeps the rows and the state column lined up.
            recent.add(r.addable() ? add : Box.createRigidArea(size), c);
        }
        // Takes the rest of the height, so a few rows start at the top.
        c.gridy = rows.size();
        c.gridx = 0;
        c.weighty = 1;
        recent.add(Box.createGlue(), c);
        ((CardLayout) recentCards.getLayout()).show(recentCards, rows.isEmpty() ? "none" : "list");
        clear.setVisible(!rows.isEmpty());
        recent.revalidate();
        recent.repaint();
    }

    private void add(String host) {
        String text = editor.getText();
        String next = SslRules.withDomain(text, host);
        if (next.equals(text)) return; // a second click before the redraw
        // Through the document, so Ctrl+Z takes it back.
        editor.area.append(next.substring(text.length()));
        fillRecent();
    }
}
