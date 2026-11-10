package tricatch.oehubapp.ui;

import tricatch.oehubapp.OeHubApp;
import tricatch.oehubapp.ProxyRuntime;
import tricatch.oehubapp.Store;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The settings screen, laid out like the oeHosts / oeProxy screens: a left list of folders
 * (oeHosts, oeProxy, General) with one entry per settings page, and the selected page on the right.
 */
public final class SettingsTab extends JPanel {

    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private record Page(String id, String titleKey, Supplier<JComponent> build) {}

    private record Folder(String titleKey, List<Page> pages) {}

    private final AppContext ctx;
    private final Runnable relaunch;
    private final Runnable reloadAll;
    private final Runnable flushEdits;
    private final List<Folder> folders = new ArrayList<>();
    private final Set<String> collapsed = new HashSet<>();
    private final Map<String, JComponent> built = new LinkedHashMap<>();
    private final JPanel list = new JPanel();
    private final JPanel cards = new JPanel(new CardLayout());
    private final JLabel title = Ui.title(" ");
    private String activeId;
    /** The preset editors built so far. */
    private final List<Autosave> autosaves = new ArrayList<>();
    /** Bottom-bar status of each page that has one, by page id; "" is the empty card. */
    private final JPanel metaStatus = new JPanel(new CardLayout());
    private final Set<String> metaPages = new HashSet<>();

    /** Saves the preset editors' pending edits (window closing, rebuild). */
    public void flush() {
        for (Autosave a : autosaves) a.flush();
    }

    public SettingsTab(AppContext ctx, Runnable relaunch, Runnable reloadAll) {
        this(ctx, relaunch, reloadAll, () -> {});
    }

    /**
     * @param relaunch rebuilds the window (language change, full restore)
     * @param reloadAll re-reads the profiles after an import
     * @param flushEdits saves the oeHosts/oeProxy editors' pending edits, before an import replaces data
     */
    public SettingsTab(AppContext ctx, Runnable relaunch, Runnable reloadAll, Runnable flushEdits) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.relaunch = relaunch;
        this.reloadAll = reloadAll;
        this.flushEdits = flushEdits;
        folders.add(new Folder("app.settings.folder.hosts", List.of(
                new Page("ua", "app.settings.ua.presets.title", () -> presetsPage(true)),
                new Page("url", "app.settings.url.presets.title", () -> presetsPage(false)),
                new Page("fwd", "app.settings.fwdproxy.title", this::fwdProxyPage))));
        folders.add(new Folder("app.settings.folder.proxy", List.of(
                new Page("ca", "app.settings.ca.title", this::caPage),
                new Page("server", "app.settings.server.title", this::serverPage))));
        folders.add(new Folder("app.settings.folder.general", List.of(
                new Page("lang", "app.settings.lang.title", this::langPage),
                new Page("data", "app.settings.data.title", this::dataPage),
                new Page("about", "app.settings.about.title", this::aboutPage),
                new Page("licenses", "licenses.section.opensource", this::licensesPage))));

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(Color.WHITE);
        list.setBorder(new EmptyBorder(8, 8, 8, 8));
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(Color.WHITE);
        sidebar.add(Ui.header(Ui.caps(ctx.m("nav.settings"))), BorderLayout.NORTH);
        JPanel listWrap = new JPanel(new BorderLayout());
        listWrap.setBackground(Color.WHITE);
        listWrap.add(list, BorderLayout.NORTH);
        sidebar.add(Ui.plainScroll(listWrap), BorderLayout.CENTER);
        JPanel meta = Ui.meta();
        meta.add(Ui.metaText("oeHubApp " + OeHubApp.version()), BorderLayout.WEST);
        sidebar.add(meta, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Color.WHITE);
        right.add(Ui.header(title), BorderLayout.NORTH);
        cards.setBackground(Color.WHITE);
        right.add(cards, BorderLayout.CENTER);
        JPanel rightMeta = Ui.meta();
        // A page's save status (the preset editors), at the right end like the oeHosts/oeProxy date bar.
        metaStatus.setOpaque(false);
        metaStatus.add(new JLabel(" "), "");
        rightMeta.add(metaStatus, BorderLayout.EAST);
        right.add(rightMeta, BorderLayout.SOUTH);

        add(Ui.sidebarSplit(ctx, sidebar, right), BorderLayout.CENTER);
        // -Doehubapp.settings.page=<id> opens another page first (screenshots of the other pages).
        select(System.getProperty("oehubapp.settings.page", "ua"));
    }

    // ── Left list ───────────────────────────────────────────────

    private void select(String id) {
        activeId = id;
        Page page = folders.stream().flatMap(f -> f.pages().stream()).filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        title.setText(ctx.m(page.titleKey()));
        if (!built.containsKey(id)) {
            JComponent c = page.build().get();
            built.put(id, c);
            cards.add(c, id);
        }
        ((CardLayout) cards.getLayout()).show(cards, id);
        ((CardLayout) metaStatus.getLayout()).show(metaStatus, metaPages.contains(id) ? id : "");
        renderList();
    }

    private void renderList() {
        list.removeAll();
        for (Folder f : folders) {
            boolean closed = collapsed.contains(f.titleKey());
            list.add(row(Ui.icon(closed ? "folder" : "folder-open", 16, new Color(0x5e6158)), ctx.m(f.titleKey()), false, 0, () -> {
                if (!collapsed.remove(f.titleKey())) collapsed.add(f.titleKey());
                renderList();
            }));
            if (closed) continue;
            for (Page p : f.pages()) {
                boolean active = p.id().equals(activeId);
                list.add(row(Ui.icon("file", 15, new Color(0x8a8d83)), ctx.m(p.titleKey()), active, 22, () -> select(p.id())));
            }
        }
        list.revalidate();
        list.repaint();
    }

    /** A 36px rounded row like the oeHosts list ({@code SidebarTree.Row}), indented for pages. */
    private JComponent row(Icon icon, String text, boolean active, int indent, Runnable onClick) {
        JPanel row = new JPanel(new BorderLayout(8, 0)) {
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
                    public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e)) onClick.run();
                    }
                });
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, 36);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(super.getPreferredSize().width, 36);
            }

            @Override
            protected void paintComponent(Graphics g) {
                if (active || hover) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(active ? Ui.BC100 : Ui.HOVER);
                    g2.fillRoundRect(indent, 0, getWidth() - indent, getHeight(), 16, 16);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(0, 8 + indent, 0, 8));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JLabel name = new JLabel(text, icon, SwingConstants.LEFT);
        name.setIconTextGap(8);
        name.setFont(Ui.font(active ? Font.BOLD : Font.PLAIN, 13f));
        name.setForeground(active ? Ui.TEXT_STRONG : Ui.TEXT);
        row.add(name, BorderLayout.CENTER);
        return row;
    }

    // ── Page building blocks ────────────────────────────────────

    /** A page body: content stacked from the top with the usual padding, scrollable. */
    private static JComponent page(JComponent... parts) {
        // Tracks the viewport width so descriptions wrap and long paths are cut instead of
        // pushing the page sideways.
        class Col extends JPanel implements Scrollable {
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
        JPanel col = new Col();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(Color.WHITE);
        col.setBorder(new EmptyBorder(18, 22, 18, 22));
        for (int i = 0; i < parts.length; i++) {
            parts[i].setAlignmentX(LEFT_ALIGNMENT);
            if (i > 0) col.add(Box.createVerticalStrut(14));
            col.add(parts[i]);
        }
        JScrollPane s = Ui.plainScroll(col);
        s.getViewport().setBackground(Color.WHITE);
        return s;
    }

    /** Gray explanatory text that wraps to the page width. */
    private static JComponent desc(String text) {
        JTextArea a = Ui.copyable(new JTextArea(text));
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setBorder(null);
        a.setFont(Ui.font(Font.PLAIN, 12.5f));
        a.setForeground(Ui.MUTED);
        return a;
    }

    private static JLabel status() {
        JLabel l = new JLabel(" ");
        l.setFont(Ui.font(Font.PLAIN, 12f));
        return l;
    }

    /** Shows {@code text} on {@code l} for a moment (an error a little longer), then clears it. */
    private static void flash(JLabel l, String text, boolean error) {
        l.setForeground(error ? Ui.DANGER : Ui.MUTED);
        l.setText(text);
        if (l.getClientProperty("flash") instanceof Timer old) old.stop();
        Timer t = new Timer(error ? 4000 : 2000, e -> l.setText(" "));
        t.setRepeats(false);
        t.start();
        l.putClientProperty("flash", t);
    }

    private static void ok(JLabel l, String text) {
        flash(l, text, false);
    }

    private static void error(JLabel l, String text) {
        flash(l, text, true);
    }

    private static JPanel buttons(JComponent... bs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        for (int i = 0; i < bs.length; i++) {
            p.add(bs[i]);
            if (i == bs.length - 1) break;
            // The gap follows its button and hides with it, so a hidden button leaves no hole.
            Component gap = Box.createHorizontalStrut(6);
            gap.setVisible(bs[i].isVisible());
            bs[i].addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentShown(java.awt.event.ComponentEvent e) {
                    gap.setVisible(true);
                }

                @Override
                public void componentHidden(java.awt.event.ComponentEvent e) {
                    gap.setVisible(false);
                }
            });
            p.add(gap);
        }
        return p;
    }

    /** Label / value rows (the CA details, the proxy status). */
    private static JPanel table(String[][] rows, JTextField[] values) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.BASELINE_LEADING;
        c.insets = new Insets(3, 0, 3, 16);
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
            // Selectable so a value (a path, the SHA-256) can be copied. A long one scrolls inside its
            // row when the page is narrow; the tooltip has it all.
            values[i] = Ui.copyable(new JTextField(rows[i][1]) {
                @Override
                public String getToolTipText() {
                    return getText();
                }
            });
            ToolTipManager.sharedInstance().registerComponent(values[i]);
            values[i].setMinimumSize(new Dimension(40, 18));
            values[i].setFont(Ui.mono(12f));
            values[i].setForeground(Ui.TEXT);
            p.add(values[i], c);
            c.fill = GridBagConstraints.NONE;
        }
        return p;
    }

    // ── oeHosts pages ───────────────────────────────────────────

    private JComponent presetsPage(boolean ua) {
        CodeEditor editor = new CodeEditor(null);
        editor.presetHighlighting();
        String initial = ctx.read(() -> {
            Store.Settings st = ctx.rawStore().data.settings;
            String typed = ua ? st.uaPresetsText : st.urlPresetsText;
            String text = typed != null ? typed : presetsText(ua ? st.uaPresets : st.urlPresets);
            // An empty editor starts with the line format as a comment.
            return text.isBlank() ? (ua ? "# {name} | {user-agent}\n" : "# {name} | {url}\n") : text;
        });
        editor.setText(initial);
        JComponent box = framed(editor.scroll);
        box.setPreferredSize(new Dimension(600, 320));
        JLabel st = new JLabel(" ");
        st.setFont(Ui.font(Font.PLAIN, 12f));
        String pageId = ua ? "ua" : "url";
        metaStatus.add(st, pageId);
        metaPages.add(pageId);
        // Saved like the oeHosts/oeProxy editors: 2s after the last edit, or at once on Ctrl+S.
        Autosave autosave = new Autosave(ctx, editor, st, (id, text) -> {
            // Malformed lines (shown red) are kept in the text but left out of the list.
            List<Store.Preset> list = SettingsDialog.parsePresets(text);
            Boolean done = ctx.store(s -> {
                if (ua) {
                    s.data.settings.uaPresets = list;
                    s.data.settings.uaPresetsText = text;
                } else {
                    s.data.settings.urlPresets = list;
                    s.data.settings.urlPresetsText = text;
                }
                s.save();
                return Boolean.TRUE;
            });
            if (done == null) return Autosave.Result.retry(ctx.m("status.save.failed"));
            ctx.fireSettingsChanged();
            return Autosave.Result.OK;
        });
        autosave.activate(pageId, initial);
        autosaves.add(autosave);
        editor.onEdit(() -> autosave.changed(editor.getText()));
        // Not page(): the editor fills the height down to the bottom bar instead of scrolling with the page.
        JPanel page = new JPanel(new BorderLayout(0, 14));
        page.setBackground(Color.WHITE);
        page.setBorder(new EmptyBorder(18, 22, 18, 22));
        page.add(desc(ctx.m(ua ? "app.settings.ua.presets.desc" : "app.settings.url.presets.desc")), BorderLayout.NORTH);
        page.add(box, BorderLayout.CENTER);
        autosave.bindSaveKey(page);
        return page;
    }

    private JComponent fwdProxyPage() {
        JTextField port = new JTextField(String.valueOf(ctx.read(() -> ctx.rawStore().data.settings.fwdproxyPort)), 8);
        port.setFont(Ui.font(Font.PLAIN, 13f));
        port.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878; margin:4,8,4,8");
        port.setMaximumSize(new Dimension(140, 34));
        JLabel st = status();
        JButton save = Ui.primary(ctx.m("btn.save"), Ui.icon("save-plus", 13, new Color(0x2d4a14)), () -> {
            int p;
            try {
                p = Integer.parseInt(port.getText().strip());
            } catch (NumberFormatException e) {
                p = -1;
            }
            if (p < 1 || p > 65535) {
                flash(st, ctx.m("app.settings.error.port"), true);
                return;
            }
            int fp = p;
            ctx.storeRun(s -> {
                s.data.settings.fwdproxyPort = fp;
                s.save();
            });
            ctx.fireSettingsChanged();
            flash(st, ctx.m("status.saved"), false);
        });
        save.setIconTextGap(5);
        // The result shows in the bottom bar, like the editors' save status.
        metaStatus.add(st, "fwd");
        metaPages.add("fwd");
        return withActions(page(desc(ctx.m("app.settings.fwdproxy.desc")), buttons(port)), save);
    }

    /** {@code page} with {@code actions} at its bottom right, just above the bottom bar. */
    private static JComponent withActions(JComponent page, JComponent... actions) {
        return withActionRows(page, actions);
    }

    /** {@code page} with rows of buttons, each right-aligned, at its bottom right above the bottom bar. */
    private static JComponent withActionRows(JComponent page, JComponent[]... rows) {
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setBackground(Color.WHITE);
        south.setBorder(new EmptyBorder(10, 22, 14, 22));
        for (int r = 0; r < rows.length; r++) {
            if (r > 0) south.add(Box.createVerticalStrut(8));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            row.setOpaque(false);
            for (int i = 0; i < rows[r].length; i++) {
                if (i > 0) row.add(Box.createHorizontalStrut(8));
                row.add(rows[r][i]);
            }
            south.add(row);
        }
        JPanel p = new JPanel(new BorderLayout());
        p.add(page, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }


    private static String presetsText(List<Store.Preset> list) {
        StringBuilder b = new StringBuilder();
        for (Store.Preset p : list) b.append(p.name).append(" | ").append(p.value).append('\n');
        return b.toString();
    }

    private static JComponent framed(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new LineBorder(Ui.BORDER, 1, true));
        p.add(c);
        return p;
    }

    // ── oeProxy pages ───────────────────────────────────────────

    private JComponent caPage() {
        JTextField[] v = new JTextField[6];
        JPanel info = table(new String[][]{
                {ctx.m("app.settings.ca.subject"), "-"},
                {ctx.m("app.settings.ca.valid"), "-"},
                {ctx.m("app.settings.ca.sha256"), "-"},
                {ctx.m("app.settings.ca.trust"), "-"},
                {ctx.m("app.settings.ca.file"), "-"},
                {ctx.m("app.settings.ca.key"), "-"}}, v);
        JLabel st = status();
        // Registering is offered only while the OS does not trust the CA yet.
        JButton[] register = new JButton[2];
        Runnable refresh = () -> {
            boolean registered = Boolean.TRUE.equals(refreshCa(v));
            if (register[0] == null) return;
            register[0].setVisible(WIN && !registered);
            register[1].setVisible(!registered);
        };
        JButton install = Ui.primary(ctx.m("app.settings.ca.install"), Ui.icon("shield-check", 13, new Color(0x2d4a14)), () -> installCa(st, refresh));
        install.setIconTextGap(5);
        install.setVisible(WIN);
        register[0] = install;
        JButton folder = Ui.secondary(ctx.m("app.settings.open.folder"), "folder-open", () -> openFolder(ProxyRuntime.caDir()));
        JButton export = Ui.secondary(ctx.m("app.settings.ca.export"), "file-down", this::exportCa);
        JButton copyCmd = Ui.secondary(ctx.m("app.settings.ca.copy.cmd"), "copy", () -> {
            OeDialog.copy(installCommand());
            ctx.toast(ctx.m("app.settings.copied"));
        });
        JButton regen = Ui.secondary(ctx.m("app.settings.ca.regenerate"), "refresh-cw", () -> regenerateCa(st, refresh));
        JButton imp = Ui.secondary(ctx.m("app.settings.ca.import"), "key-round", () -> importCa(st, refresh));
        register[1] = copyCmd;
        refresh.run();
        // Messages go to the bottom bar; the buttons sit at the bottom right, the main action last.
        metaStatus.add(st, "ca");
        metaPages.add("ca");
        // Actions on the current certificate sit under its rows; replacing it sits at the bottom right.
        return withActions(page(desc(ctx.m("app.settings.ca.desc")), info, buttons(install, copyCmd, export, folder)), imp, regen);
    }

    /** Fills the CA rows; returns whether the OS trusts the CA (null: no CA, or cannot be told). */
    private Boolean refreshCa(JTextField[] v) {
        Path cert = ProxyRuntime.caCert();
        Boolean trusted = null;
        if (cert == null || !Files.exists(cert)) {
            v[4].setText(ctx.m("app.settings.ca.missing"));
            return null;
        }
        v[4].setText(cert.toAbsolutePath().toString());
        try (InputStream in = Files.newInputStream(cert)) {
            X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd");
            v[0].setText(x.getSubjectX500Principal().getName());
            v[1].setText(d.format(x.getNotBefore()) + " ~ " + d.format(x.getNotAfter()));
            v[2].setText(HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(x.getEncoded())));
            trusted = trusted(x);
            v[3].setText(trusted == null ? ctx.m("app.settings.ca.trust.unknown")
                    : ctx.m(trusted ? "app.settings.ca.trust.yes" : "app.settings.ca.trust.no"));
            v[3].setForeground(trusted == null ? Ui.MUTED : trusted ? Ui.BC700 : Ui.DANGER);
            v[3].setFont(Ui.font(Font.BOLD, 12.5f));
        } catch (Exception e) {
            v[0].setText(e.getMessage());
        }
        v[5].setText(ProxyRuntime.caDir().resolve("ca.pfx").toAbsolutePath().toString());
        return trusted;
    }

    /** Whether the OS trusts the CA (Windows certificate store only); null when it cannot be told. */
    private static Boolean trusted(X509Certificate x) {
        if (!WIN) return null;
        try {
            KeyStore ks = KeyStore.getInstance("Windows-ROOT");
            ks.load(null, null);
            return ks.getCertificateAlias(x) != null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Adds the CA to the current user's Windows trust store ({@code certutil -user}): no
     * administrator rights needed; Windows asks the user to confirm.
     */
    private void installCa(JLabel st, Runnable refresh) {
        Path cert = ProxyRuntime.caCert();
        if (cert == null) return;
        st.setForeground(Ui.MUTED);
        st.setText(ctx.m("app.settings.ca.installing"));
        new Thread(() -> {
            int code;
            try {
                Process p = new ProcessBuilder("certutil", "-user", "-addstore", "Root", cert.toAbsolutePath().toString())
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                code = p.waitFor();
            } catch (Exception e) {
                code = -1;
            }
            int c = code;
            SwingUtilities.invokeLater(() -> {
                if (c == 0) ok(st, ctx.m("app.settings.ca.installed"));
                else error(st, ctx.m("app.settings.ca.install.failed"));
                refresh.run();
            });
        }, "ca-install").start();
    }

    private String installCommand() {
        String path = ProxyRuntime.caCert() == null ? "ca.cer" : ProxyRuntime.caCert().toAbsolutePath().toString();
        return WIN ? "certutil -addstore -f Root \"" + path + "\""
                : "sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain \"" + path + "\"";
    }

    /**
     * Brings in an existing CA (e.g. oeHub's, already trusted by the browser): a certificate file
     * and its private key (PEM PKCS#8, or a PKCS#12 keystore that may hold the certificate too).
     */
    private void importCa(JLabel st, Runnable refresh) {
        JTextField cert = fileField(), key = fileField();
        JPasswordField pwd = new JPasswordField();
        pwd.setFont(Ui.font(Font.PLAIN, 13f));
        pwd.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878; margin:4,8,4,8");
        JLabel error = new JLabel(" ");
        error.setForeground(Ui.DANGER);
        JComponent note = OeDialog.note(ctx.plain("app.settings.ca.import.desc"));
        Dialogs.fixWidth(note, 520);
        OeDialog.open(ctx, ctx.m("app.settings.ca.import"), d -> {
            d.body(OeDialog.stack(8, note,
                    label(ctx.m("app.settings.ca.import.cert")), fileRow(cert, "cer", "crt", "pem"),
                    label(ctx.m("app.settings.ca.import.key")), fileRow(key, "pfx", "p12", "key", "pem"),
                    label(ctx.m("app.settings.ca.import.password")), pwd, error));
            d.button(ctx.m("btn.cancel"), false, d::dispose);
            d.button(ctx.m("btn.import"), true, () -> {
                if (key.getText().isEmpty()) {
                    error.setText(ctx.m("app.settings.ca.import.error.no.key"));
                    return;
                }
                if (!OeDialog.confirm(ctx, ctx.m("app.settings.ca.import"), ctx.m("app.settings.ca.import.confirm"))) return;
                try {
                    ProxyRuntime.importCa(cert.getText().isEmpty() ? null : Path.of(cert.getText()),
                            Path.of(key.getText()), new String(pwd.getPassword()));
                } catch (ProxyRuntime.ImportException e) {
                    error.setText(ctx.m(e.code));
                    return;
                }
                d.dispose();
                ProxyRuntime.restart();
                refresh.run();
                ok(st, ctx.m("app.settings.ca.imported"));
            });
        });
    }

    private static JTextField fileField() {
        JTextField f = new JTextField();
        f.setEditable(false);
        f.setFont(Ui.font(Font.PLAIN, 13f));
        f.putClientProperty("FlatLaf.style", "arc:6; margin:4,8,4,8");
        return f;
    }

    private JComponent fileRow(JTextField f, String... ext) {
        JButton browse = new JButton("...");
        browse.setFocusable(false);
        browse.addActionListener(e -> {
            // Only the given extensions: no "All files" choice, and a typed name of another type is refused.
            var filter = new javax.swing.filechooser.FileNameExtensionFilter(
                    String.join(", ", java.util.Arrays.stream(ext).map(x -> "*." + x).toList()), ext);
            // Opens where the last certificate or key was picked (shared by both rows, kept across restarts).
            String lastDir = ctx.read(() -> ctx.rawStore().data.settings.caImportDir);
            java.io.File start = lastDir == null ? null : new java.io.File(lastDir);
            JFileChooser fc = new JFileChooser(start != null && start.isDirectory() ? start : null);
            fc.setAcceptAllFileFilterUsed(false);
            fc.setFileFilter(filter);
            if (fc.showOpenDialog(ctx.frame()) != JFileChooser.APPROVE_OPTION) return;
            java.io.File file = fc.getSelectedFile();
            java.io.File dir = fc.getCurrentDirectory();
            if (dir != null && !dir.getAbsolutePath().equals(lastDir)) {
                ctx.storeRun(s -> {
                    s.data.settings.caImportDir = dir.getAbsolutePath();
                    s.save();
                });
            }
            if (file.isDirectory() || !filter.accept(file)) {
                ctx.toast(ctx.m("app.settings.ca.import.error.type", filter.getDescription()));
                return;
            }
            f.setText(file.getAbsolutePath());
        });
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(f);
        row.add(browse, BorderLayout.EAST);
        return row;
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.font(Font.BOLD, 12f));
        l.setForeground(Ui.MUTED);
        return l;
    }

    /** Asks for the new CA's name (CN), then replaces the CA with a new one and restarts the proxy. */
    private void regenerateCa(JLabel st, Runnable refresh) {
        JTextField cn = new JTextField(java.util.Objects.requireNonNullElse(currentCaCn(), ProxyRuntime.CA_NAME));
        cn.setFont(Ui.font(Font.PLAIN, 13f));
        cn.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878; margin:4,8,4,8");
        JLabel error = new JLabel(" ");
        error.setForeground(Ui.DANGER);
        JComponent note = OeDialog.note(ctx.plain("app.settings.ca.regenerate.confirm"));
        Dialogs.fixWidth(note, 440);
        OeDialog.open(ctx, ctx.m("app.settings.ca.regenerate"), d -> {
            d.body(OeDialog.stack(8, note, label(ctx.m("app.settings.ca.cn", ProxyRuntime.CA_NAME_MAX)), cn, error));
            d.button(ctx.m("btn.cancel"), false, d::dispose);
            JButton create = d.button(ctx.m("app.settings.ca.regenerate"), true, () -> {
                String name = cn.getText().strip();
                String err = ProxyRuntime.caNameError(name);
                if (err != null) {
                    error.setText(ctx.m(err, ProxyRuntime.CA_NAME_MAX));
                    return;
                }
                d.dispose();
                try {
                    ProxyRuntime.regenerateCa(name);
                    ProxyRuntime.restart();
                    refresh.run();
                    ok(st, ctx.m("app.settings.ca.regenerated"));
                } catch (Exception e) {
                    error(st, e.getMessage());
                }
            });
            // Required field: nothing to submit while it is empty; the message is cleared on edit.
            Runnable sync = () -> {
                create.setEnabled(!cn.getText().isBlank());
                error.setText(" ");
            };
            cn.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    sync.run();
                }

                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    sync.run();
                }

                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    sync.run();
                }
            });
            sync.run();
            cn.selectAll();
        });
    }

    /** The CN of the CA in use, or null when there is none or it cannot be read. */
    private static String currentCaCn() {
        Path cert = ProxyRuntime.caCert();
        if (cert == null || !Files.exists(cert)) return null;
        try (InputStream in = Files.newInputStream(cert)) {
            X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            for (javax.naming.ldap.Rdn r : new javax.naming.ldap.LdapName(x.getSubjectX500Principal().getName()).getRdns()) {
                if (r.getType().equalsIgnoreCase("CN")) return r.getValue().toString();
            }
        } catch (Exception e) {
            // no name to offer
        }
        return null;
    }

    private void exportCa() {
        Path cert = ProxyRuntime.caCert();
        if (cert == null) return;
        JFileChooser fc = new JFileChooser();
        // Named after the CA (its CN), made safe for the file system.
        fc.setSelectedFile(new java.io.File(ProxyRuntime.caFileName(currentCaCn(), "oeHubApp-ca") + ".cer"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.copy(cert, fc.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            ctx.toast(ctx.m("app.settings.ca.exported"));
        } catch (Exception e) {
            ctx.toast(ctx.m("status.save.failed"));
        }
    }

    private JComponent serverPage() {
        JTextField[] v = new JTextField[3];
        JPanel info = table(new String[][]{
                {ctx.m("app.settings.server.state"), "-"},
                {ctx.m("app.settings.server.port"), String.valueOf(ProxyRuntime.port())},
                {ctx.m("app.settings.server.error"), "-"}}, v);
        // The status is a word in the UI language: Korean in the mono font falls back to a taller
        // glyph that the row cuts off at the top.
        v[0].setFont(Ui.font(Font.BOLD, 12.5f));
        v[2].setFont(Ui.font(Font.PLAIN, 12.5f));
        Runnable refresh = () -> {
            ProxyRuntime.State s = ProxyRuntime.state();
            v[0].setText(ctx.m(switch (s) {
                case STARTING -> "app.proxy.status.starting";
                case RUNNING -> "app.settings.server.running";
                case FAILED -> "app.settings.server.failed";
            }));
            v[0].setForeground(s == ProxyRuntime.State.RUNNING ? Ui.BC700 : s == ProxyRuntime.State.FAILED ? Ui.DANGER : Ui.MUTED);
            v[2].setText(s == ProxyRuntime.State.FAILED && ProxyRuntime.error() != null ? ProxyRuntime.error() : "-");
        };
        ProxyRuntime.onChange(() -> SwingUtilities.invokeLater(refresh));
        refresh.run();
        JButton restart = Ui.primary(ctx.m("app.settings.server.restart"), Ui.icon("refresh-cw", 13, new Color(0x2d4a14)), ProxyRuntime::restart);
        restart.setIconTextGap(5);
        return withActions(page(desc(ctx.m("app.settings.server.desc")), info), restart);
    }

    // ── General pages ───────────────────────────────────────────

    private JComponent langPage() {
        ButtonGroup g = new ButtonGroup();
        JPanel radios = new JPanel();
        radios.setLayout(new BoxLayout(radios, BoxLayout.Y_AXIS));
        radios.setOpaque(false);
        for (String[] lang : new String[][]{{"en", "lang.english"}, {"ko", "lang.korean"}}) {
            JRadioButton r = new JRadioButton(ctx.m(lang[1]), lang[0].equals(ctx.lang()));
            r.setOpaque(false);
            r.setFont(Ui.font(Font.PLAIN, 13f));
            r.addActionListener(a -> {
                if (lang[0].equals(ctx.lang())) return;
                ctx.storeRun(s -> {
                    s.data.settings.lang = lang[0];
                    s.save();
                });
                relaunch.run();
            });
            g.add(r);
            radios.add(r);
        }
        return page(desc(ctx.m("app.settings.lang.desc")), radios);
    }

    private JComponent dataPage() {
        Path file = ctx.rawStore().path().toAbsolutePath();
        JTextField[] v = new JTextField[1];
        JPanel info = table(new String[][]{{ctx.m("app.settings.data.file"), file.toString()}}, v);
        JButton folder = Ui.secondary(ctx.m("app.settings.open.folder"), "folder-open", () -> openFolder(file.getParent()));
        // Export / import per screen and for everything, one row each.
        JPanel transfer = new JPanel(new GridBagLayout());
        transfer.setOpaque(false);
        String[][] rows = {{"app.data.hosts", "hosts"}, {"app.data.vhosts", "vhosts"}, {"app.data.all", "all"}};
        GridBagConstraints c = new GridBagConstraints();
        for (int i = 0; i < rows.length; i++) {
            String what = rows[i][1];
            c.gridy = i;
            c.gridx = 0;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(i == 0 ? 0 : 6, 0, 0, 16);
            JLabel name = new JLabel(ctx.m(rows[i][0]));
            name.setFont(Ui.font(Font.BOLD, 12.5f));
            name.setForeground(Ui.MUTED);
            transfer.add(name, c);
            c.gridx = 1;
            JButton ex = Ui.secondary(ctx.m("app.data.export"), "file-down", () -> exportData(what));
            JButton in = Ui.secondary(ctx.m("app.data.import"), "circle-fading-plus", () -> importData(what));
            transfer.add(buttons(ex, in), c);
        }
        // Takes the spare width, so the rows stay at the left edge.
        c.gridx = 2;
        c.gridy = 0;
        c.weightx = 1;
        transfer.add(Box.createHorizontalGlue(), c);
        // Export / import first, then the data file itself, with a line between the two blocks.
        return page(transfer, separator(), desc(ctx.m("app.settings.data.desc")), info, buttons(folder));
    }

    /** A full-width 1px line with some air around it, between two blocks of a page. */
    private static JComponent separator() {
        JPanel line = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Ui.BORDER);
                g.fillRect(0, getHeight() / 2, getWidth(), 1);
            }
        };
        line.setOpaque(false);
        line.setPreferredSize(new Dimension(10, 13));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 13));
        return line;
    }

    /** Exports "hosts" / "vhosts" (a JSON file oeHub can read too) or "all" (a ZIP with the CA). */
    private void exportData(String what) {
        flushEdits.run();
        flush();
        boolean all = what.equals("all");
        if (all && !OeDialog.confirm(ctx, ctx.m("app.data.all.export.title"), ctx.m("app.data.all.export.confirm"))) return;
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));
        String fileName = switch (what) {
            case "hosts" -> "oehosts-" + ts + ".json";
            case "vhosts" -> "oeproxy-" + ts + ".json";
            default -> "oehubapp-backup-" + ts + ".zip";
        };
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(fileName));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        try {
            byte[] json = ctx.read(() -> {
                try {
                    Store s = ctx.rawStore();
                    return Store.MAPPER.writeValueAsBytes(all ? s.data : s.exportKind(
                            what.equals("hosts") ? Store.Kind.HOSTS : Store.Kind.VHOSTS));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            if (all) tricatch.oehubapp.Backup.write(json, ProxyRuntime.caDir(), out);
            else Files.write(out, json);
            ctx.toast(ctx.m("app.data.exported"));
        } catch (Exception e) {
            ctx.toast(ctx.m("app.data.export.failed"));
        }
    }

    private void importData(String what) {
        flushEdits.run();
        flush();
        switch (what) {
            case "hosts" -> SettingsDialog.importJson(ctx, ctx.m("app.data.hosts.import.title"),
                    ctx.plain("app.data.hosts.import.desc"), Store.Kind.HOSTS, reloadAll);
            case "vhosts" -> SettingsDialog.importJson(ctx, ctx.m("app.data.vhosts.import.title"),
                    ctx.plain("app.data.vhosts.import.desc"), Store.Kind.VHOSTS, reloadAll);
            default -> importAll();
        }
    }

    /**
     * Restores a full backup: every profile, vhost, option and setting, and the CA when the backup
     * has one. The CA pair is checked before anything changes; the window is rebuilt afterwards
     * (the language may differ).
     */
    private void importAll() {
        JFileChooser fc = new JFileChooser();
        fc.setAcceptAllFileFilterUsed(false);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("*.zip", "zip"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        tricatch.oehubapp.Backup.Contents b;
        try {
            b = tricatch.oehubapp.Backup.read(fc.getSelectedFile().toPath());
        } catch (Exception e) {
            OeDialog.info(ctx, ctx.m("app.data.all.import.title"), ctx.m("app.data.all.import.error.format"));
            return;
        }
        if (!OeDialog.confirm(ctx, ctx.m("app.data.all.import.title"), ctx.m("app.data.all.import.confirm"))) return;
        if (b.cert() != null) {
            Path dir = null;
            try {
                dir = Files.createTempDirectory("oehubapp-restore");
                Path cer = Files.write(dir.resolve("ca.cer"), b.cert()), key = Files.write(dir.resolve("ca.pfx"), b.key());
                ProxyRuntime.importCa(cer, key, "");
            } catch (ProxyRuntime.ImportException e) {
                OeDialog.info(ctx, ctx.m("app.data.all.import.title"), ctx.m(e.code));
                return;
            } catch (Exception e) {
                OeDialog.info(ctx, ctx.m("app.data.all.import.title"), ctx.m("app.import.failed"));
                return;
            } finally {
                deleteTree(dir);
            }
        }
        if (ctx.store(s -> {
            s.replaceAll(b.data());
            return Boolean.TRUE;
        }) == null) return;
        ProxyRuntime.restart();
        OeDialog.info(ctx, ctx.m("app.data.all.import.title"), ctx.m("app.data.all.imported"));
        relaunch.run();
    }

    private static void deleteTree(Path dir) {
        if (dir == null) return;
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) Files.deleteIfExists(p);
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
            // temp files: left to the OS
        }
    }

    private JComponent aboutPage() {
        JTextField[] v = new JTextField[4];
        String proxyVer = tricatch.oehubapp.BuildInfo.dependency("io.github.tricatch:oe-proxy");
        JPanel info = table(new String[][]{
                {"oeHubApp", OeHubApp.version()},
                {"oe-proxy", proxyVer == null ? "-" : proxyVer},
                {"Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"},
                {"OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")"}}, v);
        return page(info);
    }

    /** The bundled open-source libraries with their versions and licenses. */
    private JComponent licensesPage() {
        return page(licenses());
    }

    /** A bundled library: name, homepage, Maven coordinate (null: not a Maven library), license. */
    private record Lib(String name, String url, String coordinate, String license, Color bg, Color fg) {}

    // Badge colours of oeHub's info-page.css (.badge-apache / -mit / -bsd).
    private static final Color[] APACHE = {new Color(0xdce8ff), new Color(0x1a3a7a)};
    private static final Color[] MIT = {new Color(0xdff2b8), new Color(0x3a6b1a)};
    private static final Color[] BSD = {new Color(0xe8e4ff), new Color(0x3a1a7a)};

    private static Lib lib(String name, String url, String coordinate, String license, Color[] badge) {
        return new Lib(name, url, coordinate, license, badge[0], badge[1]);
    }

    /**
     * Every library the jar bundles (the runtime classpath, transitive ones included) and the Lucide
     * icons, in name order like oeHub's licenses page. Versions come from the build
     * ({@link tricatch.oehubapp.BuildInfo}), so they are the ones actually shipped.
     */
    private static final List<Lib> LIBS = List.of(
            lib("Ant-Style Path Matcher", "https://github.com/azagniotov/ant-style-path-matcher", "io.github.azagniotov:ant-style-path-matcher", "Apache 2.0", APACHE),
            lib("Bouncy Castle", "https://www.bouncycastle.org", "org.bouncycastle:bcprov-jdk18on", "MIT", MIT),
            lib("Caffeine", "https://github.com/ben-manes/caffeine", "com.github.ben-manes.caffeine:caffeine", "Apache 2.0", APACHE),
            lib("Checker Framework Qualifiers", "https://checkerframework.org", "org.checkerframework:checker-qual", "MIT", MIT),
            lib("Error Prone Annotations", "https://errorprone.info", "com.google.errorprone:error_prone_annotations", "Apache 2.0", APACHE),
            lib("FlatLaf", "https://www.formdev.com/flatlaf/", "com.formdev:flatlaf", "Apache 2.0", APACHE),
            lib("gotpache-keytool", "https://github.com/tricatch/gotpache-keytool", "io.github.tricatch:gotpache-keytool", "MIT", MIT),
            lib("Jackson", "https://github.com/FasterXML/jackson", "com.fasterxml.jackson.core:jackson-databind", "Apache 2.0", APACHE),
            lib("JSVG", "https://github.com/weisJ/jsvg", "com.github.weisj:jsvg", "MIT", MIT),
            lib("Lucide", "https://lucide.dev", null, "ISC", MIT),
            lib("oe-proxy", "https://github.com/tricatch/oeProxy", "io.github.tricatch:oe-proxy", "MIT", MIT),
            lib("RSyntaxTextArea", "https://github.com/bobbylight/RSyntaxTextArea", "com.fifesoft:rsyntaxtextarea", "BSD 3-Clause", BSD),
            lib("SLF4J", "https://www.slf4j.org", "org.slf4j:slf4j-api", "MIT", MIT),
            lib("SnakeYAML", "https://bitbucket.org/snakeyaml/snakeyaml", "org.yaml:snakeyaml", "Apache 2.0", APACHE));

    /** Name (a link to the homepage) / version / license badge rows. */
    private JComponent licenses() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        // Baseline, so the version (a text field) lines up with the name and badge text.
        c.anchor = GridBagConstraints.BASELINE_LEADING;
        for (int i = 0; i < LIBS.size(); i++) {
            Lib l = LIBS.get(i);
            c.gridy = i;
            c.insets = new Insets(4, 0, 4, 24);
            c.gridx = 0;
            p.add(link(l.name(), l.url()), c);
            c.gridx = 1;
            String ver = l.coordinate() == null ? null : tricatch.oehubapp.BuildInfo.dependency(l.coordinate());
            JTextField version = Ui.copyable(new JTextField(ver == null ? "-" : ver));
            version.setFont(Ui.mono(12f));
            version.setForeground(Ui.TEXT);
            p.add(version, c);
            c.gridx = 2;
            p.add(badge(l.license(), l.bg(), l.fg()), c);
        }
        c.gridx = 3;
        c.gridy = 0;
        c.weightx = 1;
        p.add(Box.createHorizontalGlue(), c);
        return p;
    }

    /** Text that opens {@code url} in the browser; underlined and green on hover ({@code .lib-link}). */
    private JComponent link(String text, String url) {
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
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                hover[0] = true;
                l.setForeground(Ui.BC700);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hover[0] = false;
                l.setForeground(Ui.TEXT);
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(java.net.URI.create(url));
                } catch (Exception ex) {
                    OeDialog.copy(url);
                    ctx.toast(ctx.m("app.settings.copied"));
                }
            }
        });
        return l;
    }

    /** A rounded pill with the license name ({@code .license-badge}). */
    private static JComponent badge(String text, Color bg, Color fg) {
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
        b.setBorder(new EmptyBorder(1, 8, 1, 8));
        return b;
    }

    private void openFolder(Path dir) {
        try {
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            ctx.toast(dir.toString());
        }
    }
}
