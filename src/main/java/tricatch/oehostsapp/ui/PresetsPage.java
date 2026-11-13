package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.store.Settings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * Settings → oeHosts → User-Agent / Open URL presets: one {@code name | value} per line, saved like
 * the oeHosts/oeProxy editors (2s after the last edit, or at once on Ctrl+S). Malformed lines are
 * shown red and kept in the text, but left out of the presets the launcher offers.
 */
final class PresetsPage {

    static final int MAX_PRESETS = 500;

    private final SettingsTab tab;
    private final AppContext ctx;
    private final boolean ua;

    PresetsPage(SettingsTab tab, boolean ua) {
        this.tab = tab;
        this.ctx = tab.ctx();
        this.ua = ua;
    }

    private Settings.PresetList list(Settings st) {
        return ua ? st.userAgentPresets : st.openUrlPresets;
    }

    JComponent build() {
        CodeEditor editor = new CodeEditor(null);
        editor.presetHighlighting();
        String initial = ctx.settings(st -> {
            String text = list(st).editorText();
            // An empty editor starts with the line format as a comment.
            return text.isBlank() ? (ua ? "# {name} | {user-agent}\n" : "# {name} | {url}\n") : text;
        });
        editor.setText(initial);
        JComponent box = SettingsKit.framed(editor.scroll);
        box.setPreferredSize(new Dimension(600, 320));
        String pageId = ua ? "ua" : "url";
        JLabel st = tab.status(pageId);
        Autosave autosave = new Autosave(ctx, editor, st, (id, text) -> {
            List<Settings.Preset> parsed = parse(text);
            if (!ctx.saveSettings(s -> {
                list(s).items = parsed;
                list(s).text = text;
            })) {
                return Autosave.Result.retry(ctx.m("status.save.failed"));
            }
            ctx.fireSettingsChanged();
            return Autosave.Result.OK;
        });
        autosave.activate(pageId, initial);
        tab.track(autosave);
        editor.onEdit(() -> autosave.changed(editor.getText()));
        // Not SettingsKit.page(): the editor fills the height down to the bottom bar instead of scrolling with the page.
        JPanel page = new JPanel(new BorderLayout(0, 14));
        page.setBackground(Color.WHITE);
        page.setBorder(new EmptyBorder(18, 22, 18, 22));
        page.add(SettingsKit.desc(ctx.m(ua ? "app.settings.ua.presets.desc" : "app.settings.url.presets.desc")), BorderLayout.NORTH);
        page.add(box, BorderLayout.CENTER);
        autosave.bindSaveKey(page);
        return page;
    }

    // ── The line format (also used by PresetTokenMaker) ─────────

    /**
     * The entries of a preset text, "name | value" per line. Blank lines, comments and malformed
     * lines (see {@link #line}) are skipped; entries past {@link #MAX_PRESETS} are dropped.
     */
    static List<Settings.Preset> parse(String text) {
        List<Settings.Preset> out = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            Settings.Preset p = line(line);
            if (p != null && out.size() < MAX_PRESETS) out.add(p);
        }
        return out;
    }

    /** A line starting with {@code #}; a {@code #} later in a line is not a comment (URLs carry fragments). */
    static boolean isComment(String line) {
        return line.strip().startsWith("#");
    }

    /** The entry on one line, or null for a blank line, a comment or a malformed line. */
    static Settings.Preset line(String line) {
        if (line.isBlank() || isComment(line)) return null;
        int i = line.indexOf('|');
        if (i < 0) return null;
        String name = line.substring(0, i).strip(), value = line.substring(i + 1).strip();
        if (name.isEmpty() || value.isEmpty() || name.length() > 200 || value.length() > 4096
                || (name + value).chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return new Settings.Preset(name, value);
    }
}
