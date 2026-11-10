package tricatch.oehubapp.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import tricatch.oehubapp.Store;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared look of the desktop app, taken from the web pages' CSS (app.css, oe-panel.css,
 * hosts.css) so the Swing screens match oeHub: brand greens, panel headers, meta bars, icon
 * buttons and preview boxes. Icons are the same Lucide SVG files the web pages use.
 */
public final class Ui {

    // Brand colors (CLAUDE.md of oeHub: BC100..BC700). No other greens.
    public static final Color BC100 = new Color(0xdff2b8);
    public static final Color BC300 = new Color(0xa8d878);
    public static final Color BC500 = new Color(0x7ab84a);
    public static final Color BC700 = new Color(0x5a9e2f);

    public static final Color BORDER = new Color(0xdee2e6);
    public static final Color MUTED = new Color(0x6c757d);
    public static final Color TEXT = new Color(0x212529);
    public static final Color TEXT_STRONG = new Color(0x1b1d18);
    public static final Color META_BG = new Color(0xf8f9fa);
    public static final Color PREVIEW_TEXT = new Color(0x495057);
    public static final Color HOVER = new Color(0, 0, 0, 10);
    public static final Color ICON = new Color(0, 0, 0, 102);
    public static final Color ICON_HOVER_BG = new Color(0xf0f7e8);
    public static final Color DANGER = new Color(0xdc3545);
    public static final Color DANGER_BG = new Color(0xffeef0);
    public static final Color NAV_BORDER = new Color(0x8ec45a);
    public static final Color GUIDE = new Color(0xd5d7cf);

    /** 2.6rem panel headers, 1.9rem meta bars (16px rem). */
    public static final int HEADER_H = 42;
    public static final int META_H = 30;

    private static final String ICON_DIR = "icon/";
    private static final Map<String, FlatSVGIcon> ICONS = new ConcurrentHashMap<>();

    private Ui() {}

    public static Font mono(float size) {
        // CLAUDE.md monospace stack: the first installed one wins. StyleContext returns a composite
        // font, so characters the face lacks (Hangul in Consolas) fall back instead of showing boxes.
        java.util.Set<String> installed = java.util.Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String name : new String[]{"Consolas", "Monaco", "Courier New"}) {
            if (installed.contains(name)) {
                return javax.swing.text.StyleContext.getDefaultStyleContext().getFont(name, Font.PLAIN, 12).deriveFont(size);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size));
    }

    /**
     * Read-only text that looks like a label but can be selected with the mouse and copied
     * (Ctrl+C / Cmd+C): no border, no background, no caret.
     */
    public static <T extends javax.swing.text.JTextComponent> T copyable(T t) {
        t.setEditable(false);
        t.setOpaque(false);
        t.setBorder(null);
        t.putClientProperty("FlatLaf.style", "background:null; inactiveBackground:null; disabledBackground:null");
        t.setCaretColor(new Color(0, 0, 0, 0));
        t.setSelectionColor(new Color(0xb5d5ff));
        // Dark text on the light blue. Null would let the look and feel put back its white
        // selection text, which disappears on this background.
        t.setSelectedTextColor(TEXT);
        t.getCaret().setBlinkRate(0);
        return t;
    }

    public static Font font(int style, float size) {
        return UIManager.getFont("defaultFont").deriveFont(style, size);
    }

    /** A Lucide icon (file name without .svg) recolored to {@code color}. */
    public static Icon icon(String name, int size, Color color) {
        FlatSVGIcon base = ICONS.computeIfAbsent(name, n -> new FlatSVGIcon(ICON_DIR + "lucide/" + n + ".svg"));
        FlatSVGIcon i = base.derive(size, size);
        i.setColorFilter(new FlatSVGIcon.ColorFilter(c -> new Color(color.getRed(), color.getGreen(), color.getBlue(),
                c.getAlpha() * color.getAlpha() / 255)));
        return i;
    }

    /** A full-color SVG from icon/ (logo, Chrome). */
    public static Icon image(String file, int w, int h) {
        return new FlatSVGIcon(ICON_DIR + file, w, h);
    }

    public static Border bottomLine() {
        return new MatteBorder(0, 0, 1, 0, BORDER);
    }

    public static Border topLine() {
        return new MatteBorder(1, 0, 0, 0, BORDER);
    }

    /** {@code .oe-nav-action-btn}: borderless icon button, green tint on hover, red for danger. */
    public static JButton iconButton(String icon, String tooltip, boolean danger, Runnable action) {
        JButton b = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                if (getModel().isRollover() && isEnabled()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(danger ? DANGER_BG : ICON_HOVER_BG);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        b.setIcon(icon(icon, 15, ICON));
        b.setRolloverIcon(icon(icon, 16, danger ? DANGER : new Color(0, 0, 0, 178)));
        b.setDisabledIcon(icon(icon, 15, new Color(0, 0, 0, 30)));
        b.setToolTipText(tooltip);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(30, 28));
        b.setMargin(new Insets(0, 0, 0, 0));
        if (action != null) b.addActionListener(e -> action.run());
        return b;
    }

    /** Panel header: title on the left, actions on the right, a line under it. */
    public static JPanel header(JComponent title, JComponent... actions) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setBackground(Color.WHITE);
        p.setBorder(new CompoundBorder(bottomLine(), new EmptyBorder(0, 12, 0, 8)));
        p.setPreferredSize(new Dimension(10, HEADER_H));
        p.add(title, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, (HEADER_H - 1 - 28) / 2));
        right.setOpaque(false);
        for (JComponent a : actions) right.add(a);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    /** {@code .hosts-sidebar-header span}: small gray uppercase label. */
    public static JLabel caps(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(font(Font.BOLD, 12f));
        l.setForeground(MUTED);
        return l;
    }

    /** {@code .oe-editor-title}. */
    public static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.BOLD, 14f));
        l.setForeground(MUTED);
        return l;
    }

    /** {@code .oe-sidebar-meta} / {@code .oe-editor-meta}: gray bar at the bottom of a panel. */
    public static JPanel meta() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBackground(META_BG);
        p.setBorder(new CompoundBorder(topLine(), new EmptyBorder(0, 14, 0, 14)));
        p.setPreferredSize(new Dimension(10, META_H));
        return p;
    }

    public static JLabel metaText(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.PLAIN, 12f));
        l.setForeground(MUTED);
        return l;
    }

    /** {@code .hosts-scheme-preview}: monospace text on a light gray rounded box. */
    public static JTextPane previewPane() {
        JTextPane t = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }
        };
        t.setEditable(false);
        t.setFont(mono(12f));
        t.setForeground(PREVIEW_TEXT);
        t.setBackground(META_BG);
        t.setBorder(new EmptyBorder(6, 8, 6, 8));
        return t;
    }

    /**
     * Gives a preview pane's current text the web's {@code line-height: 1.6}. Swing has no
     * line-height, only extra space below each line as a fraction of the font height, so the
     * fraction is derived from the pane's font. Call after every text change: replacing the
     * content can drop the paragraph attributes.
     */
    public static void previewLineHeight(JTextPane p) {
        Font f = p.getFont();
        int h = p.getFontMetrics(f).getHeight();
        float spacing = Math.max(0f, (f.getSize2D() * 1.6f - h) / h);
        javax.swing.text.SimpleAttributeSet a = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setLineSpacing(a, spacing);
        javax.swing.text.StyledDocument d = p.getStyledDocument();
        d.setParagraphAttributes(0, d.getLength() + 1, a, false);
    }

    public static JScrollPane previewScroll(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.getViewport().setBackground(META_BG);
        s.putClientProperty("JScrollPane.smoothScrolling", true);
        return s;
    }

    public static JScrollPane plainScroll(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.getVerticalScrollBar().setUnitIncrement(16);
        return s;
    }

    /** Rounded green button ({@code .hosts-launch-btn}): BC100 fill, BC300 border. */
    public static JButton primary(String text, Icon icon, Runnable action) {
        JButton b = new JButton(text, icon);
        b.setFont(font(Font.BOLD, 13f));
        b.setBackground(BC100);
        b.setForeground(new Color(0x2d4a14));
        b.putClientProperty("FlatLaf.style", "arc:6; borderColor:#a8d878; hoverBackground:#d2ec9e; pressedBackground:#c4e48a; focusedBorderColor:#7ab84a; borderWidth:1");
        b.setFocusable(false);
        if (action != null) b.addActionListener(e -> action.run());
        return b;
    }

    /** Neutral bordered button with a small icon (the Win / Mac copy buttons). */
    public static JButton secondary(String text, String icon, Runnable action) {
        JButton b = new JButton(text, icon == null ? null : Ui.icon(icon, 13, PREVIEW_TEXT));
        b.setFont(font(Font.BOLD, 12f));
        b.setForeground(PREVIEW_TEXT);
        b.setBackground(Color.WHITE);
        b.putClientProperty("FlatLaf.style", "arc:6; borderColor:#dee2e6; hoverBackground:#f8f9fa; focusedBorderColor:#a8d878");
        b.setFocusable(false);
        b.setIconTextGap(4);
        if (action != null) b.addActionListener(e -> action.run());
        return b;
    }

    /** A 5px split divider drawn as a white strip with a 1px border line (the web resize handle). */
    public static void lineDivider(JSplitPane split) {
        split.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
            @Override
            public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(Graphics g) {
                        g.setColor(Color.WHITE);
                        g.fillRect(0, 0, getWidth(), getHeight());
                        g.setColor(BORDER);
                        if (split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT) g.fillRect(getWidth() / 2, 0, 1, getHeight());
                        else g.fillRect(0, getHeight() / 2, getWidth(), 1);
                    }

                    @Override
                    public void setBorder(javax.swing.border.Border b) {
                        // no border
                    }
                };
            }
        });
        split.setBorder(null);
        split.setDividerSize(5);
    }

    public static final int SIDEBAR_W = 300;

    /** Default width of the right-hand panel of oeHosts and oeProxy. */
    public static final int RIGHT_W = 548;

    /**
     * Left list | the rest of a screen, with the list resizable by dragging the line between them.
     * The width starts at {@link #SIDEBAR_W} and, once dragged, is remembered and shared by every
     * screen ({@code settings.sidebarWidth}).
     */
    public static JSplitPane sidebarSplit(AppContext ctx, JComponent sidebar, JComponent rest) {
        sidebar.setMinimumSize(new Dimension(180, 10));
        rest.setMinimumSize(new Dimension(400, 10));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, rest);
        split.setResizeWeight(0); // the list keeps its width when the window is resized
        shareDivider(ctx, split, false, SIDEBAR_W, st -> st.sidebarWidth, (st, w) -> st.sidebarWidth = w);
        return split;
    }

    /**
     * Main area | right-hand panel. The panel keeps its width when the window is resized; the width
     * starts at {@link #RIGHT_W} and, once dragged, is shared by every screen
     * ({@code settings.rightPanelWidth}).
     */
    public static JSplitPane rightSplit(AppContext ctx, JComponent main, JComponent panel) {
        main.setMinimumSize(new Dimension(300, 10));
        panel.setMinimumSize(new Dimension(240, 10));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, main, panel);
        split.setResizeWeight(1);
        shareDivider(ctx, split, true, RIGHT_W, st -> st.rightPanelWidth, (st, w) -> st.rightPanelWidth = w);
        return split;
    }

    /**
     * Keeps a split's divider at a width stored in the settings and shared between screens: the
     * split takes the stored width when it is shown, and a drag by the user stores the new one.
     * {@code fromRight} measures the width of the right component instead of the left one.
     */
    private static void shareDivider(AppContext ctx, JSplitPane split, boolean fromRight, int def,
                                     java.util.function.Function<Store.Settings, Integer> get,
                                     java.util.function.BiConsumer<Store.Settings, Integer> set) {
        split.setContinuousLayout(true);
        lineDivider(split);
        java.util.function.IntSupplier stored = () -> {
            Integer w = ctx.read(() -> get.apply(ctx.rawStore().data.settings));
            return w != null ? w : def;
        };
        java.util.function.IntSupplier current = () -> fromRight
                ? split.getWidth() - split.getDividerLocation() - split.getDividerSize()
                : split.getDividerLocation();
        boolean[] applying = {false};
        Runnable apply = () -> {
            if (split.getWidth() <= 0) return;
            int w = stored.getAsInt();
            if (current.getAsInt() == w) return;
            applying[0] = true;
            split.setDividerLocation(fromRight ? split.getWidth() - w - split.getDividerSize() : w);
            applying[0] = false;
        };
        if (!fromRight) split.setDividerLocation(stored.getAsInt());
        Timer save = new Timer(400, e -> {
            int w = current.getAsInt();
            ctx.storeRun(s -> {
                Integer old = get.apply(s.data.settings);
                if (old != null && old == w || old == null && w == def) return;
                set.accept(s.data.settings, w);
                s.save();
            });
        });
        save.setRepeats(false);
        // Only a drag by the user is saved, not the layout settling or the stored width being applied.
        split.addPropertyChangeListener(JSplitPane.LAST_DIVIDER_LOCATION_PROPERTY, e -> {
            if (!applying[0] && split.isShowing() && split.getWidth() > 0) save.restart();
        });
        // Another screen may have moved the shared width while this one was hidden.
        split.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && split.isShowing()) {
                SwingUtilities.invokeLater(apply);
            }
        });
        // The right panel's place is only known once the split has a width.
        if (fromRight) {
            split.addComponentListener(new java.awt.event.ComponentAdapter() {
                boolean first = true;

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (first && split.getWidth() > 0) {
                        first = false;
                        apply.run();
                    }
                }
            });
        }
    }

    /** Adds a double-click handler. */
    public static void onDoubleClick(Component c, Runnable r) {
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) r.run();
            }
        });
    }

    public static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
