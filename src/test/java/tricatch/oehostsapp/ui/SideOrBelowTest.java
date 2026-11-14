package tricatch.oehostsapp.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link SettingsKit#sideOrBelow}: side by side while it fits, otherwise the right part goes underneath. */
class SideOrBelowTest {

    private static JComponent box(int w, int h) {
        JPanel b = new JPanel();
        b.setPreferredSize(new Dimension(w, h));
        return b;
    }

    @Test
    void wideRow_keepsBothOnOneLine() {
        JComponent qr = box(196, 196), btn = box(120, 32);
        JPanel row = SettingsKit.sideOrBelow(qr, btn, 10);
        row.setSize(500, 196);
        row.doLayout();
        assertEquals(0, qr.getX());
        assertEquals(500 - btn.getWidth(), btn.getX());
        assertEquals(0, btn.getY());
        assertFalse(qr.getBounds().intersects(btn.getBounds()));
    }

    @Test
    void narrowRow_putsTheButtonUnderTheQr() {
        JComponent qr = box(196, 196), btn = box(120, 32);
        JPanel row = SettingsKit.sideOrBelow(qr, btn, 10);
        row.setSize(260, 400); // less than 196 + gap + 120
        row.doLayout();
        assertEquals(0, btn.getX());
        assertTrue(btn.getY() >= qr.getY() + qr.getHeight(), "button below the QR");
        assertFalse(qr.getBounds().intersects(btn.getBounds()));
        assertTrue(row.getPreferredSize().height >= qr.getHeight() + btn.getHeight(), "height grows for the stacked layout");
    }
}
