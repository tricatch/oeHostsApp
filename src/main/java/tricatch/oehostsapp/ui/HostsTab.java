package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.chrome.ChromeArgs;
import tricatch.oehostsapp.chrome.ChromeLauncher;
import tricatch.oehostsapp.hosts.HostsRules;
import tricatch.oehostsapp.store.Store;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The oeHosts screen, laid out like oeHub's hosts page: profile sidebar, editor with the
 * merged preview, and the Chrome launcher panel. Launcher options are stored in the same
 * {@code hostsConf} keys as oeHub.
 */
public final class HostsTab extends JPanel {

    static final String DEFAULT_EXTRA_ARGS = "--disable-background-networking\n--disable-sync\n--disk-cache-size=1\n--media-cache-size=1\n--no-first-run";
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final AppContext ctx;
    private final SidebarTree tree;
    private final JLabel countLabel = Ui.metaText(" ");
    private final JLabel editorTitle;
    private final JLabel saveStatus = new JLabel(" ");
    private final CodeEditor editor = new CodeEditor(null);
    private final JPanel editorCards = whitePanel(new CardLayout());
    private final JLabel metaUpdated = Ui.metaText("—");
    private final JButton btnShare, btnDownload, btnDelete;
    private JButton launchBtn;
    private final Autosave autosave;

    private final JPanel mergedSection = new JPanel(new BorderLayout(0, 4));
    private final JLabel mergedLabel = new JLabel();
    private final JTextPane mergedBody = Ui.previewPane();
    private static final int MERGED_H = 200;
    private JSplitPane mergedSplit;
    private int mergedHeight = MERGED_H;

    // Launcher options
    private final Switch swDevTools = new Switch(false, Ui.BC500);
    private final Switch swIncognito = new Switch(false, Ui.BC500);
    private final Switch swUa = new Switch(false, Ui.BC500);
    private final JTextField uaField = new JTextField();
    private final JButton uaPreset;
    private final Switch swUdd = new Switch(false, Ui.BC500);
    private final JLabel uddDisplay = new JLabel(ChromeArgs.DEFAULT_USER_DATA_DIR);
    private final Switch swExtra = new Switch(false, Ui.BC500);
    private final JTextField extraLine = new JTextField();
    private final JTextArea extraArea = new JTextArea(5, 20);
    private final JScrollPane extraScroll;
    private final Switch swUrl = new Switch(true, Ui.BC500);
    private final JTextField urlField = new JTextField();
    private final JButton urlPreset;
    private final Switch swHrr = new Switch(true, Ui.BC500);
    private final Switch swProxy = new Switch(false, Ui.BC500);
    private final JTextPane argsPreview = Ui.previewPane();
    private final JTextPane rulesPreview = Ui.previewPane();
    private final JLabel hostsCount = Ui.metaText("Hosts: 0");

    private Store.Item active;

    public HostsTab(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        setBackground(Color.WHITE);

        tree = new SidebarTree(ctx, Store.Kind.HOSTS, new SidebarTree.Listener() {
            public void activated(Store.Item item) {
                activate(item.id);
            }

            public void toggled(Store.Item item) {
                ctx.store(s -> s.toggleSelected(Store.Kind.HOSTS, item.id));
                tree.render();
                updatePreviews();
            }

            public void renamed(Store.Item item) {
                if (active != null && active.id.equals(item.id)) editorTitle.setText(item.name);
                updatePreviews();
            }

            public void deleteRequested(Store.Item item) {
                deleteItem(item);
            }

            public void changed() {
                updatePreviews();
            }
        });

        editorTitle = Ui.title(ctx.m("editor.select.profile"));
        saveStatus.setFont(Ui.font(Font.PLAIN, 12f));
        saveStatus.setForeground(Ui.MUTED);
        btnShare = Ui.iconButton("share-2", ctx.m("app.share.tooltip"), false, this::share);
        btnDownload = Ui.iconButton("file-down", ctx.m("tooltip.export"), false, this::download);
        btnDelete = Ui.iconButton("trash", ctx.m("tooltip.delete.profile"), true, () -> {
            if (active != null) deleteItem(active);
        });
        autosave = new Autosave(ctx, editor, saveStatus, (id, content) -> {
            Store.Item r = ctx.store(s -> s.updateContent(Store.Kind.HOSTS, id, content));
            if (r == null) return Autosave.Result.retry(ctx.m("status.save.failed"));
            if (active != null && active.id.equals(id)) {
                active = r;
                metaUpdated.setText(fmt(r.updatedAt));
            }
            updatePreviews();
            return Autosave.Result.OK;
        });
        autosave.bindSaveKey(this);
        editor.onEdit(() -> autosave.changed(editor.getText()));

        uaPreset = presetButton(ctx.m("hosts.ua.preset.btn"), true);
        urlPreset = presetButton(ctx.m("hosts.url.preset.btn"), false);
        extraScroll = new JScrollPane(extraArea);

        JSplitPane split = Ui.rightSplit(ctx, buildEditor(), buildLauncher());
        add(Ui.sidebarSplit(ctx, buildSidebar(), split), BorderLayout.CENTER);

        loadConf();
        wireOptions();
        ctx.onSettingsChanged(this::updatePreviews);
        reload();
        List<Store.Item> items = ctx.read(() -> ctx.rawStore().list(Store.Kind.HOSTS));
        items.stream().filter(i -> i.selected).findFirst().or(() -> items.stream().findFirst())
                .ifPresent(i -> activate(i.id));
    }

    // ── Layout ──────────────────────────────────────────────────

    private JComponent buildSidebar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        JButton add = Ui.iconButton("square-plus", ctx.m("tooltip.new.profile"), false, this::addItem);
        JButton importBtn = Ui.iconButton("circle-fading-plus", ctx.m("app.tooltip.import.profile"), false, this::importFiles);
        JButton addFolder = Ui.iconButton("folder-plus", ctx.m("tooltip.new.folder"), false, () -> {
            ctx.store(s -> s.createFolder(Store.Kind.HOSTS));
            tree.render();
        });
        p.add(Ui.header(Ui.caps(ctx.m("hosts.sidebar.header")), add, importBtn, addFolder), BorderLayout.NORTH);
        p.add(tree, BorderLayout.CENTER);
        JPanel meta = Ui.meta();
        meta.add(countLabel, BorderLayout.WEST);
        p.add(meta, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildEditor() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        JPanel titleRow = new JPanel(new BorderLayout(10, 0));
        titleRow.setOpaque(false);
        titleRow.add(editorTitle, BorderLayout.WEST);
        p.add(Ui.header(titleRow, btnShare, btnDownload, btnDelete), BorderLayout.NORTH);

        JLabel empty = new JLabel(ctx.m("hosts.empty"), SwingConstants.CENTER);
        empty.setForeground(Ui.MUTED);
        empty.setFont(Ui.font(Font.PLAIN, 13f));
        editorCards.add(editor.scroll, "editor");
        editorCards.add(empty, "empty");
        editorCards.setMinimumSize(new Dimension(10, 80));

        // Merged preview (2+ selected profiles), then the meta bar.
        mergedSection.setBackground(Color.WHITE);
        mergedSection.setBorder(new EmptyBorder(6, 14, 10, 14));
        mergedSection.setPreferredSize(new Dimension(10, MERGED_H));
        mergedSection.setMinimumSize(new Dimension(10, 80));
        JPanel mergedHead = new JPanel(new BorderLayout(6, 0));
        mergedHead.setOpaque(false);
        mergedLabel.setFont(Ui.font(Font.BOLD, 11f));
        mergedLabel.setForeground(Ui.MUTED);
        mergedLabel.setIcon(Ui.icon("layers-plus", 12, Ui.MUTED));
        mergedHead.add(mergedLabel, BorderLayout.CENTER);
        JButton saveMerged = Ui.iconButton("copy-plus", ctx.m("tooltip.save.merged"), false, this::saveMerged);
        saveMerged.setPreferredSize(new Dimension(26, 22));
        mergedHead.add(saveMerged, BorderLayout.EAST);
        mergedSection.add(mergedHead, BorderLayout.NORTH);
        mergedSection.add(rounded(Ui.previewScroll(mergedBody)), BorderLayout.CENTER);
        mergedSection.setVisible(false);

        JPanel meta = Ui.meta();
        meta.add(metaUpdated, BorderLayout.WEST);
        // Autosave state (editing / saved) at the right end of the date bar.
        meta.add(saveStatus, BorderLayout.EAST);

        // Editor above, merged preview below; the line between them can be dragged. The split hides
        // its divider while the preview is hidden, and the preview keeps its height on window resize.
        mergedSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorCards, mergedSection);
        Ui.lineDivider(mergedSplit);
        mergedSplit.setContinuousLayout(true);
        mergedSplit.setResizeWeight(1);
        mergedSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (mergedSection.isVisible() && mergedSection.getHeight() > 0) mergedHeight = mergedSection.getHeight();
        });
        p.add(mergedSplit, BorderLayout.CENTER);
        p.add(meta, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildLauncher() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setMinimumSize(new Dimension(420, 10));
        p.add(Ui.header(Ui.caps(ctx.m("hosts.chrome.launcher"))), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Color.WHITE);

        // Options
        JPanel opts = column(new EmptyBorder(4, 0, 6, 0));
        opts.add(option(swDevTools, "--auto-open-devtools-for-tabs"));
        opts.add(option(swIncognito, "--incognito"));
        style(uaField, ctx.m("hosts.ua.placeholder"));
        opts.add(option(swUa, ctx.m("hosts.ua.label"), uaField, uaPreset));
        uddDisplay.setFont(Ui.mono(12f));
        uddDisplay.setForeground(Ui.PREVIEW_TEXT);
        uddDisplay.setOpaque(true);
        uddDisplay.setBackground(new Color(0xf1f5ec));
        uddDisplay.setBorder(new CompoundBorder(new LineBorder(new Color(0xd4e8b8), 1, true), new EmptyBorder(4, 8, 4, 8)));
        opts.add(option(swUdd, "--user-data-dir", uddDisplay));
        style(extraLine, "—");
        extraLine.setEditable(false);
        extraLine.setFont(Ui.mono(12f));
        extraLine.setBackground(new Color(0xe9ecef));
        opts.add(option(swExtra, "extraArgs", extraLine));
        extraArea.setFont(Ui.mono(12f));
        extraArea.setLineWrap(false);
        extraScroll.setBorder(new LineBorder(Ui.BORDER, 1, true));
        JPanel extraWrap = new JPanel(new BorderLayout());
        extraWrap.setOpaque(false);
        extraWrap.setBorder(new EmptyBorder(0, 12, 6, 12));
        extraWrap.add(extraScroll);
        extraWrap.setAlignmentX(LEFT_ALIGNMENT);
        extraWrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        extraWrap.setPreferredSize(new Dimension(10, 110));
        opts.add(extraWrap);
        extraScroll.putClientProperty("wrap", extraWrap);
        style(urlField, ctx.m("hosts.url.placeholder"));
        opts.add(option(swUrl, ctx.m("hosts.chrome.open.label"), urlField, urlPreset));
        body.add(opts);

        // Routing switches and launch buttons
        JPanel routing = column(new CompoundBorder(Ui.topLine(), new EmptyBorder(4, 0, 10, 0)));
        routing.add(option(swHrr, "--host-resolver-rules", infoButton(() -> ctx.plain("hosts.resolver.rules.info"))));
        routing.add(option(swProxy, "--proxy-server", infoButton(() -> ctx.plain("app.hosts.proxy.server.info", fwdPort()))));
        JPanel btnRow = new JPanel(new GridBagLayout());
        btnRow.setOpaque(false);
        btnRow.setBorder(new EmptyBorder(6, 12, 0, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 6);
        // Launch | Win | Mac share the row 6:2:2: with no preferred width of their own, GridBagLayout
        // hands out the whole width by weight.
        launchBtn = Ui.primary(ctx.m("app.chrome.launch"), Ui.image("chrome.svg", 15, 15), this::launch);
        JButton launch = launchBtn;
        c.weightx = 6;
        btnRow.add(ratioWidth(launch), c);
        JButton cpWin = Ui.secondary("Win", "copy", () -> showDirectCmd(true));
        cpWin.setToolTipText(ctx.m("tooltip.copy.win.chrome"));
        c.weightx = 2;
        btnRow.add(ratioWidth(cpWin), c);
        c.insets = new Insets(0, 0, 0, 0);
        JButton cpMac = Ui.secondary("Mac", "copy", () -> showDirectCmd(false));
        cpMac.setToolTipText(ctx.m("tooltip.copy.mac.chrome"));
        btnRow.add(ratioWidth(cpMac), c);
        btnRow.setAlignmentX(LEFT_ALIGNMENT);
        btnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        routing.add(btnRow);
        body.add(routing);

        // Chrome parameters preview
        JPanel args = column(new CompoundBorder(Ui.topLine(), new EmptyBorder(12, 12, 6, 12)));
        args.add(sectionLabel(ctx.m("hosts.chrome.args")));
        args.add(Box.createVerticalStrut(6));
        JComponent argsBox = rounded(Ui.previewScroll(argsPreview));
        argsBox.setAlignmentX(LEFT_ALIGNMENT);
        argsBox.setPreferredSize(new Dimension(10, 80));
        argsBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        args.add(argsBox);
        body.add(args);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(body, BorderLayout.NORTH);

        // --host-resolver-rules preview fills the rest
        JPanel rules = new JPanel(new BorderLayout(0, 6));
        rules.setBackground(Color.WHITE);
        rules.setBorder(new EmptyBorder(12, 12, 10, 12));
        rules.add(sectionLabel("--host-resolver-rules"), BorderLayout.NORTH);
        rules.add(rounded(Ui.previewScroll(rulesPreview)), BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Color.WHITE);
        center.add(top, BorderLayout.NORTH);
        center.add(rules, BorderLayout.CENTER);
        p.add(center, BorderLayout.CENTER);

        JPanel meta = Ui.meta();
        meta.add(hostsCount, BorderLayout.WEST);
        p.add(meta, BorderLayout.SOUTH);
        return p;
    }

    private static JPanel column(javax.swing.border.Border border) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(border);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    /** {@code .hosts-browser-option}: switch, label, optional field and button. */
    private JComponent option(Switch sw, String label, JComponent... extra) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 12, 6, 12));
        row.add(sw);
        row.add(Box.createHorizontalStrut(8));
        JLabel l = new JLabel(label);
        l.setFont(Ui.font(Font.PLAIN, 13f));
        l.setForeground(Ui.PREVIEW_TEXT);
        row.add(l);
        boolean fills = false;
        for (JComponent e : extra) {
            row.add(Box.createHorizontalStrut(e instanceof JButton && !(e.getClientProperty("info") != null) ? 4 : 8));
            if (e instanceof JTextField || e instanceof JLabel) {
                e.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                fills = true;
            }
            row.add(e);
        }
        if (!fills) row.add(Box.createHorizontalGlue());
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        row.setPreferredSize(new Dimension(10, 42));
        return row;
    }

    private static void style(JTextField f, String placeholder) {
        f.setFont(Ui.font(Font.PLAIN, 12.5f));
        f.putClientProperty("JTextField.placeholderText", placeholder);
        f.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878; borderColor:#dee2e6; margin:3,8,3,8");
        f.setPreferredSize(new Dimension(100, 30));
    }

    private JButton infoButton(java.util.function.Supplier<String> text) {
        JButton b = Ui.iconButton("info", null, false, null);
        b.setIcon(Ui.icon("info", 12, new Color(0, 0, 0, 90)));
        b.setRolloverIcon(Ui.icon("info", 12, new Color(0, 0, 0, 160)));
        b.setPreferredSize(new Dimension(20, 20));
        b.setMaximumSize(new Dimension(20, 20));
        b.putClientProperty("info", true);
        b.addActionListener(e -> {
            JTextArea a = new JTextArea(text.get());
            a.setLineWrap(true);
            a.setWrapStyleWord(true);
            a.setEditable(false);
            a.setFont(Ui.font(Font.PLAIN, 12f));
            a.setForeground(Ui.PREVIEW_TEXT);
            a.setBorder(new EmptyBorder(8, 10, 8, 10));
            a.setSize(new Dimension(320, Short.MAX_VALUE));
            a.setPreferredSize(new Dimension(320, a.getPreferredSize().height));
            JPopupMenu pop = new JPopupMenu();
            pop.setLayout(new BorderLayout());
            pop.add(a);
            pop.show(b, 0, b.getHeight() + 2);
        });
        return b;
    }

    /** Zero preferred/minimum width (height 34) so the row's weights alone set the button's width. */
    private static JButton ratioWidth(JButton b) {
        b.setPreferredSize(new Dimension(0, 34));
        b.setMinimumSize(new Dimension(0, 34));
        return b;
    }

    private JButton presetButton(String tooltip, boolean ua) {
        JButton b = new JButton(Ui.icon("list", 15, Ui.PREVIEW_TEXT));
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.setBackground(Color.WHITE);
        b.putClientProperty("FlatLaf.style", "arc:6; borderColor:#dee2e6; hoverBackground:#f8f9fa");
        b.setPreferredSize(new Dimension(32, 30));
        b.setMaximumSize(new Dimension(32, 30));
        b.addActionListener(e -> {
            List<Store.Preset> presets = ctx.read(() -> List.copyOf(ua
                    ? ctx.rawStore().data.settings.uaPresets : ctx.rawStore().data.settings.urlPresets));
            JPopupMenu m = new JPopupMenu();
            PresetPreview preview = new PresetPreview(m);
            m.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent ev) {}
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent ev) { preview.hide(); }
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent ev) { preview.hide(); }
            });
            if (presets.isEmpty()) {
                JMenuItem none = new JMenuItem(ctx.m(ua ? "hosts.ua.preset.empty" : "hosts.url.preset.empty"));
                none.setEnabled(false);
                m.add(none);
            }
            for (Store.Preset p : presets) {
                JMenuItem it = new JMenuItem(p.name);
                // .ua-preset-menu .dropdown-item: inset 6px each side, rounded BC100 hover.
                it.putClientProperty("FlatLaf.style", "selectionInsets:0,6,0,6; selectionArc:6;"
                        + " selectionBackground:#dff2b8; selectionForeground:#3a6b10");
                // Full value in a box pinned above the menu instead of the dark tooltip.
                it.getModel().addChangeListener(ev -> {
                    if (it.isArmed()) preview.show(it, p.value);
                    else preview.hideIf(p.value);
                });
                it.addActionListener(a -> {
                    JTextField f = ua ? uaField : urlField;
                    f.setText(p.value);
                    putConf(ua ? "user_agent" : "open_url", p.value);
                    updatePreviews();
                });
                m.add(it);
            }
            m.show(b, b.getWidth() - m.getPreferredSize().width, b.getHeight() + 2);
        });
        return b;
    }

    /**
     * The hovered preset's full value in a white box just left of the open preset menu, its top
     * level with the hovered item, shown through a {@link Popup} so it does not take the menu's
     * selection away.
     */
    private static final class PresetPreview {
        private static final int MIN_WIDTH = 520;
        private final JPopupMenu menu;
        private Popup popup;
        private String shown;

        PresetPreview(JPopupMenu menu) {
            this.menu = menu;
        }

        void show(JMenuItem item, String value) {
            hide();
            if (!menu.isShowing()) return;
            JTextArea a = new JTextArea(value);
            a.setLineWrap(true);
            a.setWrapStyleWord(true);
            a.setEditable(false);
            a.setFocusable(false);
            a.setFont(Ui.mono(12f));
            a.setForeground(Ui.PREVIEW_TEXT);
            a.setBackground(Color.WHITE);
            a.setBorder(new CompoundBorder(new LineBorder(Ui.BC300, 1, true), new EmptyBorder(6, 10, 6, 10)));
            int w = MIN_WIDTH;
            a.setSize(new Dimension(w, Short.MAX_VALUE));
            a.setPreferredSize(new Dimension(w, a.getPreferredSize().height));
            int x = menu.getLocationOnScreen().x - w - 4;
            int y = item.getLocationOnScreen().y;
            popup = PopupFactory.getSharedInstance().getPopup(menu, a, x, y);
            popup.show();
            shown = value;
        }

        void hideIf(String value) {
            if (value.equals(shown)) hide();
        }

        void hide() {
            if (popup != null) popup.hide();
            popup = null;
            shown = null;
        }
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(Ui.font(Font.BOLD, 11f));
        l.setForeground(Ui.MUTED);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    /** Light gray rounded frame around a preview box. */
    private static JComponent rounded(JComponent c) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Ui.META_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(2, 2, 2, 2));
        p.add(c);
        return p;
    }

    // ── Options (hostsConf) ─────────────────────────────────────

    private String conf(String key) {
        return ctx.read(() -> ctx.rawStore().data.hostsConf.get(key));
    }

    private void putConf(String key, Object value) {
        ctx.storeRun(s -> {
            s.data.hostsConf.put(key, String.valueOf(value));
            s.save();
        });
    }

    private void loadConf() {
        swProxy.setOn("true".equals(conf("proxy_server_enabled")));
        urlField.setText(orEmpty(conf("open_url")));
        swUdd.setOn("true".equals(conf("user_data_dir_enabled")));
        swUrl.setOn(!"false".equals(conf("open_url_enabled")));
        swHrr.setOn(!"false".equals(conf("host_resolver_rules_enabled")));
        swIncognito.setOn("true".equals(conf("incognito")));
        swDevTools.setOn("true".equals(conf("dev_tools")));
        uaField.setText(orEmpty(conf("user_agent")));
        swUa.setOn("true".equals(conf("user_agent_enabled")));
        String extra = conf("extra_chrome_args");
        extraArea.setText(extra == null || extra.isEmpty() ? DEFAULT_EXTRA_ARGS : extra);
        swExtra.setOn("true".equals(conf("extra_chrome_args_enabled")));
        applyEnabledStates();
    }

    private void applyEnabledStates() {
        uaField.setEnabled(swUa.isOn());
        uaPreset.setEnabled(swUa.isOn());
        urlField.setEnabled(swUrl.isOn());
        urlPreset.setEnabled(swUrl.isOn());
        uddDisplay.setForeground(swUdd.isOn() ? Ui.PREVIEW_TEXT : new Color(0xadb5bd));
        uddDisplay.setText(ChromeArgs.DEFAULT_USER_DATA_DIR);
        uddDisplay.setEnabled(swUdd.isOn());
        boolean ex = swExtra.isOn();
        extraLine.setVisible(!ex);
        ((JComponent) extraScroll.getClientProperty("wrap")).setVisible(ex);
        updateExtraLine();
        revalidate();
    }

    private void updateExtraLine() {
        List<String> lines = new ArrayList<>();
        for (String l : extraArea.getText().split("\n")) if (!l.isBlank()) lines.add(l.strip());
        extraLine.setText(String.join("  ", lines));
        extraLine.setCaretPosition(0);
    }

    private void wireOptions() {
        swDevTools.onChange(on -> {
            putConf("dev_tools", on);
            updatePreviews();
        });
        swIncognito.onChange(on -> {
            putConf("incognito", on);
            updatePreviews();
        });
        swUa.onChange(on -> {
            putConf("user_agent_enabled", on);
            applyEnabledStates();
            updatePreviews();
        });
        swUdd.onChange(on -> {
            // Turning it on asks first (wireConfirmToggle in util.js).
            if (on && !OeDialog.confirm(ctx, ctx.m("modal.udd.warn.title"), ctx.plain("hosts.udd.warn"))) {
                swUdd.setOn(false);
                return;
            }
            putConf("user_data_dir_enabled", on);
            applyEnabledStates();
            updatePreviews();
        });
        swExtra.onChange(on -> {
            putConf("extra_chrome_args_enabled", on);
            applyEnabledStates();
            updatePreviews();
        });
        swUrl.onChange(on -> {
            putConf("open_url_enabled", on);
            applyEnabledStates();
            updatePreviews();
        });
        // --host-resolver-rules and --proxy-server exclude each other.
        swHrr.onChange(on -> {
            putConf("host_resolver_rules_enabled", on);
            if (on && swProxy.isOn()) {
                swProxy.setOn(false);
                putConf("proxy_server_enabled", false);
            }
            updatePreviews();
        });
        swProxy.onChange(on -> {
            putConf("proxy_server_enabled", on);
            if (on && swHrr.isOn()) {
                swHrr.setOn(false);
                putConf("host_resolver_rules_enabled", false);
            }
            updatePreviews();
        });
        debounced(uaField, () -> putConf("user_agent", uaField.getText()));
        debounced(urlField, () -> putConf("open_url", urlField.getText()));
        debounced(extraArea, () -> putConf("extra_chrome_args", extraArea.getText()));
    }

    /** Preview on every keystroke, store 800 ms after the last one. */
    private void debounced(javax.swing.text.JTextComponent f, Runnable save) {
        Timer t = new Timer(800, e -> save.run());
        t.setRepeats(false);
        f.getDocument().addDocumentListener(new DocumentListener() {
            void ch() {
                updateExtraLine();
                updatePreviews();
                t.restart();
            }

            public void insertUpdate(DocumentEvent e) {
                ch();
            }

            public void removeUpdate(DocumentEvent e) {
                ch();
            }

            public void changedUpdate(DocumentEvent e) {
            }
        });
    }

    // ── Profiles ────────────────────────────────────────────────

    private void reload() {
        tree.render();
        updatePreviews();
    }

    private void activate(String id) {
        autosave.flush();
        Store.Item it = ctx.read(() -> ctx.rawStore().list(Store.Kind.HOSTS).stream()
                .filter(i -> i.id.equals(id)).findFirst().orElse(null));
        autosave.activate(it == null ? null : it.id, it == null ? null : it.content);
        active = it;
        tree.setActive(it == null ? null : it.id);
        editorTitle.setText(it == null ? ctx.m("editor.select.profile") : it.name);
        editor.setText(it == null ? "" : it.content);
        editor.setEditable(it != null);
        btnDelete.setEnabled(it != null);
        btnDownload.setEnabled(it != null);
        btnShare.setEnabled(it != null);
        shareIcon(it != null && it.shared);
        metaUpdated.setText(it == null ? "—" : fmt(it.updatedAt));
    }

    /** The share button is green while the open profile is shared. */
    private void shareIcon(boolean shared) {
        btnShare.setIcon(Ui.icon("share-2", 15, shared ? Ui.BC500 : Ui.ICON));
        btnShare.setRolloverIcon(Ui.icon("share-2", 16, shared ? Ui.BC700 : new Color(0, 0, 0, 178)));
    }

    /**
     * Sharing of the open profile for SwitchHosts and the like: a switch, the URL the web service
     * (ShareServer) serves it at (with a choice of this PC's address while it is on the network), and
     * Copy. Turning the switch on or off takes effect at once.
     */
    /** The share dialog of the open profile (also used by MainWindow's screenshot mode). */
    void showShare() {
        share();
    }

    private void share() {
        if (active == null) return;
        autosave.flush();
        String id = active.id;
        int port = ctx.read(() -> ctx.rawStore().data.settings.shareServerPort);
        String token = ctx.store(s -> s.shareToken(id));
        if (token == null) return;
        String url = tricatch.oehostsapp.web.ShareServer.url(port, token, active.name);
        OeDialog.open(ctx, ctx.m("app.share.title", active.name), d -> {
            Switch sw = new Switch(active.shared, Ui.BC500);
            JLabel swLabel = new JLabel(ctx.m("app.share.switch"));
            swLabel.setFont(Ui.font(Font.BOLD, 13f));
            JPanel swRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            swRow.setOpaque(false);
            swRow.add(sw);
            swRow.add(swLabel);

            // The command box of the Chrome command dialog, one line.
            JTextArea urlField = OeDialog.script(url);
            urlField.setLineWrap(false);
            urlField.setColumns(0);

            JComponent desc = OeDialog.note(ctx.m("app.share.desc"));
            Dialogs.fixWidth(desc, 580);
            List<JComponent> parts = new ArrayList<>(List.of(desc, swRow));
            // On the network too: the URL can name this PC by one of the addresses the web service
            // answers on, for SwitchHosts or an agent on another machine. 127.0.0.1 stays the default.
            JComboBox<String> host = null;
            if (tricatch.oehostsapp.web.ShareServer.lan()) {
                JComboBox<String> h = new JComboBox<>();
                h.setFont(Ui.font(Font.PLAIN, 13f));
                h.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878");
                h.setFocusable(false);
                h.addItem(tricatch.oehostsapp.proxy.ForwardProxy.LOOPBACK);
                String bound = tricatch.oehostsapp.web.ShareServer.bind();
                for (tricatch.oehostsapp.web.PhoneSetup.Lan l : tricatch.oehostsapp.web.PhoneSetup.addresses()) {
                    if (tricatch.oehostsapp.proxy.ForwardProxy.ALL.equals(bound) || bound.equals(l.ip())) h.addItem(l.ip());
                }
                h.addActionListener(e -> urlField.setText(tricatch.oehostsapp.web.ShareServer.url(
                        String.valueOf(h.getSelectedItem()), port, token, active.name)));
                JLabel hostLabel = new JLabel(ctx.m("app.share.host"));
                hostLabel.setFont(Ui.font(Font.BOLD, 12.5f));
                hostLabel.setForeground(Ui.MUTED);
                JPanel hostRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                hostRow.setOpaque(false);
                hostRow.add(hostLabel);
                hostRow.add(h);
                parts.add(hostRow);
                host = h;
            }
            parts.add(urlField);
            if (tricatch.oehostsapp.web.ShareServer.state() == tricatch.oehostsapp.web.ShareServer.State.FAILED) {
                JComponent w = OeDialog.warn(ctx.m("app.share.server.failed", port,
                        String.valueOf(tricatch.oehostsapp.web.ShareServer.error())), null);
                Dialogs.fixWidth(w, 580);
                parts.add(w);
            }
            d.body(OeDialog.stack(12, parts.toArray(new JComponent[0])));

            JButton copy = d.button(ctx.m("app.share.copy"), true, () -> {
                OeDialog.copy(urlField.getText());
                ctx.toast(ctx.m("app.share.copied"));
            });
            d.button(ctx.m("btn.close"), false, d::dispose);
            JComboBox<String> hostChoice = host;
            Runnable sync = () -> {
                urlField.setEnabled(sw.isOn());
                copy.setEnabled(sw.isOn());
                if (hostChoice != null) hostChoice.setEnabled(sw.isOn());
            };
            sw.onChange(on -> {
                Store.Item r = ctx.store(s -> s.setShared(id, on));
                if (r == null) return;
                if (active != null && active.id.equals(id)) {
                    active = r;
                    shareIcon(on);
                }
                tree.render();
                sync.run();
            });
            sync.run();
        });
    }

    private void addItem() {
        Store.Item it = ctx.store(s -> s.create(Store.Kind.HOSTS, ctx.lang()));
        if (it == null) return;
        reload();
        activate(it.id);
    }

    private void deleteItem(Store.Item it) {
        if (!OeDialog.confirm(ctx, ctx.m("tooltip.delete.profile"), ctx.m("confirm.delete.profile"))) return;
        if (active != null && active.id.equals(it.id)) {
            autosave.discard();
            autosave.activate(null, null);
        }
        ctx.storeRun(s -> s.delete(Store.Kind.HOSTS, it.id));
        if (active != null && active.id.equals(it.id)) activate(null);
        reload();
    }

    private void download() {
        if (active == null) return;
        if (!ItemFiles.save(this, active.name, ItemFiles.HOSTS_EXT, editor.getText())) ctx.toast(ctx.m("status.save.failed"));
    }

    /** Each chosen .oehosts file becomes a new profile named after the file; the last one is opened. */
    private void importFiles() {
        List<ItemFiles.Loaded> files;
        try {
            files = ItemFiles.open(this, ItemFiles.HOSTS_EXT);
        } catch (Exception e) {
            ctx.toast(ctx.m("app.import.failed"));
            return;
        }
        if (files.isEmpty()) return;
        autosave.flush();
        Store.Item last = null;
        for (ItemFiles.Loaded f : files) {
            Store.Item it = ctx.store(s -> s.createFrom(Store.Kind.HOSTS, f.name(), f.content()));
            if (it != null) last = it;
        }
        if (last == null) return;
        reload();
        activate(last.id);
        ctx.toast(ctx.m("app.item.imported", files.size()));
    }

    // ── Rules and previews ──────────────────────────────────────

    private record Entry(String host, String ip, boolean active) {}

    private int fwdPort() {
        return ctx.read(() -> ctx.rawStore().data.settings.fwdproxyPort);
    }

    /** mergeSelectedProfiles: every entry of the selected profiles, the first per host active. */
    private List<Entry> entries(boolean resolve) {
        List<Store.Item> sel = ctx.read(() -> ctx.rawStore().list(Store.Kind.HOSTS).stream().filter(i -> i.selected).toList());
        Set<String> seen = new HashSet<>();
        List<Entry> out = new ArrayList<>();
        for (Store.Item i : sel) {
            String content = resolve ? HostsRules.resolvePlaceholders(i.content) : i.content;
            for (HostsRules.Rule r : HostsRules.parse(content)) out.add(new Entry(r.host(), r.ip(), seen.add(r.host())));
        }
        return out;
    }

    private ChromeArgs.Options options(boolean win) {
        ChromeArgs.Options o = new ChromeArgs.Options();
        Map<String, String> rules = new LinkedHashMap<>();
        for (Entry e : entries(true)) if (e.active) rules.put(e.host, e.ip);
        List<String> maps = new ArrayList<>();
        rules.forEach((h, ip) -> maps.add("MAP " + h + " " + ip));
        o.devTools = swDevTools.isOn();
        o.incognito = swIncognito.isOn();
        o.extraArgsEnabled = swExtra.isOn();
        o.extraArgs = extraArea.getText();
        o.hrrEnabled = swHrr.isOn();
        o.rulesStr = String.join(",", maps);
        o.proxyServerEnabled = swProxy.isOn();
        o.proxyServer = HostsRules.THIS_PC + ":" + fwdPort();
        o.uddEnabled = swUdd.isOn();
        o.userDataDir = ChromeArgs.resolveUserDataDir(ChromeArgs.DEFAULT_USER_DATA_DIR, win);
        o.uaEnabled = swUa.isOn();
        o.userAgent = uaField.getText().strip();
        o.urlEnabled = swUrl.isOn();
        o.url = urlField.getText().strip();
        return o;
    }

    void updatePreviews() {
        int total = ctx.read(() -> ctx.rawStore().list(Store.Kind.HOSTS).size());
        countLabel.setText(ctx.m("hosts.sidebar.count", total));
        ((CardLayout) editorCards.getLayout()).show(editorCards, total == 0 ? "empty" : "editor");

        List<Entry> all = entries(true);
        long activeCount = all.stream().filter(Entry::active).count();
        hostsCount.setText("Hosts: " + (swHrr.isOn() ? activeCount : 0));
        if (!swHrr.isOn()) {
            plain(rulesPreview, ctx.m("hosts.scheme.disabled"));
        } else if (all.isEmpty()) {
            plain(rulesPreview, ctx.m("hosts.scheme.preview.empty"));
        } else {
            lines(rulesPreview, all, true);
        }
        ChromeArgs.Options o = options(WIN);
        setMono(argsPreview, String.join("\n", ChromeArgs.previewLines(o, activeCount > 0)));

        List<Store.Item> sel = ctx.read(() -> ctx.rawStore().list(Store.Kind.HOSTS).stream().filter(i -> i.selected).toList());
        boolean showMerged = sel.size() >= 2;
        if (showMerged != mergedSection.isVisible()) {
            mergedSection.setVisible(showMerged);
            // Reopen at the height it had last time (dragged or the default).
            if (showMerged) SwingUtilities.invokeLater(() -> mergedSplit.setDividerLocation(
                    Math.max(80, mergedSplit.getHeight() - mergedHeight - mergedSplit.getDividerSize())));
        }
        if (sel.size() >= 2) {
            mergedLabel.setText(String.join(", ", sel.stream().map(i -> i.name).toList()));
            if (all.isEmpty()) plain(mergedBody, ctx.m("hosts.scheme.preview.empty"));
            else lines(mergedBody, all, false);
        }
        revalidate();
        repaint();
    }

    private static void plain(JTextPane p, String text) {
        p.setFont(Ui.font(Font.PLAIN, 12.5f));
        p.setText(text);
        Ui.previewLineHeight(p);
    }

    private static void setMono(JTextPane p, String text) {
        p.setFont(Ui.mono(12f));
        p.setText(text);
        Ui.previewLineHeight(p);
        p.setCaretPosition(0);
    }

    /** MAP lines (or "ip host" lines), duplicates struck through and faded. */
    private static void lines(JTextPane p, List<Entry> entries, boolean map) {
        p.setFont(Ui.mono(12f));
        p.setText("");
        StyledDocument d = p.getStyledDocument();
        SimpleAttributeSet normal = new SimpleAttributeSet();
        SimpleAttributeSet dup = new SimpleAttributeSet();
        Color faded = new Color(0x49, 0x50, 0x57, 115);
        StyleConstants.setForeground(dup, faded);
        p.getHighlighter().removeAllHighlights();
        try {
            // Struck ranges are highlighted once all text is in: a highlight ending at the end of
            // the document grows with every insert there, so one added while appending would also
            // strike all the lines that follow.
            List<int[]> struck = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                String line = map ? "MAP " + e.host + " " + e.ip : e.ip + " " + e.host;
                if (i > 0) d.insertString(d.getLength(), "\n", normal);
                int start = d.getLength();
                d.insertString(start, line, e.active ? normal : dup);
                if (!e.active) struck.add(new int[]{start, start + line.length()});
            }
            for (int[] r : struck) p.getHighlighter().addHighlight(r[0], r[1], new StrikePainter(faded));
        } catch (javax.swing.text.BadLocationException ignored) {
            // cannot happen: always appending at the end
        }
        Ui.previewLineHeight(p);
        p.setCaretPosition(0);
    }

    /**
     * A strikethrough drawn through the middle of the glyphs. The text attribute's line sits where
     * the font says, and the composite monospace font (Consolas with a Hangul fallback) puts it
     * visibly off-centre; here it goes halfway between the baseline and the middle of digits and
     * lowercase letters' height, per visual line (a long line may wrap).
     */
    private static final class StrikePainter implements javax.swing.text.Highlighter.HighlightPainter {
        private final Color color;

        StrikePainter(Color color) {
            this.color = color;
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, javax.swing.text.JTextComponent c) {
            try {
                Graphics2D g2 = (Graphics2D) g;
                // The font the text view draws with (from the document's attributes; with the
                // Hangul fallback its ascent differs from the component's font), so the baseline
                // below is where the glyphs really sit.
                Font font = c.getFont();
                if (c.getDocument() instanceof StyledDocument sd) {
                    font = sd.getFont(sd.getCharacterElement(p0).getAttributes());
                }
                FontMetrics fm = c.getFontMetrics(font);
                var frc = g2.getFontRenderContext();
                // Middle of the ink of "0" (cap/digit height) and "x" (x-height), averaged.
                double mid0 = centre(font.createGlyphVector(frc, "0").getVisualBounds());
                double midX = centre(font.createGlyphVector(frc, "x").getVisualBounds());
                // Negative (above the baseline); floor, as the 1px line is drawn below its y.
                int offset = (int) Math.floor((mid0 + midX) / 2);
                g2.setColor(color);
                java.awt.geom.Rectangle2D first = c.modelToView2D(p0);
                for (int i = p0 + 1; i <= p1; i++) {
                    java.awt.geom.Rectangle2D r = i < p1 ? c.modelToView2D(i) : null;
                    if (r != null && Math.abs(r.getY() - first.getY()) < 1) continue;
                    // Draw from segStart to the end of this visual line.
                    java.awt.geom.Rectangle2D end = c.modelToView2D(i - 1);
                    int x0 = (int) first.getX();
                    int x1 = (int) (end.getX() + fm.charWidth(c.getDocument().getText(i - 1, 1).charAt(0)));
                    // Where GlyphPainter draws the text: line top + ascent + leading.
                    int baseline = (int) first.getY() + fm.getHeight() - fm.getDescent();
                    line(g2, x0, x1, baseline + offset);
                    if (r == null) break;
                    first = r;
                }
            } catch (javax.swing.text.BadLocationException ignored) {
                // the text changed under the highlight: nothing to draw
            }
        }

        /**
         * A horizontal line from x0 to x1 just below y, snapped to device pixels. A 1-unit rect in
         * user space is 1.25 or 1.5 device pixels at 125% / 150% scaling and lands on a whole pixel
         * on some text lines and a half pixel on others, so the strokes alternated between thin and
         * thick; here every one is round(scale) device pixels thick.
         */
        private static void line(Graphics2D g2, int x0, int x1, int y) {
            java.awt.geom.AffineTransform t = g2.getTransform();
            int thick = Math.max(1, (int) Math.round(t.getScaleY()));
            int dx0 = (int) Math.round(t.getTranslateX() + x0 * t.getScaleX());
            int dx1 = (int) Math.round(t.getTranslateX() + x1 * t.getScaleX());
            int dy = (int) Math.round(t.getTranslateY() + y * t.getScaleY());
            Graphics2D d = (Graphics2D) g2.create();
            try {
                d.setTransform(new java.awt.geom.AffineTransform());
                d.fillRect(dx0, dy, Math.max(0, dx1 - dx0), thick);
            } finally {
                d.dispose();
            }
        }

        private static double centre(java.awt.geom.Rectangle2D ink) {
            return ink.getY() + ink.getHeight() / 2;
        }
    }

    private void saveMerged() {
        List<Store.Item> sel = ctx.read(() -> ctx.rawStore().list(Store.Kind.HOSTS).stream().filter(i -> i.selected).toList());
        if (sel.size() < 2) return;
        List<String> lines = new ArrayList<>();
        for (Entry e : entries(false)) if (e.active) lines.add(e.ip + " " + e.host);
        String name = String.join(" + ", sel.stream().map(i -> i.name).toList());
        Store.Item it = ctx.store(s -> {
            Store.Item n = s.create(Store.Kind.HOSTS, ctx.lang());
            try {
                s.rename(Store.Kind.HOSTS, n.id, name);
            } catch (Store.StoreException ignored) {
                // keep the generated name when the merged one is taken
            }
            return s.updateContent(Store.Kind.HOSTS, n.id, String.join("\n", lines));
        });
        if (it == null) {
            ctx.toast(ctx.m("toast.merge.save.failed"));
            return;
        }
        reload();
        activate(it.id);
        ctx.toast(ctx.m("toast.merge.saved"));
    }

    // ── Launch ──────────────────────────────────────────────────

    /**
     * Starts Chrome with the panel's flags ({@link ChromeLauncher}); a Chrome already running on the
     * same profile is closed first once the user agrees. The process work runs off the UI thread.
     */
    private void launch() {
        autosave.flush();
        ChromeArgs.Options o = options(WIN);
        List<String> words = ChromeLauncher.words(ChromeArgs.parts(o), o.urlEnabled ? o.url : null);
        launchBtn.setEnabled(false);
        runLaunch(words, false);
    }

    private void runLaunch(List<String> words, boolean closeRunning) {
        new Thread(() -> {
            ChromeLauncher.Result r = ChromeLauncher.launch(words, closeRunning);
            SwingUtilities.invokeLater(() -> {
                if (r == ChromeLauncher.Result.RUNNING) {
                    if (OeDialog.confirm(ctx, ctx.m("app.chrome.launch"), ctx.m("app.chrome.running.confirm"))) {
                        ctx.toast(ctx.m("app.chrome.closing"));
                        runLaunch(words, true);
                        return;
                    }
                } else if (r != ChromeLauncher.Result.LAUNCHED) {
                    OeDialog.info(ctx, ctx.m("app.chrome.launch"), ctx.m(switch (r) {
                        case NOT_FOUND -> "app.chrome.not.found";
                        case REFUSED -> "app.chrome.refused";
                        default -> "app.chrome.failed";
                    }));
                }
                launchBtn.setEnabled(true);
            });
        }, "chrome-launch").start();
    }

    void showDirectCmd(boolean win) {
        ChromeArgs.Options o = options(win);
        String launch = ChromeArgs.commandLine(o, win);
        String udd = o.uddEnabled ? o.userDataDir : "";
        String cmd = ChromeArgs.killAndLaunch(win, launch, udd, ctx.m("modal.chrome.cmd.kill.prompt"));
        Dialogs.command(ctx, ctx.m("modal.chrome.cmd.title") + "  " + ctx.m(win ? "modal.chrome.cmd.os.win" : "modal.chrome.cmd.os.mac"),
                OeDialog.warn(ctx.plain("modal.chrome.cmd.kill.why"), udd.isEmpty() ? null : ctx.plain("modal.chrome.cmd.kill.warn.profile")),
                cmd);
    }

    private static String fmt(String ts) {
        return ts == null || ts.isEmpty() ? "—" : ts.substring(0, Math.min(16, ts.length())).replace('T', ' ');
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Re-reads everything from the store (after an import). */
    void reloadAll() {
        autosave.flush();
        reload();
        List<Store.Item> items = ctx.read(() -> ctx.rawStore().list(Store.Kind.HOSTS));
        activate(items.stream().filter(i -> i.selected).findFirst().or(() -> items.stream().findFirst()).map(i -> i.id).orElse(null));
    }

    /** Saves a pending edit (window closing). */
    public void flush() {
        autosave.flush();
    }

    private static JPanel whitePanel(LayoutManager l) {
        JPanel p = new JPanel(l);
        p.setBackground(Color.WHITE);
        return p;
    }
}
