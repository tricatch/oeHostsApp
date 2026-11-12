package tricatch.oehubapp.ui;

import tricatch.oehubapp.HostsAgent;
import tricatch.oehubapp.OeHubApp;
import tricatch.oehubapp.ProxyRuntime;
import tricatch.oehubapp.ShareServer;
import tricatch.oehubapp.Store;
import tricatch.oehubapp.Update;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
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
        folders.add(new Folder("app.settings.folder.general", List.of(
                new Page("about", "app.settings.about.title", this::aboutPage),
                new Page("web", "app.settings.web.title", this::webPage),
                new Page("fwd", "app.settings.fwdproxy.title", this::fwdProxyPage),
                new Page("lang", "app.settings.lang.title", this::langPage),
                new Page("data", "app.settings.data.title", this::dataPage),
                new Page("licenses", "licenses.section.opensource", this::licensesPage))));
        folders.add(new Folder("app.settings.folder.hosts", List.of(
                new Page("ua", "app.settings.ua.presets.title", () -> presetsPage(true)),
                new Page("url", "app.settings.url.presets.title", () -> presetsPage(false)),
                new Page("agent", "app.settings.agent.title", this::agentPage))));
        folders.add(new Folder("app.settings.folder.proxy", List.of(
                new Page("ca", "app.settings.ca.title", this::caPage),
                new Page("mobile", "app.settings.phone.title", this::mobilePage),
                new Page("server", "app.settings.server.title", this::serverPage))));

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
        select(System.getProperty("oehubapp.settings.page", folders.get(0).pages().get(0).id()));
    }

    // ── Left list ───────────────────────────────────────────────

    /** The page shown now, to reopen it after the window is rebuilt. */
    String activePage() {
        return activeId;
    }

    void showPage(String id) {
        if (!id.equals(activeId)) select(id);
    }

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
        stack(col, parts);
        JScrollPane s = Ui.plainScroll(col);
        s.getViewport().setBackground(Color.WHITE);
        return s;
    }

    /**
     * A page body whose single part fills the whole page height (when the page is taller than the
     * part), so a divider drawn by the part reaches the bottom; scrollable like {@link #page}.
     */
    private static JComponent fullPage(JComponent body) {
        class Fill extends JPanel implements Scrollable {
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
                return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height;
            }
        }
        JPanel p = new Fill();
        p.setLayout(new BorderLayout());
        p.setBackground(Color.WHITE);
        // The same padding as page(): a divider in the body stops as far from the bottom as from the top.
        p.setBorder(new EmptyBorder(18, 22, 18, 22));
        p.add(body, BorderLayout.CENTER);
        JScrollPane s = Ui.plainScroll(p);
        s.getViewport().setBackground(Color.WHITE);
        return s;
    }

    /** Adds {@code parts} to a vertical BoxLayout panel, 14px apart; a hidden part takes its gap with it. */
    private static void stack(JPanel col, JComponent... parts) {
        for (int i = 0; i < parts.length; i++) {
            parts[i].setAlignmentX(LEFT_ALIGNMENT);
            if (i > 0) {
                // The gap above a part hides with it, so a hidden part leaves no hole.
                Component gap = Box.createVerticalStrut(14);
                gap.setVisible(parts[i].isVisible());
                parts[i].addComponentListener(new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentShown(java.awt.event.ComponentEvent e) {
                        gap.setVisible(true);
                    }

                    @Override
                    public void componentHidden(java.awt.event.ComponentEvent e) {
                        gap.setVisible(false);
                    }
                });
                col.add(gap);
            }
            col.add(parts[i]);
        }
    }

    /** A part of a page that is itself a column of parts (see {@link #stack}), kept at its natural height. */
    private static JComponent column(JComponent... parts) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        stack(col, parts);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(col, BorderLayout.NORTH);
        return top;
    }

    /** Buttons at the right end of their row, 6px apart: the action row of a page half. */
    private static JPanel rightAligned(JComponent... buttons) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        p.setOpaque(false);
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) p.add(Box.createHorizontalStrut(6));
            p.add(buttons[i]);
        }
        return p;
    }

    /** A thin horizontal rule between the sections of a page (description, settings, actions). */
    private static JComponent line() {
        JComponent l = new JComponent() {
            // Extra room above and below the 1px line, on top of the usual gap between parts.
            static final int MARGIN = 8;

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Ui.BORDER);
                g.fillRect(0, MARGIN, getWidth(), 1);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(0, MARGIN * 2 + 1);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, MARGIN * 2 + 1);
            }
        };
        return l;
    }

    /** Two equal halves side by side; the right one is set off by a line, like a side panel. */
    private static JComponent halves(JComponent left, JComponent right) {
        JPanel p = new JPanel(new GridLayout(1, 2, 24, 0));
        p.setOpaque(false);
        right.setBorder(new CompoundBorder(new MatteBorder(0, 1, 0, 0, Ui.BORDER), new EmptyBorder(0, 20, 0, 0)));
        p.add(left);
        p.add(right);
        return p;
    }

    /**
     * A read-only text area whose caret never follows text changes. A text area built with its text
     * moves the caret to the end and queues a scroll to it, which runs once the page is on screen
     * and scrolls a tall page down; so the text is set only after the caret is told not to follow.
     */
    private static <T extends javax.swing.text.JTextComponent> T still(T t, String text) {
        ((javax.swing.text.DefaultCaret) t.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        t.setText(text);
        return Ui.copyable(t);
    }

    /** Gray explanatory text that wraps to the page width. */
    private static JComponent desc(String text) {
        JTextArea a = still(new JTextArea(), text);
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
            values[i] = still(new JTextField() {
                @Override
                public String getToolTipText() {
                    return getText();
                }
            }, rows[i][1]);
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
        // Where it listens: this PC only (the default), one network card, or every interface.
        NetworkChoice bind = new NetworkChoice(ctx.read(() -> ctx.rawStore().data.settings.fwdproxyBindAddress()),
                "app.settings.fwdproxy.bind.all.warn");
        // Authentication: none (the default) or Basic (username and password) other devices must send; the
        // fields are usable only while that is chosen, and keep their text when switched off.
        boolean authOn = ctx.read(() -> ctx.rawStore().data.settings.fwdproxyAuth);
        JRadioButton authNone = new JRadioButton(ctx.m("app.settings.fwdproxy.auth.none"), !authOn);
        JRadioButton authPassword = new JRadioButton(ctx.m("app.settings.fwdproxy.auth.password"), authOn);
        ButtonGroup authGroup = new ButtonGroup();
        for (JRadioButton r : new JRadioButton[]{authNone, authPassword}) {
            r.setOpaque(false);
            r.setFocusable(false);
            r.setFont(Ui.font(Font.PLAIN, 13f));
            authGroup.add(r);
        }
        JTextField user = new JTextField(ctx.read(() -> {
            String u = ctx.rawStore().data.settings.fwdproxyUser;
            return u == null ? "" : u;
        }), 12);
        user.setFont(Ui.font(Font.PLAIN, 13f));
        user.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878; margin:4,8,4,8");
        user.putClientProperty("JTextField.placeholderText", ctx.m("app.settings.fwdproxy.auth.user"));
        user.setMaximumSize(new Dimension(180, 34));
        JPasswordField password = new JPasswordField(ctx.read(() -> {
            String pw = ctx.rawStore().data.settings.fwdproxyPassword;
            return pw == null ? "" : pw;
        }), 16);
        password.setFont(Ui.font(Font.PLAIN, 13f));
        password.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878; margin:4,8,4,8; showRevealButton:true");
        password.putClientProperty("JTextField.placeholderText", ctx.m("app.settings.fwdproxy.auth.pw"));
        password.setMaximumSize(new Dimension(220, 34));
        JComponent authNote = desc(ctx.m("app.settings.fwdproxy.auth.note"));
        // The network warning under the PC address shows for any address but 127.0.0.1, with or
        // without a password; the note under the credentials only while Basic is chosen.
        Runnable authState = () -> {
            boolean on = authPassword.isSelected();
            user.setEnabled(on);
            password.setEnabled(on);
            authNote.setVisible(on);
            boolean lan = !tricatch.oehubapp.ForwardProxy.LOOPBACK.equals(bind.selected());
            bind.warn.setVisible(lan);
        };
        authPassword.addItemListener(e -> {
            authState.run();
            if (authPassword.isSelected()) user.requestFocusInWindow();
        });
        authNone.addItemListener(e -> authState.run());
        bind.onChange = authState;
        authState.run();
        JLabel st = status();
        // Saves the port and the binding, then restarts the proxy with them; the state rows show the outcome.
        JButton restart = Ui.primary(ctx.m("app.settings.server.restart"), Ui.icon("refresh-cw", 13, new Color(0x2d4a14)), () -> {
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
            boolean auth = authPassword.isSelected();
            String id = user.getText().strip();
            String pw = new String(password.getPassword());
            // In screen order: the username before the password.
            if (auth && id.isEmpty()) {
                flash(st, ctx.m("app.settings.error.fwdproxy.user"), true);
                return;
            }
            if (auth && pw.isEmpty()) {
                flash(st, ctx.m("app.settings.error.fwdproxy.password"), true);
                return;
            }
            int fp = p;
            String to = bind.selected();
            ctx.storeRun(s -> {
                s.data.settings.fwdproxyPort = fp;
                s.data.settings.putFwdproxyBind(to);
                s.data.settings.fwdproxyAuth = auth;
                // Empty fields with authentication off keep the stored username and password for next time.
                if (!id.isEmpty()) s.data.settings.fwdproxyUser = id;
                if (!pw.isEmpty()) s.data.settings.fwdproxyPassword = pw;
                s.save();
            });
            ctx.fireSettingsChanged();
            Thread.ofVirtual().name("fwdproxy-restart").start(tricatch.oehubapp.ForwardProxy::restart);
        });
        restart.setIconTextGap(5);
        // The result shows in the bottom bar, like the editors' save status.
        metaStatus.add(st, "fwd");
        metaPages.add("fwd");
        JTextField[] v = new JTextField[3];
        JPanel info = table(new String[][]{
                {ctx.m("app.settings.server.state"), "-"},
                {ctx.m("app.settings.fwdproxy.address"), "-"},
                {ctx.m("app.settings.server.error"), "-"}}, v);
        // A word in the UI language: see serverPage.
        v[0].setFont(Ui.font(Font.BOLD, 12.5f));
        v[2].setFont(Ui.font(Font.PLAIN, 12.5f));
        JTextArea addresses = multiLineCell(info, v[1]);
        Runnable refresh = () -> {
            tricatch.oehubapp.ForwardProxy.State s = tricatch.oehubapp.ForwardProxy.state();
            v[0].setText(ctx.m(switch (s) {
                case STOPPED -> "app.proxy.status.starting";
                case RUNNING -> "app.settings.server.running";
                case FAILED -> "app.settings.server.failed";
            }));
            v[0].setForeground(s == tricatch.oehubapp.ForwardProxy.State.RUNNING ? Ui.BC700
                    : s == tricatch.oehubapp.ForwardProxy.State.FAILED ? Ui.DANGER : Ui.MUTED);
            int fp = tricatch.oehubapp.ForwardProxy.port();
            addresses.setText(fp > 0 ? String.join("\n", reachableAt(tricatch.oehubapp.ForwardProxy.bind(), fp)) : "-");
            info.revalidate();
            String err = tricatch.oehubapp.ForwardProxy.error();
            errorRow(info, v[2], s == tricatch.oehubapp.ForwardProxy.State.FAILED ? err : null);
        };
        tricatch.oehubapp.ForwardProxy.onChange(() -> SwingUtilities.invokeLater(refresh));
        refresh.run();
        // The address, port and authentication labels share one width, so their fields line up.
        JLabel nicLabel = label(ctx.m("app.settings.fwdproxy.nic"));
        JLabel portLabel = label(ctx.m("app.settings.fwdproxy.port"));
        JLabel authLabel = label(ctx.m("app.settings.fwdproxy.auth"));
        sameWidth(nicLabel, portLabel, authLabel);
        bind.label(nicLabel);
        // The same layout as the web service page: description | settings | action on the left,
        // set apart by lines, the state on the right. Under the authentication choice, the username
        // and password fields sit on a row of their own, starting where the fields above start.
        int fieldsAt = nicLabel.getPreferredSize().width + 6;
        JPanel authRow = buttons(authLabel, authNone, authPassword);
        JPanel credentials = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        credentials.setOpaque(false);
        credentials.setBorder(new EmptyBorder(0, fieldsAt, 0, 0));
        user.setColumns(10);
        password.setColumns(10);
        credentials.add(user);
        credentials.add(Box.createHorizontalStrut(6));
        credentials.add(password);
        for (JComponent c : new JComponent[]{bind.warn, authNote}) c.setBorder(new EmptyBorder(0, fieldsAt, 0, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(restart);
        return fullPage(halves(column(desc(ctx.m("app.settings.fwdproxy.desc")), line(), bind.row, bind.warn,
                buttons(portLabel, port), authRow, credentials, authNote, line(), actions), column(info)));
    }

    /**
     * The web service ({@link ShareServer}): its PC address, port and state. Who can reach it directly
     * is decided by its binding alone.
     */
    private JComponent webPage() {
        JTextField port = new JTextField(String.valueOf(ctx.read(() -> ctx.rawStore().data.settings.shareServerPort)), 8);
        port.setFont(Ui.font(Font.PLAIN, 13f));
        port.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878; margin:4,8,4,8");
        port.setMaximumSize(new Dimension(140, 34));
        NetworkChoice bind = new NetworkChoice(ctx.read(() -> ctx.rawStore().data.settings.shareServerBindAddress()),
                "app.settings.web.bind.warn");
        JLabel st = status();
        JButton restart = Ui.primary(ctx.m("app.settings.server.restart"), Ui.icon("refresh-cw", 13, new Color(0x2d4a14)), () -> {
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
            int sp = p;
            String to = bind.selected();
            ctx.storeRun(s -> {
                s.data.settings.shareServerPort = sp;
                s.data.settings.shareServerBind = to;
                s.save();
            });
            ctx.fireSettingsChanged();
            Thread.ofVirtual().name("web-restart").start(ShareServer::restart);
        });
        restart.setIconTextGap(5);
        metaStatus.add(st, "web");
        metaPages.add("web");
        JTextField[] sv = new JTextField[3];
        JPanel serverInfo = table(new String[][]{
                {ctx.m("app.settings.server.state"), "-"},
                {ctx.m("app.settings.fwdproxy.address"), "-"},
                {ctx.m("app.settings.server.error"), "-"}}, sv);
        sv[0].setFont(Ui.font(Font.BOLD, 12.5f));
        sv[2].setFont(Ui.font(Font.PLAIN, 12.5f));
        JTextArea serverAddresses = multiLineCell(serverInfo, sv[1]);
        JLabel nicLabel = label(ctx.m("app.settings.fwdproxy.nic"));
        JLabel portLabel = label(ctx.m("app.settings.fwdproxy.port"));
        sameWidth(nicLabel, portLabel);
        bind.label(nicLabel);
        Runnable refresh = () -> {
            ShareServer.State s = ShareServer.state();
            sv[0].setText(ctx.m(switch (s) {
                case STOPPED -> "app.proxy.status.starting";
                case RUNNING -> "app.settings.server.running";
                case FAILED -> "app.settings.server.failed";
            }));
            sv[0].setForeground(s == ShareServer.State.RUNNING ? Ui.BC700 : s == ShareServer.State.FAILED ? Ui.DANGER : Ui.MUTED);
            int wp = ShareServer.port();
            serverAddresses.setText(wp > 0 ? String.join("\n", reachableAt(ShareServer.bind(), wp)) : "-");
            String err = ShareServer.error();
            errorRow(serverInfo, sv[2], s == ShareServer.State.FAILED ? err : null);
            serverInfo.revalidate();
        };
        ShareServer.onChange(() -> SwingUtilities.invokeLater(refresh));
        refresh.run();
        // The description and settings on the left half, the server's state on the right.
        // The warning starts where the drop-down does, under it rather than under the label.
        bind.warn.setBorder(new EmptyBorder(0, nicLabel.getPreferredSize().width + 6, 0, 0));
        // Left: description | settings | action, set apart by lines; right: the server's state.
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(restart);
        return fullPage(halves(column(desc(ctx.m("app.settings.web.desc")), line(), bind.row, bind.warn, buttons(portLabel, port),
                line(), actions), column(serverInfo)));
    }

    /**
     * Installing the CA on a mobile device: a QR code of the web service's certificate page
     * ({@link tricatch.oehubapp.PhoneSetup}), which the phone opens through the forward proxy
     * ({@link tricatch.oehubapp.ForwardProxy#webService}). Only the download; nothing is configured here.
     */
    private JComponent mobilePage() {
        // The phone reaches the page through the forward proxy, so the proxy has to be open to the
        // phone's network and the web service has to run (on 127.0.0.1 at least).
        JComponent fwdOff = OeDialog.warn(ctx.m("app.settings.phone.off"), null);
        JPanel fwdOffButtons = buttons(Ui.secondary(ctx.m("app.settings.phone.open.fwd"), "settings", () -> showPage("fwd")));
        JComponent webOff = OeDialog.warn(ctx.m("app.settings.phone.web.stopped"), null);
        JPanel webOffButtons = buttons(Ui.secondary(ctx.m("app.settings.phone.open.web"), "settings", () -> showPage("web")));

        JComboBox<tricatch.oehubapp.PhoneSetup.Lan> address = new JComboBox<>();
        address.setFont(Ui.font(Font.PLAIN, 13f));
        address.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878");
        address.setFocusable(false);
        address.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, value, i, sel, focus);
                if (value instanceof tricatch.oehubapp.PhoneSetup.Lan a) setText(ctx.m("app.settings.bind.iface", a.ip(), a.iface()));
                return this;
            }
        });
        JLabel addressLabel = label(ctx.m("app.settings.phone.address"));
        sameWidth(addressLabel);
        JPanel addressRow = buttons(addressLabel, address);
        JComponent noAddress = OeDialog.warn(ctx.m("app.settings.phone.no.address"), null);

        JTextField[] v = new JTextField[1];
        JPanel info = table(new String[][]{{ctx.m("app.settings.phone.ca.url"), "-"}}, v);
        JButton copyUrl = Ui.secondary(ctx.m("app.settings.phone.copy.url"), "copy", () -> {
            OeDialog.copy(v[0].getText());
            ctx.toast(ctx.m("app.settings.copied"));
        });
        QrCode qr = new QrCode(196);
        qr.setBorder(new LineBorder(Ui.BORDER));
        JPanel qrRow = new JPanel(new BorderLayout(18, 0));
        qrRow.setOpaque(false);
        JPanel qrWrap = new JPanel(new BorderLayout());
        qrWrap.setOpaque(false);
        qrWrap.add(qr, BorderLayout.NORTH);
        qrRow.add(qrWrap, BorderLayout.WEST);
        JTextArea steps = (JTextArea) desc("");
        qrRow.add(steps, BorderLayout.CENTER);
        JComponent ready = OeDialog.stack(14, addressRow, info, buttons(copyUrl), qrRow);

        Runnable fill = () -> {
            Object sel = address.getSelectedItem();
            String ip = sel instanceof tricatch.oehubapp.PhoneSetup.Lan a ? a.ip() : null;
            int wp = ShareServer.port(), fp = tricatch.oehubapp.ForwardProxy.port();
            String url = ip == null || wp <= 0 ? null : tricatch.oehubapp.PhoneSetup.pageUrl(ip, wp);
            v[0].setText(url == null ? "-" : url);
            steps.setText(ctx.m("app.settings.phone.steps", ip == null ? "-" : ip, fp > 0 ? String.valueOf(fp) : "-"));
            copyUrl.setEnabled(url != null);
            qr.setText(url);
        };
        Runnable refresh = () -> {
            boolean fwdOn = tricatch.oehubapp.ForwardProxy.state() == tricatch.oehubapp.ForwardProxy.State.RUNNING
                    && tricatch.oehubapp.ForwardProxy.lan();
            boolean webOn = ShareServer.state() == ShareServer.State.RUNNING;
            // The addresses the proxy answers on: the phone sets one of them as its Wi-Fi proxy.
            Object prev = address.getSelectedItem();
            String prevIp = prev instanceof tricatch.oehubapp.PhoneSetup.Lan a ? a.ip() : null;
            List<tricatch.oehubapp.PhoneSetup.Lan> reachable = new ArrayList<>(tricatch.oehubapp.PhoneSetup.addresses());
            reachable.removeIf(a -> !answersOn(tricatch.oehubapp.ForwardProxy.bind(), a.ip()));
            address.removeAllItems();
            for (tricatch.oehubapp.PhoneSetup.Lan a : reachable) address.addItem(a);
            for (tricatch.oehubapp.PhoneSetup.Lan a : reachable) if (a.ip().equals(prevIp)) address.setSelectedItem(a);
            fwdOff.setVisible(!fwdOn);
            fwdOffButtons.setVisible(!fwdOn);
            webOff.setVisible(!webOn);
            webOffButtons.setVisible(!webOn);
            noAddress.setVisible(fwdOn && webOn && reachable.isEmpty());
            ready.setVisible(fwdOn && webOn && !reachable.isEmpty());
            fill.run();
            ready.revalidate();
        };
        address.addActionListener(e -> fill.run());
        ShareServer.onChange(() -> SwingUtilities.invokeLater(refresh));
        tricatch.oehubapp.ForwardProxy.onChange(() -> SwingUtilities.invokeLater(refresh));
        refresh.run();
        return page(desc(ctx.m("app.settings.phone.desc")), fwdOff, fwdOffButtons, webOff, webOffButtons, noAddress, ready);
    }

    /** Whether a server bound to {@code bind} answers on {@code ip}. */
    private static boolean answersOn(String bind, String ip) {
        return tricatch.oehubapp.ForwardProxy.ALL.equals(bind) || bind.equals(ip);
    }

    /** Gives the labels of one form the same width (at least {@link #FORM_LABEL_WIDTH}), so their fields line up. */
    private static void sameWidth(JLabel... labels) {
        int w = FORM_LABEL_WIDTH;
        for (JLabel l : labels) {
            l.setFont(Ui.font(Font.BOLD, 12.5f));
            w = Math.max(w, l.getPreferredSize().width + 10);
        }
        for (JLabel l : labels) l.setPreferredSize(new Dimension(w, l.getPreferredSize().height));
    }

    /** Label column width of the address / port forms. */
    private static final int FORM_LABEL_WIDTH = 64;

    /**
     * The error row of a server's status {@link #table}: shown with {@code error} while there is one,
     * hidden (label and value) otherwise.
     */
    private static void errorRow(JPanel table, JTextField field, String error) {
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
    private static JTextArea multiLineCell(JPanel table, JTextField field) {
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
     * The addresses a server (forward proxy, web service) answers on for {@code bind}: 127.0.0.1
     * always, then the chosen address, or for 0.0.0.0 every IPv4 address, with the network card's name.
     */
    private List<String> reachableAt(String bind, int port) {
        List<String> out = new ArrayList<>();
        out.add(tricatch.oehubapp.ForwardProxy.LOOPBACK + ":" + port);
        if (tricatch.oehubapp.ForwardProxy.LOOPBACK.equals(bind)) return out;
        boolean all = tricatch.oehubapp.ForwardProxy.ALL.equals(bind);
        boolean found = false;
        for (tricatch.oehubapp.PhoneSetup.Lan l : tricatch.oehubapp.PhoneSetup.addresses()) {
            if (!all && !l.ip().equals(bind)) continue;
            out.add(ctx.m("app.settings.bind.iface", l.ip() + ":" + port, l.iface()));
            found = true;
        }
        if (!all && !found) out.add(bind + ":" + port);
        return out;
    }

    /**
     * A server's address as a drop-down of this PC's networks: 127.0.0.1 (this PC only), each
     * IPv4 address with its network card's name, and 0.0.0.0 (every interface). A stored address this
     * PC no longer has stays in the list, marked, so the setting is visible rather than lost.
     */
    private final class NetworkChoice {
        /** One entry of the drop-down: the address to bind and the text shown for it. */
        private record Choice(String address, String text) {
            @Override
            public String toString() {
                return text;
            }
        }

        /** The widest the label / drop-down row grows on a wide page. */
        private static final int ROW_MAX_WIDTH = 600;

        /** The form label and the drop-down. */
        final JPanel row;
        /** The network-exposure warning, shown while the choice is not 127.0.0.1; placed by the page. */
        final JComponent warn;
        private final JComboBox<Choice> combo = new JComboBox<>();
        private String selected;
        /** Runs after the choice changes; the page decides which warning shows (it depends on the password too). */
        Runnable onChange;
        /** Set while the list is being refilled, so the refill does not count as the user's choice. */
        private boolean filling;

        NetworkChoice(String current, String warnKey) {
            selected = current;
            warn = desc(ctx.m(warnKey));
            combo.setFont(Ui.font(Font.PLAIN, 13f));
            combo.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878");
            combo.setFocusable(false);
            combo.addActionListener(e -> {
                if (filling || !(combo.getSelectedItem() instanceof Choice c)) return;
                selected = c.address();
                showWarn();
            });
            // Label and drop-down: the drop-down takes what width is left (a long network card name is cut).
            row = new JPanel(new BorderLayout(6, 0)) {
                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(ROW_MAX_WIDTH, getPreferredSize().height);
                }
            };
            row.setOpaque(false);
            row.add(combo, BorderLayout.CENTER);
            // The networks are read again each time the list opens (a cable plugged in, another
            // Wi-Fi since the page was built), so no refresh button is needed.
            combo.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                    reload();
                }

                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                }
            });
            combo.setMinimumSize(new Dimension(80, combo.getPreferredSize().height));
            warn.setForeground(Ui.DANGER);
            reload();
        }

        String selected() {
            return selected;
        }

        /** Puts the form label in front of the drop-down. */
        void label(JLabel l) {
            row.add(l, BorderLayout.WEST);
        }

        /** Lists this PC's networks again (each time the drop-down opens), keeping the choice. */
        void reload() {
            List<Choice> items = new ArrayList<>();
            items.add(new Choice(tricatch.oehubapp.ForwardProxy.LOOPBACK, ctx.m("app.settings.bind.local")));
            boolean found = false;
            for (tricatch.oehubapp.PhoneSetup.Lan l : tricatch.oehubapp.PhoneSetup.addresses()) {
                items.add(new Choice(l.ip(), ctx.m("app.settings.bind.iface", l.ip(), l.iface())));
                found |= l.ip().equals(selected);
            }
            boolean special = tricatch.oehubapp.ForwardProxy.LOOPBACK.equals(selected)
                    || tricatch.oehubapp.ForwardProxy.ALL.equals(selected);
            if (!found && !special) items.add(new Choice(selected, ctx.m("app.settings.bind.iface.missing", selected)));
            items.add(new Choice(tricatch.oehubapp.ForwardProxy.ALL, ctx.m("app.settings.fwdproxy.bind.all")));
            filling = true;
            try {
                combo.removeAllItems();
                for (Choice c : items) {
                    combo.addItem(c);
                    if (c.address().equals(selected)) combo.setSelectedItem(c);
                }
            } finally {
                filling = false;
            }
            showWarn();
        }

        private void showWarn() {
            if (onChange != null) onChange.run();
            else warn.setVisible(!tricatch.oehubapp.ForwardProxy.LOOPBACK.equals(selected));
            row.revalidate();
            row.repaint();
        }
    }

    /**
     * oeHosts-Agent: where the app installed it, its version and update check, and the
     * {@code -javaagent} option for a shared profile, ready to copy into a Java command line or an
     * IDE's VM options. Follows {@link HostsAgent} as it changes (a release downloaded at start).
     */
    private JComponent agentPage() {
        JTextField[] v = new JTextField[4];
        JPanel info = table(new String[][]{
                {ctx.m("app.settings.agent.file"), "-"},
                {ctx.m("app.settings.agent.version"), "-"},
                {ctx.m("app.settings.server.state"), "-"},
                {ctx.m("app.settings.agent.latest"), "-"}}, v);
        v[2].setFont(Ui.font(Font.BOLD, 12.5f));
        v[3].setFont(Ui.font(Font.PLAIN, 12.5f));

        // Shared profiles only: the agent reads a profile through its share URL.
        record Shared(String name, String url) {
            @Override
            public String toString() {
                return name;
            }
        }
        JComboBox<Shared> profile = new JComboBox<>();
        profile.setFont(Ui.font(Font.PLAIN, 13f));
        profile.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878");
        profile.setFocusable(false);
        JLabel profileLabel = new JLabel(ctx.m("app.settings.agent.profile"));
        profileLabel.setFont(Ui.font(Font.BOLD, 12.5f));
        profileLabel.setForeground(Ui.MUTED);
        JPanel profileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        profileRow.setOpaque(false);
        profileRow.add(profileLabel);
        profileRow.add(Box.createHorizontalStrut(10));
        profileRow.add(profile);

        JTextArea option = OeDialog.script("");
        option.setColumns(0);
        JComponent none = desc(ctx.m("app.settings.agent.noshared"));
        JComponent inUse = desc(ctx.m("app.settings.agent.inuse"));
        JComponent missing = OeDialog.warn(ctx.m("app.settings.agent.missing"), null);
        JLabel st = status();
        JButton copy = Ui.primary(ctx.m("app.settings.agent.copy"), Ui.icon("copy", 13, new Color(0x2d4a14)), () -> {
            OeDialog.copy(option.getText());
            ok(st, ctx.m("app.settings.copied"));
        });
        copy.setIconTextGap(5);
        JButton folder = Ui.secondary(ctx.m("app.settings.agent.folder"), "folder-open", () -> {
            if (HostsAgent.path() != null) openFolder(HostsAgent.path().getParent());
        });
        JPanel actions = buttons(copy, folder);
        JComponent hint = desc(ctx.m("app.settings.agent.hint"));
        metaStatus.add(st, "agent");
        metaPages.add("agent");

        boolean[] usable = {false};
        Runnable showOption = () -> {
            Shared sel = (Shared) profile.getSelectedItem();
            String url = sel == null ? ctx.m("app.settings.agent.url.placeholder") : sel.url();
            java.nio.file.Path agent = HostsAgent.path();
            option.setText(agent == null ? "-" : HostsAgent.option(agent, url));
            copy.setEnabled(usable[0] && sel != null);
        };
        // The shared profiles change elsewhere (the oeHosts share dialog): reload whenever the page is shown.
        Runnable reloadProfiles = () -> {
            Shared prev = (Shared) profile.getSelectedItem();
            int port = ctx.read(() -> ctx.rawStore().data.settings.shareServerPort);
            List<Shared> items = new ArrayList<>();
            List<Store.Item> shared = ctx.read(() -> ctx.rawStore().list(Store.Kind.HOSTS).stream()
                    .filter(i -> i.shared).toList());
            for (Store.Item i : shared) {
                String token = ctx.store(s -> s.shareToken(i.id));
                if (token != null) items.add(new Shared(i.name, ShareServer.url(port, token, i.name)));
            }
            profile.setModel(new DefaultComboBoxModel<>(items.toArray(new Shared[0])));
            if (prev != null) {
                items.stream().filter(x -> x.url().equals(prev.url())).findFirst().ifPresent(profile::setSelectedItem);
            }
            profileRow.setVisible(usable[0] && !items.isEmpty());
            none.setVisible(usable[0] && items.isEmpty());
            showOption.run();
        };
        Runnable refresh = () -> {
            HostsAgent.State state = HostsAgent.state();
            usable[0] = HostsAgent.path() != null
                    && (state == HostsAgent.State.INSTALLED || state == HostsAgent.State.IN_USE);
            v[0].setText(HostsAgent.path() == null ? "-" : HostsAgent.path().toString());
            v[1].setText(HostsAgent.version() == null ? "-" : HostsAgent.version());
            String stateText = ctx.m(switch (state) {
                case INSTALLED -> "app.settings.agent.state.installed";
                case IN_USE -> "app.settings.agent.state.inuse";
                case MISSING -> "app.settings.agent.state.missing";
                case FAILED -> "app.settings.agent.state.failed";
            });
            if (state == HostsAgent.State.FAILED && HostsAgent.error() != null) stateText += " - " + HostsAgent.error();
            v[2].setText(stateText);
            v[2].setForeground(state == HostsAgent.State.INSTALLED ? Ui.BC700
                    : state == HostsAgent.State.IN_USE ? Ui.MUTED : Ui.DANGER);
            String latest = HostsAgent.latest();
            v[3].setText(switch (HostsAgent.check()) {
                case IDLE -> "-";
                case CHECKING -> ctx.m("app.update.checking");
                case LATEST -> ctx.m("app.settings.agent.check.latest", latest);
                case DOWNLOADING -> ctx.m("app.settings.agent.check.downloading", latest);
                case UPDATED -> ctx.m("app.settings.agent.check.updated", latest);
                case PENDING -> ctx.m("app.settings.agent.check.pending", latest);
                case FAILED -> ctx.m("app.update.check.failed", HostsAgent.checkError());
            });
            v[3].setForeground(HostsAgent.check() == HostsAgent.Check.FAILED ? Ui.DANGER : Ui.TEXT);
            missing.setVisible(!usable[0]);
            inUse.setVisible(state == HostsAgent.State.IN_USE);
            option.setVisible(usable[0]);
            actions.setVisible(usable[0]);
            hint.setVisible(usable[0]);
            reloadProfiles.run();
            for (JTextField f : v) f.setCaretPosition(0);
            info.revalidate();
        };
        profile.addActionListener(e -> showOption.run());
        option.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && option.isShowing()) reloadProfiles.run();
        });
        refresh.run();
        HostsAgent.setListener(() -> SwingUtilities.invokeLater(refresh));
        return page(desc(ctx.m("app.settings.agent.desc")), info, missing, inUse, profileRow, none, option, actions, hint);
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
        // The certificate's details on the left, its files on the right; refreshCa fills both
        // through one array (details 0-3, files 4-5).
        JTextField[] d = new JTextField[4];
        JPanel info = table(new String[][]{
                {ctx.m("app.settings.ca.subject"), "-"},
                {ctx.m("app.settings.ca.valid"), "-"},
                {ctx.m("app.settings.ca.sha256"), "-"},
                {ctx.m("app.settings.ca.trust"), "-"}}, d);
        JTextField[] f = new JTextField[2];
        JPanel files = table(new String[][]{
                {ctx.m("app.settings.ca.file"), "-"},
                {ctx.m("app.settings.ca.key"), "-"}}, f);
        JTextField[] v = {d[0], d[1], d[2], d[3], f[0], f[1]};
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
        // Left: the CA's actions (register it; save a copy; bring in or make another one).
        // Right: its files and their folder.
        return fullPage(halves(
                column(desc(ctx.m("app.settings.ca.desc")), line(), info, line(), rightAligned(copyCmd, install),
                        rightAligned(export, imp, regen)),
                column(desc(ctx.m("app.settings.ca.files.desc")), line(), files, line(), rightAligned(folder))));
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
        // Where it listens: this PC only, one network card (with 127.0.0.1), or every interface (the default).
        NetworkChoice bind = new NetworkChoice(ctx.read(() -> ctx.rawStore().data.settings.reverseProxyBindAddress()),
                "app.settings.server.bind.all.warn");
        JLabel nicLabel = label(ctx.m("app.settings.fwdproxy.nic"));
        sameWidth(nicLabel);
        bind.label(nicLabel);
        bind.warn.setBorder(new EmptyBorder(0, nicLabel.getPreferredSize().width + 6, 0, 0));
        JTextField[] v = new JTextField[3];
        JPanel info = table(new String[][]{
                {ctx.m("app.settings.server.state"), "-"},
                {ctx.m("app.settings.fwdproxy.address"), "-"},
                {ctx.m("app.settings.server.error"), "-"}}, v);
        // The status is a word in the UI language: Korean in the mono font falls back to a taller
        // glyph that the row cuts off at the top.
        v[0].setFont(Ui.font(Font.BOLD, 12.5f));
        v[2].setFont(Ui.font(Font.PLAIN, 12.5f));
        // Every address it answers on, like the forward proxy and web service pages.
        JTextArea addresses = multiLineCell(info, v[1]);
        Runnable refresh = () -> {
            ProxyRuntime.State s = ProxyRuntime.state();
            v[0].setText(ctx.m(switch (s) {
                case STARTING -> "app.proxy.status.starting";
                case RUNNING -> "app.settings.server.running";
                case FAILED -> "app.settings.server.failed";
            }));
            v[0].setForeground(s == ProxyRuntime.State.RUNNING ? Ui.BC700 : s == ProxyRuntime.State.FAILED ? Ui.DANGER : Ui.MUTED);
            addresses.setText(String.join("\n", reachableAt(ProxyRuntime.bind(), ProxyRuntime.port())));
            info.revalidate();
            errorRow(info, v[2], s == ProxyRuntime.State.FAILED ? ProxyRuntime.error() : null);
        };
        ProxyRuntime.onChange(() -> SwingUtilities.invokeLater(refresh));
        refresh.run();
        // Saves the binding, then restarts the proxy with it; the state rows show the outcome.
        JButton restart = Ui.primary(ctx.m("app.settings.server.restart"), Ui.icon("refresh-cw", 13, new Color(0x2d4a14)), () -> {
            String to = bind.selected();
            ctx.storeRun(s -> {
                s.data.settings.putReverseProxyBind(to);
                s.save();
            });
            ProxyRuntime.restart();
        });
        restart.setIconTextGap(5);
        // Left: description | access choice | action; right: the state.
        return fullPage(halves(column(desc(ctx.m("app.settings.server.desc")), line(), bind.row, bind.warn, line(), rightAligned(restart)),
                column(info)));
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
        // What to export / import: one check box per part, all on by default except the CA (its private key is sensitive).
        JCheckBox[] parts = new JCheckBox[DATA_PARTS.length];
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checks.setOpaque(false);
        JButton ex = Ui.secondary(ctx.m("app.data.export"), "file-down", () -> exportData(chosen(parts)));
        JButton in = Ui.secondary(ctx.m("app.data.import"), "circle-fading-plus", () -> importData(chosen(parts)));
        for (int i = 0; i < parts.length; i++) {
            parts[i] = new JCheckBox(ctx.m(DATA_PARTS[i]), i != CA_PART);
            parts[i].setOpaque(false);
            parts[i].setFocusable(false);
            parts[i].setFont(Ui.font(Font.PLAIN, 13f));
            parts[i].addActionListener(e -> {
                boolean any = chosen(parts).any();
                ex.setEnabled(any);
                in.setEnabled(any);
            });
            if (i > 0) checks.add(Box.createHorizontalStrut(18));
            checks.add(parts[i]);
        }
        // Left: export / import (what changes data), description | choice | actions, set apart by
        // lines; right: where the data file is (looking only).
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(ex);
        actions.add(Box.createHorizontalStrut(6));
        actions.add(in);
        return fullPage(halves(column(desc(ctx.m("app.data.desc")), line(), checks, line(), actions),
                column(desc(ctx.m("app.settings.data.desc")), line(), info, line(), rightAligned(folder))));
    }

    /** The data page's check boxes, in {@link Parts} order. */
    private static final int CA_PART = 3;
    private static final String[] DATA_PARTS = {"app.data.hosts", "app.data.vhosts", "app.data.settings", "app.data.ca"};

    /** The parts of the data chosen for an export or import. */
    private record Parts(boolean hosts, boolean vhosts, boolean settings, boolean ca) {
        boolean any() {
            return hosts || vhosts || settings || ca;
        }
    }

    private static Parts chosen(JCheckBox[] b) {
        return new Parts(b[0].isSelected(), b[1].isSelected(), b[2].isSelected(), b[3].isSelected());
    }

    /** The names of the parts that are on, comma separated, for a message. */
    private String names(Parts p) {
        List<String> n = new ArrayList<>();
        boolean[] on = {p.hosts(), p.vhosts(), p.settings(), p.ca()};
        for (int i = 0; i < on.length; i++) if (on[i]) n.add(ctx.m(DATA_PARTS[i]));
        return String.join(", ", n);
    }

    /** Exports the chosen parts to one backup ZIP ({@link tricatch.oehubapp.Backup}). */
    private void exportData(Parts p) {
        if (!p.any()) return;
        flushEdits.run();
        flush();
        if (p.ca() && !OeDialog.confirm(ctx, ctx.m("app.data.export.title"), ctx.m("app.data.export.confirm.ca"))) return;
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("oehubapp-backup-" + ts + ".zip"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        try {
            byte[] json = ctx.read(() -> {
                try {
                    return Store.MAPPER.writeValueAsBytes(ctx.rawStore().exportParts(p.hosts(), p.vhosts(), p.settings()));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            tricatch.oehubapp.Backup.write(json, p.ca() ? ProxyRuntime.caDir() : null, out);
            ctx.toast(ctx.m("app.data.exported"));
        } catch (Exception e) {
            ctx.toast(ctx.m("app.data.export.failed"));
        }
    }

    /**
     * Imports the chosen parts from a backup ZIP: each part both chosen and in the file is added to, or overwrites, what is stored (the CA,
     * being one, is always overwritten). The CA pair is checked before
     * anything changes. The window is rebuilt when the settings change (the language may differ).
     */
    private void importData(Parts chosen) {
        if (!chosen.any()) return;
        flushEdits.run();
        flush();
        String title = ctx.m("app.data.import.title");
        JFileChooser fc = new JFileChooser();
        fc.setAcceptAllFileFilterUsed(false);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("*.zip", "zip"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        tricatch.oehubapp.Backup.Contents b;
        try {
            b = tricatch.oehubapp.Backup.read(fc.getSelectedFile().toPath());
        } catch (Exception e) {
            OeDialog.info(ctx, title, ctx.m("app.data.import.error.format"));
            return;
        }
        Parts p = new Parts(chosen.hosts() && b.hasHosts(), chosen.vhosts() && b.hasVhosts(),
                chosen.settings() && b.hasSettings(), chosen.ca() && b.hasCa());
        if (!p.any()) {
            OeDialog.info(ctx, title, ctx.m("app.data.import.error.none"));
            return;
        }
        Parts missing = new Parts(chosen.hosts() && !p.hosts(), chosen.vhosts() && !p.vhosts(),
                chosen.settings() && !p.settings(), chosen.ca() && !p.ca());
        Boolean replace = askImportMode(title, p, missing);
        if (replace == null) return;
        if (p.ca()) {
            Path dir = null;
            try {
                dir = Files.createTempDirectory("oehubapp-restore");
                Path cer = Files.write(dir.resolve("ca.cer"), b.cert()), key = Files.write(dir.resolve("ca.pfx"), b.key());
                ProxyRuntime.importCa(cer, key, "");
            } catch (ProxyRuntime.ImportException e) {
                OeDialog.info(ctx, title, ctx.m(e.code));
                return;
            } catch (Exception e) {
                OeDialog.info(ctx, title, ctx.m("app.import.failed"));
                return;
            } finally {
                deleteTree(dir);
            }
        }
        if ((p.hosts() || p.vhosts() || p.settings()) && ctx.store(s -> {
            try {
                if (replace) {
                    s.restoreParts(b.data(), p.hosts(), p.vhosts(), p.settings());
                } else {
                    s.mergeParts(b.data(), p.hosts(), p.vhosts(), p.settings());
                }
            } catch (java.io.IOException e) {
                OeDialog.info(ctx, title, ctx.m("app.data.import.error.format"));
                return null;
            }
            return Boolean.TRUE;
        }) == null) return;
        if (p.vhosts() || p.settings() || p.ca()) ProxyRuntime.restart();
        if (p.settings()) {
            OeDialog.info(ctx, title, ctx.m("app.data.imported.reload"));
            relaunch.run();
        } else {
            reloadAll.run();
            ctx.toast(ctx.m("app.data.imported"));
        }
    }

    /**
     * Asks how to import the parts {@code p}: add to the current data (the default) or overwrite it.
     * True to overwrite, false to add, null when cancelled. Overwriting asks once more.
     */
    private Boolean askImportMode(String title, Parts p, Parts missing) {
        String text = ctx.m("app.data.import.parts", names(p));
        if (missing.any()) text += " " + ctx.m("app.data.import.missing", names(missing));
        if (p.ca()) text += " " + ctx.m("app.data.import.ca.always");
        JComponent note = OeDialog.note(text);
        Dialogs.fixWidth(note, 440);
        JRadioButton add = new JRadioButton(ctx.m("app.data.import.mode.add"), true);
        JRadioButton overwrite = new JRadioButton(ctx.m("app.data.import.mode.replace"));
        ButtonGroup g = new ButtonGroup();
        for (JRadioButton r : new JRadioButton[]{add, overwrite}) {
            r.setOpaque(false);
            r.setFocusable(false);
            r.setFont(Ui.font(Font.PLAIN, 13f));
            g.add(r);
        }
        Boolean[] result = {null};
        OeDialog.open(ctx, title, d -> {
            d.body(OeDialog.stack(8, note, add, overwrite));
            d.button(ctx.m("btn.import"), true, () -> {
                result[0] = overwrite.isSelected();
                d.dispose();
            });
            d.button(ctx.m("btn.cancel"), false, d::dispose);
        });
        if (Boolean.TRUE.equals(result[0])
                && !OeDialog.confirm(ctx, title, ctx.m("app.data.import.confirm", names(p)))) return null;
        return result[0];
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
        // The app's own license and source, as rows of the same table.
        aboutRow(info, v.length, ctx.m("app.settings.about.license"), badge("MIT", MIT[0], MIT[1]));
        aboutRow(info, v.length + 1, "GitHub", link(REPO_URL.substring("https://".length()), REPO_URL));
        aboutRow(info, v.length + 2, ctx.m("app.settings.about.update"), updateRow());
        return page(info);
    }

    /**
     * The update status (checked at start, {@link Update}) and its action: check again, or
     * download, install and restart. A run that cannot replace its own jar only names the new version.
     */
    private JComponent updateRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        Runnable refresh = () -> {
            row.removeAll();
            Update.Status s = Update.status();
            JLabel text = new JLabel();
            text.setFont(Ui.font(Font.PLAIN, 12.5f));
            text.setForeground(Ui.TEXT);
            JComponent action = null;
            JButton check = Ui.secondary(ctx.m("app.update.check"), null, Update::checkInBackground);
            switch (s.state()) {
                case IDLE -> action = check;
                case CHECKING -> text.setText(ctx.m("app.update.checking"));
                case UP_TO_DATE -> {
                    text.setText(ctx.m("app.update.latest"));
                    action = check;
                }
                case FAILED -> {
                    text.setText(ctx.m("app.update.check.failed", s.error()));
                    text.setForeground(Ui.DANGER);
                    action = check;
                }
                case AVAILABLE -> {
                    if (s.error() != null) {
                        text.setText(ctx.m("app.update.failed", s.error()));
                        text.setForeground(Ui.DANGER);
                    } else {
                        text.setText(ctx.m("app.update.available", s.latest()));
                    }
                    action = Update.canSelfUpdate()
                            ? Ui.primary(ctx.m("app.update.install"), null, () -> confirmUpdate(s.latest()))
                            : null;
                }
                case DOWNLOADING -> text.setText(ctx.m("app.update.downloading", s.percent()));
                case RESTARTING -> text.setText(ctx.m("app.update.restarting"));
            }
            if (!text.getText().isEmpty()) {
                row.add(text);
                row.add(Box.createHorizontalStrut(10));
            }
            if (s.state() == Update.State.AVAILABLE) {
                row.add(link(ctx.m("app.update.notes"), Update.releaseUrl(s.latest())));
                row.add(Box.createHorizontalStrut(10));
                if (action == null) {
                    JLabel manual = new JLabel(ctx.m("app.update.manual"));
                    manual.setFont(Ui.font(Font.PLAIN, 12.5f));
                    manual.setForeground(Ui.MUTED);
                    row.add(manual);
                }
            }
            if (action != null) row.add(action);
            row.revalidate();
            row.repaint();
        };
        refresh.run();
        Update.setListener(() -> SwingUtilities.invokeLater(refresh));
        return row;
    }

    private void confirmUpdate(String version) {
        if (!OeDialog.confirm(ctx, ctx.m("app.update.confirm.title"), ctx.m("app.update.confirm", version))) return;
        flushEdits.run();
        flush();
        Update.updateInBackground(() -> SwingUtilities.invokeLater(MainWindow::quit));
    }

    private static final String REPO_URL = "https://github.com/tricatch/oeHubApp";

    /** A label / component row under {@link #table}'s rows, in the same columns. */
    private static void aboutRow(JPanel table, int row, String key, JComponent value) {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.BASELINE_LEADING;
        c.insets = new Insets(3, 0, 3, 16);
        c.gridy = row;
        c.gridx = 0;
        JLabel k = new JLabel(key);
        k.setFont(Ui.font(Font.BOLD, 12.5f));
        k.setForeground(Ui.MUTED);
        table.add(k, c);
        c.gridx = 1;
        table.add(value, c);
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
            lib("Apache Commons Lang", "https://commons.apache.org/proper/commons-lang/", "org.apache.commons:commons-lang3", "Apache 2.0", APACHE),
            lib("Ant-Style Path Matcher", "https://github.com/azagniotov/ant-style-path-matcher", "io.github.azagniotov:ant-style-path-matcher", "MIT", MIT),
            lib("Bouncy Castle", "https://www.bouncycastle.org", "org.bouncycastle:bcprov-jdk18on", "MIT", MIT),
            lib("Caffeine", "https://github.com/ben-manes/caffeine", "com.github.ben-manes.caffeine:caffeine", "Apache 2.0", APACHE),
            lib("Checker Framework Qualifiers", "https://checkerframework.org", "org.checkerframework:checker-qual", "MIT", MIT),
            lib("Error Prone Annotations", "https://errorprone.info", "com.google.errorprone:error_prone_annotations", "Apache 2.0", APACHE),
            lib("FlatLaf", "https://www.formdev.com/flatlaf/", "com.formdev:flatlaf", "Apache 2.0", APACHE),
            lib("gotpache-keytool", "https://github.com/tricatch/gotpache-keytool", "io.github.tricatch:gotpache-keytool", "MIT", MIT),
            lib("Guava", "https://github.com/google/guava", "com.google.guava:guava", "Apache 2.0", APACHE),
            lib("J2ObjC Annotations", "https://github.com/google/j2objc", "com.google.j2objc:j2objc-annotations", "Apache 2.0", APACHE),
            lib("Jackson", "https://github.com/FasterXML/jackson", "com.fasterxml.jackson.core:jackson-databind", "Apache 2.0", APACHE),
            lib("JSpecify", "https://jspecify.dev", "org.jspecify:jspecify", "Apache 2.0", APACHE),
            lib("JSVG", "https://github.com/weisJ/jsvg", "com.github.weisj:jsvg", "MIT", MIT),
            lib("LittleProxy", "https://github.com/LittleProxy/LittleProxy", "io.github.littleproxy:littleproxy", "Apache 2.0", APACHE),
            lib("Lucide", "https://lucide.dev", null, "ISC", MIT),
            lib("Netty", "https://netty.io", "io.netty:netty-common", "Apache 2.0", APACHE),
            lib("oe-proxy", "https://github.com/tricatch/oeProxy", "io.github.tricatch:oe-proxy", "MIT", MIT),
            lib("RSyntaxTextArea", "https://github.com/bobbylight/RSyntaxTextArea", "com.fifesoft:rsyntaxtextarea", "BSD 3-Clause", BSD),
            lib("SLF4J", "https://www.slf4j.org", "org.slf4j:slf4j-api", "MIT", MIT),
            lib("SnakeYAML", "https://bitbucket.org/snakeyaml/snakeyaml", "org.yaml:snakeyaml", "Apache 2.0", APACHE),
            lib("ZXing", "https://github.com/zxing/zxing", "com.google.zxing:core", "Apache 2.0", APACHE));

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
