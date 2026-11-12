package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.store.Store;

import java.util.ArrayList;
import java.util.List;

/** The preset text format shared with the settings screen. */
final class SettingsDialog {

    private SettingsDialog() {}

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

}
