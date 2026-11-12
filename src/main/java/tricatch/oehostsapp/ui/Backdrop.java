package tricatch.oehostsapp.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.image.BufferedImage;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The blurred, dimmed window behind an open dialog (the web pages' {@code .modal-backdrop}, with a
 * blur). A snapshot of the window taken when the first dialog opens, blurred by scaling it down and
 * back up, is laid over the window below the toast layer; it goes when the last dialog closes.
 */
final class Backdrop {

    /** Snapshot shrink factor: the blur radius, roughly. */
    private static final int SHRINK = 6;
    private static final Color DIM = new Color(0, 0, 0, 46);

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
        o = new Overlay(blurred(layers));
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

    /** The window's current content, blurred: drawn small, then scaled back up smoothly when painted. */
    private static BufferedImage blurred(JComponent c) {
        int w = Math.max(1, c.getWidth() / SHRINK), h = Math.max(1, c.getHeight() / SHRINK);
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.scale(1.0 / SHRINK, 1.0 / SHRINK);
        c.paint(g);
        g.dispose();
        // A 3x3 box blur on the small image softens what bilinear upscaling alone leaves blocky.
        float[] k = new float[9];
        java.util.Arrays.fill(k, 1f / 9);
        return new java.awt.image.ConvolveOp(new java.awt.image.Kernel(3, 3, k), java.awt.image.ConvolveOp.EDGE_NO_OP, null)
                .filter(small, null);
    }

    private static final class Overlay extends JComponent {
        final BufferedImage image;
        int count = 1;
        ComponentAdapter resize;

        Overlay(BufferedImage image) {
            this.image = image;
            // Swallows clicks that would otherwise reach the window underneath while it repaints.
            addMouseListener(new MouseAdapter() {});
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            g2.setColor(DIM);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }
}
