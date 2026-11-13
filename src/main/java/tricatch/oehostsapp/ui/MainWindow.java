package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.OeHostsApp;
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
    private int current = -1;
    /**
     * The newer release already handled for the toast in this run, and the one for which the gear
     * already led to Settings → About. Static: the window is rebuilt on a language change.
     */
    private static volatile String toastedUpdate, seenUpdate;

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
        String shot = System.getProperty("oehostsapp.shot");
        if (shot != null) w.screenshots(shot);
    }

    /**
     * Development aid ({@code -Doehostsapp.shot=<prefix>}): saves each tab as {@code <prefix>-<n>.png}
     * once the window has settled, each dialog as {@code <prefix>-d<n>.png} and the window behind it
     * as {@code <prefix>-d<n>-window.png}, then exits. Lets the UI be checked without a person at the screen.
     */
    private void screenshots(String prefix) {
        Runnable[] dialogs = {() -> hosts.showDirectCmd(true), hosts::showShare};
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

    private static void save(JComponent c, String file) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(c.getWidth(), c.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
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
        frame.setMinimumSize(new Dimension(1000, 600));
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
     * Sizes and places the window as it was last time ({@code settings.window*}), or at the default
     * size in the middle of the screen when nothing is stored or the stored place is no longer on
     * any screen (a monitor was unplugged). Returns whether the window should open maximized.
     */
    private boolean restoreBounds() {
        Rectangle r = ctx.read(() -> {
            Store.Settings st = ctx.rawStore().data.settings;
            return st.windowWidth == null || st.windowHeight == null || st.windowX == null || st.windowY == null
                    ? null : new Rectangle(st.windowX, st.windowY, st.windowWidth, st.windowHeight);
        });
        if (r != null && onScreen(r)) {
            frame.setBounds(r);
        } else {
            frame.setSize(DEFAULT_W, DEFAULT_H);
            frame.setLocationRelativeTo(null);
        }
        return ctx.read(() -> ctx.rawStore().data.settings.windowMaximized);
    }

    /** True when enough of the window's title bar lies on some screen to grab it with the mouse. */
    private static boolean onScreen(Rectangle r) {
        Rectangle titleBar = new Rectangle(r.x, r.y, r.width, 30);
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
            Store.Settings st = s.data.settings;
            boolean changed = st.windowMaximized != max;
            st.windowMaximized = max;
            if (r != null && !(Integer.valueOf(r.x).equals(st.windowX) && Integer.valueOf(r.y).equals(st.windowY)
                    && Integer.valueOf(r.width).equals(st.windowWidth) && Integer.valueOf(r.height).equals(st.windowHeight))) {
                st.windowX = r.x;
                st.windowY = r.y;
                st.windowWidth = r.width;
                st.windowHeight = r.height;
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

    private void build() {
        hosts = new HostsTab(ctx);
        proxy = new ProxyTab(ctx);
        monitor = new MonitorTab(ctx, proxy);
        content.add(hosts, TABS[0]);
        content.add(proxy, TABS[1]);
        content.add(monitor, TABS[2]);
        settings = new SettingsTab(ctx, this::relaunch, () -> {
            hosts.reloadAll();
            proxy.reloadAll();
        }, () -> {
            hosts.flush();
            proxy.flush();
        });
        content.add(settings, TABS[SETTINGS]);
        monitor.onGotoProxy(() -> select(1));
        Update.onChange(() -> SwingUtilities.invokeLater(this::updateChanged));

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
        bar.setBackground(Ui.BC300);
        bar.setBorder(new javax.swing.border.CompoundBorder(new MatteBorder(0, 0, 1, 0, Ui.NAV_BORDER), new EmptyBorder(0, 6, 0, 10)));
        bar.setPreferredSize(new Dimension(10, 46));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        // The logo in front of the tabs, as in oeHub's navbar.
        JLabel logo = new JLabel(Ui.image("oe_logo_nav.svg", 28, 28));
        logo.setBorder(new EmptyBorder(0, 2, 0, 8));
        left.add(wrapMiddle(logo));
        for (int i = 0; i < NAV_TABS; i++) {
            if (i > 0) {
                JLabel sep = new JLabel("|");
                sep.setForeground(new Color(255, 255, 255, 102));
                sep.setFont(Ui.font(Font.PLAIN, 13f));
                sep.setBorder(new EmptyBorder(0, 5, 0, 5));
                left.add(wrapMiddle(sep));
            }
            left.add(wrapMiddle(tab(i)));
        }
        navActions.setOpaque(false);
        navActions.add(new JPanel() {{ setOpaque(false); }}, TABS[0]);
        navActions.add(new JPanel() {{ setOpaque(false); }}, TABS[1]);
        // Network: start/stop capture and clear.
        navActions.add(wrapMiddle(monitor.navActions()), TABS[MONITOR]);
        navActions.add(new JPanel() {{ setOpaque(false); }}, TABS[SETTINGS]);
        bar.add(left);
        bar.add(wrapMiddle(settingsButton()));
        bar.add(navActions);
        return bar;
    }

    private static JComponent wrapMiddle(JComponent c) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.add(c);
        p.setPreferredSize(new Dimension(c.getPreferredSize().width, 45));
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
                    g2.fillRect(0, getHeight() - 2, getWidth(), 2);
                } else if (hover) {
                    g2.setColor(new Color(255, 255, 255, 51));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(Ui.font(Font.BOLD, 14f));
        l.setBorder(new EmptyBorder(5, 8, 5, 8));
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

    /** The newer release while one is available (the gear keeps its dot until the app is updated); else null. */
    private static String pendingUpdate() {
        Update.Status s = Update.status();
        return s.state() == Update.State.AVAILABLE && s.latest() != null ? s.latest() : null;
    }

    /**
     * A newer release was found: a dot on the gear, and a toast pointing at it once per release
     * ever, not at every start (the release it was shown for is saved in {@code settings.updateToasted}).
     */
    private void updateChanged() {
        if (gear != null) gear.repaint();
        String v = pendingUpdate();
        if (v == null || v.equals(toastedUpdate)) return;
        toastedUpdate = v;
        if (v.equals(ctx.read(() -> ctx.rawStore().data.settings.updateToasted))) return;
        ctx.storeRun(s -> {
            s.data.settings.updateToasted = v;
            s.save();
        });
        ctx.toast(ctx.m("app.update.available.toast", v));
    }

    /**
     * The gear opens the settings tab; it is underlined like a nav tab while that tab is shown.
     * While a newer release waits it carries a dot, and its first click for that release in this
     * run opens Settings → About.
     */
    private JComponent settingsButton() {
        gear = new JButton(Ui.icon("settings", 16, new Color(255, 255, 255, 217))) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (current == SETTINGS) {
                    g.setColor(Color.WHITE);
                    g.fillRect(4, getHeight() - 2, getWidth() - 8, 2);
                }
                if (pendingUpdate() != null) {
                    // Top right of the 16px icon: a dark green dot in a white ring, readable on the green bar.
                    Graphics2D d = (Graphics2D) g.create();
                    d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int cx = getWidth() / 2 + 7, cy = getHeight() / 2 - 7;
                    d.setColor(Color.WHITE);
                    d.fillOval(cx - 5, cy - 5, 10, 10);
                    d.setColor(Ui.BC700);
                    d.fillOval(cx - 3, cy - 3, 6, 6);
                    d.dispose();
                }
            }
        };
        gear.setRolloverIcon(Ui.icon("settings", 16, Color.WHITE));
        gear.setContentAreaFilled(false);
        gear.setBorderPainted(false);
        gear.setFocusable(false);
        gear.setToolTipText(ctx.m("nav.settings"));
        gear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gear.setPreferredSize(new Dimension(36, 45));
        gear.addActionListener(e -> {
            String v = pendingUpdate();
            select(SETTINGS);
            if (v != null && !v.equals(seenUpdate)) {
                seenUpdate = v;
                settings.showPage("about");
            }
        });
        // After the window is up (the check may have finished already, e.g. on a rebuild).
        SwingUtilities.invokeLater(this::updateChanged);
        return gear;
    }

    /**
     * Rebuilds the screens (language change, import) in the same window, on the same tab and
     * settings page: the window itself never closes, so it does not blink or move.
     */
    private void relaunch() {
        flush();
        monitor.dispose();
        MainWindow w = new MainWindow(ctx.rawStore(), frame);
        w.build();
        w.settings.showPage(settings.activePage());
        w.select(current);
        shown = w;
        frame.revalidate();
        frame.repaint();
    }
}
