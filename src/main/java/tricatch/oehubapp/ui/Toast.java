package tricatch.oehubapp.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** {@code .oe-toast}: a white box with a green border at the top center, gone after 2.5 s; at least {@link #MIN_WIDTH} wide. */
final class Toast {

    private static final int MIN_WIDTH = 280;

    private static JComponent current;

    private Toast() {}

    static void show(JFrame frame, String text) {
        JLayeredPane layers = frame.getLayeredPane();
        if (current != null) layers.remove(current);
        JLabel l = new JLabel(text, Ui.icon("circle-dashed-check", 16, Ui.BC500), SwingConstants.LEFT) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, 18));
                g2.fillRoundRect(1, 3, getWidth() - 2, getHeight() - 3, 24, 24);
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 3, 24, 24);
                g2.setColor(Ui.BC300);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 4, 24, 24);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setIconTextGap(10);
        l.setFont(Ui.font(Font.BOLD, 13f));
        l.setForeground(new Color(0x3a6b1a));
        l.setBorder(new EmptyBorder(12, 22, 15, 22));
        l.setHorizontalAlignment(SwingConstants.CENTER);
        // Short messages ("Exported.") would make a stubby box: keep a minimum width.
        Dimension d = l.getPreferredSize();
        d.width = Math.max(d.width, MIN_WIDTH);
        l.setBounds((layers.getWidth() - d.width) / 2, 24, d.width, d.height);
        layers.add(l, JLayeredPane.POPUP_LAYER);
        layers.repaint();
        current = l;
        Timer t = new Timer(2500, e -> {
            layers.remove(l);
            layers.repaint();
            if (current == l) current = null;
        });
        t.setRepeats(false);
        t.start();
    }
}
