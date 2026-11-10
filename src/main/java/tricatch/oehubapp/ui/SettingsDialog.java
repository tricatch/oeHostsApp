package tricatch.oehubapp.ui;

import com.fasterxml.jackson.databind.JsonNode;
import tricatch.oehubapp.Store;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The oeHosts / oeProxy import dialog and the preset text format shared with the settings screen. */
final class SettingsDialog {

    private SettingsDialog() {}

    /**
     * Imports one kind from a JSON export (this app's or oeHub's), merged into or replacing what is
     * stored; {@code done} runs after a successful import.
     */
    static void importJson(AppContext ctx, String title, String descText, Store.Kind kind, Runnable done) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON", "json"));
        JTextField path = field("");
        path.setEditable(false);
        JButton browse = new JButton("...");
        browse.setFocusable(false);
        browse.addActionListener(e -> {
            if (fc.showOpenDialog(ctx.frame()) == JFileChooser.APPROVE_OPTION) path.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        fileRow.setOpaque(false);
        fileRow.add(path);
        fileRow.add(browse, BorderLayout.EAST);
        JRadioButton merge = new JRadioButton(ctx.m("nav.restore.mode.merge"), true);
        JRadioButton replace = new JRadioButton(ctx.m("nav.restore.mode.replace"));
        ButtonGroup g = new ButtonGroup();
        g.add(merge);
        g.add(replace);
        merge.setOpaque(false);
        replace.setOpaque(false);
        JLabel error = new JLabel(" ");
        error.setForeground(Ui.DANGER);
        JComponent desc = OeDialog.note(descText);
        Dialogs.fixWidth(desc, 520);
        OeDialog.open(ctx, title, d -> {
            d.body(OeDialog.stack(8, desc, fileRow, merge, replace, error));
            d.button(ctx.m("btn.cancel"), false, d::dispose);
            d.button(ctx.m("btn.import"), true, () -> {
                if (path.getText().isEmpty()) {
                    error.setText(ctx.m("app.import.error.no.file"));
                    return;
                }
                boolean rep = replace.isSelected();
                if (rep && !OeDialog.confirm(ctx, title, ctx.m(kind == Store.Kind.VHOSTS
                        ? "app.data.import.confirm.replace.vhosts" : "app.import.confirm.replace"))) return;
                JsonNode root;
                try {
                    root = Store.MAPPER.readTree(new File(path.getText()));
                } catch (Exception e) {
                    error.setText(ctx.m("app.import.error.format"));
                    return;
                }
                Store.ImportResult r;
                try {
                    synchronized (ctx.rawStore()) {
                        r = ctx.rawStore().importKind(root, rep, kind);
                    }
                } catch (Store.StoreException e) {
                    error.setText(ctx.m(e.code.startsWith("app.") ? e.code : "app.import.failed"));
                    return;
                }
                d.dispose();
                String msg = ctx.m(kind == Store.Kind.HOSTS ? "app.data.import.done.hosts" : "app.data.import.done.vhosts", r.imported());
                if (r.skipped() > 0) msg += " " + ctx.m("app.import.skipped", r.skipped());
                done.run();
                ctx.toast(msg);
            });
        });
    }

    static final int MAX_PRESETS = 500;

    /**
     * The entries of a preset text, "name | value" per line. Blank lines, comments and malformed
     * lines (see {@link #presetLine}) are skipped; entries past {@link #MAX_PRESETS} are dropped.
     */
    static List<Store.Preset> parsePresets(String text) {
        List<Store.Preset> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            Store.Preset p = presetLine(line);
            if (p != null && out.size() < MAX_PRESETS) out.add(p);
        }
        return out;
    }

    /** A line starting with {@code #}; a {@code #} later in a line is not a comment (URLs carry fragments). */
    static boolean isPresetComment(String line) {
        return line.strip().startsWith("#");
    }

    /** The entry on one line, or null for a blank line, a comment or a malformed line. */
    static Store.Preset presetLine(String line) {
        if (line.isBlank() || isPresetComment(line)) return null;
        int i = line.indexOf('|');
        if (i < 0) return null;
        String name = line.substring(0, i).strip(), value = line.substring(i + 1).strip();
        if (name.isEmpty() || value.isEmpty() || name.length() > 200 || value.length() > 4096
                || (name + value).chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return new Store.Preset(name, value);
    }

    private static JTextField field(String v) {
        JTextField f = new JTextField(v);
        f.setFont(Ui.font(Font.PLAIN, 13f));
        f.putClientProperty("FlatLaf.style", "arc:6; focusedBorderColor:#a8d878; margin:4,8,4,8");
        return f;
    }

}
