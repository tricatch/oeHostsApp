package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.store.Store;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * What the oeHosts and oeProxy screens share: the item list ({@link SidebarTree}) with its header
 * buttons and count, the editor with its title, export/delete buttons, empty state and autosave,
 * and adding, importing, deleting and opening items. Each screen adds its own right-hand panel and
 * reacts to changes through {@link Hooks}.
 */
final class ItemPanel {

    /** The screen's message keys, file extension and editor syntax. */
    record Spec(Store.Kind kind, String syntax, String ext, String headerKey, String newKey, String importKey,
                String deleteKey, String confirmDeleteKey, String selectKey, String emptyKey, String countKey) {}

    /** What the screen does on top of the shared behavior. */
    interface Hooks {
        /** Why {@code content} may not be stored (shown, and the edit stays unsaved), or null. */
        default String invalid(String content) {
            return null;
        }

        /** The list or a stored item changed (selection, rename, content, delete, order, import). */
        default void changed() {}

        /** Another item (or none) is open in the editor. */
        default void opened(Store.Item item) {}

        /** Every keystroke in the editor. */
        default void edited(String text) {}
    }

    private final AppContext ctx;
    private final Spec spec;
    private final Hooks hooks;
    final SidebarTree tree;
    final CodeEditor editor;
    private final JLabel countLabel = Ui.metaText(" ");
    private final JLabel title;
    final JLabel saveStatus = new JLabel(" ");
    private final JPanel editorCards = new JPanel(new CardLayout());
    private final JLabel metaUpdated = Ui.metaText("—");
    private final JButton btnDownload, btnDelete;
    private final Autosave autosave;
    private Store.Item active;

    ItemPanel(AppContext ctx, Spec spec, Hooks hooks) {
        this.ctx = ctx;
        this.spec = spec;
        this.hooks = hooks;
        editor = new CodeEditor(spec.syntax());
        tree = new SidebarTree(ctx, spec.kind(), new SidebarTree.Listener() {
            public void activated(Store.Item item) {
                activate(item.id);
            }

            public void toggled(Store.Item item) {
                ctx.store(s -> s.toggleSelected(spec.kind(), item.id));
                tree.render();
                hooks.changed();
            }

            public void renamed(Store.Item item) {
                if (isActive(item.id)) {
                    active = item;
                    title.setText(item.name);
                }
                hooks.changed();
            }

            public void deleteRequested(Store.Item item) {
                delete(item);
            }

            public void changed() {
                hooks.changed();
            }
        });
        title = Ui.title(ctx.m(spec.selectKey()));
        saveStatus.setFont(Ui.font(Font.PLAIN, 12f));
        saveStatus.setForeground(Ui.MUTED);
        btnDownload = Ui.iconButton("file-down", ctx.m("tooltip.export"), false, this::download);
        btnDelete = Ui.iconButton("trash", ctx.m(spec.deleteKey()), true, () -> {
            if (active != null) delete(active);
        });
        autosave = new Autosave(ctx, editor, saveStatus, this::save);
        editor.onEdit(() -> {
            hooks.edited(editor.getText());
            autosave.changed(editor.getText());
        });
        editorCards.setBackground(Color.WHITE);
        editorCards.setMinimumSize(new Dimension(10, 80));
    }

    /** Ctrl+S / Cmd+S saves while the focus is anywhere in {@code root}. */
    void bindSaveKey(JComponent root) {
        autosave.bindSaveKey(root);
    }

    Store.Item active() {
        return active;
    }

    private boolean isActive(String id) {
        return active != null && active.id.equals(id);
    }

    // ── Layout ──────────────────────────────────────────────────

    /** The list with its header (new, import, new folder) and the count bar. */
    JComponent sidebar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        JButton add = Ui.iconButton("square-plus", ctx.m(spec.newKey()), false, this::add);
        JButton importBtn = Ui.iconButton("circle-fading-plus", ctx.m(spec.importKey()), false, this::importFiles);
        JButton addFolder = Ui.iconButton("folder-plus", ctx.m("tooltip.new.folder"), false, () -> {
            ctx.store(s -> s.createFolder(spec.kind()));
            tree.render();
        });
        p.add(Ui.header(Ui.caps(ctx.m(spec.headerKey())), add, importBtn, addFolder), BorderLayout.NORTH);
        p.add(tree, BorderLayout.CENTER);
        JPanel meta = Ui.meta();
        meta.add(countLabel, BorderLayout.WEST);
        p.add(meta, BorderLayout.SOUTH);
        return p;
    }

    /** The editor, or the empty-list notice when there is no item; {@link #editorPanel} shows it by default. */
    JComponent editorCards() {
        JLabel empty = new JLabel(ctx.m(spec.emptyKey()), SwingConstants.CENTER);
        empty.setForeground(Ui.MUTED);
        empty.setFont(Ui.font(Font.PLAIN, 13f));
        editorCards.add(editor.scroll, "editor");
        editorCards.add(empty, "empty");
        return editorCards;
    }

    /**
     * The editor column: title row with {@code extraButtons} before export and delete, then
     * {@code center} (the {@link #editorCards}, possibly with more below it), then the date / save
     * status bar.
     */
    JComponent editorPanel(JComponent center, JButton... extraButtons) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        JPanel titleRow = new JPanel(new BorderLayout(10, 0));
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.WEST);
        JButton[] buttons = new JButton[extraButtons.length + 2];
        System.arraycopy(extraButtons, 0, buttons, 0, extraButtons.length);
        buttons[extraButtons.length] = btnDownload;
        buttons[extraButtons.length + 1] = btnDelete;
        p.add(Ui.header(titleRow, buttons), BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);
        JPanel meta = Ui.meta();
        meta.add(metaUpdated, BorderLayout.WEST);
        // Autosave state (editing / saved) at the right end of the date bar.
        meta.add(saveStatus, BorderLayout.EAST);
        p.add(meta, BorderLayout.SOUTH);
        return p;
    }

    // ── Items ───────────────────────────────────────────────────

    /** Draws the list and the count again from the store. */
    void render() {
        tree.render();
        int total = ctx.read(() -> ctx.rawStore().list(spec.kind()).size());
        countLabel.setText(ctx.m(spec.countKey(), total));
        ((CardLayout) editorCards.getLayout()).show(editorCards, total == 0 ? "empty" : "editor");
    }

    /** Opens the first selected item, else the first item, else nothing. */
    void openFirst() {
        List<Store.Item> items = ctx.read(() -> ctx.rawStore().list(spec.kind()));
        activate(items.stream().filter(i -> i.selected).findFirst().or(() -> items.stream().findFirst())
                .map(i -> i.id).orElse(null));
    }

    /** Opens item {@code id} (null or unknown: nothing), saving the open one's pending edit first. */
    void activate(String id) {
        autosave.flush();
        Store.Item it = ctx.read(() -> ctx.rawStore().find(spec.kind(), id));
        autosave.activate(it == null ? null : it.id, it == null ? null : it.content);
        active = it;
        tree.setActive(it == null ? null : it.id);
        title.setText(it == null ? ctx.m(spec.selectKey()) : it.name);
        editor.setText(it == null ? "" : it.content);
        editor.setEditable(it != null);
        btnDelete.setEnabled(it != null);
        btnDownload.setEnabled(it != null);
        metaUpdated.setText(it == null ? "—" : date(it.updatedAt));
        hooks.opened(it);
    }

    /** Replaces the open item with what the store now holds for it (sharing turned on, say). */
    void refreshActive(Store.Item it) {
        if (it != null && isActive(it.id)) active = it;
    }

    private Autosave.Result save(String id, String content) {
        String invalid = hooks.invalid(content);
        // Not saved and not retried: the next edit tries again.
        if (invalid != null) return Autosave.Result.refused(invalid);
        Store.Item r = ctx.store(s -> s.updateContent(spec.kind(), id, content));
        if (r == null) return Autosave.Result.retry(ctx.m("status.save.failed"));
        if (isActive(id)) {
            active = r;
            metaUpdated.setText(date(r.updatedAt));
        }
        hooks.changed();
        return Autosave.Result.OK;
    }

    private void add() {
        Store.Item it = ctx.store(s -> s.create(spec.kind(), ctx.lang()));
        if (it == null) return;
        render();
        activate(it.id);
        hooks.changed();
    }

    /** Stores {@code content} as a new item named {@code name} (a merge of the selected ones) and opens it. */
    void addMerged(String name, String content) {
        Store.Item it = ctx.store(s -> s.createFrom(spec.kind(), name, content));
        if (it == null) {
            ctx.toast(ctx.m("toast.merge.save.failed"));
            return;
        }
        render();
        activate(it.id);
        hooks.changed();
        ctx.toast(ctx.m("toast.merge.saved"));
    }

    private void delete(Store.Item it) {
        if (!OeDialog.confirm(ctx, ctx.m(spec.deleteKey()), ctx.m(spec.confirmDeleteKey()))) return;
        boolean wasActive = isActive(it.id);
        if (wasActive) {
            autosave.discard();
            autosave.activate(null, null);
        }
        ctx.storeRun(s -> s.delete(spec.kind(), it.id));
        if (wasActive) activate(null);
        render();
        hooks.changed();
    }

    private void download() {
        if (active == null) return;
        if (!ItemFiles.save(editor.scroll, active.name, spec.ext(), editor.getText())) ctx.toast(ctx.m("status.save.failed"));
    }

    /**
     * Each chosen file becomes a new item named after the file; the last one is opened. A file the
     * screen calls invalid ({@link Hooks#invalid}) is skipped, as an invalid edit is never saved either.
     */
    private void importFiles() {
        List<ItemFiles.Loaded> files;
        try {
            files = ItemFiles.open(editor.scroll, spec.ext());
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
            String why = hooks.invalid(f.content());
            if (why != null) {
                if (invalid == null) invalid = ctx.m("app.item.import.invalid", f.name(), why);
                continue;
            }
            Store.Item it = ctx.store(s -> s.createFrom(spec.kind(), f.name(), f.content()));
            if (it != null) {
                last = it;
                imported++;
            }
        }
        if (last != null) {
            render();
            activate(last.id);
            hooks.changed();
        }
        if (invalid != null) OeDialog.info(ctx, ctx.m(spec.importKey()), invalid);
        else if (imported > 0) ctx.toast(ctx.m("app.item.imported", imported));
    }

    /** Re-reads everything from the store (after an import). */
    void reloadAll() {
        autosave.flush();
        render();
        openFirst();
        hooks.changed();
    }

    /** Saves a pending edit now (window closing, rebuild). */
    void flush() {
        autosave.flush();
    }

    /** {@code yyyy-MM-dd HH:mm} of a stored timestamp. */
    static String date(String ts) {
        return ts == null || ts.isEmpty() ? "—" : ts.substring(0, Math.min(16, ts.length())).replace('T', ' ');
    }
}
