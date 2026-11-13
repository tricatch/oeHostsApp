package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.web.ShareServer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.HierarchyEvent;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → oeHosts → active hosts: the {@code /active-hosts} URL (the profiles selected in
 * oeHosts, merged), for tools that read a hosts list from a URL: oeHosts-Agent, SwitchHosts' remote
 * hosts, and the like. Reachable like the rest of the web service (127.0.0.1, plus its network
 * address when one is chosen). Left: the URL to copy; right: what it returns now.
 */
final class ActiveHostsPage {

    private final SettingsTab tab;
    private final AppContext ctx;

    ActiveHostsPage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        JTextField[] v = new JTextField[1];
        JPanel info = table(new String[][]{{ctx.m("app.settings.active.url"), "-"}}, v);
        JButton copy = primary(ctx.m("app.settings.active.copy"), "copy", () -> {
            OeDialog.copy(v[0].getText());
            ctx.toast(ctx.m("app.settings.copied"));
        });
        JComponent webOff = OeDialog.warn(ctx.m("app.settings.active.web.stopped"), null);
        JPanel webOffButtons = buttons(Ui.secondary(ctx.m("app.settings.phone.open.web"), "settings", () -> tab.showPage("web")));

        // Which address the URL names: 127.0.0.1 (the default, for tools on this PC), or, while the
        // web service is open to the network, one of the addresses it answers on (another PC).
        JComboBox<String> host = Ui.combo();
        host.setMinimumSize(new Dimension(0, host.getPreferredSize().height));
        JLabel hostLabel = label(ctx.m("app.settings.phone.address"));
        alignWithTable(info, ROW_GAP, hostLabel);
        JPanel hostRow = formRow(hostLabel, host);

        // A long list scrolls inside its box, which takes the rest of the half's height, instead of
        // making the page taller. The box keeps the script look: the frame on the scroll pane, the
        // padding on the text.
        JTextArea preview = OeDialog.script("");
        preview.setColumns(0);
        preview.setLineWrap(false);
        preview.setBorder(new EmptyBorder(8, 10, 8, 10));
        ((DefaultCaret) preview.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        JScrollPane previewScroll = Ui.plainScroll(preview);
        previewScroll.setBorder(new LineBorder(Ui.BORDER, 1, true));
        previewScroll.getViewport().setBackground(preview.getBackground());
        // Small on purpose: the BorderLayout stretches it, and a large preferred height would make the page scroll.
        previewScroll.setPreferredSize(new Dimension(0, 120));
        JComponent empty = desc(ctx.m("app.settings.active.empty"));

        Runnable showUrl = () -> {
            int port = ctx.settings(st -> st.web.port);
            Object sel = host.getSelectedItem();
            v[0].setText(ShareServer.activeUrl(sel == null ? Network.LOOPBACK : sel.toString(), port));
            v[0].setCaretPosition(0);
        };
        boolean[] filling = {false};
        Runnable refreshUrl = () -> {
            Object prev = host.getSelectedItem();
            filling[0] = true;
            host.removeAllItems();
            for (String h : ShareServer.urlHosts()) host.addItem(h);
            if (prev != null) host.setSelectedItem(prev);
            if (host.getSelectedIndex() < 0) host.setSelectedIndex(0);
            filling[0] = false;
            hostRow.setVisible(host.getItemCount() > 1);
            showUrl.run();
            boolean on = ShareServer.state() == ShareServer.State.RUNNING;
            webOff.setVisible(!on);
            webOffButtons.setVisible(!on);
        };
        host.addActionListener(e -> {
            if (!filling[0]) showUrl.run();
        });
        // The selection changes on the oeHosts tab: read it again whenever the page is shown.
        Runnable refreshPreview = () -> {
            String text = ShareServer.activeHosts(ctx.read(() -> ctx.rawStore().selectedContents(Store.Kind.HOSTS)));
            preview.setText(text.strip());
            preview.setCaretPosition(0);
            previewScroll.setVisible(!text.isEmpty());
            empty.setVisible(text.isEmpty());
        };
        // On a part that is always visible (the preview hides while nothing is selected).
        info.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && info.isShowing()) refreshPreview.run();
        });
        ctx.listen(ShareServer::onChange, refreshUrl);
        refreshUrl.run();
        refreshPreview.run();
        JPanel right = new JPanel(new BorderLayout(0, 14));
        right.setOpaque(false);
        right.add(column(desc(ctx.m("app.settings.active.preview.desc")), line(), empty), BorderLayout.NORTH);
        right.add(previewScroll, BorderLayout.CENTER);
        return fullPage(halves(
                column(desc(ctx.m("app.settings.active.desc")), line(), webOff, webOffButtons, hostRow, info, line(),
                        rightAligned(copy)),
                right));
    }
}
