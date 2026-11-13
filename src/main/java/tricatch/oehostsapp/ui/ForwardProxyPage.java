package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.proxy.ForwardProxy;
import tricatch.oehostsapp.store.Settings;

import javax.swing.*;
import java.awt.*;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → General → forward proxy: its PC address, port and optional Basic authentication on
 * the left (saving restarts it), its state and addresses on the right.
 */
final class ForwardProxyPage {

    private final SettingsTab tab;
    private final AppContext ctx;

    ForwardProxyPage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        Settings.ForwardProxy stored = ctx.settings(st -> st.fwdproxy);
        JTextField port = portField(stored.port);
        // The address, port and authentication labels share one width, so their fields line up.
        JLabel nicLabel = label(ctx.m("app.settings.fwdproxy.nic"));
        JLabel portLabel = label(ctx.m("app.settings.fwdproxy.port"));
        JLabel authLabel = label(ctx.m("app.settings.fwdproxy.auth"));
        sameWidth(nicLabel, portLabel, authLabel);
        // Where it listens: this PC only (the default), one network card, or every interface.
        NetworkChoice bind = new NetworkChoice(ctx, stored.bind, "app.settings.fwdproxy.bind.all.warn", nicLabel);

        // Authentication: none (the default) or Basic (username and password) other devices must send; the
        // fields are usable only while that is chosen, and keep their text when switched off.
        JRadioButton authNone = new JRadioButton(ctx.m("app.settings.fwdproxy.auth.none"), !stored.auth);
        JRadioButton authPassword = new JRadioButton(ctx.m("app.settings.fwdproxy.auth.password"), stored.auth);
        ButtonGroup authGroup = new ButtonGroup();
        for (JRadioButton r : new JRadioButton[]{authNone, authPassword}) {
            r.setOpaque(false);
            r.setFocusable(false);
            r.setFont(Ui.font(Font.PLAIN, 13f));
            authGroup.add(r);
        }
        JTextField user = Ui.formField(new JTextField(stored.user == null ? "" : stored.user, 10), "");
        user.putClientProperty("JTextField.placeholderText", ctx.m("app.settings.fwdproxy.auth.user"));
        user.setMaximumSize(new Dimension(180, 34));
        JPasswordField password = Ui.formField(new JPasswordField(stored.password == null ? "" : stored.password, 10),
                "; showRevealButton:true");
        password.putClientProperty("JTextField.placeholderText", ctx.m("app.settings.fwdproxy.auth.pw"));
        password.setMaximumSize(new Dimension(220, 34));
        JComponent authNote = underField(desc(ctx.m("app.settings.fwdproxy.auth.note")), nicLabel);
        // The network warning under the PC address shows for any address but 127.0.0.1, with or
        // without a password; the note under the credentials only while Basic is chosen.
        Runnable authState = () -> {
            boolean on = authPassword.isSelected();
            user.setEnabled(on);
            password.setEnabled(on);
            authNote.setVisible(on);
            bind.warn.setVisible(Network.isLan(bind.selected()));
        };
        authPassword.addItemListener(e -> {
            authState.run();
            if (authPassword.isSelected()) user.requestFocusInWindow();
        });
        authNone.addItemListener(e -> authState.run());
        bind.onChange = authState;
        authState.run();

        // The result shows in the bottom bar, like the editors' save status.
        JLabel st = tab.status("fwd");
        ServerStatus status = new ServerStatus(ctx, ForwardProxy::onChange, () -> new ServerStatus.Snapshot(
                ForwardProxy.state() == ForwardProxy.State.RUNNING, ForwardProxy.state() == ForwardProxy.State.FAILED,
                ForwardProxy.error(), ForwardProxy.bind(), ForwardProxy.port()));
        JButton[] self = {null};
        // Saves the port and the binding, then restarts the proxy with them; the state rows show the outcome.
        JButton restart = self[0] = primary(ctx.m("app.settings.server.restart"), "refresh-cw", () -> {
            int p = port(port);
            boolean auth = authPassword.isSelected();
            String id = user.getText().strip();
            String pw = new String(password.getPassword());
            // In screen order: the port, then the username before the password.
            if (p < 0) {
                error(st, ctx.m("app.settings.error.port"));
                return;
            }
            if (auth && id.isEmpty()) {
                error(st, ctx.m("app.settings.error.fwdproxy.user"));
                return;
            }
            if (auth && pw.isEmpty()) {
                error(st, ctx.m("app.settings.error.fwdproxy.password"));
                return;
            }
            String to = bind.selected();
            if (!ctx.saveSettings(s -> {
                Settings.ForwardProxy fp = s.fwdproxy;
                fp.port = p;
                fp.bind = to;
                fp.auth = auth;
                // Empty fields with authentication off keep the stored username and password for next time.
                if (!id.isEmpty()) fp.user = id;
                if (!pw.isEmpty()) fp.password = pw;
            })) return;
            ctx.fireSettingsChanged();
            status.restart(self[0], st, ForwardProxy::restart);
        });


        // Under the authentication choice, the username and password fields sit on a row of their
        // own, starting where the fields above start.
        JPanel credentials = underField(buttons(user, password), nicLabel);
        return fullPage(halves(column(desc(ctx.m("app.settings.fwdproxy.desc")), line(), bind.row, bind.warn,
                buttons(portLabel, port), buttons(authLabel, authNone, authPassword), credentials, authNote,
                line(), rightAligned(restart)), column(status.panel)));
    }
}
