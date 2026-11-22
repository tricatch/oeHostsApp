package tricatch.oehostsapp.ui;

import org.junit.jupiter.api.Test;

import javax.swing.plaf.basic.BasicHTML;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlainTextTest {

    @Test
    void textFromOutsideIsNeverTakenForMarkup() {
        for (String s : List.of("<html><i>x", "<HTML>", "<HtMl><b>x")) {
            assertTrue(BasicHTML.isHTMLString(s), "Swing would render it: " + s);
            String plain = Ui.plain(s);
            assertFalse(BasicHTML.isHTMLString(plain), plain);
            assertEquals(s, plain.strip(), "still readable as it was");
        }
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        for (String s : List.of("", "api.example.com", "<htm", "a <html> b")) assertSame(s, Ui.plain(s));
        assertNull(Ui.plain(null));
    }

    @Test
    void aDeviceNameTakenFromItsUserAgentIsPlainToo() {
        String model = UaSummary.of("Mozilla/5.0 (Linux; Android 14; <html><i>x) Chrome/120.0");
        assertFalse(BasicHTML.isHTMLString(Ui.plain(model)), model);
    }
}
