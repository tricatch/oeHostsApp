package tricatch.oehostsapp.ui;

import javax.swing.*;
import java.awt.*;

/** The dialogs of the oeHosts screen (the web page's modals). */
final class Dialogs {

    private Dialogs() {}

    /** A command to copy: an explanation on top, the command box, Copy / Close. */
    static void command(AppContext ctx, String title, JComponent top, String cmd) {
        OeDialog.open(ctx, title, d -> {
            JTextArea script = OeDialog.script(cmd);
            JScrollPane sp = new JScrollPane(script);
            sp.setBorder(BorderFactory.createEmptyBorder());
            // Fixed height: the command is always a long kill-and-launch script, scrolled inside.
            sp.setPreferredSize(new Dimension(640, 360));
            fixWidth(top, 640);
            d.body(OeDialog.stack(10, top, sp));
            d.button(ctx.m("modal.chrome.cmd.copy"), true, () -> {
                OeDialog.copy(cmd);
                d.dispose();
                ctx.toast(ctx.m("toast.chrome.cmd.copied"));
            });
            d.button(ctx.m("btn.close"), false, d::dispose);
        });
    }

    /** Gives a wrapping text area a fixed width so its preferred height is right. */
    static void fixWidth(JComponent c, int w) {
        if (c instanceof JTextArea a) {
            a.setSize(new Dimension(w, Short.MAX_VALUE));
            a.setPreferredSize(new Dimension(w, a.getPreferredSize().height));
        } else if (c instanceof JPanel p) {
            for (Component k : p.getComponents()) if (k instanceof JComponent j) fixWidth(j, w - 22);
        }
    }
}
