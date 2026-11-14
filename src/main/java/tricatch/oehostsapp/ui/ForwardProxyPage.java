package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.proxy.ClientAccess;
import tricatch.oehostsapp.proxy.ForwardProxy;

import javax.swing.*;
import java.awt.*;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → General → proxy service: its port on the left (saving restarts it); its state and
 * addresses on the right, then the devices allowed or refused on screen ({@link ClientAccess},
 * saved until removed), each with a delete button, and one that removes them all.
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

    /**
     * The devices answered on screen (saved until removed), one row each: address with allowed or
     * refused, a short device name from the User-Agent (the full one as tooltip) and a delete button;
     * below, a button that removes them all after asking.
     */
    private JComponent devices() {
        // One grid for all rows, so the columns (address | state | device | delete) line up from row
        // to row and the texts of a row share one baseline.
        JPanel list = new JPanel(new GridBagLayout());
        list.setOpaque(false);
        list.setAlignmentX(Component.LEFT_ALIGNMENT);
        JComponent none = desc(ctx.m("app.access.devices.none"));
        JButton clear = Ui.secondary(ctx.m("app.access.devices.clear"), "trash", () -> {
            if (OeDialog.confirm(ctx, ctx.m("app.access.devices.title"), ctx.plain("app.access.devices.clear.confirm"))) {
                ClientAccess.clear();
            }
        });
        JPanel clearRow = rightAligned(clear);
        // A line between the list and its button, as between a page's settings and its actions.
        JComponent clearLine = line();
        Runnable fill = () -> {
            list.removeAll();
            java.util.List<ClientAccess.Entry> all = ClientAccess.decisions();
            int y = 0;
            for (ClientAccess.Entry e : all) {
                JTextField ip = value(e.ip());
                ip.setColumns(0);
                JLabel state = new JLabel(ctx.m(e.allowed() ? "app.access.allowed" : "app.access.denied"));
                state.setFont(Ui.font(Font.BOLD, 12f));
                state.setForeground(e.allowed() ? Ui.BC700 : Ui.DANGER);
                JLabel model = new JLabel(UaSummary.of(e.userAgent()));
                model.setFont(Ui.font(Font.PLAIN, 12.5f));
                model.setForeground(Ui.MUTED);
                model.setToolTipText(e.userAgent());
                model.setMinimumSize(new Dimension(0, model.getPreferredSize().height));
                JButton remove = Ui.iconButton("trash", ctx.m("app.access.devices.remove"), true, () -> ClientAccess.forget(e.ip()));
                GridBagConstraints c = new GridBagConstraints();
                c.gridy = y++;
                c.anchor = GridBagConstraints.BASELINE_LEADING;
                c.insets = new Insets(Ui.px(2), 0, Ui.px(2), Ui.px(12));
                c.gridx = 0;
                list.add(ip, c);
                c.gridx = 1;
                list.add(state, c);
                c.gridx = 2;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                list.add(model, c);
                c.gridx = 3;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                c.anchor = GridBagConstraints.CENTER;
                c.insets = new Insets(Ui.px(2), 0, Ui.px(2), 0);
                list.add(remove, c);
            }
            none.setVisible(all.isEmpty());
            list.setVisible(!all.isEmpty());
            clearRow.setVisible(!all.isEmpty());
            clearLine.setVisible(!all.isEmpty());
            list.revalidate();
            list.repaint();
        };
        ctx.listen(ClientAccess::onChange, fill);
        fill.run();
        return column(heading(ctx.m("app.access.devices.title")), desc(ctx.m("app.access.devices.desc")), line(), none, list,
                clearLine, clearRow);
    }
}
