package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.store.Settings;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * The User-Agent / Open URL preset editor, opened from the button next to the launcher field: one
 * {@code name | value} per line. Malformed lines are shown red and kept in the text, but left out
 * of the presets the launcher offers. Save stores the text and closes; Cancel, Esc and the close
 * button drop the edit.
 */
final class PresetsDialog {

    private PresetsDialog() {}

    private static Settings.PresetList list(Settings st, boolean ua) {
        return ua ? st.userAgentPresets : st.openUrlPresets;
    }

    /** Opens the editor of the User-Agent ({@code ua}) or the Open URL presets. */
    static void show(AppContext ctx, boolean ua) {
        CodeEditor editor = new CodeEditor(null);
        editor.presetHighlighting();
        String initial = ctx.settings(st -> {
            String text = list(st, ua).editorText();
            // An empty editor starts with the line format as a comment.
            return text.isBlank() ? (ua ? "# {name} | {user-agent}\n" : "# {name} | {url}\n") : text;
        });
        editor.setText(initial);
        editor.area.setCaretPosition(0);
        JComponent box = SettingsKit.framed(editor.scroll);
        box.setPreferredSize(new Dimension(Ui.px(640), Ui.px(340)));

        JComponent desc = SettingsKit.desc(ctx.m(ua ? "app.presets.ua.desc" : "app.presets.url.desc"));
        // Measured at the editor's width before pack(), or the wrapped lines are cut off.
        Dialogs.fixWidth(desc, 640);
        JPanel body = new JPanel(new BorderLayout(0, Ui.px(12)));
        body.setOpaque(false);
        body.add(desc, BorderLayout.NORTH);
        body.add(box, BorderLayout.CENTER);

        OeDialog d = new OeDialog(ctx.frame(), ctx.m(ua ? "app.presets.ua.title" : "app.presets.url.title"));
        d.body(body);
        JLabel error = new JLabel(" ");
        error.setFont(Ui.font(Font.PLAIN, 12f));
        error.setForeground(Autosave.FAILED);
        d.footerLeft(error);
        Runnable save = () -> {
            String text = editor.getText();
            if (!text.equals(initial)) {
                if (!ctx.saveSettings(s -> list(s, ua).replaceText(text))) {
                    error.setText(ctx.m("status.save.failed"));
                    return;
                }
                ctx.fireSettingsChanged();
            }
            d.dispose();
        };
        d.button(ctx.m("btn.cancel"), false, d::dispose);
        d.button(ctx.m("app.presets.save"), true, save);
        // Ctrl+S / Cmd+S saves like the other editors.
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        d.getRootPane().registerKeyboardAction(e -> save.run(), ks, JComponent.WHEN_IN_FOCUSED_WINDOW);
        SwingUtilities.invokeLater(editor.area::requestFocusInWindow);
        d.showCentered();
    }

    // ── The line format (also used by PresetTokenMaker; kept in Settings) ─────────

    static boolean isComment(String line) {
        return Settings.isPresetComment(line);
    }

    static Settings.Preset line(String line) {
        return Settings.presetLine(line);
    }
}
