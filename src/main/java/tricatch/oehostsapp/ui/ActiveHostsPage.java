package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.proxy.ForwardProxy;
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
 * oeHosts, merged), for tools that read a hosts list from a URL: oeHosts-Agent, hosts manager
 * programs with remote sources, and the like. Reachable like the rest of the web service (127.0.0.1, plus its network
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
        // The URL in the command box look of the Java agent page's option.
        JTextArea info = fieldScript("-");
        JButton copy = primary(ctx.m("app.settings.active.copy"), "copy", () -> {
            OeDialog.copy(info.getText());
            ctx.toast(ctx.m("app.settings.copied"));
        });
        JComponent webOff = OeDialog.warn(ctx.m("app.settings.active.web.stopped"), null);
        JPanel webOffButtons = buttons(Ui.secondary(ctx.m("app.settings.phone.open.fwd"), "settings", () -> tab.showPage("fwd")));

        // Which address the URL names: 127.0.0.1 (the default, for tools on this PC), or one of this
        // PC's addresses (another PC; the proxy service listens on all of them).
        JComboBox<String> host = Ui.combo();
        host.setMinimumSize(new Dimension(0, host.getPreferredSize().height));
        JLabel hostLabel = label(ctx.m("app.settings.phone.address"));
        // The label above, the list under it, as wide as the longest address (not the whole row).
        addressNames(ctx, host);
        JPanel hostRow = OeDialog.stack(6, hostLabel, buttons(host));

        // A long list scrolls inside its box, which takes the rest of the half's height, instead of
        // making the page taller. The box keeps the script look: the frame on the scroll pane, the
        // padding on the text.
        JTextArea preview = OeDialog.script("");
        preview.setColumns(0);
        preview.setLineWrap(false);
        preview.setBorder(Ui.pad(8, 10, 8, 10));
        ((DefaultCaret) preview.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        JScrollPane previewScroll = Ui.plainScroll(preview);
        previewScroll.setBorder(new LineBorder(Ui.BORDER, 1, true));
        previewScroll.getViewport().setBackground(preview.getBackground());
        // Small on purpose: the BorderLayout stretches it, and a large preferred height would make the page scroll.
        previewScroll.setPreferredSize(new Dimension(0, Ui.px(120)));
        JComponent empty = desc(ctx.m("app.settings.active.empty"));

        Runnable showUrl = () -> {
            int port = ctx.settings(st -> st.fwdproxy.port);
            Object sel = host.getSelectedItem();
            info.setText(ShareServer.activeUrl(sel == null ? Network.LOOPBACK : sel.toString(), port));
            info.setCaretPosition(0);
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
            boolean on = ForwardProxy.state() == ForwardProxy.State.RUNNING;
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
        ctx.listen(ForwardProxy::onChange, refreshUrl);
        refreshUrl.run();
        refreshPreview.run();
        JPanel right = new JPanel(new BorderLayout(0, Ui.px(14)));
        right.setOpaque(false);
        right.add(column(heading(ctx.m("app.settings.active.preview.desc")), line(), empty), BorderLayout.NORTH);
        right.add(previewScroll, BorderLayout.CENTER);
        return fullPage(halves(
                column(desc(ctx.m("app.settings.active.desc")), line(), webOff, webOffButtons, hostRow,
                        OeDialog.stack(6, label(ctx.m("app.settings.active.address")), info), line(),
                        rightAligned(copy)),
                right));
    }
}
