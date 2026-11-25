package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.store.Settings;
import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.update.Update;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The app window: the green navbar of _layout.html (OEHOSTS | OEPROXY tabs,
 * settings gear) over the screens.
 */
public final class MainWindow {

    private static final String[] TABS = {"oeHosts", "oeProxy", "Network", "settings"};
    /** Tabs with a label in the navbar; the settings tab is opened with the gear button. */
    private static final int NAV_TABS = 3;
    private static final int MONITOR = 2;
    private static final int SETTINGS = 3;
    /** The window on screen (replaced when the window is rebuilt for a language change). */
    private static volatile MainWindow shown;

    private final AppContext ctx;
    /** Kept across rebuilds: a language change swaps the contents, not the window. */
    private final JFrame frame;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final List<JLabel> tabLabels = new ArrayList<>();
    private final JPanel navActions = new JPanel(new CardLayout());
    private HostsTab hosts;
    private ProxyTab proxy;
    private MonitorTab monitor;
    private SettingsTab settings;
    private JButton gear;
    /** Next to the gear while a newer release waits ({@link #updateChanged}). */
    private JButton updateButton;
    private JPanel navBar;
    private int current = -1;

    private MainWindow(Store store, JFrame frame) {
        this.frame = frame;
        ctx = new AppContext(store);
        ctx.setFrame(frame);
    }

    public static void open(Store store) {
        JFrame frame = new JFrame("oeHostsApp");
        setUpFrame(frame);
        MainWindow w = new MainWindow(store, frame);
        shown = w;
        w.build();
        boolean maximized = w.restoreBounds();
        w.select(0);
        if (maximized) frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
        frame.setVisible(true);
        // Another device's first request to the proxy service asks here (on the proxy's thread).
        tricatch.oehostsapp.proxy.ClientAccess.setAsker(r -> SwingUtilities.invokeLater(() -> askAccess(r)));
        String shot = System.getProperty("oehostsapp.shot");
        showReleaseNotesAfterUpdate(store, shot == null);
        if (shot != null) w.screenshots(shot);
    }

    /**
     * The first start of a newer version on this data (an update, by the app or the installer) opens
     * that release's notes in the browser, once. Not in screenshot mode ({@code browse} false),
     * which only logs it.
     */
    private static void showReleaseNotesAfterUpdate(Store store, boolean browse) {
        String current = tricatch.oehostsapp.OeHostsApp.version();
        String previous;
        synchronized (store) {
            previous = store.swapLastRunVersion(current);
        }
        if (!Update.justUpdated(previous, current)) return;
        String url = Update.releaseUrl(current);
        org.slf4j.LoggerFactory.getLogger(MainWindow.class).info("updated from {} to {}: release notes {}", previous, current, url);
        if (!browse || !Desktop.isDesktopSupported()) return;
        // Off the event thread: the browser may take a moment to start.
        Thread.ofVirtual().name("release-notes").start(() -> {
            try {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(MainWindow.class).warn("cannot open the release notes: {}", e.getMessage());
            }
        });
    }

    /** Devices waiting for an answer, asked one after another (the question is a modal dialog). */
    private static final java.util.ArrayDeque<tricatch.oehostsapp.proxy.ClientAccess.Request> accessQueue = new java.util.ArrayDeque<>();
    private static boolean asking;

    /**
     * Asks whether a device may use the proxy service: its address and User-Agent, Allow or Deny.
     * Closing the dialog denies. The window comes to the front first, since the request usually
     * arrives while the user is on the phone or another PC. EDT only.
     */
    private static void askAccess(tricatch.oehostsapp.proxy.ClientAccess.Request first) {
        accessQueue.add(first);
        if (asking) return;
        asking = true;
        try {
            tricatch.oehostsapp.proxy.ClientAccess.Request r;
            while ((r = accessQueue.poll()) != null) {
                MainWindow w = shown;
                if (w == null) {
                    tricatch.oehostsapp.proxy.ClientAccess.decide(r, false);
                    continue;
                }
                bringToFront();
                AppContext ctx = w.ctx;
                boolean[] allow = {false};
                tricatch.oehostsapp.proxy.ClientAccess.Request req = r;
                OeDialog.open(ctx, ctx.m("app.access.title"), d -> {
                    JComponent note = OeDialog.note(ctx.m("app.access.desc"));
                    Dialogs.fixWidth(note, 520);
                    JTextField[] v = new JTextField[1];
                    JPanel info = SettingsKit.table(new String[][]{{ctx.m("app.access.ip"), req.ip()}}, v);
                    // The User-Agent is long and tells the devices apart: wrapped in full, not cut off.
                    JLabel uaLabel = SettingsKit.label(ctx.m("app.access.ua"));
                    JTextArea ua = OeDialog.script(req.userAgent() == null || req.userAgent().isBlank() ? "-" : req.userAgent());
                    ua.setWrapStyleWord(true);
                    Dialogs.fixWidth(ua, 520);
                    d.body(OeDialog.stack(12, note, info, OeDialog.stack(6, uaLabel, ua)));
                    d.button(ctx.m("app.access.allow"), true, () -> {
                        allow[0] = true;
                        d.dispose();
                    });
                    d.button(ctx.m("app.access.deny"), false, d::dispose);
                });
                tricatch.oehostsapp.proxy.ClientAccess.decide(req, allow[0]);
            }
        } finally {
            asking = false;
        }
    }

    /**
     * Development aid ({@code -Doehostsapp.shot=<prefix>}): saves each tab as {@code <prefix>-<n>.png}
     * once the window has settled, each dialog as {@code <prefix>-d<n>.png} and the window behind it
     * as {@code <prefix>-d<n>-window.png}, then exits. Lets the UI be checked without a person at the screen.
     */
    private void screenshots(String prefix) {
        Runnable[] dialogs = {() -> hosts.showDirectCmd(true), hosts::showShare, monitor::showSslSettings};
        Timer t = new Timer(1500, null);
        int[] step = {0};
        t.addActionListener(e -> {
            if (step[0] < TABS.length) {
                save(frame.getRootPane(), prefix + "-" + step[0]);
                step[0]++;
                if (step[0] < TABS.length) select(step[0]);
                else select(0);
                return;
            }
            int d = step[0] - TABS.length;
            if (d >= dialogs.length) {
                monitor.dispose();
                System.exit(0);
            }
            step[0]++;
            // The dialog is modal: capture and close it from a timer running inside its event loop.
            Timer cap = new Timer(800, ev -> {
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog dl && dl.isVisible()) {
                        save(dl.getRootPane(), prefix + "-d" + d);
                        // The window behind it, with the dialog backdrop.
                        save(frame.getRootPane(), prefix + "-d" + d + "-window");
                        dl.dispose();
                    }
                }
            });
            cap.setRepeats(false);
            cap.start();
            dialogs[d].run();
        });
        select(0);
        t.start();
    }

    /**
     * Widens every visible label under {@code c} a little, keeping its old width in {@code old}.
     * Only labels without a background or border: a boxed label would cover its neighbour.
     */
    private static void widenLabels(Container c, java.util.Map<JLabel, Integer> old) {
        for (Component child : c.getComponents()) {
            if (child instanceof JLabel l && l.isShowing() && l.getWidth() > 0 && !l.isOpaque() && l.getBorder() == null) {
                old.put(l, l.getWidth());
                l.setSize(l.getWidth() + Math.max(4, l.getWidth() / 25), l.getHeight());
            }
            if (child instanceof Container sub) widenLabels(sub, old);
        }
    }

    private static void save(JComponent c, String file) {
        // At the display's scale (e.g. 150%), as on screen: text is measured at that scale, and
        // painted at 100% it can come out wider than its label and look cut off.
        // -Doehostsapp.shot.scale=<n> paints the same layout at n times the pixels instead (sharp
        // pictures for high-density screens, e.g. the intro page's).
        double s = c.getGraphicsConfiguration() == null ? 1 : c.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        boolean forcedScale = false;
        try {
            String forced = System.getProperty("oehostsapp.shot.scale");
            if (forced != null && Double.parseDouble(forced) > 0) {
                s = Double.parseDouble(forced);
                forcedScale = true;
            }
        } catch (NumberFormatException ignored) {
            // keep the display's scale
        }
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage((int) Math.ceil(c.getWidth() * s),
                (int) Math.ceil(c.getHeight() * s), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // Glyphs at their exact scaled widths: hinted at n times the size they can come out wider
        // than the text measured for the layout, and be cut off.
        if (forcedScale) g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.scale(s, s);
        // A label sized to its text at 100% can still lose its last letter at n times: widen the
        // labels by a few pixels for the picture only, then put them back.
        java.util.Map<JLabel, Integer> widened = new java.util.HashMap<>();
        if (forcedScale) widenLabels(c, widened);
        c.paint(g);
        widened.forEach((l, w) -> l.setSize(w, l.getHeight()));
        g.dispose();
        try {
            ImageIO.write(img, "png", new java.io.File(file + ".png"));
        } catch (java.io.IOException ex) {
            ex.printStackTrace();
        }
    }

    /** Shows the window in front of others (a second start of the app asked for it). Any thread. */
    public static void bringToFront() {
        SwingUtilities.invokeLater(() -> {
            MainWindow w = shown;
            if (w == null) return;
            JFrame f = w.frame;
            if ((f.getExtendedState() & Frame.ICONIFIED) != 0) f.setExtendedState(f.getExtendedState() & ~Frame.ICONIFIED);
            f.setVisible(true);
            // Windows refuses a plain toFront() from a background app; a brief always-on-top does it.
            f.setAlwaysOnTop(true);
            f.toFront();
            f.requestFocus();
            f.setAlwaysOnTop(false);
        });
    }

    /** Saves the window state and pending edits, then ends the process (window close, update restart). */
    public static void quit() {
        if (shown != null) {
            shown.saveWindowState();
            shown.flush();
        }
        System.exit(0);
    }

    /** What the window needs once, whatever is shown in it. */
    private static void setUpFrame(JFrame frame) {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }
        });
        // Drawn at each size the OS may pick (title bar, taskbar, Alt+Tab, high DPI), in its style.
        List<Image> icons = new ArrayList<>();
        for (int s : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) icons.add(Ui.appIcon(s, Ui.MAC));
        frame.setIconImages(icons);
        frame.setMinimumSize(Ui.dim(1000, 600));
        fontSizeKeys(frame);
        // Window state is also saved shortly after each move/resize/maximize, not only on close:
        // quitting another way (e.g. Cmd+Q on macOS) skips windowClosing.
        Timer saveLater = new Timer(600, e -> {
            if (shown != null) shown.saveWindowState();
        });
        saveLater.setRepeats(false);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                saveLater.restart();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                saveLater.restart();
            }
        });
        frame.addWindowStateListener(e -> saveLater.restart());
    }

    private static final int DEFAULT_W = 1400;
    private static final int DEFAULT_H = 860;

    /**
     * Sizes and places the window as it was last time ({@code settings.window}), or at the default
     * size in the middle of the screen when nothing is stored or the stored place is no longer on
     * any screen (a monitor was unplugged). Returns whether the window should open maximized.
     */
    private boolean restoreBounds() {
        Rectangle r = ctx.settings(st -> {
            Settings.Window w = st.window;
            return w.width == null || w.height == null || w.x == null || w.y == null
                    ? null : new Rectangle(w.x, w.y, w.width, w.height);
        });
        if (r != null && onScreen(r)) {
            frame.setBounds(r);
        } else {
            frame.setSize(Ui.px(DEFAULT_W), Ui.px(DEFAULT_H));
            frame.setLocationRelativeTo(null);
        }
        return ctx.settings(st -> st.window.maximized);
    }

    /** True when enough of the window's title bar lies on some screen to grab it with the mouse. */
    private static boolean onScreen(Rectangle r) {
        Rectangle titleBar = new Rectangle(r.x, r.y, r.width, Ui.px(30));
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle hit = titleBar.intersection(d.getDefaultConfiguration().getBounds());
            if (!hit.isEmpty() && hit.width >= 100 && hit.height >= 20) return true;
        }
        return false;
    }

    /**
     * Stores the window state for {@link #restoreBounds}. The bounds are taken only while the window
     * is in its normal state, so a maximized window keeps the size it returns to; a minimized window
     * stores nothing. Saves only when something changed.
     */
    private void saveWindowState() {
        int state = frame.getExtendedState();
        if (!frame.isShowing() || (state & Frame.ICONIFIED) != 0) return;
        boolean max = (state & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        Rectangle r = max ? null : frame.getBounds();
        ctx.storeRun(s -> {
            Settings.Window w = s.data.settings.window;
            boolean changed = w.maximized != max;
            w.maximized = max;
            if (r != null && !(Integer.valueOf(r.x).equals(w.x) && Integer.valueOf(r.y).equals(w.y)
                    && Integer.valueOf(r.width).equals(w.width) && Integer.valueOf(r.height).equals(w.height))) {
                w.x = r.x;
                w.y = r.y;
                w.width = r.width;
                w.height = r.height;
                changed = true;
            }
            if (changed) s.save();
        });
    }

    private void flush() {
        hosts.flush();
        proxy.flush();
        settings.flush();
    }

    /** What the MCP tools that change something go through ({@link tricatch.oehostsapp.mcp.AppControl}). */
    private tricatch.oehostsapp.mcp.AppControl control;

    /**
     * The window's side of the AI tools: a change runs on the event thread after the editors' pending
     * edits are saved, and then the screens of the areas it touched read the store again.
     */
    private final class WindowControl implements tricatch.oehostsapp.mcp.AppControl {
        @Override
        public <T> T apply(List<Area> areas, java.util.function.Supplier<T> change) {
            return tricatch.oehostsapp.mcp.AppControl.onEventThread(() -> {
                flush();
                T result = change.get();
                if (areas.contains(Area.HOSTS)) hosts.reloadFromStore();
                if (areas.contains(Area.VHOSTS)) proxy.reloadFromStore();
                if (areas.contains(Area.LAUNCHER)) hosts.reloadOptions();
                if (areas.contains(Area.SETTINGS)) {
                    settings.rebuildPages();
                    ctx.fireSettingsChanged();
                }
                return result;
            });
        }

        @Override
        public List<String> chromeWords(boolean uddOnly) {
            return tricatch.oehostsapp.mcp.AppControl.onEventThread(() -> hosts.chromeWords(uddOnly));
        }

        @Override
        public void setCapturing(boolean on) {
            tricatch.oehostsapp.mcp.AppControl.onEventThread(() -> {
                monitor.setCapturing(on);
                return null;
            });
        }

        @Override
        public void clearTraffic() {
            tricatch.oehostsapp.mcp.AppControl.onEventThread(() -> {
                monitor.clearRows();
                return null;
            });
        }
    }

    private void build() {
        hosts = new HostsTab(ctx, () -> select(MONITOR));
        proxy = new ProxyTab(ctx);
        monitor = new MonitorTab(ctx);
        content.add(hosts, TABS[0]);
        content.add(proxy, TABS[1]);
        content.add(monitor, TABS[2]);
        settings = new SettingsTab(ctx, new SettingsTab.Host() {
            public void relaunch() {
                MainWindow.this.relaunch();
            }

            public void reloadAll() {
                hosts.reloadAll();
                proxy.reloadAll();
            }

            public void flushEdits() {
                hosts.flush();
                proxy.flush();
            }

            public void fontSize(Integer size) {
                MainWindow.this.fontSize(size);
            }
        });
        content.add(settings, TABS[SETTINGS]);
        control = new WindowControl();
        tricatch.oehostsapp.mcp.AppControl.set(control);
        monitor.onGotoCa(() -> {
            select(SETTINGS);
            settings.showPage("ca");
        });
        ctx.listen(Update::onChange, this::updateChanged);

        JPanel root = new JPanel(new BorderLayout());
        root.add(navbar(), BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        frame.setContentPane(root);
    }

    private JComponent navbar() {
        // West: the tabs; east: the gear; the current screen's actions sit in the middle of the
        // whole bar (not of the space left between the two sides).
        JPanel bar = new JPanel(null) {
            @Override
            public void doLayout() {
                Insets in = getInsets();
                int h = getHeight() - in.top - in.bottom;
                Component west = getComponent(0), east = getComponent(1), mid = getComponent(2);
                int ww = west.getPreferredSize().width, ew = east.getPreferredSize().width;
                west.setBounds(in.left, in.top, ww, h);
                east.setBounds(getWidth() - in.right - ew, in.top, ew, h);
                int mw = Math.min(mid.getPreferredSize().width, Math.max(0, getWidth() - 2 * Math.max(in.left + ww, in.right + ew)));
                mid.setBounds((getWidth() - mw) / 2, in.top, mw, h);
            }
        };
        navBar = bar;
        bar.setBackground(Ui.BC300);
        bar.setBorder(new javax.swing.border.CompoundBorder(new MatteBorder(0, 0, 1, 0, Ui.NAV_BORDER), Ui.pad(0, 6, 0, 10)));
        bar.setPreferredSize(new Dimension(Ui.px(10), Ui.px(46)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        // The logo in front of the tabs, as in oeHub's navbar.
        JLabel logo = new JLabel(Ui.image("oe_logo_nav.svg", 28, 28));
        logo.setBorder(Ui.pad(0, 2, 0, 8));
        left.add(wrapMiddle(logo));
        for (int i = 0; i < NAV_TABS; i++) {
            if (i > 0) {
                JLabel sep = new JLabel("|");
                sep.setForeground(new Color(255, 255, 255, 102));
                sep.setFont(Ui.font(Font.PLAIN, 13f));
                sep.setBorder(Ui.pad(0, 5, 0, 5));
                left.add(wrapMiddle(sep));
            }
            left.add(wrapMiddle(tab(i)));
        }
        navActions.setOpaque(false);
        navActions.add(new JPanel() {{ setOpaque(false); }}, TABS[0]);
        navActions.add(new JPanel() {{ setOpaque(false); }}, TABS[1]);
        // Network: start/stop capture, clear, SSL proxy.
        navActions.add(wrapMiddle(monitor.navActions()), TABS[MONITOR]);
        navActions.add(new JPanel() {{ setOpaque(false); }}, TABS[SETTINGS]);
        bar.add(left);
        bar.add(eastButtons());
        bar.add(navActions);
        return bar;
    }

    private static JComponent wrapMiddle(JComponent c) {
        // The width follows the child every time (not a size fixed at creation), so a label whose
        // font or text changes later is never cut off.
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(c.getPreferredSize().width, Ui.px(45));
            }
        };
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    /** {@code .oe-nav-item}: white uppercase, underlined when active, light tint on hover. */
    private JLabel tab(int index) {
        JLabel l = new JLabel(TABS[index].toUpperCase()) {
            boolean hover;

            {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        repaint();
                    }

                    @Override
                    public void mousePressed(MouseEvent e) {
                        select(index);
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (current == index) {
                    g2.setColor(Color.WHITE);
                    g2.fillRect(0, getHeight() - Ui.px(2), getWidth(), Ui.px(2));
                } else if (hover) {
                    g2.setColor(new Color(255, 255, 255, 51));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), Ui.px(12), Ui.px(12));
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(Ui.font(Font.BOLD, 14f));
        l.setBorder(Ui.pad(5, 8, 5, 8));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tabLabels.add(l);
        return l;
    }

    private void select(int index) {
        if (index == current) return;
        current = index;
        cards.show(content, TABS[index]);
        ((CardLayout) navActions.getLayout()).show(navActions, TABS[index]);
        for (int i = 0; i < tabLabels.size(); i++) {
            tabLabels.get(i).setForeground(i == index ? Color.WHITE : new Color(255, 255, 255, 191));
            tabLabels.get(i).repaint();
        }
        monitor.setVisibleTab(index == MONITOR);
        if (gear != null) gear.repaint();
    }

    /** The newer release while one is available (until the app is updated); else null. */
    private static String pendingUpdate() {
        Update.Status s = Update.status();
        return s.state() == Update.State.AVAILABLE && s.latest() != null ? s.latest() : null;
    }

    /** Shows the update button next to the gear while a newer release waits, and hides it otherwise. */
    private void updateChanged() {
        if (updateButton == null) return;
        String v = pendingUpdate();
        if (v != null) {
            updateButton.setText(ctx.m("app.update.nav", v));
            updateButton.setToolTipText(ctx.m("app.update.available", v));
            // As tall as the other navbar buttons, as wide as its text.
            updateButton.setPreferredSize(null);
            updateButton.setPreferredSize(new Dimension(updateButton.getPreferredSize().width, Ui.px(28)));
        }
        if (updateButton.isVisible() != (v != null)) {
            updateButton.setVisible(v != null);
            // The bar places its east side by preferred width: lay it out again.
            navBar.revalidate();
            navBar.repaint();
        }
    }

    /**
     * The east side of the navbar: the update button (only while a newer release waits; it opens
     * Settings → About, where the update is installed) and the gear.
     */
    private JComponent eastButtons() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        p.setOpaque(false);
        updateButton = Ui.navIconButton("download", null, () -> {
            select(SETTINGS);
            settings.showPage("about");
        });
        updateButton.setFont(Ui.font(Font.BOLD, 12f));
        updateButton.setForeground(Ui.NAV_ICON_HOVER);
        updateButton.setIconTextGap(Ui.px(5));
        updateButton.setBorder(Ui.pad(0, 9, 0, 10));
        updateButton.setPreferredSize(null);
        updateButton.setVisible(false);
        JPanel slot = new JPanel(new GridBagLayout());
        slot.setOpaque(false);
        slot.setBorder(Ui.pad(0, 0, 0, 4));
        slot.add(updateButton);
        p.add(slot);
        p.add(settingsButton());
        // After the window is up (the check may have finished already, e.g. on a rebuild).
        SwingUtilities.invokeLater(this::updateChanged);
        return p;
    }

    /** The gear opens the settings tab; it is underlined like a nav tab while that tab is shown. */
    private JComponent settingsButton() {
        gear = new JButton(Ui.icon("settings", 16, new Color(255, 255, 255, 217))) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (current == SETTINGS) {
                    g.setColor(Color.WHITE);
                    g.fillRect(Ui.px(4), getHeight() - Ui.px(2), getWidth() - Ui.px(8), Ui.px(2));
                }
            }
        };
        gear.setRolloverIcon(Ui.icon("settings", 16, Color.WHITE));
        gear.setContentAreaFilled(false);
        gear.setBorderPainted(false);
        gear.setFocusable(false);
        gear.setToolTipText(ctx.m("nav.settings"));
        gear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gear.setPreferredSize(new Dimension(Ui.px(36), Ui.px(45)));
        gear.addActionListener(e -> select(SETTINGS));
        return gear;
    }

    /**
     * Ctrl (Cmd on macOS) with + / - / 0: one font size step up or down, or back to the default.
     * Bound on the root pane, which outlives the rebuilds, for whichever window is shown.
     */
    private static void fontSizeKeys(JFrame frame) {
        int mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        int shift = java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        Object[][] keys = {
                {java.awt.event.KeyEvent.VK_EQUALS, mod, 1}, {java.awt.event.KeyEvent.VK_EQUALS, mod | shift, 1}, {java.awt.event.KeyEvent.VK_PLUS, mod, 1},
                {java.awt.event.KeyEvent.VK_ADD, mod, 1}, {java.awt.event.KeyEvent.VK_MINUS, mod, -1}, {java.awt.event.KeyEvent.VK_SUBTRACT, mod, -1},
                {java.awt.event.KeyEvent.VK_0, mod, 0}, {java.awt.event.KeyEvent.VK_NUMPAD0, mod, 0}};
        for (Object[] k : keys) {
            int dir = (Integer) k[2];
            frame.getRootPane().registerKeyboardAction(e -> {
                if (shown != null) shown.stepFontSize(dir);
            }, KeyStroke.getKeyStroke((Integer) k[0], (Integer) k[1]), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
    }

    /** One step of {@link Ui#fontSizes()} up (1) or down (-1), or the default size (0). */
    private void stepFontSize(int dir) {
        if (dir == 0) {
            fontSize(null);
            return;
        }
        int[] sizes = Ui.fontSizes();
        Integer stored = ctx.settings(st -> st.fontSize);
        int cur = stored == null ? Ui.defaultFontSize() : stored;
        int next = cur;
        if (dir > 0) {
            for (int s : sizes) if (s > cur) { next = s; break; }
        } else {
            for (int i = sizes.length - 1; i >= 0; i--) if (sizes[i] < cur) { next = sizes[i]; break; }
        }
        if (next != cur) fontSize(next == Ui.defaultFontSize() ? null : next);
    }

    /**
     * Saves the font size (null: the default), zooms the whole UI to it and rebuilds the screens
     * in the same window, like a language change.
     */
    private void fontSize(Integer size) {
        Integer current = ctx.settings(st -> st.fontSize);
        if (java.util.Objects.equals(current, size)) return;
        if (!ctx.saveSettings(st -> st.fontSize = size)) return;
        // Later, not inside the event that asked for it: a combo box's list is a popup in the
        // window's layered pane that is still closing, and swapping the content pane under it
        // throws (IndexOutOfBoundsException in Container.remove).
        // Several quick key presses may queue several of these before the first runs: each one
        // applies the latest stored size to the window shown then, and only when it changed.
        SwingUtilities.invokeLater(() -> {
            MainWindow w = shown;
            if (w == null) return;
            Integer latest = w.ctx.settings(st -> st.fontSize);
            if (java.util.Objects.equals(latest, Ui.appliedFontSize())) return;
            Ui.applyFontSize(latest);
            w.frame.setMinimumSize(Ui.dim(1000, 600));
            // The rebuilt screens are created with the new sizes. No FlatLaf.updateUI(): it would
            // reset what the screens customize on top of the look and feel (the split dividers).
            w.relaunch();
        });
    }

    /**
     * Rebuilds the screens (language change, import) in the same window, on the same tab and
     * settings page: the window itself never closes, so it does not blink or move.
     */
    private void relaunch() {
        flush();
        // The rebuilt window registers its own (in build).
        tricatch.oehostsapp.mcp.AppControl.clear(control);
        monitor.dispose();
        // The old screens stop listening to the services, so they can go.
        ctx.dispose();
        MainWindow w = new MainWindow(ctx.rawStore(), frame);
        w.build();
        w.settings.showPage(settings.activePage());
        w.select(current);
        shown = w;
        frame.revalidate();
        frame.repaint();
    }
}
