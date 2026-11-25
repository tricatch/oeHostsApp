package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.store.ItemSearch;
import tricatch.oehostsapp.store.Store;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
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
                String deleteKey, String confirmDeleteKey, String selectKey, String emptyKey, String countKey,
                String searchKey) {}

    /** Why the content may not be stored: the first problem's {@code line} (0-based) and its text. */
    record Invalid(int line, String message) {}

    /** What the screen does on top of the shared behavior. */
    interface Hooks {
        /**
         * Why {@code content} may not be stored, or null. Its line is marked in the editor and its
         * text is the save status's tooltip; the edit stays unsaved and an import is skipped.
         */
        default Invalid invalid(String content) {
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
    private final JButton btnFind, btnDownload, btnDelete;
    private final Autosave autosave;
    private final SearchPanel search;
    private final FindBar findBar;
    /** Other kinds of file the import button offers ({@link #addImportSource}). */
    private final List<ImportSource> importSources = new ArrayList<>();
    /** The menu label of the screen's own files while {@link #importSources} makes the import button a menu. */
    private String ownImportKey;
    private Store.Item active;

    /**
     * An import of another kind of file: its menu label's key, what it does, and whether it can be
     * used now (else the item is off, with {@code unavailableKey} as its tooltip).
     */
    private record ImportSource(String labelKey, Runnable action, java.util.function.BooleanSupplier available,
                                String unavailableKey) {}

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
                // An item with a problem cannot be turned on; for the open item that is what the
                // editor shows, even while the edit is still unsaved. Turning one off always works.
                if (!item.selected) {
                    String text = isActive(item.id) ? editor.getText() : item.content;
                    Invalid why = hooks.invalid(text == null ? "" : text);
                    if (why != null) {
                        tree.render();
                        ctx.toast(ctx.m("app.item.select.invalid", why.message()));
                        return;
                    }
                }
                ctx.store(s -> s.toggleSelected(spec.kind(), item.id));
                tree.render();
                hooks.changed();
            }

            public void renamed(Store.Item item) {
                if (isActive(item.id)) {
                    active = item;
                    title.setText(Ui.plain(item.name));
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
        findBar = new FindBar(ctx, editor);
        btnFind = Ui.iconButton("search-code", ctx.m("app.find.tooltip", FindBar.shortcutText()), false, findBar::toggle);
        editor.onEdit(() -> {
            lint(editor.getText());
            hooks.edited(editor.getText());
            autosave.changed(editor.getText());
            findBar.textEdited();
        });
        search = new SearchPanel(ctx, spec.searchKey(), new SearchPanel.Target() {
            public List<ItemSearch.Hit> find(String query) {
                // The open item is searched as the editor shows it, so line numbers match.
                return ctx.read(() -> ItemSearch.search(ctx.rawStore().tree(spec.kind()), query,
                        it -> isActive(it.id) ? editor.getText() : it.content));
            }

            public void open(Store.Item item, ItemSearch.Line line, String query) {
                // Re-opening the open item would reload it from the store and drop an edit that
                // could not be saved, so it stays as it is.
                if (!isActive(item.id)) activate(item.id);
                if (!isActive(item.id)) return;
                // The list search's highlights take the editor over from the find bar.
                findBar.close(false);
                // Show the opened item in the list: open its folder when it is collapsed.
                boolean collapsed = item.folderId != null && ctx.read(() -> ctx.rawStore().tree(spec.kind()).stream()
                        .map(Store.Node::folder)
                        .anyMatch(f -> f != null && f.id.equals(item.folderId) && f.collapsed));
                if (collapsed) {
                    ctx.store(s -> s.updateFolder(spec.kind(), item.folderId, null, false));
                    tree.render();
                }
                editor.markAll(query);
                if (line != null) editor.goTo(line.line(), line.start());
            }

            public void closed() {
                if (findBar.isVisible()) findBar.refresh();
                else editor.clearMarks();
            }
        });
        editorCards.setBackground(Color.WHITE);
        editorCards.setMinimumSize(new Dimension(Ui.px(10), Ui.px(80)));
    }

    /** Ctrl+S / Cmd+S saves and Ctrl+F / Cmd+F finds in the open item while the focus is anywhere in {@code root}. */
    void bindSaveKey(JComponent root) {
        autosave.bindSaveKey(root);
        findBar.bind(root);
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
        JButton[] importBtn = {null};
        importBtn[0] = Ui.iconButton("circle-fading-plus",
                ctx.m(importSources.isEmpty() ? spec.importKey() : "app.tooltip.import.menu"), false, () -> {
                    if (importSources.isEmpty()) {
                        importFiles();
                        return;
                    }
                    JPopupMenu m = new JPopupMenu();
                    JMenuItem own = new JMenuItem(ctx.m(ownImportKey != null ? ownImportKey : spec.importKey()));
                    own.addActionListener(a -> importFiles());
                    m.add(own);
                    for (ImportSource s : importSources) {
                        JMenuItem it = new JMenuItem(ctx.m(s.labelKey()));
                        it.addActionListener(a -> s.action().run());
                        // Checked each time the menu opens: the other app may be installed meanwhile.
                        if (!s.available().getAsBoolean()) {
                            it.setEnabled(false);
                            it.setToolTipText(ctx.m(s.unavailableKey()));
                        }
                        m.add(it);
                    }
                    m.show(importBtn[0], 0, importBtn[0].getHeight());
                });
        JButton addFolder = Ui.iconButton("folder-plus", ctx.m("tooltip.new.folder"), false, () -> {
            ctx.store(s -> s.createFolder(spec.kind()));
            tree.render();
        });
        p.add(Ui.header(Ui.caps(ctx.m(spec.headerKey())), add, importBtn[0], search.toggleButton(), addFolder), BorderLayout.NORTH);
        p.add(searchSplit(), BorderLayout.CENTER);
        JPanel meta = Ui.meta();
        meta.add(countLabel, BorderLayout.WEST);
        p.add(meta, BorderLayout.SOUTH);
        return p;
    }

    /**
     * Search panel above the list, the line between them draggable while the panel is open (no
     * divider while the panel is hidden). The panel opens at the height last
     * dragged to ({@code settings.window.searchHeight}, shared by both screens), else at oeHub's
     * 30% of the list, and keeps it when the window is resized.
     */
    private JSplitPane searchSplit() {
        search.setMinimumSize(new Dimension(Ui.px(10), Ui.px(90)));
        tree.setMinimumSize(new Dimension(Ui.px(10), Ui.px(60)));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, search, tree);
        Ui.lineDivider(split);
        split.setContinuousLayout(true);
        split.setResizeWeight(0);
        int dividerSize = split.getDividerSize();
        // The panel starts hidden; a hidden panel leaves no divider line above the list.
        split.setDividerSize(0);
        boolean[] applying = {false};
        search.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                split.setDividerSize(0);
            }

            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                split.setDividerSize(dividerSize);
                Integer stored = ctx.settings(st -> st.window.searchHeight);
                int total = split.getHeight();
                int h = stored != null ? Ui.px(stored) : Math.max(Ui.px(140), Math.round(total * 0.3f));
                applying[0] = true;
                split.setDividerLocation(Math.max(Ui.px(90), Math.min(h, total - Ui.px(60) - split.getDividerSize())));
                applying[0] = false;
            }
        });
        // Only a drag by the user is saved (stored at 100%, like the list width).
        Timer save = new Timer(400, e -> {
            if (!search.isVisible()) return;
            int h = com.formdev.flatlaf.util.UIScale.unscale(split.getDividerLocation());
            ctx.storeRun(st -> {
                if (Integer.valueOf(h).equals(st.data.settings.window.searchHeight)) return;
                st.data.settings.window.searchHeight = h;
                st.save();
            });
        });
        save.setRepeats(false);
        split.addPropertyChangeListener(JSplitPane.LAST_DIVIDER_LOCATION_PROPERTY, e -> {
            if (!applying[0] && search.isVisible() && split.isShowing()) save.restart();
        });
        return split;
    }

    /** The editor, or the empty-list notice when there is no item; {@link #editorPanel} shows it by default. */
    JComponent editorCards() {
        JLabel empty = new JLabel(ctx.m(spec.emptyKey()), SwingConstants.CENTER);
        empty.setForeground(Ui.MUTED);
        empty.setFont(Ui.font(Font.PLAIN, 13f));
        JPanel withFind = new JPanel(new BorderLayout());
        withFind.add(findBar, BorderLayout.NORTH);
        withFind.add(editor.scroll, BorderLayout.CENTER);
        editorCards.add(withFind, "editor");
        editorCards.add(empty, "empty");
        return editorCards;
    }

    /**
     * The editor column: title row with find, then {@code extraButtons}, then export and delete, then
     * {@code center} (the {@link #editorCards}, possibly with more below it), then the date / save
     * status bar.
     */
    JComponent editorPanel(JComponent center, JButton... extraButtons) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        JPanel titleRow = new JPanel(new BorderLayout(Ui.px(10), 0));
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.WEST);
        JButton[] buttons = new JButton[extraButtons.length + 3];
        buttons[0] = btnFind;
        System.arraycopy(extraButtons, 0, buttons, 1, extraButtons.length);
        buttons[extraButtons.length + 1] = btnDownload;
        buttons[extraButtons.length + 2] = btnDelete;
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
        search.refresh();
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
        editor.clearMarks();
        tree.setActive(it == null ? null : it.id);
        title.setText(it == null ? ctx.m(spec.selectKey()) : Ui.plain(it.name));
        editor.setText(it == null ? "" : it.content);
        editor.setEditable(it != null);
        findBar.itemChanged();
        btnFind.setEnabled(it != null);
        btnDelete.setEnabled(it != null);
        btnDownload.setEnabled(it != null);
        metaUpdated.setText(it == null ? "—" : date(it.updatedAt));
        lint(it == null ? "" : it.content);
        hooks.opened(it);
    }

    /** Marks the first problem's line in the editor and puts its text in the save status's tooltip. */
    private void lint(String content) {
        editor.area.removeAllLineHighlights();
        Invalid p = hooks.invalid(content);
        saveStatus.setToolTipText(p == null ? null : Ui.plain(p.message()));
        if (p == null) return;
        try {
            editor.area.addLineHighlight(Math.min(p.line(), editor.area.getLineCount() - 1), new Color(255, 0, 0, 28));
        } catch (javax.swing.text.BadLocationException ignored) {
            // the line is clamped to the document
        }
    }

    /** Replaces the open item with what the store now holds for it (sharing turned on, say). */
    void refreshActive(Store.Item it) {
        if (it != null && isActive(it.id)) active = it;
    }

    private Autosave.Result save(String id, String content) {
        Invalid invalid = hooks.invalid(content);
        // Not saved and not retried: the next edit tries again.
        if (invalid != null) return Autosave.Result.refused(invalid.message());
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
            Invalid why = hooks.invalid(f.content());
            if (why != null) {
                if (invalid == null) invalid = ctx.m("app.item.import.invalid", f.name(), why.message());
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

    /**
     * Offers another kind of file in a menu on the import button, after the screen's own files.
     * Call before {@link #sidebar}. The action stores the items itself, then calls {@link #imported}.
     */
    void addImportSource(String labelKey, Runnable action) {
        importSources.add(new ImportSource(labelKey, action, () -> true, null));
    }

    /** {@link #addImportSource} for a source that is off (tooltip {@code unavailableKey}) while {@code available} is false. */
    void addImportSource(String labelKey, Runnable action, java.util.function.BooleanSupplier available, String unavailableKey) {
        importSources.add(new ImportSource(labelKey, action, available, unavailableKey));
    }

    /** The menu label of the screen's own files once other sources make the import button a menu. */
    void ownImportLabel(String key) {
        ownImportKey = key;
    }

    /** Shows items another import added to the store; the open item stays open. */
    void imported() {
        render();
        if (active == null) openFirst();
        hooks.changed();
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

    /**
     * Shows a change made outside the screen (the AI tools): draws the list again and keeps the open
     * item open while it exists, reloading the editor only when its stored text differs from what
     * the editor shows (so the caret stays put otherwise). The pending edit is saved first.
     */
    void reloadFromStore() {
        autosave.flush();
        String id = active == null ? null : active.id;
        render();
        Store.Item it = id == null ? null : ctx.read(() -> ctx.rawStore().find(spec.kind(), id));
        if (it == null) {
            openFirst();
        } else if (!it.content.equals(editor.getText())) {
            activate(it.id);
        } else {
            active = it;
            title.setText(Ui.plain(it.name));
            metaUpdated.setText(date(it.updatedAt));
            tree.setActive(it.id);
        }
        hooks.changed();
    }

    /** {@code yyyy-MM-dd HH:mm} of a stored timestamp. */
    static String date(String ts) {
        return ts == null || ts.isEmpty() ? "—" : ts.substring(0, Math.min(16, ts.length())).replace('T', ' ');
    }
}
