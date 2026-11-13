package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.hosts.HostsAgent;
import tricatch.oehostsapp.proxy.ForwardProxy;
import tricatch.oehostsapp.web.ShareServer;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → oeHosts → Java agent (oeHosts-Agent): the {@code -javaagent} option for the profiles
 * selected in oeHosts, ready to copy into a Java command line or an IDE's VM options, on the left;
 * where the app installed the agent and its version on the right. Follows
 * {@link HostsAgent} as it changes (a release downloaded at start).
 */
final class AgentPage {

    private final SettingsTab tab;
    private final AppContext ctx;

    AgentPage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        JTextField[] v = new JTextField[2];
        JPanel info = table(new String[][]{
                {ctx.m("app.settings.agent.file"), "-"},
                {ctx.m("app.settings.agent.version"), "-"}}, v);

        JTextArea option = fieldScript("");
        JComponent inUse = desc(ctx.m("app.settings.agent.inuse"));
        JComponent missing = OeDialog.warn(ctx.m("app.settings.agent.missing"), null);
        // Installing failed: the reason in the red box (a full disk, a folder without write access).
        JPanel failed = new JPanel(new BorderLayout());
        failed.setOpaque(false);
        JLabel st = tab.status("agent");
        JButton copy = primary(ctx.m("app.settings.agent.copy"), "copy", () -> {
            OeDialog.copy(option.getText());
            ok(st, ctx.m("app.settings.copied"));
        });
        JButton folder = Ui.secondary(ctx.m("app.settings.agent.folder"), "folder-open", () -> {
            if (HostsAgent.path() != null) openFolder(ctx, HostsAgent.path().getParent());
        });
        JComponent actionsLine = line();
        JPanel actions = rightAligned(copy);
        JComponent folderLine = line();
        JPanel folderRow = rightAligned(folder);

        // Which address the option's URL names: 127.0.0.1 (the default, an application on this PC),
        // or one of this PC's addresses (an application on another machine), as on the Active-Hosts page.
        JComboBox<String> host = Ui.combo();
        host.setMinimumSize(new Dimension(0, host.getPreferredSize().height));
        JLabel hostLabel = label(ctx.m("app.settings.phone.address"));
        sameWidth(hostLabel);
        // The label above, the list under it, as wide as the longest address (not the whole row).
        addressNames(ctx, host);
        JPanel hostRow = OeDialog.stack(6, hostLabel, buttons(host));
        JPanel optionRow = OeDialog.stack(6, label(ctx.m("app.settings.agent.option")), option);

        boolean[] usable = {false};
        // The agent reads the profiles selected in oeHosts (ShareServer.ACTIVE_PATH), so the option
        // needs no profile choice; it changes with the chosen address and the proxy service port.
        Runnable showOption = () -> {
            int port = ctx.settings(s -> s.fwdproxy.port);
            Path agent = HostsAgent.path();
            Object sel = host.getSelectedItem();
            String url = ShareServer.activeUrl(sel == null ? tricatch.oehostsapp.net.Network.LOOPBACK : sel.toString(), port);
            option.setText(agent == null ? "-" : HostsAgent.option(agent, url));
            copy.setEnabled(usable[0]);
        };
        boolean[] filling = {false};
        // This PC's addresses can change (another Wi-Fi): listed again whenever the page is shown.
        Runnable fillHosts = () -> {
            Object prev = host.getSelectedItem();
            filling[0] = true;
            host.removeAllItems();
            for (String h : ShareServer.urlHosts()) host.addItem(h);
            if (prev != null) host.setSelectedItem(prev);
            if (host.getSelectedIndex() < 0 && host.getItemCount() > 0) host.setSelectedIndex(0);
            filling[0] = false;
            showOption.run();
        };
        host.addActionListener(e -> {
            if (!filling[0]) showOption.run();
        });
        host.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && host.isShowing()) fillHosts.run();
        });
        Runnable refresh = () -> {
            HostsAgent.State state = HostsAgent.state();
            usable[0] = HostsAgent.path() != null
                    && (state == HostsAgent.State.INSTALLED || state == HostsAgent.State.IN_USE);
            v[0].setText(HostsAgent.path() == null ? "-" : HostsAgent.path().toString());
            v[1].setText(HostsAgent.version() == null ? "-" : HostsAgent.version());
            boolean fail = state == HostsAgent.State.FAILED;
            missing.setVisible(!usable[0] && !fail);
            failed.removeAll();
            if (fail) failed.add(OeDialog.warn(ctx.m("app.settings.agent.failed"), HostsAgent.error()));
            failed.setVisible(fail);
            inUse.setVisible(state == HostsAgent.State.IN_USE);
            optionRow.setVisible(usable[0]);
            hostRow.setVisible(usable[0]);
            actionsLine.setVisible(usable[0]);
            actions.setVisible(usable[0]);
            folderLine.setVisible(HostsAgent.path() != null);
            folderRow.setVisible(HostsAgent.path() != null);
            showOption.run();
            for (JTextField f : v) f.setCaretPosition(0);
            info.revalidate();
        };
        fillHosts.run();
        refresh.run();
        ctx.listen(HostsAgent::onChange, refresh);
        // The proxy service restarts on a new port from its settings page.
        ctx.listen(ForwardProxy::onChange, showOption);
        return fullPage(halves(
                column(desc(ctx.m("app.settings.agent.desc")), line(), missing, failed, hostRow, optionRow, actionsLine, actions),
                column(heading(ctx.m("app.settings.agent.install.title")), line(), info, inUse, folderLine, folderRow)));
    }
}
