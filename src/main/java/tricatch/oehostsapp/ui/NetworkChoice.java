package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.net.Network;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A server's address as a drop-down of this PC's networks: 127.0.0.1 (this PC only), each
 * IPv4 address with its network card's name, and 0.0.0.0 (every interface). A stored address this
 * PC no longer has stays in the list, marked, so the setting is visible rather than lost.
 */
final class NetworkChoice {

    /** One entry of the drop-down: the address to bind and the text shown for it. */
    private record Choice(String address, String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    private final AppContext ctx;
    /** The form label, and the drop-down under it. */
    final JPanel row;
    /** The network-exposure warning, shown while the choice is not 127.0.0.1; placed by the page. */
    final JTextPane warn;
    private final JComboBox<Choice> combo = Ui.combo();
    private String selected;
    /** Runs after the choice changes, instead of showing {@link #warn}: the page decides what shows. */
    Runnable onChange;
    /** Set while the list is being refilled, so the refill does not count as the user's choice. */
    private boolean filling;

    NetworkChoice(AppContext ctx, String current, String warnKey, JLabel label) {
        this.ctx = ctx;
        selected = current;
        warn = SettingsKit.desc(ctx.m(warnKey));
        warn.setForeground(Ui.DANGER);
        combo.addActionListener(e -> {
            if (filling || !(combo.getSelectedItem() instanceof Choice c)) return;
            selected = c.address();
            changed();
        });
        // The label above, the drop-down under it as wide as its longest entry (not the whole row),
        // as on the other pages that choose one of this PC's addresses.
        row = OeDialog.stack(6, label, SettingsKit.buttons(combo));
        // The networks are read again each time the list opens (a cable plugged in, another
        // Wi-Fi since the page was built), so no refresh button is needed.
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                reload();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        combo.setMinimumSize(new Dimension(80, combo.getPreferredSize().height));
        reload();
    }

    String selected() {
        return selected;
    }

    /** Lists this PC's networks again (each time the drop-down opens), keeping the choice. */
    void reload() {
        List<Choice> items = new ArrayList<>();
        items.add(new Choice(Network.LOOPBACK, ctx.m("app.settings.bind.loopback", Network.LOOPBACK)));
        boolean found = false;
        for (Network.Lan l : Network.addresses()) {
            items.add(new Choice(l.ip(), ctx.m("app.settings.bind.iface", l.ip(), l.iface())));
            found |= l.ip().equals(selected);
        }
        boolean special = Network.LOOPBACK.equals(selected) || Network.ALL.equals(selected);
        if (!found && !special) items.add(new Choice(selected, ctx.m("app.settings.bind.iface.missing", selected)));
        items.add(new Choice(Network.ALL, ctx.m("app.settings.fwdproxy.bind.all")));
        filling = true;
        try {
            combo.removeAllItems();
            for (Choice c : items) {
                combo.addItem(c);
                if (c.address().equals(selected)) combo.setSelectedItem(c);
            }
        } finally {
            filling = false;
        }
        changed();
    }

    private void changed() {
        if (onChange != null) onChange.run();
        else warn.setVisible(Network.isLan(selected));
        row.revalidate();
        row.repaint();
    }
}
