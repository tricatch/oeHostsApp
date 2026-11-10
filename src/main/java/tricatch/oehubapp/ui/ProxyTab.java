package tricatch.oehubapp.ui;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import tricatch.oehubapp.HostsRules;
import tricatch.oehubapp.ProxyRuntime;
import tricatch.oehubapp.Store;

import javax.swing.*;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The oeProxy screen, laid out like proxy.html: vhost sidebar, YAML editor with the schema check
 * of the web page (validateSchema), and the merged routes of the selected vhosts with oeHub's
 * placeholders filled in ({@link HostsRules#resolvePlaceholders}). The merged YAML is stored in
 * {@code proxyConf.vhost}, which the embedded proxy ({@link ProxyRuntime}) serves.
 */
public final class ProxyTab extends JPanel {

    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                    .build())
            .build();

    private final AppContext ctx;
    private final SidebarTree tree;
    private final JLabel countLabel = Ui.metaText(" ");
    private final JLabel editorTitle;
    private final JLabel saveStatus = new JLabel(" ");
    private final CodeEditor editor = new CodeEditor(SyntaxConstants.SYNTAX_STYLE_YAML);
    private final CodeEditor merged = new CodeEditor(SyntaxConstants.SYNTAX_STYLE_YAML);
    private final JPanel editorCards = whitePanel(new CardLayout());
    private final JPanel mergedCards = whitePanel(new CardLayout());
    private final JLabel metaUpdated = Ui.metaText("—");
    private final JButton btnDownload, btnDelete, btnSaveMerged;
    private final JLabel routesTitle;
    private final JLabel proxyStatus = Ui.metaText(" ");
    private final Autosave autosave;
    private Store.Item active;

    public ProxyTab(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        tree = new SidebarTree(ctx, Store.Kind.VHOSTS, new SidebarTree.Listener() {
            public void activated(Store.Item item) {
                activate(item.id);
            }

            public void toggled(Store.Item item) {
                ctx.store(s -> s.toggleSelected(Store.Kind.VHOSTS, item.id));
                tree.render();
                mergeAndSave();
            }

            public void renamed(Store.Item item) {
                if (active != null && active.id.equals(item.id)) editorTitle.setText(item.name);
            }

            public void deleteRequested(Store.Item item) {
                deleteItem(item);
            }

            public void changed() {
                mergeAndSave();
            }
        });
        editorTitle = Ui.title(ctx.m("editor.select.vhost"));
        saveStatus.setFont(Ui.font(Font.PLAIN, 12f));
        saveStatus.setForeground(Ui.MUTED);
        btnDownload = Ui.iconButton("file-down", ctx.m("tooltip.export"), false, this::download);
        btnDelete = Ui.iconButton("trash", ctx.m("tooltip.delete.vhost"), true, () -> {
            if (active != null) deleteItem(active);
        });
        btnSaveMerged = Ui.iconButton("copy-plus", ctx.m("tooltip.save.merged"), false, this::saveMerged);
        routesTitle = Ui.caps(ctx.m("proxy.panel.header"));
        autosave = new Autosave(ctx, editor, saveStatus, this::save);
        autosave.bindSaveKey(this);
        editor.onEdit(() -> {
            lint(editor.getText());
            autosave.changed(editor.getText());
        });
        merged.setEditable(false);
        merged.area.setBackground(Color.WHITE);
        merged.area.setHighlightCurrentLine(false);

        JSplitPane split = Ui.rightSplit(ctx, buildEditor(), buildRoutes());
        add(Ui.sidebarSplit(ctx, buildSidebar(), split), BorderLayout.CENTER);

        tree.render();
        updateCount();
        List<Store.Item> items = ctx.read(() -> ctx.rawStore().list(Store.Kind.VHOSTS));
        items.stream().filter(i -> i.selected).findFirst().or(() -> items.stream().findFirst())
                .ifPresent(i -> activate(i.id));
        showMerged();
    }

    // ── Layout ──────────────────────────────────────────────────

    private JComponent buildSidebar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        JButton add = Ui.iconButton("square-plus", ctx.m("tooltip.new.vhost"), false, this::addItem);
        JButton importBtn = Ui.iconButton("circle-fading-plus", ctx.m("app.tooltip.import.vhost"), false, this::importFiles);
        JButton addFolder = Ui.iconButton("folder-plus", ctx.m("tooltip.new.folder"), false, () -> {
            ctx.store(s -> s.createFolder(Store.Kind.VHOSTS));
            tree.render();
        });
        p.add(Ui.header(Ui.caps(ctx.m("proxy.sidebar.header")), add, importBtn, addFolder), BorderLayout.NORTH);
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
        p.add(Ui.header(titleRow, btnDownload, btnDelete), BorderLayout.NORTH);
        JLabel empty = new JLabel(ctx.m("proxy.empty"), SwingConstants.CENTER);
        empty.setForeground(Ui.MUTED);
        editorCards.add(editor.scroll, "editor");
        editorCards.add(empty, "empty");
        p.add(editorCards, BorderLayout.CENTER);
        JPanel meta = Ui.meta();
        meta.add(metaUpdated, BorderLayout.WEST);
        // Autosave state (editing / saved) at the right end of the date bar.
        meta.add(saveStatus, BorderLayout.EAST);
        p.add(meta, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildRoutes() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setMinimumSize(new Dimension(360, 10));
        JPanel titleWrap = new JPanel(new GridBagLayout());
        titleWrap.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1;
        titleWrap.add(routesTitle, c);
        JButton help = Ui.iconButton("circle-help", ctx.m("tooltip.install.guide"), false,
                () -> OeDialog.info(ctx, ctx.m("modal.install.ca"), ctx.plain("app.proxy.ca.guide",
                        ProxyRuntime.caCert() == null ? "-" : ProxyRuntime.caCert().toAbsolutePath())));
        p.add(Ui.header(titleWrap, btnSaveMerged, help), BorderLayout.NORTH);

        JLabel empty = new JLabel(ctx.m("proxy.config.empty"), SwingConstants.CENTER);
        empty.setForeground(Ui.MUTED);
        mergedCards.add(merged.scroll, "merged");
        mergedCards.add(empty, "empty");
        p.add(mergedCards, BorderLayout.CENTER);

        JPanel meta = Ui.meta();
        meta.add(proxyStatus, BorderLayout.WEST);
        p.add(meta, BorderLayout.SOUTH);
        ProxyRuntime.onChange(() -> SwingUtilities.invokeLater(this::showProxyStatus));
        showProxyStatus();
        return p;
    }

    private void showProxyStatus() {
        switch (ProxyRuntime.state()) {
            case STARTING -> {
                proxyStatus.setText(ctx.m("app.proxy.status.starting"));
                proxyStatus.setForeground(Ui.MUTED);
                proxyStatus.setToolTipText(null);
            }
            case RUNNING -> {
                proxyStatus.setText(ctx.m("app.proxy.status.running", ProxyRuntime.port()));
                proxyStatus.setForeground(new Color(0x3a6b1a));
                proxyStatus.setToolTipText(null);
            }
            case FAILED -> {
                proxyStatus.setText(ctx.m("app.proxy.status.failed"));
                proxyStatus.setForeground(Ui.DANGER);
                proxyStatus.setToolTipText(ProxyRuntime.error());
            }
        }
    }

    // ── Items ───────────────────────────────────────────────────

    private void updateCount() {
        int total = ctx.read(() -> ctx.rawStore().list(Store.Kind.VHOSTS).size());
        countLabel.setText(ctx.m("proxy.sidebar.count", total));
        ((CardLayout) editorCards.getLayout()).show(editorCards, total == 0 ? "empty" : "editor");
    }

    private void activate(String id) {
        autosave.flush();
        Store.Item it = ctx.read(() -> ctx.rawStore().list(Store.Kind.VHOSTS).stream()
                .filter(i -> i.id.equals(id)).findFirst().orElse(null));
        autosave.activate(it == null ? null : it.id, it == null ? null : it.content);
        active = it;
        tree.setActive(it == null ? null : it.id);
        editorTitle.setText(it == null ? ctx.m("editor.select.vhost") : it.name);
        editor.setText(it == null ? "" : it.content);
        editor.setEditable(it != null);
        btnDelete.setEnabled(it != null);
        btnDownload.setEnabled(it != null);
        metaUpdated.setText(it == null || it.updatedAt == null ? "—" : it.updatedAt.substring(0, Math.min(16, it.updatedAt.length())).replace('T', ' '));
        lint(editor.getText());
    }

    private void addItem() {
        Store.Item it = ctx.store(s -> s.create(Store.Kind.VHOSTS, ctx.lang()));
        if (it == null) return;
        tree.render();
        updateCount();
        activate(it.id);
    }

    private void deleteItem(Store.Item it) {
        if (!OeDialog.confirm(ctx, ctx.m("tooltip.delete.vhost"), ctx.m("confirm.delete.vhost"))) return;
        boolean wasActive = active != null && active.id.equals(it.id);
        if (wasActive) {
            autosave.discard();
            autosave.activate(null, null);
        }
        ctx.storeRun(s -> s.delete(Store.Kind.VHOSTS, it.id));
        if (wasActive) activate(null);
        tree.render();
        updateCount();
        mergeAndSave();
    }

    private Autosave.Result save(String id, String content) {
        // Invalid YAML is not saved and not retried: the next edit tries again.
        List<Problem> problems = problems(content);
        if (!problems.isEmpty()) return Autosave.Result.refused(problems.get(0).message);
        Store.Item r = ctx.store(s -> s.updateContent(Store.Kind.VHOSTS, id, content));
        if (r == null) return Autosave.Result.retry(ctx.m("status.save.failed"));
        if (active != null && active.id.equals(id)) active = r;
        mergeAndSave();
        return Autosave.Result.OK;
    }

    private void download() {
        if (active == null) return;
        if (!ItemFiles.save(this, active.name, ItemFiles.VHOST_EXT, editor.getText())) ctx.toast(ctx.m("status.save.failed"));
    }

    /**
     * Each chosen .oeproxy file becomes a new vhost named after the file; the last one is opened.
     * A file that fails the vhost schema check is skipped, as an invalid edit is never saved either.
     */
    private void importFiles() {
        List<ItemFiles.Loaded> files;
        try {
            files = ItemFiles.open(this, ItemFiles.VHOST_EXT);
        } catch (Exception e) {
            ctx.toast(ctx.m("app.import.failed"));
            return;
        }
        if (files.isEmpty()) return;
        autosave.flush();
        Store.Item last = null;
        int imported = 0;
        String invalid = null;
        for (ItemFiles.Loaded f : files) {
            List<Problem> problems = problems(f.content());
            if (!problems.isEmpty()) {
                if (invalid == null) invalid = ctx.m("app.item.import.invalid", f.name(), problems.get(0).message);
                continue;
            }
            Store.Item it = ctx.store(s -> s.createFrom(Store.Kind.VHOSTS, f.name(), f.content()));
            if (it != null) {
                last = it;
                imported++;
            }
        }
        if (last != null) {
            tree.render();
            updateCount();
            activate(last.id);
        }
        if (invalid != null) OeDialog.info(ctx, ctx.m("app.tooltip.import.vhost"), invalid);
        else if (imported > 0) ctx.toast(ctx.m("app.item.imported", imported));
    }

    // ── Validation (validateSchema / lintDoc of proxy.html) ─────

    private record Problem(int line, String message) {}

    private List<Problem> problems(String content) {
        List<Problem> out = new ArrayList<>();
        if (content.isBlank()) return out;
        JsonNode parsed;
        try {
            parsed = YAML.readTree(content);
        } catch (JsonProcessingException e) {
            JsonLocation loc = e.getLocation();
            out.add(new Problem(loc == null ? 0 : Math.max(0, loc.getLineNr() - 1), e.getOriginalMessage()));
            return out;
        }
        for (String m : validate(parsed)) out.add(new Problem(0, m));
        return out;
    }

    private List<String> validate(JsonNode parsed) {
        List<String> errors = new ArrayList<>();
        if (parsed == null || !parsed.isObject() || !parsed.path("virtual").isArray()) {
            errors.add(ctx.m("proxy.validate.virtual.required"));
            return errors;
        }
        int i = 0;
        for (JsonNode v : parsed.get("virtual")) {
            String p = "virtual[" + i++ + "]";
            if (!v.path("domain").isTextual() || v.path("domain").asText().isEmpty()) {
                errors.add(p + ": " + ctx.m("proxy.validate.domain.required"));
            }
            JsonNode loc = v.path("location");
            if (!loc.isArray() || loc.isEmpty()) {
                errors.add(p + ": " + ctx.m("proxy.validate.location.required"));
                continue;
            }
            int j = 0;
            for (JsonNode l : loc) {
                String lp = p + ".location[" + j++ + "]";
                if (!l.path("host").isTextual() || l.path("host").asText().isEmpty()) errors.add(lp + ": " + ctx.m("proxy.validate.host.required"));
                if (!l.path("path").isArray() || l.path("path").isEmpty()) errors.add(lp + ": " + ctx.m("proxy.validate.path.required"));
                if (l.has("header") && !l.get("header").isArray()) errors.add(lp + ": " + ctx.m("proxy.validate.header.array"));
            }
        }
        return errors;
    }

    /** Marks the first problem's line in the editor and its text as the tooltip of the status. */
    private void lint(String content) {
        editor.area.removeAllLineHighlights();
        List<Problem> problems = problems(content);
        if (problems.isEmpty()) {
            saveStatus.setToolTipText(null);
            return;
        }
        Problem p = problems.get(0);
        try {
            editor.area.addLineHighlight(Math.min(p.line, editor.area.getLineCount() - 1), new Color(255, 0, 0, 28));
        } catch (javax.swing.text.BadLocationException ignored) {
            // the line is clamped to the document
        }
        saveStatus.setToolTipText(p.message);
    }

    // ── Merge ───────────────────────────────────────────────────

    /** buildMergedVirtualYaml: selected vhosts' entries, first per domain wins; raw ${LOCAL_SVR}. */
    private String mergedYaml(List<Store.Item> selected) {
        ArrayNode all = JsonNodeFactory.instance.arrayNode();
        Set<String> seen = new HashSet<>();
        for (Store.Item v : selected) {
            if (v.content.isBlank()) continue;
            try {
                JsonNode parsed = YAML.readTree(v.content);
                if (parsed == null || !parsed.path("virtual").isArray()) continue;
                for (JsonNode e : parsed.get("virtual")) {
                    String d = e.path("domain").isTextual() ? e.path("domain").asText() : null;
                    if (d != null && !d.isEmpty() && !seen.add(d)) continue;
                    all.add(e);
                }
            } catch (JsonProcessingException ignored) {
                // skip invalid
            }
        }
        if (all.isEmpty()) return "";
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("virtual", all);
        try {
            return YAML.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private List<Store.Item> selected() {
        return ctx.read(() -> ctx.rawStore().list(Store.Kind.VHOSTS).stream().filter(i -> i.selected).toList());
    }

    private void showMerged() {
        List<Store.Item> sel = selected();
        boolean has = !sel.isEmpty();
        ((CardLayout) mergedCards.getLayout()).show(mergedCards, has ? "merged" : "empty");
        routesTitle.setForeground(has ? new Color(0xe53e3e) : Ui.MUTED);
        btnSaveMerged.setEnabled(sel.size() >= 2);
        merged.setText(HostsRules.resolvePlaceholders(mergedYaml(sel)));
    }

    private void mergeAndSave() {
        String yaml = mergedYaml(selected());
        ctx.storeRun(s -> {
            s.data.proxyConf.put("vhost", yaml);
            s.save();
        });
        ProxyRuntime.reload();
        showMerged();
    }

    /** The merged YAML with oeHub's placeholders filled in (for the monitor's sample traffic). */
    String effectiveMergedYaml() {
        return HostsRules.resolvePlaceholders(mergedYaml(selected()));
    }

    boolean hasSelected() {
        return !selected().isEmpty();
    }

    private void saveMerged() {
        List<Store.Item> sel = selected();
        if (sel.size() < 2) return;
        String content = mergedYaml(sel);
        String name = String.join(" + ", sel.stream().map(v -> v.name).toList());
        Store.Item it = ctx.store(s -> {
            Store.Item n = s.create(Store.Kind.VHOSTS, ctx.lang());
            try {
                s.rename(Store.Kind.VHOSTS, n.id, name);
            } catch (Store.StoreException ignored) {
                // keep the generated name when the merged one is taken
            }
            return s.updateContent(Store.Kind.VHOSTS, n.id, content);
        });
        if (it == null) {
            ctx.toast(ctx.m("toast.merge.save.failed"));
            return;
        }
        tree.render();
        updateCount();
        activate(it.id);
        ctx.toast(ctx.m("toast.merge.saved"));
    }

    /** Re-reads everything from the store (after an import). */
    void reloadAll() {
        autosave.flush();
        tree.render();
        updateCount();
        List<Store.Item> items = ctx.read(() -> ctx.rawStore().list(Store.Kind.VHOSTS));
        activate(items.stream().filter(i -> i.selected).findFirst().or(() -> items.stream().findFirst()).map(i -> i.id).orElse(null));
        mergeAndSave();
    }

    public void flush() {
        autosave.flush();
    }

    private static JPanel whitePanel(LayoutManager l) {
        JPanel p = new JPanel(l);
        p.setBackground(Color.WHITE);
        return p;
    }
}
