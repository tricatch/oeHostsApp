package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.store.Settings;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;

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

    // ── Font size (whole-UI zoom) ───────────────────────────────

    /** Steps offered for the font size, added to the default size (the first one is smaller). */
    private static final int[] FONT_STEPS = {-1, 0, 1, 2, 4, 6};

    /** The look and feel's default font size before zoom (12 on Windows). */
    public static int defaultFontSize() {
        return Math.round(UIManager.getFont("defaultFont").getSize2D() / UIScale.getUserScaleFactor());
    }

    /** The font sizes to choose from, smallest first; one of them is {@link #defaultFontSize()}. */
    public static int[] fontSizes() {
        int base = defaultFontSize();
        int[] sizes = new int[FONT_STEPS.length];
        for (int i = 0; i < sizes.length; i++) sizes[i] = base + FONT_STEPS[i];
        return sizes;
    }

    /**
     * Scales the whole UI so the default font is {@code size} points (null: the default size).
     * FlatLaf's zoom re-creates its defaults at once; screens built afterwards pick up the new
     * size, and every size this app sets itself goes through {@link #px} or {@link #font}.
     */
    public static void applyFontSize(Integer size) {
        applied = size == null || size <= 0 ? null : size;
        float base = defaultFontSize();
        UIScale.setZoomFactor(size == null || size <= 0 ? 1f : size / base);
    }

    /** The size last given to {@link #applyFontSize} (null: the default). */
    private static Integer applied;

    public static Integer appliedFontSize() {
        return applied;
    }

    /** A size in pixels of the 100% design, scaled to the current zoom. 0 and "unbounded" stay. */
    public static int px(int v) {
        if (v == 0 || v >= Short.MAX_VALUE || v <= -Short.MAX_VALUE) return v;
        return Math.round(v * UIScale.getUserScaleFactor());
    }

    /** {@link #px(int)} for fractional sizes (font sizes, strokes). */
    public static float px(float v) {
        return v * UIScale.getUserScaleFactor();
    }

    /**
     * Padding of the 100% design, scaled (see {@link #px(int)}). A plain EmptyBorder on purpose:
     * FlatLaf's own scaled empty border is a UIResource, so FlatLaf.updateUI (after a font size
     * change) would replace it with the look and feel's default border.
     */
    public static javax.swing.border.EmptyBorder pad(int top, int left, int bottom, int right) {
        return new javax.swing.border.EmptyBorder(px(top), px(left), px(bottom), px(right));
    }

    /** A byte count for people: B, KB, MB, GB (1024 based), one decimal above bytes. */
    public static String byteSize(long n) {
        if (n < 1024) return n + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = n;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[u]);
    }

    /** A dimension of the 100% design, scaled (see {@link #px(int)}). */
    public static Dimension dim(int w, int h) {
        return new Dimension(px(w), px(h));
    }

    public static Font mono(float size) {
        // CLAUDE.md monospace stack: the first installed one wins. StyleContext returns a composite
        // font, so characters the face lacks (Hangul in Consolas) fall back instead of showing boxes.
        java.util.Set<String> installed = java.util.Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String name : new String[]{"Consolas", "Monaco", "Courier New"}) {
            if (installed.contains(name)) {
                return javax.swing.text.StyleContext.getDefaultStyleContext().getFont(name, Font.PLAIN, 12).deriveFont(px(size));
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, Math.round(px(size)));
    }

    /**
     * Read-only text that looks like a label but can be selected with the mouse and copied
     * (Ctrl+C / Cmd+C): no border, no background, no caret.
     */
    public static <T extends javax.swing.text.JTextComponent> T copyable(T t) {
        // A caret that never paints: the look and feel paints the caret on click despite a
        // transparent caret color. Keeps the old caret's update policy (see SettingsKit.still).
        javax.swing.text.DefaultCaret hidden = new javax.swing.text.DefaultCaret() {
            @Override
            public void paint(Graphics g) {
            }
        };
        if (t.getCaret() instanceof javax.swing.text.DefaultCaret old) hidden.setUpdatePolicy(old.getUpdatePolicy());
        t.setCaret(hidden);
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

    /** The default font in {@code style} at {@code size} points of the 100% design, scaled to the zoom. */
    public static Font font(int style, float size) {
        return UIManager.getFont("defaultFont").deriveFont(style, px(size));
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

    /** Whether this is macOS, whose app icons sit on a rounded square ({@link #appIcon}). */
    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");

    /**
     * The application icon at {@code size} pixels, in this OS's style: {@code framed} (macOS) puts
     * the logo on a white rounded square with a margin, outlined in light gray so it does not vanish
     * on a white background; otherwise (Windows) the round logo fills the whole icon with nothing
     * around it. One drawing for every place the icon appears: the window icons, the macOS Dock
     * icon set at runtime, and the files in {@code packaging/} (generated from this method:
     * {@code .icns} and the Linux {@code .png} framed, {@code .ico} not; regenerate them when it changes).
     */
    public static java.awt.image.BufferedImage appIcon(int size, boolean framed) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        if (!framed) {
            paintLogo(g, 0, 0, size);
            g.dispose();
            return img;
        }
        float line = Math.max(1f, size / 128f);
        float arc = size * 0.45f;
        java.awt.geom.RoundRectangle2D box = new java.awt.geom.RoundRectangle2D.Float(
                line / 2, line / 2, size - line, size - line, arc, arc);
        g.setColor(Color.WHITE);
        g.fill(box);
        g.setColor(new Color(0xd9d9d9));
        g.setStroke(new BasicStroke(line));
        g.draw(box);
        int margin = Math.round(size * 0.1f);
        int logo = size - 2 * margin;
        paintLogo(g, margin, margin, logo);
        g.dispose();
        return img;
    }

    /** Below this size the logo's ten thin seeds blur into a smudge, so {@link #paintLogo} draws a simpler one. */
    private static final int SMALL_LOGO = 24;

    /**
     * The logo in an {@code s}-pixel square at ({@code x}, {@code y}). From {@link #SMALL_LOGO} up it
     * is the SVG; smaller (the 16px title bar icon) it is redrawn for the pixel grid: a slightly
     * thicker rim and five larger seeds in the darkest brand green, so it still reads as the logo.
     */
    private static void paintLogo(Graphics2D g, int x, int y, int s) {
        if (s >= SMALL_LOGO) {
            // An SVG icon paints itself scaled by the UI zoom; undo that, this is a bitmap of s pixels.
            float f = UIScale.getUserScaleFactor();
            Graphics2D d = (Graphics2D) g.create();
            d.scale(1 / f, 1 / f);
            image("oe_logo_favicon.svg", s, s).paintIcon(null, d, Math.round(x * f), Math.round(y * f));
            d.dispose();
            return;
        }
        Graphics2D d = (Graphics2D) g.create();
        d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        d.translate(x, y);
        double r = s / 2.0;
        d.setColor(BC300);
        d.fill(new java.awt.geom.Ellipse2D.Double(0, 0, s, s));
        double inner = r * 0.78;
        d.setColor(BC100);
        d.fill(new java.awt.geom.Ellipse2D.Double(r - inner, r - inner, inner * 2, inner * 2));
        d.setColor(BC700);
        double len = r * 0.50, wid = r * 0.26, dist = r * 0.40;
        for (int i = 0; i < 5; i++) {
            Graphics2D seed = (Graphics2D) d.create();
            seed.rotate(Math.toRadians(-90 + 15 + i * 72), r, r);
            seed.fill(new java.awt.geom.Ellipse2D.Double(r - wid / 2, r - dist - len / 2, wid, len));
            seed.dispose();
        }
        d.dispose();
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
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), px(12), px(12));
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
        b.setPreferredSize(new Dimension(px(30), px(28)));
        b.setMargin(new Insets(0, 0, 0, 0));
        if (action != null) b.addActionListener(e -> action.run());
        return b;
    }

    /**
     * Icon button for the middle of the green navbar (Network start/stop capture, clear): a rounded square
     * frame so it reads against BC300.
     */
    public static JButton navIconButton(String icon, String tooltip, Runnable action) {
        JButton b = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth() - 1, h = getHeight() - 1;
                Color fill, line;
                if (!isEnabled()) {
                    fill = new Color(255, 255, 255, 40);
                    line = new Color(0, 0, 0, 30);
                } else {
                    fill = new Color(255, 255, 255, getModel().isRollover() ? 140 : 64);
                    line = new Color(0, 0, 0, 64);
                }
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, w, h, px(12), px(12));
                g2.setColor(line);
                g2.drawRoundRect(0, 0, w, h, px(12), px(12));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setIcon(icon(icon, 15, NAV_ICON));
        b.setRolloverIcon(icon(icon, 15, NAV_ICON_HOVER));
        b.setDisabledIcon(icon(icon, 15, NAV_ICON_DISABLED));
        b.setToolTipText(tooltip);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(px(30), px(28)));
        b.setMargin(new Insets(0, 0, 0, 0));
        if (action != null) b.addActionListener(e -> action.run());
        return b;
    }

    public static final Color NAV_ICON = new Color(0, 0, 0, 140);
    public static final Color NAV_ICON_HOVER = new Color(0, 0, 0, 204);
    public static final Color NAV_ICON_DISABLED = new Color(0, 0, 0, 64);

    /** Panel header: title on the left, actions on the right, a line under it. */
    public static JPanel header(JComponent title, JComponent... actions) {
        JPanel p = new JPanel(new BorderLayout(px(6), 0));
        p.setBackground(Color.WHITE);
        p.setBorder(new CompoundBorder(bottomLine(), pad(0, 12, 0, 8)));
        p.setPreferredSize(new Dimension(px(10), px(HEADER_H)));
        p.add(title, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, px(2), (px(HEADER_H) - 1 - px(28)) / 2));
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
        JPanel p = new JPanel(new BorderLayout(px(8), 0));
        p.setBackground(META_BG);
        p.setBorder(new CompoundBorder(topLine(), pad(0, 14, 0, 14)));
        p.setPreferredSize(new Dimension(px(10), px(META_H)));
        return p;
    }

    public static JLabel metaText(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.PLAIN, 12f));
        l.setForeground(MUTED);
        return l;
    }

    /** Extra space between the lines of a message, as a fraction of the line height. */
    public static final float MESSAGE_LINE_SPACING = 0.3f;

    /**
     * An empty text pane for messages (dialog and settings page descriptions) with wider space
     * between lines, which a text area cannot have. The spacing sits on the document's default
     * style, so it survives later {@code setText} calls. Pass it through {@link #copyable}.
     */
    public static JTextPane messagePane() {
        // Wraps at spaces only, as a word-wrapping text area does. Once non-ASCII text is inserted,
        // a document turns on its "multiByte" property and its views then wrap by the locale's line
        // breaker, which splits Korean words between syllables. Clearing the property after each
        // insert keeps the whitespace breaker: listeners added later are notified first, so this
        // runs before the views lay out the new text.
        JTextPane t = new JTextPane();
        t.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                e.getDocument().putProperty("multiByte", Boolean.FALSE);
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        javax.swing.text.StyledDocument d = t.getStyledDocument();
        javax.swing.text.StyleConstants.setLineSpacing(
                d.getStyle(javax.swing.text.StyleContext.DEFAULT_STYLE), MESSAGE_LINE_SPACING);
        return t;
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
        t.setBorder(pad(6, 8, 6, 8));
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
        s.getVerticalScrollBar().setUnitIncrement(px(16));
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
        b.setIconTextGap(px(4));
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
        split.setDividerSize(px(5));
    }

    public static final int SIDEBAR_W = 300;

    /** Default width of the right-hand panel of oeHosts and oeProxy. */
    public static final int RIGHT_W = 548;

    /**
     * Left list | the rest of a screen, with the list resizable by dragging the line between them.
     * The width starts at {@link #SIDEBAR_W} and, once dragged, is remembered and shared by every
     * screen ({@code settings.window.sidebarWidth}).
     */
    public static JSplitPane sidebarSplit(AppContext ctx, JComponent sidebar, JComponent rest) {
        sidebar.setMinimumSize(new Dimension(px(180), px(10)));
        rest.setMinimumSize(new Dimension(px(400), px(10)));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, rest);
        split.setResizeWeight(0); // the list keeps its width when the window is resized
        shareDivider(ctx, split, false, SIDEBAR_W, st -> st.window.sidebarWidth, (st, w) -> st.window.sidebarWidth = w);
        return split;
    }

    /**
     * Main area | right-hand panel. The panel keeps its width when the window is resized; the width
     * starts at {@link #RIGHT_W} and, once dragged, is shared by every screen
     * ({@code settings.window.rightPanelWidth}).
     */
    public static JSplitPane rightSplit(AppContext ctx, JComponent main, JComponent panel) {
        main.setMinimumSize(new Dimension(px(300), px(10)));
        panel.setMinimumSize(new Dimension(px(240), px(10)));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, main, panel);
        split.setResizeWeight(1);
        shareDivider(ctx, split, true, RIGHT_W, st -> st.window.rightPanelWidth, (st, w) -> st.window.rightPanelWidth = w);
        return split;
    }

    /**
     * Keeps a split's divider at a width stored in the settings and shared between screens: the
     * split takes the stored width when it is shown, and a drag by the user stores the new one.
     * {@code fromRight} measures the width of the right component instead of the left one.
     */
    private static void shareDivider(AppContext ctx, JSplitPane split, boolean fromRight, int def,
                                     java.util.function.Function<Settings, Integer> get,
                                     java.util.function.BiConsumer<Settings, Integer> set) {
        split.setContinuousLayout(true);
        lineDivider(split);
        // Widths are stored at 100% (the design size) and scaled to the current zoom here, so a
        // dragged width keeps its proportion when the font size changes.
        java.util.function.IntSupplier stored = () -> {
            Integer w = ctx.settings(get);
            return px(w != null ? w : def);
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
            int w = UIScale.unscale(current.getAsInt());
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

    /** A drop-down in the form style: 13px text, rounded, green focus ring, not focusable by Tab. */
    public static <T> JComboBox<T> combo() {
        JComboBox<T> c = new JComboBox<>();
        c.setFont(font(Font.PLAIN, 13f));
        c.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878");
        c.setFocusable(false);
        return c;
    }

    /** A text field in the form style; {@code extraStyle} is appended to its FlatLaf style (may be empty). */
    public static <T extends JTextField> T formField(T f, String extraStyle) {
        f.setFont(font(Font.PLAIN, 13f));
        f.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878; margin:4,8,4,8" + extraStyle);
        return f;
    }
}
