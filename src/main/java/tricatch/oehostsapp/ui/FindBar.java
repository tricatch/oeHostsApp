package tricatch.oehostsapp.ui;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Find in the open item (Ctrl+F / Cmd+F): a bar above the editor with the text, "n/total", previous
 * and next. Every match is highlighted, the current one stronger; Enter / Down / F3 goes to the next,
 * Shift+Enter / Up / Shift+F3 to the previous (both wrap). Esc or the close button closes it and
 * leaves the current match selected in the editor; the field losing the focus (a click in the
 * editor, say) closes it too and leaves the caret where it went. Plain text, ignoring case, like
 * the list search.
 */
final class FindBar extends JPanel {

    private final AppContext ctx;
    private final CodeEditor editor;
    private final JTextField field = new JTextField();
    private final JLabel count = new JLabel();
    private final Timer retype;
    private int[] hits = new int[0];
    private int cur = -1;
    /** Where the search starts: the caret when the bar opened, then the match last moved to. */
    private int anchor;

    FindBar(AppContext ctx, CodeEditor editor) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.editor = editor;
        setBackground(Ui.META_BG);
        setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Ui.BORDER), Ui.pad(4, 8, 4, 6)));
        setVisible(false);

        field.setFont(Ui.font(Font.PLAIN, 13f));
        field.setColumns(18);
        field.putClientProperty("JTextField.placeholderText", ctx.m("app.find.placeholder"));
        field.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878");
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); }
            public void removeUpdate(DocumentEvent e) { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        count.setFont(Ui.font(Font.PLAIN, 12f));
        count.setHorizontalAlignment(SwingConstants.RIGHT);
        count.setPreferredSize(new Dimension(Ui.px(76), Ui.px(28)));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Ui.px(4), 0));
        right.setOpaque(false);
        right.add(field);
        right.add(count);
        right.add(Ui.iconButton("chevron-up", ctx.m("app.find.prev"), false, () -> step(-1)));
        right.add(Ui.iconButton("chevron-down", ctx.m("app.find.next"), false, () -> step(1)));
        right.add(Ui.iconButton("x", ctx.m("app.find.close"), false, () -> close(true)));
        add(right, BorderLayout.EAST);

        key(field, KeyEvent.VK_ENTER, 0, "next", () -> step(1));
        key(field, KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK, "prev", () -> step(-1));
        // Up / Down step like Shift+Enter / Enter, in the field only (in the editor they move lines).
        key(field, KeyEvent.VK_UP, 0, "prev-arrow", () -> step(-1));
        key(field, KeyEvent.VK_DOWN, 0, "next-arrow", () -> step(1));
        key(field, KeyEvent.VK_F3, 0, "next-f3", () -> step(1));
        key(field, KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK, "prev-f3", () -> step(-1));
        key(field, KeyEvent.VK_ESCAPE, 0, "close", () -> close(true));
        // The bar lives only while its field has the focus. A temporary loss (another app's window,
        // a menu) keeps it; the bar's own buttons are not focusable, so clicking them keeps it too.
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                if (!e.isTemporary()) close(false);
            }
        });
        // An edit in the editor moves the matches: find again once typing pauses.
        retype = new Timer(150, e -> refresh());
        retype.setRepeats(false);
    }

    /** Ctrl+F (Cmd+F) opens the bar while the focus is anywhere in {@code root}. */
    void bind(JComponent root) {
        KeyStroke find = KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        key(root, WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, find, "oe-find", this::open);
        key(editor.area, WHEN_FOCUSED, find, "oe-find", this::open);
    }

    /** The editor header's find button: opens the bar, or closes it when it is open. */
    void toggle() {
        if (isVisible()) close(true);
        else open();
    }

    /** How the find shortcut is written on this OS, for the button's tooltip. */
    static String shortcutText() {
        return KeyEvent.getModifiersExText(Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) + "+F";
    }

    /** Opens the bar (or focuses it again), starting with the editor's one-line selection if there is one. */
    void open() {
        if (!editor.area.isEditable()) return;
        String sel = editor.area.getSelectedText();
        anchor = editor.area.getSelectionStart();
        setVisible(true);
        revalidate();
        if (sel != null && !sel.isEmpty() && sel.indexOf('\n') < 0) field.setText(sel);
        else refresh();
        field.selectAll();
        field.requestFocusInWindow();
    }

    /**
     * Hides the bar and its highlights; with {@code focusEditor} the current match ends up selected
     * in the editor. The text does not jump: the editor grows by the bar's height at the top, so the
     * view scrolls by the same amount (as far as it can).
     */
    void close(boolean focusEditor) {
        if (!isVisible()) return;
        retype.stop();
        boolean found = cur >= 0 && cur < hits.length;
        int s = found ? hits[cur] : -1;
        int len = field.getText().length();
        int h = getHeight();
        JViewport vp = editor.scroll.getViewport();
        Point view = vp.getViewPosition();
        Container parent = getParent();
        setVisible(false);
        if (parent != null) {
            // Lay the editor out at its new height now, so the view position below is kept.
            parent.validate();
            vp.setViewPosition(new Point(view.x, Math.max(0, view.y - h)));
        }
        editor.clearMarks();
        hits = new int[0];
        cur = -1;
        if (focusEditor) {
            if (found) editor.area.select(s, s + len);
            editor.area.requestFocusInWindow();
        }
    }

    /** The editor's text changed (an edit): find again shortly. */
    void textEdited() {
        if (isVisible()) retype.restart();
    }

    /** Another item (or none) is open now: find again from its start, or close when nothing is open. */
    void itemChanged() {
        anchor = 0;
        if (!editor.area.isEditable()) close(false);
        else refresh();
    }

    /** Highlights again for the current text, keeping the current match at or after {@link #anchor}. */
    void refresh() {
        if (!isVisible()) return;
        String q = field.getText();
        hits = q.isEmpty() ? new int[0] : editor.markAll(q);
        cur = -1;
        for (int i = 0; i < hits.length && cur < 0; i++) if (hits[i] >= anchor) cur = i;
        if (cur < 0 && hits.length > 0) cur = 0;
        showCurrent();
    }

    private void step(int dir) {
        if (!isVisible()) return;
        if (hits.length == 0) {
            refresh();
            return;
        }
        cur = Math.floorMod(cur + dir, hits.length);
        anchor = hits[cur];
        showCurrent();
    }

    /** Marks the current match, scrolls to it and updates the count. */
    private void showCurrent() {
        String q = field.getText();
        if (cur >= 0) {
            editor.markCurrent(hits[cur], hits[cur] + q.length());
            editor.reveal(hits[cur]);
        }
        if (q.isEmpty()) {
            count.setText("");
        } else if (hits.length == 0) {
            count.setText(ctx.m("ui.no.results"));
            count.setForeground(Ui.DANGER);
        } else {
            count.setText(ctx.m("app.find.count", cur + 1, hits.length));
            count.setForeground(Ui.MUTED);
        }
    }

    private static void key(JComponent c, int code, int mods, String name, Runnable r) {
        key(c, WHEN_FOCUSED, KeyStroke.getKeyStroke(code, mods), name, r);
    }

    private static void key(JComponent c, int when, KeyStroke ks, String name, Runnable r) {
        c.getInputMap(when).put(ks, name);
        c.getActionMap().put(name, new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }
}
