package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.proxy.ProxyRuntime;

import javax.swing.*;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → oeProxy → reverse proxy: where it listens (this PC only, one network card with
 * 127.0.0.1, or every interface, the default) on the left, its state on the right.
 */
final class ReverseProxyPage {

    private final SettingsTab tab;
    private final AppContext ctx;

    ReverseProxyPage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        JLabel nicLabel = label(ctx.m("app.settings.fwdproxy.nic"));
        sameWidth(nicLabel);
        NetworkChoice bind = new NetworkChoice(ctx, ctx.settings(st -> st.reverseProxy.bind),
                "app.settings.server.bind.all.warn", nicLabel);
        ServerStatus status = new ServerStatus(ctx, ProxyRuntime::onChange, () -> new ServerStatus.Snapshot(
                ProxyRuntime.state() == ProxyRuntime.State.RUNNING, ProxyRuntime.state() == ProxyRuntime.State.FAILED,
                ProxyRuntime.error(), ProxyRuntime.bind(), ProxyRuntime.port()));
        // The progress and the result show in the bottom bar.
        JLabel st = tab.status("server");
        // Saves the binding, then restarts the proxy with it.
        JButton[] self = {null};
        JButton restart = self[0] = primary(ctx.m("app.settings.server.restart"), "refresh-cw", () -> {
            String to = bind.selected();
            if (ctx.saveSettings(s -> s.reverseProxy.bind = to)) status.restart(self[0], st, ProxyRuntime::restart);
        });
        return fullPage(halves(column(desc(ctx.m("app.settings.server.desc")), line(), bind.row, bind.warn,
                line(), rightAligned(restart)), column(status.panel)));
    }
}
