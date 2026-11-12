package tricatch.oehostsapp.ui;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Saves one hosts profile / vhost to a file and reads files back, by extension (.oehosts / .oeproxy). */
final class ItemFiles {

    static final String HOSTS_EXT = "oehosts";
    static final String VHOST_EXT = "oeproxy";

    private ItemFiles() {}

    /** A file read for import: the item name (file name without extension) and its text. */
    record Loaded(String name, String content) {}

    /** Asks where to save {@code text} as {@code <name>.<ext>}; returns false if writing failed. */
    static boolean save(Component parent, String name, String ext, String text) {
        JFileChooser fc = chooser(ext);
        fc.setSelectedFile(new File(name.replaceAll("[\\\\/:*?\"<>|]", "_") + "." + ext));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return true;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith("." + ext)) f = new File(f.getPath() + "." + ext);
        try {
            Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Asks for one or more {@code .<ext>} files; returns them read, empty if cancelled. Throws if a file cannot be read. */
    static List<Loaded> open(Component parent, String ext) throws IOException {
        JFileChooser fc = chooser(ext);
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return List.of();
        List<Loaded> out = new ArrayList<>();
        for (File f : fc.getSelectedFiles()) {
            Path p = f.toPath();
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            out.add(new Loaded(dot > 0 ? n.substring(0, dot) : n, Files.readString(p, StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static JFileChooser chooser(String ext) {
        JFileChooser fc = new JFileChooser();
        FileNameExtensionFilter filter = new FileNameExtensionFilter("*." + ext, ext);
        fc.addChoosableFileFilter(filter);
        fc.setAcceptAllFileFilterUsed(false);
        fc.setFileFilter(filter);
        return fc;
    }
}
