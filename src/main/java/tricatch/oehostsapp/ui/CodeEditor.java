package tricatch.oehostsapp.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.DocumentRange;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.LineNumberFormatter;
import org.fife.ui.rtextarea.LineNumberList;
import org.fife.ui.rtextarea.RTextScrollPane;
import tricatch.oehostsapp.store.ItemSearch;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Line-numbered editor that looks like the web pages' Ace editor (ace-init.js). */
public final class CodeEditor {

    private static final float LINE_HEIGHT = 1.6f;

    public final RSyntaxTextArea area;
    public final RTextScrollPane scroll;
    private boolean loading;
    /** Autosave state outline drawn over the editor, or null. */
    private Color outline;
    /** The find bar's current match (BC300 over the BC100 of the others), or null. */
    private Object current;
    private static final javax.swing.text.Highlighter.HighlightPainter CURRENT =
            new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(Ui.BC300);

    public CodeEditor(String syntax) {
        area = new RSyntaxTextArea() {
            // Ace on the web pages uses line-height 1.6; RSyntaxTextArea uses the bare font height.
            // The gutter and the text view both read these two values, so the extra space is split
            // evenly above and below each line and the line numbers stay aligned.
            @Override
            public int getLineHeight() {
                Font f = getFont(); // null while the superclass constructor runs
                int base = super.getLineHeight();
                return f == null ? base : Math.max(base, Math.round(f.getSize2D() * LINE_HEIGHT));
            }

            @Override
            public int getMaxAscent() {
                return super.getMaxAscent() + (getLineHeight() - super.getLineHeight()) / 2;
            }
        };
        area.setSyntaxEditingStyle(syntax == null ? SyntaxConstants.SYNTAX_STYLE_NONE : syntax);
        area.setFont(Ui.mono(13f));
        // No italics: the composite font's Hangul fallback has no italic face.
        org.fife.ui.rsyntaxtextarea.SyntaxScheme scheme = area.getSyntaxScheme();
        for (int i = 0; i < scheme.getStyleCount(); i++) {
            org.fife.ui.rsyntaxtextarea.Style st = scheme.getStyle(i);
            if (st != null && st.font != null && st.font.isItalic()) st.font = area.getFont();
        }
        area.setTabSize(2);
        area.setTabsEmulated(true);
        area.setCodeFoldingEnabled(false);
        area.setAntiAliasingEnabled(true);
        area.setHighlightCurrentLine(true);
        // Lighter than the gutter (#ebebeb) so the current line does not read as part of it.
        area.setCurrentLineHighlightColor(new Color(0xf5f5f5));
        area.setFadeCurrentLineHighlight(false);
        area.setMarkAllHighlightColor(Ui.BC100);
        area.setMarginLineEnabled(false);
        area.setBackground(Color.WHITE);
        area.setSelectionColor(new Color(0xb5d5ff));
        area.setBracketMatchingEnabled(false);
        area.setMargin(new Insets(Ui.px(2), Ui.px(4), Ui.px(2), Ui.px(4)));
        scroll = new RTextScrollPane(area, true) {
            // Drawn over the children, like the web's ::after overlay, so the layout never shifts.
            // While it shows, a child's repaint starts here too, or typing would paint over it.
            @Override
            protected boolean isPaintingOrigin() {
                return outline != null;
            }

            @Override
            public void paint(Graphics g) {
                super.paint(g);
                if (outline == null) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(outline);
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new java.awt.geom.Rectangle2D.Float(0.75f, 0.75f, getWidth() - 1.5f, getHeight() - 1.5f));
                g2.dispose();
            }
        };
        scroll.setBorder(BorderFactory.createEmptyBorder());
        // A white strip between the gutter and the text, so the current-line band does not touch the gutter.
        scroll.setViewportBorder(BorderFactory.createMatteBorder(0, Ui.px(4), 0, 0, Color.WHITE));
        scroll.getVerticalScrollBar().setUnitIncrement(Ui.px(16));
        Gutter g = scroll.getGutter();
        g.setBackground(new Color(0xebebeb));
        g.setLineNumberColor(new Color(0x333333));
        g.setLineNumberFont(Ui.mono(12f));
        g.setBorderColor(new Color(0xebebeb));
        g.setCurrentLineNumberColor(new Color(0x111111));
        g.setActiveLineRangeColor(new Color(0xdcdcdc));
        // Room for three digits from the start: the gutter would otherwise widen at line 100 and
        // shift the text sideways while typing.
        LineNumberFormatter numbers = LineNumberList.DEFAULT_LINE_NUMBER_FORMATTER;
        g.setLineNumberFormatter(new LineNumberFormatter() {
            @Override
            public String format(int lineNumber) {
                return numbers.format(lineNumber);
            }

            @Override
            public int getMaxLength(int maxLineNumber) {
                return Math.max(3, numbers.getMaxLength(maxLineNumber));
            }
        });
    }

    /** Outlines the editor in {@code c} (null: none), as {@link Autosave} does while an edit waits. */
    public void setOutline(Color c) {
        if (java.util.Objects.equals(c, outline)) return;
        outline = c;
        scroll.repaint();
    }

    /**
     * Preset text colouring ({@link PresetTokenMaker}): comment lines gray, lines that are not a
     * valid entry red.
     */
    public void presetHighlighting() {
        org.fife.ui.rsyntaxtextarea.SyntaxScheme scheme =
                (org.fife.ui.rsyntaxtextarea.SyntaxScheme) area.getSyntaxScheme().clone();
        org.fife.ui.rsyntaxtextarea.Style comment = scheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.COMMENT_EOL);
        comment.foreground = new Color(0x8a8f98);
        comment.font = area.getFont();
        org.fife.ui.rsyntaxtextarea.Style error = scheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.ERROR_IDENTIFIER);
        error.foreground = new Color(0xdc3545);
        error.background = null;
        error.underline = false;
        error.font = area.getFont();
        area.setSyntaxScheme(scheme);
        ((org.fife.ui.rsyntaxtextarea.RSyntaxDocument) area.getDocument()).setSyntaxStyle(new PresetTokenMaker());
        area.repaint();
    }

    /** Called on every user edit (not on {@link #setText}). */
    public void onEdit(Runnable r) {
        area.getDocument().addDocumentListener(new DocumentListener() {
            void fire() {
                if (!loading) r.run();
            }

            public void insertUpdate(DocumentEvent e) {
                fire();
            }

            public void removeUpdate(DocumentEvent e) {
                fire();
            }

            public void changedUpdate(DocumentEvent e) {
            }
        });
    }

    public void setText(String text) {
        loading = true;
        try {
            area.setText(text == null ? "" : text);
            area.setCaretPosition(0);
            area.discardAllEdits();
        } finally {
            loading = false;
        }
    }

    public String getText() {
        return area.getText();
    }

    /**
     * Highlights every occurrence of {@code text} (plain text, ignoring case, as
     * {@link ItemSearch#occurrences} finds them) until {@link #clearMarks}; returns their start offsets.
     */
    public int[] markAll(String text) {
        clearMarks();
        int[] at = ItemSearch.occurrences(area.getText(), text);
        List<DocumentRange> ranges = new ArrayList<>(at.length);
        for (int s : at) ranges.add(new DocumentRange(s, s + text.length()));
        if (!ranges.isEmpty()) area.markAll(ranges);
        return at;
    }

    /** Highlights {@code [start, end)} as the current match, over the {@link #markAll} ones. */
    public void markCurrent(int start, int end) {
        clearCurrent();
        try {
            current = area.getHighlighter().addHighlight(start, end, CURRENT);
        } catch (javax.swing.text.BadLocationException ignored) {
            // the text changed in between
        }
    }

    public void clearMarks() {
        area.clearMarkAllHighlights();
        clearCurrent();
    }

    private void clearCurrent() {
        if (current != null) area.getHighlighter().removeHighlight(current);
        current = null;
    }

    /** Puts the caret at {@code offset} and scrolls it to the middle unless it is already in view. */
    public void reveal(int offset) {
        area.setCaretPosition(Math.max(0, Math.min(offset, area.getDocument().getLength())));
        try {
            Rectangle r = area.modelToView2D(area.getCaretPosition()).getBounds();
            Rectangle vis = area.getVisibleRect();
            if (vis.contains(r)) return;
            area.scrollRectToVisible(new Rectangle(0, r.y - (vis.height - r.height) / 2, 1, vis.height));
        } catch (javax.swing.text.BadLocationException ignored) {
            // the caret is clamped to the document
        }
    }

    /** Puts the caret at {@code column} of {@code line} (both 0-based, clamped) and scrolls that line to the middle. */
    public void goTo(int line, int column) {
        try {
            int l = Math.max(0, Math.min(line, area.getLineCount() - 1));
            int start = area.getLineStartOffset(l);
            int end = area.getLineEndOffset(l);
            int offset = Math.max(start, Math.min(start + column, end));
            area.setCaretPosition(offset);
            // After the layout of a text set just now, so the view knows the line's position.
            SwingUtilities.invokeLater(() -> {
                try {
                    Rectangle r = area.modelToView2D(offset).getBounds();
                    Rectangle vis = area.getVisibleRect();
                    area.scrollRectToVisible(new Rectangle(0, r.y - (vis.height - r.height) / 2, 1, vis.height));
                } catch (javax.swing.text.BadLocationException ignored) {
                    // the text changed in between
                }
            });
        } catch (javax.swing.text.BadLocationException ignored) {
            // the line is clamped to the document
        }
    }

    public void setEditable(boolean editable) {
        area.setEditable(editable);
        area.setBackground(editable ? Color.WHITE : new Color(0xfafafa));
    }
}
