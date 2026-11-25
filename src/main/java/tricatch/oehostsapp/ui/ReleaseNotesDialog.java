package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.update.ReleaseNotes;
import tricatch.oehostsapp.update.Update;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.List;

/**
 * What changed in the version that just started (the first start after an update): the release's
 * change list from {@link ReleaseNotes}, drawn as styled text (never as HTML: the text comes from
 * the network), with a link to the full release page.
 */
final class ReleaseNotesDialog {

    private static final int WIDTH = 620;
    private static final int MAX_HEIGHT = 420;

    private ReleaseNotesDialog() {}

    static void show(AppContext ctx, String version, List<ReleaseNotes.Line> lines) {
        // Wraps at spaces, so Korean words are not split between syllables.
        JTextPane pane = Ui.copyable(Ui.messagePane());
        pane.setEditable(false);
        // Selectable for copying, but no blinking caret in a text that is only read.
        pane.setCaretColor(new Color(0, 0, 0, 0));
        pane.setOpaque(false);
        pane.setBorder(null);
        pane.setFont(Ui.font(Font.PLAIN, 13f));
        pane.setForeground(Ui.TEXT);
        fill(pane, lines);
        pane.setCaretPosition(0);

        // Wrapped at the dialog's width; scrolls only when the list is long.
        int w = Ui.px(WIDTH);
        pane.setSize(new Dimension(w, Short.MAX_VALUE));
        int h = pane.getPreferredSize().height;
        JScrollPane scroll = Ui.plainScroll(pane);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(w + Ui.px(14), Math.min(h + Ui.px(4), Ui.px(MAX_HEIGHT))));

        OeDialog d = new OeDialog(ctx.frame(), ctx.m("app.notes.title", version));
        d.body(scroll);
        d.footerLeft(SettingsKit.link(ctx, ctx.m("app.notes.full"), Update.releaseUrl(version)));
        d.button(ctx.m("btn.close"), true, d::dispose);
        d.showCentered();
    }

    private static void fill(JTextPane pane, List<ReleaseNotes.Line> lines) {
        StyledDocument doc = pane.getStyledDocument();
        float size = pane.getFont().getSize2D();
        int indent = Ui.px(18);
        FontMetrics fm = pane.getFontMetrics(pane.getFont());
        try {
            for (int i = 0; i < lines.size(); i++) {
                ReleaseNotes.Line line = lines.get(i);
                int start = doc.getLength();
                SimpleAttributeSet base = new SimpleAttributeSet();
                StyleConstants.setFontFamily(base, pane.getFont().getFamily());
                StyleConstants.setFontSize(base, Math.round(size));
                // A title's description, under it, a little lighter.
                StyleConstants.setForeground(base, line.detail() ? Ui.PREVIEW_TEXT : Ui.TEXT);
                String mark = line.level() == 0 ? "• " : "◦ ";
                if (line.bullet()) {
                    SimpleAttributeSet dot = new SimpleAttributeSet(base);
                    StyleConstants.setForeground(dot, Ui.BC500);
                    doc.insertString(doc.getLength(), mark, dot);
                }
                for (ReleaseNotes.Span s : line.spans()) {
                    SimpleAttributeSet a = new SimpleAttributeSet(base);
                    if (s.bold()) {
                        StyleConstants.setBold(a, true);
                        StyleConstants.setForeground(a, new Color(0x1e4a0a));
                    }
                    if (s.code()) {
                        StyleConstants.setFontFamily(a, Ui.mono(12f).getFamily());
                        StyleConstants.setBackground(a, Ui.META_BG);
                    }
                    doc.insertString(doc.getLength(), s.text(), a);
                }
                if (i < lines.size() - 1) doc.insertString(doc.getLength(), "\n", base);
                // Hanging indent: wrapped lines and a title's description start under the title's
                // text, not under the bullet.
                SimpleAttributeSet para = new SimpleAttributeSet();
                int left = line.bullet() || line.detail() ? indent * (line.level() + 1) : 0;
                StyleConstants.setLeftIndent(para, left);
                StyleConstants.setFirstLineIndent(para, line.bullet() ? -fm.stringWidth(mark) : 0);
                StyleConstants.setLineSpacing(para, 0.25f);
                // A title stays close to its description; items are set apart.
                boolean titled = i + 1 < lines.size() && lines.get(i + 1).detail();
                StyleConstants.setSpaceBelow(para, titled ? Ui.px(1) : line.level() == 0 ? Ui.px(10) : Ui.px(3));
                doc.setParagraphAttributes(start, Math.max(1, doc.getLength() - start), para, false);
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }
}
