package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.store.Settings;
import tricatch.oehostsapp.web.ShareServer;

import javax.swing.*;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → General → web service ({@link ShareServer}): its PC address and port on the left
 * (saving restarts it), its state on the right. Who can reach it is decided by its binding alone.
 */
final class WebServicePage {

    private final SettingsTab tab;
    private final AppContext ctx;

    WebServicePage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        Settings.WebService stored = ctx.settings(st -> st.web);
        JTextField port = portField(stored.port);
        JLabel nicLabel = label(ctx.m("app.settings.fwdproxy.nic"));
        JLabel portLabel = label(ctx.m("app.settings.fwdproxy.port"));
        sameWidth(nicLabel, portLabel);
        NetworkChoice bind = new NetworkChoice(ctx, stored.bind, "app.settings.web.bind.warn", nicLabel);
        JLabel st = tab.status("web");
        ServerStatus status = new ServerStatus(ctx, ShareServer::onChange, () -> new ServerStatus.Snapshot(
                ShareServer.state() == ShareServer.State.RUNNING, ShareServer.state() == ShareServer.State.FAILED,
                ShareServer.error(), ShareServer.bind(), ShareServer.port()));
        JButton[] self = {null};
        JButton restart = self[0] = primary(ctx.m("app.settings.server.restart"), "refresh-cw", () -> {
            int p = port(port);
            if (p < 0) {
                error(st, ctx.m("app.settings.error.port"));
                return;
            }
            String to = bind.selected();
            if (!ctx.saveSettings(s -> {
                s.web.port = p;
                s.web.bind = to;
            })) return;
            ctx.fireSettingsChanged();
            status.restart(self[0], st, ShareServer::restart);
        });
        // Left: description | settings | action, set apart by lines; right: the server's state.
        return fullPage(halves(column(desc(ctx.m("app.settings.web.desc")), line(), bind.row, bind.warn,
                buttons(portLabel, port), line(), rightAligned(restart)), column(heading(ctx.m("app.settings.server.status.title")), line(), status.panel)));
    }
}
