package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.BuildInfo;
import tricatch.oehostsapp.OeHostsApp;
import tricatch.oehostsapp.update.Update;

import javax.swing.*;
import java.awt.*;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/** Settings → General → about: versions, license, source, and the app update ({@link Update}). */
final class AboutPage {

    static final String REPO_URL = "https://github.com/tricatch/oeHostsApp";

    private final SettingsTab tab;
    private final AppContext ctx;

    AboutPage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        JTextField[] v = new JTextField[4];
        String proxyVer = BuildInfo.dependency("io.github.tricatch:oe-proxy");
        JPanel info = table(new String[][]{
                {"oeHostsApp", OeHostsApp.version()},
                {"oe-proxy", proxyVer == null ? "-" : proxyVer},
                {"Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"},
                {"OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")"}}, v);
        // The app's own license and source, as rows of the same table.
        tableRow(info, v.length, ctx.m("app.settings.about.license"), badge("MIT", LicensesPage.MIT[0], LicensesPage.MIT[1]));
        tableRow(info, v.length + 1, "GitHub", link(ctx, REPO_URL.substring("https://".length()), REPO_URL));
        tableRow(info, v.length + 2, ctx.m("app.settings.about.update"), updateRow());
        return page(info);
    }

    /**
     * The update status (checked at start, {@link Update}) and its action: check again, or
     * download, install and restart. A run that cannot replace its own jar only names the new version.
     */
    private JComponent updateRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        Runnable refresh = () -> {
            row.removeAll();
            Update.Status s = Update.status();
            JLabel text = new JLabel();
            text.setFont(Ui.font(Font.PLAIN, 12.5f));
            text.setForeground(Ui.TEXT);
            JComponent action = null;
            JButton check = Ui.secondary(ctx.m("app.update.check"), null, Update::checkInBackground);
            switch (s.state()) {
                case IDLE -> action = check;
                case CHECKING -> text.setText(ctx.m("app.update.checking"));
                case UP_TO_DATE -> {
                    text.setText(ctx.m("app.update.latest"));
                    action = check;
                }
                case FAILED -> {
                    text.setText(ctx.m("app.update.check.failed", s.error()));
                    text.setForeground(Ui.DANGER);
                    action = check;
                }
                case AVAILABLE -> {
                    if (s.error() != null) {
                        text.setText(ctx.m("app.update.failed", s.error()));
                        text.setForeground(Ui.DANGER);
                    } else {
                        text.setText(ctx.m("app.update.available", s.latest()));
                    }
                    action = Update.canSelfUpdate()
                            ? Ui.primary(ctx.m("app.update.install"), null, () -> confirmUpdate(s.latest()))
                            : null;
                }
                case DOWNLOADING -> text.setText(ctx.m("app.update.downloading", s.percent()));
                case RESTARTING -> text.setText(ctx.m("app.update.restarting"));
            }
            if (!text.getText().isEmpty()) {
                row.add(text);
                row.add(Box.createHorizontalStrut(Ui.px(10)));
            }
            if (s.state() == Update.State.AVAILABLE) {
                row.add(link(ctx, ctx.m("app.update.notes"), Update.releaseUrl(s.latest())));
                row.add(Box.createHorizontalStrut(Ui.px(10)));
                if (action == null) {
                    JLabel manual = new JLabel(ctx.m("app.update.manual"));
                    manual.setFont(Ui.font(Font.PLAIN, 12.5f));
                    manual.setForeground(Ui.MUTED);
                    row.add(manual);
                }
            }
            if (action != null) row.add(action);
            row.revalidate();
            row.repaint();
        };
        refresh.run();
        ctx.listen(Update::onChange, refresh);
        return row;
    }

    private void confirmUpdate(String version) {
        if (!OeDialog.confirm(ctx, ctx.m("app.update.confirm.title"), ctx.m("app.update.confirm", version))) return;
        tab.flushAll();
        Update.updateInBackground(() -> SwingUtilities.invokeLater(MainWindow::quit));
    }
}
