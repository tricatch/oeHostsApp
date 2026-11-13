package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.proxy.ProxyRuntime;
import tricatch.oehostsapp.proxy.Vhosts;
import tricatch.oehostsapp.store.Store;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * The oeProxy screen, laid out like proxy.html: vhost sidebar, YAML editor with the schema check
 * of the web page ({@link Vhosts#problems}), and the routes the embedded proxy serves: the merged
 * YAML of the selected vhosts with oeHub's placeholders filled in ({@link Vhosts#served}).
 */
public final class ProxyTab extends JPanel {

    private final AppContext ctx;
    private final ItemPanel items;
    private final CodeEditor merged = new CodeEditor(SyntaxConstants.SYNTAX_STYLE_YAML);
    private final JPanel mergedCards = new JPanel(new CardLayout());
    private final JButton btnSaveMerged;
    private final JLabel routesTitle;
    private final JLabel proxyStatus = Ui.metaText(" ");

    public ProxyTab(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        items = new ItemPanel(ctx, new ItemPanel.Spec(Store.Kind.VHOSTS, SyntaxConstants.SYNTAX_STYLE_YAML,
                ItemFiles.VHOST_EXT, "proxy.sidebar.header", "tooltip.new.vhost", "app.tooltip.import.vhost",
                "tooltip.delete.vhost", "confirm.delete.vhost", "editor.select.vhost", "proxy.empty", "proxy.sidebar.count"),
                new ItemPanel.Hooks() {
                    @Override
                    public String invalid(String content) {
                        List<Vhosts.Problem> p = Vhosts.problems(content);
                        return p.isEmpty() ? null : message(p.get(0));
                    }

                    @Override
                    public void changed() {
                        // The proxy reads the selected vhosts from the store; drop its cached routes.
                        ProxyRuntime.reload();
                        showMerged();
                    }

                    @Override
                    public void opened(Store.Item item) {
                        lint(item == null ? "" : item.content);
                    }

                    @Override
                    public void edited(String text) {
                        lint(text);
                    }
                });
        items.bindSaveKey(this);
        btnSaveMerged = Ui.iconButton("copy-plus", ctx.m("tooltip.save.merged"), false, this::saveMerged);
        routesTitle = Ui.caps(ctx.m("proxy.panel.header"));
        merged.setEditable(false);
        merged.area.setBackground(Color.WHITE);
        merged.area.setHighlightCurrentLine(false);

        JSplitPane split = Ui.rightSplit(ctx, items.editorPanel(items.editorCards()), buildRoutes());
        add(Ui.sidebarSplit(ctx, items.sidebar(), split), BorderLayout.CENTER);

        items.render();
        items.openFirst();
        showMerged();
    }

    private JComponent buildRoutes() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setMinimumSize(new Dimension(Ui.px(360), Ui.px(10)));
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
        mergedCards.setBackground(Color.WHITE);
        mergedCards.add(merged.scroll, "merged");
        mergedCards.add(empty, "empty");
        p.add(mergedCards, BorderLayout.CENTER);

        JPanel meta = Ui.meta();
        meta.add(proxyStatus, BorderLayout.WEST);
        p.add(meta, BorderLayout.SOUTH);
        ctx.listen(ProxyRuntime::onChange, this::showProxyStatus);
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

    // ── Validation (validateSchema / lintDoc of proxy.html) ─────

    private String message(Vhosts.Problem p) {
        if (p.key() == null) return p.detail();
        return p.path() == null ? ctx.m(p.key()) : p.path() + ": " + ctx.m(p.key());
    }

    /** Marks the first problem's line in the editor and its text as the tooltip of the status. */
    private void lint(String content) {
        CodeEditor editor = items.editor;
        editor.area.removeAllLineHighlights();
        List<Vhosts.Problem> problems = Vhosts.problems(content);
        if (problems.isEmpty()) {
            items.saveStatus.setToolTipText(null);
            return;
        }
        Vhosts.Problem p = problems.get(0);
        try {
            editor.area.addLineHighlight(Math.min(p.line(), editor.area.getLineCount() - 1), new Color(255, 0, 0, 28));
        } catch (javax.swing.text.BadLocationException ignored) {
            // the line is clamped to the document
        }
        items.saveStatus.setToolTipText(message(p));
    }

    // ── Merge ───────────────────────────────────────────────────

    private List<Store.Item> selected() {
        return ctx.read(() -> ctx.rawStore().selected(Store.Kind.VHOSTS));
    }

    private void showMerged() {
        List<Store.Item> sel = selected();
        boolean has = !sel.isEmpty();
        ((CardLayout) mergedCards.getLayout()).show(mergedCards, has ? "merged" : "empty");
        routesTitle.setForeground(has ? new Color(0xe53e3e) : Ui.MUTED);
        btnSaveMerged.setEnabled(sel.size() >= 2);
        merged.setText(Vhosts.served(sel.stream().map(i -> i.content).toList()));
    }

    /** Stores the selected vhosts merged (placeholders kept) as a new vhost. */
    private void saveMerged() {
        List<Store.Item> sel = selected();
        if (sel.size() < 2) return;
        items.addMerged(String.join(" + ", sel.stream().map(v -> v.name).toList()),
                Vhosts.merge(sel.stream().map(i -> i.content).toList()));
    }

    /** Re-reads everything from the store (after an import). */
    void reloadAll() {
        items.reloadAll();
    }

    public void flush() {
        items.flush();
    }
}
