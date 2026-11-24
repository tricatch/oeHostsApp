package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.mcp.CliSkill;
import tricatch.oehostsapp.mcp.McpServer;
import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Store;

import javax.swing.*;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → General → AI tools: the switch and port of the MCP server for AI coding tools, and whether
 * it offers the Network tab's traffic, on the left (applying saves them and restarts it); its state,
 * the command that registers it with Claude Code (with a button that replaces the access token) and
 * the URL of the command line's skill ({@link CliSkill}) on the right.
 */
final class McpPage {

    private final SettingsTab tab;
    private final AppContext ctx;

    McpPage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        Switch on = new Switch(ctx.settings(st -> st.mcp.enabled), Ui.BC500);
        JPanel onField = buttons(on);
        JTextField port = portField(ctx.settings(st -> st.mcp.port));
        Switch traffic = new Switch(ctx.settings(st -> st.mcp.traffic), Ui.BC500);
        JLabel onLabel = label(ctx.m("app.settings.mcp.enabled"));
        JLabel portLabel = label(ctx.m("app.settings.fwdproxy.port"));
        JLabel trafficLabel = label(ctx.m("app.settings.mcp.traffic"));
        sameWidth(onLabel, portLabel, trafficLabel);
        JComponent trafficNote = desc(ctx.m("app.settings.mcp.traffic.desc"));

        JLabel st = tab.status("mcp");
        ServerStatus status = new ServerStatus(ctx, McpServer::onChange, () -> new ServerStatus.Snapshot(
                McpServer.state() == McpServer.State.RUNNING, McpServer.state() == McpServer.State.FAILED,
                McpServer.error(), Network.LOOPBACK, McpServer.port(), McpServer.state() == McpServer.State.OFF));

        JTextArea command = fieldScript("");
        Runnable showCommand = () -> {
            int p = ctx.settings(s -> s.mcp.port);
            String token = ctx.store(Store::mcpToken);
            command.setText(token == null ? "-" : registerCommand(p, token));
        };

        // The CLI skill's URL: an AI tool asked to install it fetches it from the running server.
        boolean hasJar = CliSkill.runningJar() != null;
        JTextArea skillUrl = fieldScript("");
        Runnable showSkillUrl = () -> skillUrl.setText(hasJar
                ? CliSkill.url(ctx.settings(s -> s.mcp.port)) : ctx.m("app.settings.mcp.skill.nojar"));
        JButton copySkill = primary(ctx.m("app.settings.mcp.skill.copy"), "copy", () -> {
            OeDialog.copy(skillUrl.getText());
            ok(st, ctx.m("app.settings.copied"));
        });
        copySkill.setEnabled(hasJar);
        showSkillUrl.run();

        JButton[] self = {null};
        // Saves the switches and the port, then restarts the server with them; the state rows show the outcome.
        JButton apply = self[0] = primary(ctx.m("app.settings.mcp.apply"), "refresh-cw", () -> {
            int p = port(port);
            if (p < 0) {
                error(st, ctx.m("app.settings.error.port"));
                return;
            }
            boolean enable = on.isOn();
            boolean offerTraffic = traffic.isOn();
            if (!ctx.saveSettings(s -> {
                s.mcp.enabled = enable;
                s.mcp.port = p;
                s.mcp.traffic = offerTraffic;
            })) return;
            showCommand.run();
            showSkillUrl.run();
            status.restart(self[0], st, McpServer::restart);
        });

        JButton copy = primary(ctx.m("app.settings.mcp.copy"), "copy", () -> {
            OeDialog.copy(command.getText());
            ok(st, ctx.m("app.settings.copied"));
        });
        JButton newToken = Ui.secondary(ctx.m("app.settings.mcp.token.new"), "key-round", () -> {
            if (!OeDialog.confirm(ctx, ctx.m("app.settings.mcp.token.new"), ctx.plain("app.settings.mcp.token.new.confirm"))) return;
            if (ctx.store(Store::newMcpToken) == null) return;
            showCommand.run();
            ok(st, ctx.m("app.settings.mcp.token.renewed"));
        });
        showCommand.run();

        return fullPage(halves(
                column(desc(ctx.m("app.settings.mcp.desc")), line(),
                        OeDialog.stack(10, formRow(onLabel, onField), formRow(portLabel, buttons(port)),
                                formRow(trafficLabel, buttons(traffic)), underField(trafficNote, trafficLabel)),
                        line(), rightAligned(apply)),
                column(heading(ctx.m("app.settings.server.status.title")), line(), status.panel,
                        (JComponent) Box.createVerticalStrut(Ui.px(18)),
                        heading(ctx.m("app.settings.mcp.register.title")), desc(ctx.m("app.settings.mcp.register.desc")),
                        line(), command, line(), rightAligned(newToken, copy),
                        (JComponent) Box.createVerticalStrut(Ui.px(18)),
                        heading(ctx.m("app.settings.mcp.skill.title")), desc(ctx.m("app.settings.mcp.skill.desc")),
                        line(), skillUrl, line(), rightAligned(copySkill))));
    }

    /** The Claude Code command that adds this server for every project of the user. */
    static String registerCommand(int port, String token) {
        return "claude mcp add --transport http --scope user oehostsapp " + McpServer.url(port)
                + " --header \"Authorization: Bearer " + token + "\"";
    }
}
