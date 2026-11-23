package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.store.ItemSearch;
import tricatch.oehostsapp.store.Store;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * oeHub's {@code .oe-search-panel}: opened by the sidebar header's search button above the list, a
 * text field and the matching items with their matching lines. A press anywhere else, Esc or the
 * button closes it and clears the text.
 */
@SuppressWarnings("serial") // a Swing component, never serialized
final class SearchPanel extends JPanel {

    /** What the panel searches and what a click on a result does. */
    interface Target {
        List<ItemSearch.Hit> find(String query);

        /** Opens {@code item}; {@code line} is the clicked line (null: the name row). */
        void open(Store.Item item, ItemSearch.Line line, String query);

        /** The panel closed. */
        void closed();
    }

    private static final int LINE_CONTEXT = 24;
    private static final int LINE_MAX = 160;
    private static final Color HOVER = new Color(0, 0, 0, 13);
    private static final String MARK = "<span style=\"background-color:#dff2b8\">";

    private final AppContext ctx;
    private final Target target;
    private final JTextField field = new JTextField();
    private final Results results = new Results();
    private final Timer debounce;
    private final JButton toggle;
    private AWTEventListener outside;

    SearchPanel(AppContext ctx, String placeholderKey, Target target) {
        super(new BorderLayout(0, Ui.px(6)));
        this.ctx = ctx;
        this.target = target;
        setBackground(Color.WHITE);
        // No bottom line of its own: the split divider below draws it.
        setBorder(Ui.pad(8, 8, 8, 8));
        setVisible(false);

        field.setFont(Ui.font(Font.PLAIN, 13f));
        field.putClientProperty("JTextField.placeholderText", ctx.m(placeholderKey));
        field.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878");
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        debounce = new Timer(300, e -> refresh());
        debounce.setRepeats(false);
        add(field, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(results, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Color.WHITE);
        sp.getVerticalScrollBar().setUnitIncrement(Ui.px(16));
        add(sp, BorderLayout.CENTER);

        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getActionMap().put("close", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                close();
            }
        });
        toggle = Ui.iconButton("search", ctx.m("tooltip.search"), false, () -> {
            if (isVisible()) close();
            else open();
        });
    }

    /** The sidebar header's button that opens and closes the panel. */
    JButton toggleButton() {
        return toggle;
    }

    private void open() {
        setVisible(true);
        revalidate();
        field.requestFocusInWindow();
        outside = ev -> {
            if (ev.getID() != MouseEvent.MOUSE_PRESSED) return;
            Component c = ((MouseEvent) ev).getComponent();
            if (c == null || !(SwingUtilities.isDescendingFrom(c, this) || SwingUtilities.isDescendingFrom(c, toggle)
                    || isOwnDivider(c))) close();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(outside, AWTEvent.MOUSE_EVENT_MASK);
    }

    /** The divider of the split this panel sits in (its height is dragged there), not a click elsewhere. */
    private boolean isOwnDivider(Component c) {
        return c instanceof javax.swing.plaf.basic.BasicSplitPaneDivider && c.getParent() == getParent();
    }

    void close() {
        if (outside != null) Toolkit.getDefaultToolkit().removeAWTEventListener(outside);
        outside = null;
        if (!isVisible()) return;
        debounce.stop();
        field.setText("");
        results.show(List.of(), "");
        setVisible(false);
        revalidate();
        target.closed();
    }

    /** Searches again with the current text (the list or an item changed). */
    void refresh() {
        if (!isVisible()) return;
        String q = field.getText().strip();
        results.show(q.isEmpty() ? List.of() : target.find(q), q);
    }

    @Override
    public void removeNotify() {
        // The window is rebuilt (language change): never leave the listener behind.
        if (outside != null) Toolkit.getDefaultToolkit().removeAWTEventListener(outside);
        outside = null;
        super.removeNotify();
    }

    /** The result rows; as wide as the viewport, so long lines are cut instead of scrolled. */
    private final class Results extends JPanel implements Scrollable {

        Results() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(Color.WHITE);
        }

        void show(List<ItemSearch.Hit> hits, String q) {
            removeAll();
            if (!q.isEmpty() && hits.isEmpty()) {
                JLabel none = new JLabel(ctx.m("ui.no.results"));
                none.setFont(Ui.font(Font.PLAIN, 12.5f));
                none.setForeground(new Color(0xaaaaaa));
                none.setBorder(Ui.pad(4, 4, 4, 4));
                add(row(none, null));
            }
            for (ItemSearch.Hit h : hits) {
                String folder = h.folder() == null ? "" : "<font color=\"#6c757d\">" + Ui.esc(h.folder().name) + " / </font>";
                JLabel name = html(folder + "<b>" + marked(h.item().name, h.nameStart(), h.nameEnd()) + "</b>");
                name.setFont(Ui.font(Font.PLAIN, 13f));
                name.setForeground(Ui.TEXT);
                name.setBorder(Ui.pad(4, 6, 2, 6));
                add(row(name, () -> target.open(h.item(), null, q)));
                for (ItemSearch.Line l : h.lines()) {
                    JLabel line = html("<font color=\"#adb5bd\">" + (l.line() + 1) + "</font>&nbsp;&nbsp;" + excerpt(l));
                    line.setFont(Ui.mono(12f));
                    line.setForeground(Ui.PREVIEW_TEXT);
                    line.setBorder(Ui.pad(1, 18, 1, 6));
                    add(row(line, () -> target.open(h.item(), l, q)));
                }
                if (h.moreLines() > 0) {
                    JLabel more = new JLabel(ctx.m("app.search.more", h.moreLines()));
                    more.setFont(Ui.font(Font.PLAIN, 11.5f));
                    more.setForeground(Ui.MUTED);
                    more.setBorder(Ui.pad(1, 18, 2, 6));
                    add(row(more, null));
                }
                add(Box.createVerticalStrut(Ui.px(4)));
            }
            revalidate();
            repaint();
            SwingUtilities.invokeLater(() -> scrollRectToVisible(new Rectangle(0, 0, 1, 1)));
        }

        /** A full-width row; with {@code onClick} it shows oeHub's hover tint and a hand cursor. */
        private JComponent row(JLabel label, Runnable onClick) {
            JPanel r = new JPanel(new BorderLayout()) {
                boolean hover;

                {
                    if (onClick != null) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        addMouseListener(new MouseAdapter() {
                            public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                            public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                            public void mouseClicked(MouseEvent e) {
                                if (SwingUtilities.isLeftMouseButton(e)) onClick.run();
                            }
                        });
                    }
                }

                @Override
                protected void paintComponent(Graphics g) {
                    if (hover) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(HOVER);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), Ui.px(8), Ui.px(8));
                        g2.dispose();
                    }
                }

                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
            r.setOpaque(false);
            r.add(label, BorderLayout.CENTER);
            r.setAlignmentX(LEFT_ALIGNMENT);
            return r;
        }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return Ui.px(16); }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static JLabel html(String body) {
        JLabel l = new JLabel("<html><nobr>" + body + "</nobr></html>");
        // A wide line must not widen the sidebar: the label is cut at the row's width.
        l.setMinimumSize(new Dimension(0, 0));
        return l;
    }

    /** {@code s} escaped, with {@code [start, end)} highlighted (start -1: none). */
    private static String marked(String s, int start, int end) {
        if (start < 0) return Ui.esc(s);
        return Ui.esc(s.substring(0, start)) + MARK + Ui.esc(s.substring(start, end)) + "</span>" + Ui.esc(s.substring(end));
    }

    /** The line without its indentation, starting a little before a match far to the right, at most {@link #LINE_MAX} chars. */
    private static String excerpt(ItemSearch.Line l) {
        String t = l.text();
        int from = 0;
        while (from < l.start() && Character.isWhitespace(t.charAt(from))) from++;
        String lead = "";
        if (l.start() - from > LINE_CONTEXT) {
            from = l.start() - LINE_CONTEXT;
            lead = "…";
        }
        int to = Math.min(t.length(), from + LINE_MAX);
        return lead + marked(t.substring(from, to), l.start() - from, Math.min(l.end(), to) - from);
    }
}
