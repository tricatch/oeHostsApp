package tricatch.oehubapp.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Bootstrap-style {@code form-switch} toggle. {@code onColor} is BC300 for the sidebar list
 * switches and BC500 for the launcher options, as in the web CSS.
 */
public final class Switch extends JComponent {

    private boolean on;
    private final Color onColor;
    private final List<Consumer<Boolean>> listeners = new ArrayList<>();

    public Switch(boolean on, Color onColor) {
        this.on = on;
        this.onColor = onColor;
        setPreferredSize(new Dimension(28, 16));
        setMinimumSize(getPreferredSize());
        setMaximumSize(getPreferredSize());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e)) return;
                e.consume();
                setOn(!Switch.this.on);
                for (Consumer<Boolean> l : List.copyOf(listeners)) l.accept(Switch.this.on);
            }
        });
    }

    /** Called with the new state after a user click (not after {@link #setOn}). */
    public void onChange(Consumer<Boolean> l) {
        listeners.add(l);
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean on) {
        if (this.on == on) return;
        this.on = on;
        repaint();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        if (!isEnabled()) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
        float w = 26, h = 14;
        float x = (getWidth() - w) / 2f, y = (getHeight() - h) / 2f;
        RoundRectangle2D track = new RoundRectangle2D.Float(x, y, w, h, h, h);
        if (on) {
            g2.setColor(onColor);
            g2.fill(track);
        } else {
            g2.setColor(Color.WHITE);
            g2.fill(track);
            g2.setColor(new Color(0xced4da));
            g2.draw(track);
        }
        float d = h - 4;
        float kx = on ? x + w - d - 2 : x + 2;
        g2.setColor(on ? Color.WHITE : new Color(0, 0, 0, 64));
        g2.fillOval(Math.round(kx), Math.round(y + 2), Math.round(d), Math.round(d));
        g2.dispose();
    }
}
