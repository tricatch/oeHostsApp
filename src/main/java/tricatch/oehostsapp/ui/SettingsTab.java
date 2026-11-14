package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.OeHostsApp;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The settings screen, laid out like the oeHosts / oeProxy screens: a left list of folders
 * (General, oeHosts, oeProxy) with one entry per settings page, and the selected page on the right.
 * Each page is its own class ({@code *Page}); a page is built the first time it is shown.
 */
public final class SettingsTab extends JPanel {

    /** What the settings pages ask of the window. */
    interface Host {
        /** Rebuilds the window (language change, settings import). */
        void relaunch();

        /** Re-reads the profiles after an import. */
        void reloadAll();

        /** Saves the oeHosts/oeProxy editors' pending edits (before an import replaces data, an update). */
        void flushEdits();

        /** Applies a font size to the whole UI (null: the default size) and rebuilds the window. */
        void fontSize(Integer size);
    }

    private record Page(String id, String titleKey, Supplier<JComponent> build) {}

    private record Folder(String titleKey, List<Page> pages) {}

    private final AppContext ctx;
    private final Host host;
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

    SettingsTab(AppContext ctx, Host host) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.host = host;
        folders.add(new Folder("app.settings.folder.general", List.of(
                new Page("about", "app.settings.about.title", () -> new AboutPage(this).build()),
                new Page("fwd", "app.settings.fwdproxy.title", () -> new ForwardProxyPage(this).build()),
                new Page("lang", "app.settings.lang.title", this::langPage),
                new Page("data", "app.settings.data.title", () -> new DataPage(this).build()),
                new Page("licenses", "licenses.section.opensource", () -> LicensesPage.build(ctx)))));
        folders.add(new Folder("app.settings.folder.hosts", List.of(
                new Page("ua", "app.settings.ua.presets.title", () -> new PresetsPage(this, true).build()),
                new Page("url", "app.settings.url.presets.title", () -> new PresetsPage(this, false).build()),
                new Page("active", "app.settings.active.title", () -> new ActiveHostsPage(this).build()),
                new Page("agent", "app.settings.agent.title", () -> new AgentPage(this).build()))));
        folders.add(new Folder("app.settings.folder.proxy", List.of(
                new Page("ca", "app.settings.ca.title", () -> new CaPage(this).build()),
                new Page("server", "app.settings.server.title", () -> new ReverseProxyPage(this).build()))));

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(Color.WHITE);
        list.setBorder(Ui.pad(8, 8, 8, 8));
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(Color.WHITE);
        sidebar.add(Ui.header(Ui.caps(ctx.m("nav.settings"))), BorderLayout.NORTH);
        JPanel listWrap = new JPanel(new BorderLayout());
        listWrap.setBackground(Color.WHITE);
        listWrap.add(list, BorderLayout.NORTH);
        sidebar.add(Ui.plainScroll(listWrap), BorderLayout.CENTER);
        JPanel meta = Ui.meta();
        meta.add(Ui.metaText("oeHostsApp " + OeHostsApp.version()), BorderLayout.WEST);
        sidebar.add(meta, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Color.WHITE);
        right.add(Ui.header(title), BorderLayout.NORTH);
        cards.setBackground(Color.WHITE);
        right.add(cards, BorderLayout.CENTER);
        JPanel rightMeta = Ui.meta();
        // A page's status (save state, a result), at the right end like the oeHosts/oeProxy date bar.
        metaStatus.setOpaque(false);
        metaStatus.add(new JLabel(" "), "");
        rightMeta.add(metaStatus, BorderLayout.EAST);
        right.add(rightMeta, BorderLayout.SOUTH);

        add(Ui.sidebarSplit(ctx, sidebar, right), BorderLayout.CENTER);
        // -Doehostsapp.settings.page=<id> opens another page first (screenshots of the other pages).
        select(System.getProperty("oehostsapp.settings.page", folders.get(0).pages().get(0).id()));
    }

    // ── For the pages ───────────────────────────────────────────

    AppContext ctx() {
        return ctx;
    }

    Host host() {
        return host;
    }

    /** The bottom-bar status label of page {@code pageId}, shown while that page is. */
    JLabel status(String pageId) {
        JLabel st = SettingsKit.status();
        metaStatus.add(st, pageId);
        metaPages.add(pageId);
        return st;
    }

    /** An editor's autosave, flushed with the others ({@link #flush}). */
    void track(Autosave a) {
        autosaves.add(a);
    }

    /** Saves the preset editors' pending edits (window closing, rebuild). */
    public void flush() {
        for (Autosave a : autosaves) a.flush();
    }

    /** Saves every pending edit of the window: the editors of all screens and the preset editors. */
    void flushAll() {
        host.flushEdits();
        flush();
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
    private JComponent row(Icon icon, String text, boolean active, int indentAt100, Runnable onClick) {
        int indent = Ui.px(indentAt100);
        JPanel row = new JPanel(new BorderLayout(Ui.px(8), 0)) {
            boolean hover;

            {
                addMouseListener(Ui.clickOn(this, n -> onClick.run()));
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
                });
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, Ui.px(36));
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(super.getPreferredSize().width, Ui.px(36));
            }

            @Override
            protected void paintComponent(Graphics g) {
                if (active || hover) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(active ? Ui.BC100 : Ui.HOVER);
                    g2.fillRoundRect(indent, 0, getWidth() - indent, getHeight(), Ui.px(16), Ui.px(16));
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(0, Ui.px(8) + indent, 0, Ui.px(8)));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JLabel name = new JLabel(text, icon, SwingConstants.LEFT);
        name.setIconTextGap(Ui.px(8));
        name.setFont(Ui.font(active ? Font.BOLD : Font.PLAIN, 13f));
        name.setForeground(active ? Ui.TEXT_STRONG : Ui.TEXT);
        row.add(name, BorderLayout.CENTER);
        return row;
    }

    // ── Language (small enough to stay here) ────────────────────

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
                if (ctx.saveSettings(st -> st.lang = lang[0])) host.relaunch();
            });
            g.add(r);
            radios.add(r);
        }
        return SettingsKit.page(SettingsKit.desc(ctx.m("app.settings.lang.desc")), radios,
                SettingsKit.line(), fontSizeSection());
    }

    /** The font size the whole UI is scaled to (Ui.applyFontSize), chosen from a few steps. */
    private JComponent fontSizeSection() {
        int base = Ui.defaultFontSize();
        int[] sizes = Ui.fontSizes();
        Integer stored = ctx.settings(st -> st.fontSize);
        int current = stored == null ? base : stored;
        JComboBox<String> combo = new JComboBox<>();
        int selected = -1;
        for (int i = 0; i < sizes.length; i++) {
            combo.addItem(sizes[i] == base ? ctx.m("app.settings.font.default", sizes[i]) : String.valueOf(sizes[i]));
            if (sizes[i] == current) selected = i;
        }
        // A size stored on another OS (another default size) that is not one of the steps here.
        if (selected < 0) {
            combo.addItem(String.valueOf(current));
            selected = combo.getItemCount() - 1;
        }
        combo.setSelectedIndex(selected);
        combo.setFont(Ui.font(Font.PLAIN, 13f));
        combo.addActionListener(a -> {
            int i = combo.getSelectedIndex();
            int size = i < sizes.length ? sizes[i] : current;
            if (size != current) host.fontSize(size == base ? null : size);
        });
        JLabel label = SettingsKit.label(ctx.m("app.settings.font.label"));
        SettingsKit.sameWidth(label);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(combo);
        String key = Ui.MAC ? "\u2318" : "Ctrl";
        return OeDialog.stack(10, SettingsKit.desc(ctx.m("app.settings.font.desc", key)), SettingsKit.formRow(label, left));
    }
}
