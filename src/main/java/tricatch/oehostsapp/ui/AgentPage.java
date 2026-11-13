package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.hosts.HostsAgent;
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

        JTextArea option = OeDialog.script("");
        option.setColumns(0);
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

        boolean[] usable = {false};
        // The agent reads the profiles selected in oeHosts (ShareServer.ACTIVE_PATH), so the option
        // needs no profile choice; it changes only with the web service port.
        Runnable showOption = () -> {
            int port = ctx.settings(s -> s.web.port);
            Path agent = HostsAgent.path();
            option.setText(agent == null ? "-" : HostsAgent.option(agent, ShareServer.activeUrl(port)));
            copy.setEnabled(usable[0]);
        };
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
            option.setVisible(usable[0]);
            actionsLine.setVisible(usable[0]);
            actions.setVisible(usable[0]);
            folderLine.setVisible(HostsAgent.path() != null);
            folderRow.setVisible(HostsAgent.path() != null);
            showOption.run();
            for (JTextField f : v) f.setCaretPosition(0);
            info.revalidate();
        };
        refresh.run();
        ctx.listen(HostsAgent::onChange, refresh);
        // The web service restarts on a new port from its settings page.
        ctx.listen(ShareServer::onChange, showOption);
        return fullPage(halves(
                column(desc(ctx.m("app.settings.agent.desc")), line(), missing, failed, option, actionsLine, actions),
                column(heading(ctx.m("app.settings.agent.install.title")), line(), info, inUse, folderLine, folderRow)));
    }
}
