package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.proxy.ClientAccess;
import tricatch.oehostsapp.proxy.ForwardProxy;

import javax.swing.*;
import java.awt.*;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → General → proxy service: its port on the left (saving restarts it); its state and
 * addresses on the right, then the devices allowed or refused on screen this run
 * ({@link ClientAccess}), with a button that forgets them.
 */
final class ForwardProxyPage {

    private final SettingsTab tab;
    private final AppContext ctx;

    ForwardProxyPage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        JTextField port = portField(ctx.settings(st -> st.fwdproxy.port));
        JLabel portLabel = label(ctx.m("app.settings.fwdproxy.port"));
        sameWidth(portLabel);

        // The result shows in the bottom bar, like the editors' save status.
        JLabel st = tab.status("fwd");
        ServerStatus status = new ServerStatus(ctx, ForwardProxy::onChange, () -> new ServerStatus.Snapshot(
                ForwardProxy.state() == ForwardProxy.State.RUNNING, ForwardProxy.state() == ForwardProxy.State.FAILED,
                ForwardProxy.error(), Network.ALL, ForwardProxy.port()));
        JButton[] self = {null};
        // Saves the port, then restarts the service on it; the state rows show the outcome.
        JButton restart = self[0] = primary(ctx.m("app.settings.server.restart"), "refresh-cw", () -> {
            int p = port(port);
            if (p < 0) {
                error(st, ctx.m("app.settings.error.port"));
                return;
            }
            if (!ctx.saveSettings(s -> s.fwdproxy.port = p)) return;
            ctx.fireSettingsChanged();
            status.restart(self[0], st, ForwardProxy::restart);
        });

        return fullPage(halves(
                column(desc(ctx.m("app.settings.fwdproxy.desc")), line(), buttons(portLabel, port), line(), rightAligned(restart)),
                column(heading(ctx.m("app.settings.server.status.title")), line(), status.panel,
                        (JComponent) Box.createVerticalStrut(Ui.px(18)), devices())));
    }

    /** The devices answered on screen this run: address, allowed or refused, User-Agent; and a reset. */
    private JComponent devices() {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        JComponent none = desc(ctx.m("app.access.devices.none"));
        JButton clear = Ui.secondary(ctx.m("app.access.devices.clear"), "trash", ClientAccess::clear);
        JPanel clearRow = rightAligned(clear);
        Runnable fill = () -> {
            list.removeAll();
            java.util.List<ClientAccess.Entry> all = ClientAccess.decisions();
            for (ClientAccess.Entry e : all) {
                JLabel state = new JLabel(ctx.m(e.allowed() ? "app.access.allowed" : "app.access.denied"));
                state.setFont(Ui.font(Font.BOLD, 12.5f));
                state.setForeground(e.allowed() ? Ui.BC700 : Ui.DANGER);
                JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, Ui.px(10), 0));
                head.setOpaque(false);
                head.add(value(e.ip()));
                head.add(state);
                JTextField ua = value(e.userAgent() == null ? "-" : e.userAgent());
                ua.setForeground(Ui.MUTED);
                JPanel row = new JPanel(new BorderLayout(0, Ui.px(2)));
                row.setOpaque(false);
                row.setBorder(Ui.pad(0, 0, 10, 0));
                row.add(head, BorderLayout.NORTH);
                row.add(ua, BorderLayout.CENTER);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                list.add(row);
            }
            none.setVisible(all.isEmpty());
            list.setVisible(!all.isEmpty());
            clearRow.setVisible(!all.isEmpty());
            list.revalidate();
            list.repaint();
        };
        ctx.listen(ClientAccess::onChange, fill);
        fill.run();
        return column(heading(ctx.m("app.access.devices.title")), desc(ctx.m("app.access.devices.desc")), line(), none, list,
                clearRow);
    }
}
