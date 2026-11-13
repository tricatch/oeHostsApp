package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.net.Network;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Building blocks of the settings pages (see "Settings Page Layout" in CLAUDE.md): page bodies,
 * halves, separating lines, descriptions, label / value tables, form labels and status flashes.
 */
final class SettingsKit {

    /** The gap between a row's label and its field in a {@code BorderLayout} row next to a {@link #table}. */
    static final int ROW_GAP = 6;
    /** The space {@link #table} leaves between a label and its value (its right inset). */
    static final int TABLE_GAP = 16;
    /** Label column width of the address / port forms. */
    private static final int FORM_LABEL_WIDTH = 64;
    /** Text color on the primary (green) buttons' icons. */
    static final Color ON_PRIMARY = new Color(0x2d4a14);

    private SettingsKit() {}

    // ── Page bodies ─────────────────────────────────────────────

    /** A panel that follows the viewport's width, so descriptions wrap and long paths are cut. */
    private static class WidthTracking extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 16;
        }

        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height;
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static JComponent scroll(JPanel body) {
        body.setBackground(Color.WHITE);
        body.setBorder(Ui.pad(18, 22, 18, 22));
        JScrollPane s = Ui.plainScroll(body);
        s.getViewport().setBackground(Color.WHITE);
        return s;
    }

    /** A page body: content stacked from the top with the usual padding, scrollable. */
    static JComponent page(JComponent... parts) {
        JPanel col = new WidthTracking();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        stack(col, parts);
        return scroll(col);
    }

    /**
     * A page body whose single part fills the whole page height (when the page is taller than the
     * part), so a divider drawn by the part reaches the bottom; scrollable like {@link #page}.
     */
    static JComponent fullPage(JComponent body) {
        JPanel p = new WidthTracking() {
            @Override
            public boolean getScrollableTracksViewportHeight() {
                return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height;
            }
        };
        p.setLayout(new BorderLayout());
        // The same padding as page(): a divider in the body stops as far from the bottom as from the top.
        p.add(body, BorderLayout.CENTER);
        return scroll(p);
    }

    /** Makes {@code gap} show and hide with {@code part}, so a hidden part leaves no hole. */
    private static void hideWith(JComponent part, Component gap) {
        gap.setVisible(part.isVisible());
        part.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                gap.setVisible(true);
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                gap.setVisible(false);
            }
        });
    }

    /** Adds {@code parts} to a vertical BoxLayout panel, 14px apart; a hidden part takes its gap with it. */
    static void stack(JPanel col, JComponent... parts) {
        for (int i = 0; i < parts.length; i++) {
            parts[i].setAlignmentX(Component.LEFT_ALIGNMENT);
            if (i > 0) {
                Component gap = Box.createVerticalStrut(Ui.px(14));
                hideWith(parts[i], gap);
                col.add(gap);
            }
            col.add(parts[i]);
        }
    }

    /** A part of a page that is itself a column of parts (see {@link #stack}), kept at its natural height. */
    static JComponent column(JComponent... parts) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        stack(col, parts);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(col, BorderLayout.NORTH);
        return top;
    }

    /** Two equal halves side by side; the right one is set off by a line, like a side panel. */
    static JComponent halves(JComponent left, JComponent right) {
        JPanel p = new JPanel(new GridLayout(1, 2, Ui.px(24), 0));
        p.setOpaque(false);
        right.setBorder(new CompoundBorder(new MatteBorder(0, 1, 0, 0, Ui.BORDER), Ui.pad(0, 20, 0, 0)));
        p.add(left);
        p.add(right);
        return p;
    }

    /**
     * A full-width command box (URL, launch option) under an address drop-down: its text starts at
     * the same x as the drop-down's, so the left padding is 4px less than {@link OeDialog#script}'s.
     */
    static JTextArea fieldScript(String text) {
        JTextArea a = OeDialog.script(text);
        a.setColumns(0);
        a.setBorder(new CompoundBorder(new LineBorder(Ui.BORDER, 1, true), Ui.pad(8, 6, 8, 10)));
        return a;
    }

    /** A thin horizontal rule between the sections of a page (description, settings, actions). */
    static JComponent line() {
        return new JComponent() {
            // Extra room above and below the 1px line, on top of the usual gap between parts.
            static final int MARGIN = 8;

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Ui.BORDER);
                g.fillRect(0, Ui.px(MARGIN), getWidth(), 1);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(0, Ui.px(MARGIN) * 2 + 1);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, Ui.px(MARGIN) * 2 + 1);
            }
        };
    }

    /** Buttons at the right end of their row, 6px apart: the action row of a page half. */
    static JPanel rightAligned(JComponent... buttons) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        p.setOpaque(false);
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) p.add(Box.createHorizontalStrut(Ui.px(6)));
            p.add(buttons[i]);
        }
        return p;
    }

    /** Components in a row from the left, 6px apart; a hidden one takes its gap with it. */
    static JPanel buttons(JComponent... bs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        for (int i = 0; i < bs.length; i++) {
            p.add(bs[i]);
            if (i == bs.length - 1) break;
            // The gap follows its button and hides with it.
            Component gap = Box.createHorizontalStrut(Ui.px(6));
            hideWith(bs[i], gap);
            p.add(gap);
        }
        return p;
    }

    /** A border around {@code c}, as the editors have. */
    static JComponent framed(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new LineBorder(Ui.BORDER, 1, true));
        p.add(c);
        return p;
    }

    // ── Text ────────────────────────────────────────────────────

    /**
     * A read-only text area whose caret never follows text changes. A text area built with its text
     * moves the caret to the end and queues a scroll to it, which runs once the page is on screen
     * and scrolls a tall page down; so the text is set only after the caret is told not to follow.
     */
    static <T extends JTextComponent> T still(T t, String text) {
        ((DefaultCaret) t.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        t.setText(text);
        return Ui.copyable(t);
    }

    /** Gray explanatory text that wraps to the page width, with the wider message line spacing. */
    static JTextPane desc(String text) {
        JTextPane a = still(Ui.messagePane(), text);
        a.setOpaque(false);
        a.setBorder(null);
        a.setFont(Ui.font(Font.PLAIN, 12.5f));
        a.setForeground(Ui.MUTED);
        return a;
    }

    /** A small bold gray label above or in front of a field. */
    static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.font(Font.BOLD, 12f));
        l.setForeground(Ui.MUTED);
        return l;
    }

    /** A status line (the bottom bar's message of a page). */
    static JLabel status() {
        JLabel l = new JLabel(" ");
        l.setFont(Ui.font(Font.PLAIN, 12f));
        return l;
    }

    /** Shows {@code text} on {@code l} for a moment (an error a little longer), then clears it. */
    static void flash(JLabel l, String text, boolean error) {
        l.setForeground(error ? Ui.DANGER : Ui.MUTED);
        l.setText(text);
        if (l.getClientProperty("flash") instanceof Timer old) old.stop();
        Timer t = new Timer(error ? 4000 : 2000, e -> l.setText(" "));
        t.setRepeats(false);
        t.start();
        l.putClientProperty("flash", t);
    }

    static void ok(JLabel l, String text) {
        flash(l, text, false);
    }

    static void error(JLabel l, String text) {
        flash(l, text, true);
    }

    /** A green button with an icon, the main action of a page half. */
    static JButton primary(String text, String icon, Runnable action) {
        JButton b = Ui.primary(text, Ui.icon(icon, 13, ON_PRIMARY), action);
        b.setIconTextGap(Ui.px(5));
        return b;
    }

    /** A port typed in {@code f}, or -1 when it is not 1-65535. */
    static int port(JTextField f) {
        try {
            int p = Integer.parseInt(f.getText().strip());
            return p >= 1 && p <= 65535 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A port field showing {@code port}. */
    static JTextField portField(int port) {
        JTextField f = Ui.formField(new JTextField(String.valueOf(port), 8), "");
        f.setMaximumSize(new Dimension(Ui.px(140), Ui.px(34)));
        return f;
    }

    // ── Tables and forms ────────────────────────────────────────

    /** Label / value rows (the CA details, the proxy status); the value fields are put into {@code values}. */
    /**
     * A monospace value (a path, the SHA-256), selectable so it can be copied. A long one scrolls
     * inside its row when the page is narrow; the tooltip has it all.
     */
    static JTextField value(String text) {
        JTextField f = still(new JTextField() {
            @Override
            public String getToolTipText() {
                return getText();
            }
        }, text);
        ToolTipManager.sharedInstance().registerComponent(f);
        f.setMinimumSize(new Dimension(Ui.px(40), Ui.px(18)));
        f.setFont(Ui.mono(12f));
        f.setForeground(Ui.TEXT);
        return f;
    }

    /** A section heading inside a page (a page with more than one section, e.g. export / import). */
    static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.font(Font.BOLD, 13.5f));
        l.setForeground(Ui.TEXT);
        return l;
    }

    static JPanel table(String[][] rows, JTextField[] values) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.BASELINE_LEADING;
        c.insets = new Insets(Ui.px(3), 0, Ui.px(3), Ui.px(TABLE_GAP));
        for (int i = 0; i < rows.length; i++) {
            c.gridy = i;
            c.gridx = 0;
            c.weightx = 0;
            JLabel k = new JLabel(rows[i][0]);
            k.setFont(Ui.font(Font.BOLD, 12.5f));
            k.setForeground(Ui.MUTED);
            p.add(k, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            values[i] = value(rows[i][1]);
            p.add(values[i], c);
            c.fill = GridBagConstraints.NONE;
        }
        return p;
    }

    /** A label / component row under {@link #table}'s rows, in the same columns. */
    static void tableRow(JPanel table, int row, String key, JComponent value) {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.BASELINE_LEADING;
        c.insets = new Insets(Ui.px(3), 0, Ui.px(3), Ui.px(TABLE_GAP));
        c.gridy = row;
        c.gridx = 0;
        JLabel k = new JLabel(key);
        k.setFont(Ui.font(Font.BOLD, 12.5f));
        k.setForeground(Ui.MUTED);
        table.add(k, c);
        c.gridx = 1;
        table.add(value, c);
    }

    /**
     * The error row of a server's status {@link #table}: shown with {@code error} while there is one,
     * hidden (label and value) otherwise.
     */
    static void errorRow(JPanel table, JTextField field, String error) {
        boolean show = error != null && !error.isBlank();
        field.setText(show ? error : "");
        GridBagLayout grid = (GridBagLayout) table.getLayout();
        int row = grid.getConstraints(field).gridy;
        // GridBagLayout skips hidden components, so the row takes no space.
        for (Component c : table.getComponents()) {
            if (grid.getConstraints(c).gridy == row) c.setVisible(show);
        }
        table.revalidate();
    }

    /**
     * Replaces a {@link #table} value field with a text area in the same grid cell, for a value that
     * can take several lines (every address while bound to 0.0.0.0).
     */
    static JTextArea multiLineCell(JPanel table, JTextField field) {
        JTextArea a = still(new JTextArea(), "-");
        a.setOpaque(false);
        a.setBorder(null);
        a.setFont(field.getFont());
        a.setForeground(field.getForeground());
        GridBagConstraints cell = ((GridBagLayout) table.getLayout()).getConstraints(field);
        table.remove(field);
        table.add(a, cell);
        return a;
    }

    /**
     * Lines up form rows built next to a {@link #table} (a label, then a field, {@code gap} apart)
     * with the table's rows: every label in both gets the widest label's width, so the fields start
     * where the table's values start.
     */
    static void alignWithTable(JPanel table, int gap, JLabel... rowLabels) {
        List<JLabel> tableLabels = new ArrayList<>();
        for (Component c : table.getComponents()) if (c instanceof JLabel l) tableLabels.add(l);
        int w = 0;
        for (JLabel l : rowLabels) {
            l.setFont(Ui.font(Font.BOLD, 12.5f));
            w = Math.max(w, l.getPreferredSize().width);
        }
        for (JLabel l : tableLabels) w = Math.max(w, l.getPreferredSize().width);
        for (JLabel l : tableLabels) l.setPreferredSize(new Dimension(w, l.getPreferredSize().height));
        for (JLabel l : rowLabels) l.setPreferredSize(new Dimension(w + Ui.px(TABLE_GAP) - Ui.px(gap), l.getPreferredSize().height));
    }

    /** Gives the labels of one form the same width (at least {@link #FORM_LABEL_WIDTH}), so their fields line up. */
    static void sameWidth(JLabel... labels) {
        int w = Ui.px(FORM_LABEL_WIDTH);
        for (JLabel l : labels) {
            l.setFont(Ui.font(Font.BOLD, 12.5f));
            w = Math.max(w, l.getPreferredSize().width + Ui.px(10));
        }
        for (JLabel l : labels) l.setPreferredSize(new Dimension(w, l.getPreferredSize().height));
    }

    /** A label / field row for a form whose labels share {@link #sameWidth}. */
    static JPanel formRow(JLabel label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(Ui.px(ROW_GAP), 0));
        row.setOpaque(false);
        row.add(label, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    /** Indents {@code c} to where the fields of a form whose labels are {@code label} start. */
    static <T extends JComponent> T underField(T c, JLabel label) {
        // Unscaled EmptyBorder: the label width is already scaled, Ui.pad would scale it again.
        c.setBorder(new EmptyBorder(0, label.getPreferredSize().width + Ui.px(ROW_GAP), 0, 0));
        return c;
    }

    // ── Addresses ───────────────────────────────────────────────

    /**
     * The addresses a server (forward proxy, web service, reverse proxy) answers on for {@code bind}:
     * 127.0.0.1 always, then the chosen address, or for 0.0.0.0 every IPv4 address, with the network
     * card's name.
     */
    static List<String> reachableAt(AppContext ctx, String bind, int port) {
        List<String> out = new ArrayList<>();
        out.add(Network.LOOPBACK + ":" + port);
        if (!Network.isLan(bind)) return out;
        List<Network.Lan> lans = Network.addressesFor(bind);
        for (Network.Lan l : lans) out.add(ctx.m("app.settings.bind.iface", l.ip() + ":" + port, l.iface()));
        if (!Network.ALL.equals(bind) && lans.isEmpty()) out.add(bind + ":" + port);
        return out;
    }

    /**
     * Shows the addresses of a URL-host choice ({@code ShareServer.urlHosts()}) with what they are:
     * {@code 127.0.0.1  (this PC)}, {@code 192.168.1.23  (Realtek …)}. The network card names are
     * read when the list opens or is drawn, so they follow the PC's current networks.
     */
    static void addressNames(AppContext ctx, JComboBox<String> box) {
        box.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, value, i, sel, focus);
                if (value != null) setText(addressName(ctx, value.toString()));
                return this;
            }
        });
    }

    static String addressName(AppContext ctx, String ip) {
        if (Network.LOOPBACK.equals(ip)) return ctx.m("app.settings.bind.loopback", ip);
        for (Network.Lan l : Network.addresses()) if (l.ip().equals(ip)) return ctx.m("app.settings.bind.iface", ip, l.iface());
        return ip;
    }

    // ── Links and files ─────────────────────────────────────────

    /** Text that opens {@code url} in the browser; underlined and green on hover ({@code .lib-link}). */
    static JComponent link(AppContext ctx, String text, String url) {
        boolean[] hover = {false};
        // The underline is drawn, not markup: changing the text would change its size and baseline,
        // and the rows would shift every time the mouse passes.
        JLabel l = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (!hover[0]) return;
                FontMetrics fm = g.getFontMetrics(getFont());
                Insets in = getInsets();
                int y = in.top + fm.getAscent() + 1;
                g.setColor(getForeground());
                g.fillRect(in.left, y, fm.stringWidth(getText()), 1);
            }
        };
        l.setFont(Ui.font(Font.PLAIN, 12.5f));
        l.setForeground(Ui.TEXT);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setToolTipText(url);
        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover[0] = true;
                l.setForeground(Ui.BC700);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover[0] = false;
                l.setForeground(Ui.TEXT);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(URI.create(url));
                } catch (Exception ex) {
                    OeDialog.copy(url);
                    ctx.toast(ctx.m("app.settings.copied"));
                }
            }
        });
        return l;
    }

    /** A rounded pill with a license name ({@code .license-badge}). */
    static JComponent badge(String text, Color bg, Color fg) {
        JLabel b = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(Ui.font(Font.PLAIN, 11f));
        b.setForeground(fg);
        b.setBorder(Ui.pad(1, 8, 1, 8));
        return b;
    }

    /** Opens {@code dir} in the file manager; shows the path when that is not possible. */
    static void openFolder(AppContext ctx, Path dir) {
        try {
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            ctx.toast(dir.toString());
        }
    }
}
