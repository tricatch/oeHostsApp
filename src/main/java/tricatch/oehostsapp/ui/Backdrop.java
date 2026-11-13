package tricatch.oehostsapp.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The dimmed window behind an open dialog (the web pages' {@code .modal-backdrop}): a translucent
 * black layer over the window, below the toast layer, from when the first dialog opens until the
 * last one closes. No blur: half-blurred text behind a dialog is tiring to look at, while dimmed
 * text stays sharp.
 */
final class Backdrop {

    private static final Color DIM = new Color(0, 0, 0, 90);

    private static final Map<JRootPane, Overlay> open = new IdentityHashMap<>();

    private Backdrop() {}

    /** Covers {@code owner}'s window; each call needs a matching {@link #hide}. */
    static void show(Window owner) {
        if (!(owner instanceof RootPaneContainer rpc)) return;
        JRootPane root = rpc.getRootPane();
        Overlay o = open.get(root);
        if (o != null) {
            o.count++;
            return;
        }
        JLayeredPane layers = root.getLayeredPane();
        if (layers.getWidth() <= 0 || layers.getHeight() <= 0) return;
        o = new Overlay();
        o.setBounds(0, 0, layers.getWidth(), layers.getHeight());
        Overlay ov = o;
        o.resize = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                ov.setBounds(0, 0, layers.getWidth(), layers.getHeight());
            }
        };
        layers.addComponentListener(o.resize);
        layers.add(o, JLayeredPane.MODAL_LAYER);
        layers.repaint();
        open.put(root, o);
    }

    static void hide(Window owner) {
        if (!(owner instanceof RootPaneContainer rpc)) return;
        JRootPane root = rpc.getRootPane();
        Overlay o = open.get(root);
        if (o == null || --o.count > 0) return;
        open.remove(root);
        JLayeredPane layers = root.getLayeredPane();
        layers.removeComponentListener(o.resize);
        layers.remove(o);
        layers.repaint();
    }

    private static final class Overlay extends JComponent {
        int count = 1;
        ComponentAdapter resize;

        Overlay() {
            // Swallows clicks that would otherwise reach the window underneath.
            addMouseListener(new MouseAdapter() {});
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(DIM);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}
