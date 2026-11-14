package tricatch.oehostsapp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Ui#clickOn}: a click is press + release inside the area, even if the pointer moved a little. */
class ClickOnTest {

    private final List<Integer> clicks = new ArrayList<>();
    private JPanel area;
    private JLabel child;
    private MouseAdapter h;

    @BeforeEach
    void setUp() {
        area = new JPanel(null);
        area.setSize(200, 36);
        child = new JLabel("name");
        child.setBounds(20, 8, 60, 20);
        area.add(child);
        h = Ui.clickOn(area, clicks::add);
    }

    private static MouseEvent ev(Component c, int id, int x, int y, int count, int button) {
        int mods = id == MouseEvent.MOUSE_PRESSED ? buttonMask(button) : 0;
        return new MouseEvent(c, id, System.currentTimeMillis(), mods, x, y, count, false, button);
    }

    private static int buttonMask(int button) {
        return button == MouseEvent.BUTTON1 ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
    }

    private void click(Component c, int px, int py, int rx, int ry, int count, int button) {
        h.mousePressed(ev(c, MouseEvent.MOUSE_PRESSED, px, py, count, button));
        h.mouseReleased(ev(c, MouseEvent.MOUSE_RELEASED, rx, ry, count, button));
    }

    @Test
    void releaseAfterSmallMove_stillClicks() {
        // The case mouseClicked drops: the pointer moved 3px between press and release.
        click(area, 100, 18, 103, 19, 1, MouseEvent.BUTTON1);
        assertEquals(List.of(1), clicks);
    }

    @Test
    void releaseOnChild_isConvertedToTheArea() {
        click(child, 5, 5, 8, 6, 1, MouseEvent.BUTTON1);
        assertEquals(List.of(1), clicks);
    }

    @Test
    void releaseOutsideTheArea_cancels() {
        click(area, 100, 18, 100, 80, 1, MouseEvent.BUTTON1);
        assertTrue(clicks.isEmpty());
    }

    @Test
    void releaseWithoutPress_isIgnored() {
        h.mouseReleased(ev(area, MouseEvent.MOUSE_RELEASED, 100, 18, 1, MouseEvent.BUTTON1));
        assertTrue(clicks.isEmpty());
    }

    @Test
    void rightButton_isNotAClick() {
        click(area, 100, 18, 100, 18, 1, MouseEvent.BUTTON3);
        assertTrue(clicks.isEmpty());
    }

    @Test
    void doubleClick_passesTheCount() {
        click(area, 100, 18, 101, 18, 1, MouseEvent.BUTTON1);
        click(area, 101, 18, 102, 18, 2, MouseEvent.BUTTON1);
        assertEquals(List.of(1, 2), clicks);
    }
}
