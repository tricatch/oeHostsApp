package tricatch.oehostsapp;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import tricatch.oehostsapp.ui.MainWindow;
import tricatch.oehostsapp.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * oeHostsApp desktop app: oeHosts / oeProxy / Network as tabs of one Swing window, no browser or
 * local web server needed. Run: {@code java -jar oehostsapp.jar [--data DIR]}.
 */
public final class OeHostsApp {

    /** Held for the life of the process; dropping it would let the lock go. */
    private static SingleInstance instance;

    private OeHostsApp() {}

    public static String version() {
        String v = BuildInfo.version();
        if (v == null) v = OeHostsApp.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    public static void main(String[] args) throws Exception {
        configureLogging();
        // macOS menu bar name instead of the main class; read when AWT starts, so set it first.
        System.setProperty("apple.awt.application.name", "oeHostsApp");
        Path dataDir = AppData.defaultDir();
        for (int i = 0; i < args.length; i++) {
            if ("--data".equals(args[i]) && i + 1 < args.length) {
                dataDir = Path.of(args[++i]);
            } else {
                System.err.println("usage: java -jar oehostsapp.jar [--data DIR]");
                System.exit(2);
            }
        }
        org.slf4j.LoggerFactory.getLogger(OeHostsApp.class).info("oeHostsApp {} on Java {} ({} {}), data {}", version(),
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                dataDir.toAbsolutePath());
        instance = SingleInstance.acquire(dataDir);
        if (instance == null) {
            // Another oeHostsApp owns this data directory; it has been asked to show its window.
            System.out.println("[oehostsapp] already running for " + dataDir.toAbsolutePath() + "; showing that window");
            System.exit(0);
        }
        quietClipboardFlavors();
        // Pure-Java rendering: no native FlatLaf DLL to extract (Smart App Control blocks unsigned ones).
        System.setProperty("flatlaf.useNativeLibrary", "false");
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#7ab84a"));
        FlatLightLaf.setup();
        UIManager.put("Component.arc", 6);
        UIManager.put("Button.arc", 6);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("SplitPaneDivider.style", "plain");
        UIManager.put("SplitPane.dividerSize", 5);
        UIManager.put("ToolTip.background", new java.awt.Color(0x212529));
        UIManager.put("ToolTip.foreground", java.awt.Color.WHITE);
        dockIcon();
        Store store = AppData.open(dataDir);
        HostsAgent.start(dataDir);
        ProxyRuntime.start(dataDir, store);
        Thread.ofVirtual().name("fwdproxy-start").start(() -> ForwardProxy.start(store));
        Thread.ofVirtual().name("share-start").start(() -> ShareServer.start(store));
        SwingUtilities.invokeLater(() -> MainWindow.open(store));
        Update.setAppArgs(args);
        Update.cleanUp();
        Update.checkInBackground();
        instance.watchFocusRequests(MainWindow::bringToFront);
    }

    /**
     * Console log (stderr) through slf4j-simple, for the app and the embedded proxy. The level is
     * {@code info} unless {@code -Doehostsapp.log=debug} (or trace, warn...) is given. Must run before
     * the first logger is created.
     */
    private static void configureLogging() {
        String p = "org.slf4j.simpleLogger.";
        System.setProperty(p + "defaultLogLevel", System.getProperty("oehostsapp.log", "info"));
        System.setProperty(p + "logFile", "System.err");
        System.setProperty(p + "showDateTime", "true");
        System.setProperty(p + "dateTimeFormat", "HH:mm:ss.SSS");
        System.setProperty(p + "showThreadName", "false");
        System.setProperty(p + "showShortLogName", "true");
    }

    /**
     * The macOS Dock shows the Java icon unless the app sets its own through {@link Taskbar}; the
     * window icons do not reach it. The logo is drawn large (Dock icons are up to 1024px on Retina)
     * and fills the whole icon, with no margin, the same as packaging/macos/oehostsapp.icns. Windows has
     * no such feature and keeps the window icons.
     */
    private static void dockIcon() {
        if (!Taskbar.isTaskbarSupported() || !Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) return;
        BufferedImage img = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Ui.image("oe_logo_favicon.svg", 1024, 1024).paintIcon(null, g, 0, 0);
        g.dispose();
        try {
            Taskbar.getTaskbar().setIconImage(img);
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // keep the default icon
        }
    }

    /**
     * Drops one JDK console message: the first clipboard read of the process (a paste, for one)
     * makes SystemFlavorMap print, for every Java flavor another program put on the clipboard
     * whose class this JVM lacks (IntelliJ's editor copies, for example),
     * {@code Exception "java.lang.ClassNotFoundException: ..."while constructing DataFlavor for: ...}.
     * The text flavor still pastes; every other line goes through unchanged.
     */
    private static void quietClipboardFlavors() {
        PrintStream err = System.err;
        System.setErr(new PrintStream(err, true, err.charset()) {
            @Override
            public void println(String x) {
                if (x != null && x.startsWith("Exception \"") && x.contains("\"while constructing DataFlavor for: ")) return;
                super.println(x);
            }
        });
    }
}
