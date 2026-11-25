package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.hosts.SwitchHostsBackup;
import tricatch.oehostsapp.hosts.SwitchHostsFolder;
import tricatch.oehostsapp.store.Store;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Importing from SwitchHosts into the oeHosts list, from its exported backup
 * ({@link SwitchHostsBackup}) or straight from its data folder ({@link SwitchHostsFolder}).
 * Offered by the oeHosts screen's import menu and by Settings → General → Import.
 */
final class SwitchHostsImport {

    private SwitchHostsImport() {}

    /** What one of the imports reads. */
    private interface Source {
        SwitchHostsBackup.Result read() throws IOException, SwitchHostsBackup.FormatException;
    }

    /**
     * Asks for a backup file and adds its folders and profiles to the oeHosts list, none of them
     * selected. {@code flush} saves the editors' pending edits first; {@code refresh} shows the
     * stored result.
     */
    static void fromFile(AppContext ctx, Component parent, Runnable flush, Runnable refresh) {
        Path file = ItemFiles.choose(parent, "json");
        if (file != null) add(ctx, () -> SwitchHostsBackup.read(file), flush, refresh);
    }

    /**
     * Adds what SwitchHosts' data folder holds, as {@link #fromFile} does: the folder found on this
     * PC ({@link SwitchHostsFolder#defaultFolder}) after showing its path, or another folder the
     * user picks from there. Callers offer it only while a folder is found. The folder is only read.
     */
    static void fromFolder(AppContext ctx, Component parent, Runnable flush, Runnable refresh) {
        Path found = SwitchHostsFolder.defaultFolder();
        Path dir;
        if (found != null) {
            int[] choice = {0};
            JComponent note = OeDialog.note(ctx.m("app.import.switchhosts.folder.found", found.toString()));
            Dialogs.fixWidth(note, 440);
            OeDialog.open(ctx, ctx.m("app.import.switchhosts"), d -> {
                d.body(note);
                d.button(ctx.m("btn.import"), true, () -> {
                    choice[0] = 1;
                    d.dispose();
                });
                d.button(ctx.m("app.import.switchhosts.folder.other"), false, () -> {
                    choice[0] = 2;
                    d.dispose();
                });
                d.button(ctx.m("btn.cancel"), false, d::dispose);
            });
            if (choice[0] == 0) return;
            dir = choice[0] == 1 ? found : chooseFolder(ctx, parent, found);
        } else {
            // The menu item and the button are off while nothing is found; this is only reached
            // when SwitchHosts' folder went away in between.
            OeDialog.info(ctx, ctx.m("app.import.switchhosts"), ctx.m("app.import.switchhosts.folder.notfound"));
            return;
        }
        if (dir != null) add(ctx, () -> SwitchHostsFolder.read(dir), flush, refresh);
    }

    /** Asks for a folder, starting at {@code start} (null: the default); null if cancelled. */
    private static Path chooseFolder(AppContext ctx, Component parent, Path start) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(ctx.m("app.import.switchhosts.folder.choose"));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        // ~/.SwitchHosts is a hidden folder.
        fc.setFileHidingEnabled(false);
        if (start != null) {
            if (start.getParent() != null) fc.setCurrentDirectory(start.getParent().toFile());
            fc.setSelectedFile(start.toFile());
        }
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) return null;
        return fc.getSelectedFile().toPath();
    }

    /** Reads {@code source} and stores what it holds; lines that had to be commented out are reported in a dialog. */
    private static void add(AppContext ctx, Source source, Runnable flush, Runnable refresh) {
        String title = ctx.m("app.import.switchhosts");
        SwitchHostsBackup.Result r;
        try {
            r = source.read();
        } catch (SwitchHostsBackup.FormatException e) {
            OeDialog.info(ctx, title, ctx.m("app.import.switchhosts.error." + e.code));
            return;
        } catch (IOException | RuntimeException e) {
            ctx.toast(ctx.m("app.import.failed"));
            return;
        }
        flush.run();
        if (!ctx.storeRun(s -> s.addItems(Store.Kind.HOSTS, r.entries()))) return;
        refresh.run();
        String done = ctx.m("app.import.switchhosts.done", r.profiles());
        if (r.commentedLines() > 0) {
            OeDialog.info(ctx, title, done + "\n" + ctx.m("app.import.switchhosts.commented", r.commentedLines()));
        } else {
            ctx.toast(done);
        }
    }
}
