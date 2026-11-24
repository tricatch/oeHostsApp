package tricatch.oehostsapp.ui;

import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.Rectangle2D;
import java.text.CharacterIterator;

/**
 * A text area for the Network tab's detail panels: lines {@link #LINE_SPACING} times as tall as
 * usual and, for the headers and cookies ({@code rules}), a faint line under every line that has
 * text, so the rows of a long list are easy to follow across the panel. Blank lines get no rule, and
 * none at all while {@link #setRuled} is off (a placeholder text).
 *
 * <p>{@code JTextArea} has no line spacing of its own: its view lays lines out by the height of the
 * {@link FontMetrics} the component hands out, so {@link #getFontMetrics} returns metrics whose
 * ascent and descent are each half the extra space larger (the text stays centered in its row).
 */
@SuppressWarnings("serial") // a Swing component, never serialized
final class RuledTextArea extends JTextArea {

    static final Color RULE = new Color(0xececec);
    static final float LINE_SPACING = 1.3f;
    /** How far the rule sits above a row's bottom edge, at 100% scale. */
    static final int RULE_LIFT = 2;

    private final boolean rules;
    private boolean ruled = true;

    /** {@code rules}: draw the faint line under each line; otherwise only the line spacing applies. */
    RuledTextArea(boolean rules) {
        this.rules = rules;
    }

    void setRuled(boolean ruled) {
        if (this.ruled == ruled) return;
        this.ruled = ruled;
        repaint();
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        FontMetrics m = super.getFontMetrics(font);
        return new Spaced(m, Math.round(m.getHeight() * (LINE_SPACING - 1)));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!rules || !ruled) return;
        Insets in = getInsets();
        int h = getRowHeight();
        int lines = getLineCount();
        Rectangle clip = g.getClipBounds();
        int first = clip == null ? 0 : Math.max(0, (clip.y - in.top) / h);
        int last = clip == null ? lines - 1 : Math.min(lines - 1, (clip.y + clip.height - in.top) / h);
        Document d = getDocument();
        // A little above the row's bottom edge: letters sit low in their row (the ascent holds room for
        // accents), so a rule on the edge itself looks closer to the line below than to its own.
        int lift = Ui.px(RULE_LIFT);
        g.setColor(RULE);
        for (int i = first; i <= last; i++) {
            try {
                int s = getLineStartOffset(i);
                if (d.getText(s, getLineEndOffset(i) - s).isBlank()) continue;
            } catch (BadLocationException e) {
                continue;
            }
            int y = in.top + (i + 1) * h - 1 - lift;
            g.drawLine(in.left, y, getWidth() - in.right, y);
        }
    }

    /** {@code m} with {@code extra} pixels more line height, split between ascent and descent; widths unchanged. */
    private static final class Spaced extends FontMetrics {
        private static final long serialVersionUID = 1L;
        private final transient FontMetrics m;
        private final int above, below;

        Spaced(FontMetrics m, int extra) {
            super(m.getFont());
            this.m = m;
            this.above = extra / 2;
            this.below = extra - above;
        }

        @Override public int getAscent() { return m.getAscent() + above; }
        @Override public int getDescent() { return m.getDescent() + below; }
        @Override public int getLeading() { return m.getLeading(); }
        @Override public int getHeight() { return m.getHeight() + above + below; }
        @Override public int getMaxAscent() { return m.getMaxAscent() + above; }
        @Override public int getMaxDescent() { return m.getMaxDescent() + below; }
        @Override public int getMaxAdvance() { return m.getMaxAdvance(); }
        @Override public int charWidth(int codePoint) { return m.charWidth(codePoint); }
        @Override public int charWidth(char ch) { return m.charWidth(ch); }
        @Override public int stringWidth(String str) { return m.stringWidth(str); }
        @Override public int charsWidth(char[] data, int off, int len) { return m.charsWidth(data, off, len); }
        @Override public int bytesWidth(byte[] data, int off, int len) { return m.bytesWidth(data, off, len); }
        @Override public int[] getWidths() { return m.getWidths(); }
        @Override public boolean hasUniformLineMetrics() { return m.hasUniformLineMetrics(); }
        @Override public FontRenderContext getFontRenderContext() { return m.getFontRenderContext(); }
        @Override public LineMetrics getLineMetrics(String str, Graphics g) { return m.getLineMetrics(str, g); }
        @Override public LineMetrics getLineMetrics(String str, int b, int l, Graphics g) { return m.getLineMetrics(str, b, l, g); }
        @Override public LineMetrics getLineMetrics(char[] c, int b, int l, Graphics g) { return m.getLineMetrics(c, b, l, g); }
        @Override public LineMetrics getLineMetrics(CharacterIterator ci, int b, int l, Graphics g) { return m.getLineMetrics(ci, b, l, g); }
        @Override public Rectangle2D getStringBounds(String str, Graphics g) { return m.getStringBounds(str, g); }
        @Override public Rectangle2D getStringBounds(String str, int b, int l, Graphics g) { return m.getStringBounds(str, b, l, g); }
        @Override public Rectangle2D getStringBounds(char[] c, int b, int l, Graphics g) { return m.getStringBounds(c, b, l, g); }
        @Override public Rectangle2D getStringBounds(CharacterIterator ci, int b, int l, Graphics g) { return m.getStringBounds(ci, b, l, g); }
        @Override public Rectangle2D getMaxCharBounds(Graphics g) { return m.getMaxCharBounds(g); }
    }
}
