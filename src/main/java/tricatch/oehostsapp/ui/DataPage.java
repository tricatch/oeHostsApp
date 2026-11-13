package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.proxy.Ca;
import tricatch.oehostsapp.proxy.ProxyRuntime;
import tricatch.oehostsapp.store.Backup;
import tricatch.oehostsapp.store.Store;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → General → data: exporting the checked parts (oeHosts, oeProxy, settings, CA) to one
 * backup ZIP ({@link Backup}) and importing them back, added to or overwriting what is stored
 * (left); where the data file is (right).
 */
final class DataPage {

    /** The check boxes, in {@link Parts} order. */
    private static final String[] PARTS = {"app.data.hosts", "app.data.vhosts", "app.data.settings", "app.data.ca"};
    private static final int CA_PART = 3;

    /** The parts of the data chosen for an export or import. */
    private record Parts(boolean hosts, boolean vhosts, boolean settings, boolean ca) {
        boolean any() {
            return hosts || vhosts || settings || ca;
        }

        boolean data() {
            return hosts || vhosts || settings;
        }

        boolean[] all() {
            return new boolean[]{hosts, vhosts, settings, ca};
        }
    }

    private final SettingsTab tab;
    private final AppContext ctx;

    DataPage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        Path file = ctx.rawStore().path().toAbsolutePath();
        JButton folder = Ui.secondary(ctx.m("app.settings.open.folder"), "folder-open", () -> openFolder(ctx, file.getParent()));
        JCheckBox[] exParts = new JCheckBox[PARTS.length];
        JButton ex = Ui.secondary(ctx.m("app.data.export"), "file-down", () -> export(chosen(exParts)));
        JCheckBox[] inParts = new JCheckBox[PARTS.length];
        JButton in = Ui.secondary(ctx.m("app.data.import"), "circle-fading-plus", () -> importData(chosen(inParts)));
        // Left: export above import, each a section of its own (heading, description | its own
        // choice of parts | its action, set apart by lines); right: where the data file is (looking only).
        return fullPage(halves(
                column(section("app.data.export", "app.data.export.desc", exParts, ex),
                        (JComponent) Box.createVerticalStrut(Ui.px(18)),
                        section("app.data.import", "app.data.import.desc", inParts, in)),
                column(heading(ctx.m("app.settings.data.file")), line(), value(file.toString()), fileSize(file), line(),
                        rightAligned(folder))));
    }

    /** The data file's size, read again each time the page is shown (the file grows with every save). */
    private static JComponent fileSize(Path file) {
        JTextField size = value("");
        Runnable read = () -> {
            try {
                size.setText(Ui.byteSize(Files.size(file)));
            } catch (IOException e) {
                size.setText("-");
            }
        };
        read.run();
        size.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && size.isShowing()) read.run();
        });
        return size;
    }

    /**
     * One of the export / import sections: a heading, a description, one check box per part (all
     * on by default except the CA, whose private key is sensitive) and the action, which is off
     * while nothing is chosen.
     */
    private JComponent section(String titleKey, String descKey, JCheckBox[] parts, JButton action) {
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checks.setOpaque(false);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = new JCheckBox(ctx.m(PARTS[i]), i != CA_PART);
            parts[i].setOpaque(false);
            parts[i].setFocusable(false);
            parts[i].setFont(Ui.font(Font.PLAIN, 13f));
            parts[i].addActionListener(e -> action.setEnabled(chosen(parts).any()));
            if (i > 0) checks.add(Box.createHorizontalStrut(Ui.px(18)));
            checks.add(parts[i]);
        }
        return column(heading(ctx.m(titleKey)), desc(ctx.m(descKey)), line(), checks, line(), rightAligned(action));
    }

    private static Parts chosen(JCheckBox[] b) {
        return new Parts(b[0].isSelected(), b[1].isSelected(), b[2].isSelected(), b[3].isSelected());
    }

    /** The names of the parts that are on, comma separated, for a message. */
    private String names(Parts p) {
        List<String> n = new ArrayList<>();
        boolean[] on = p.all();
        for (int i = 0; i < on.length; i++) if (on[i]) n.add(ctx.m(PARTS[i]));
        return String.join(", ", n);
    }

    /** Exports the chosen parts to one backup ZIP. */
    private void export(Parts p) {
        if (!p.any()) return;
        tab.flushAll();
        if (p.ca() && !OeDialog.confirm(ctx, ctx.m("app.data.export.title"), ctx.m("app.data.export.confirm.ca"))) return;
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("oehostsapp-backup-" + ts + ".zip"));
        if (fc.showSaveDialog(ctx.frame()) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        try {
            byte[] json = ctx.read(() -> {
                try {
                    return Store.MAPPER.writeValueAsBytes(ctx.rawStore().exportParts(p.hosts(), p.vhosts(), p.settings()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Backup.write(json, p.ca() ? ProxyRuntime.caDir() : null, out);
            ctx.toast(ctx.m("app.data.exported"));
        } catch (Exception e) {
            ctx.toast(ctx.m("app.data.export.failed"));
        }
    }

    /**
     * Imports the chosen parts from a backup ZIP: each part both chosen and in the file is added to,
     * or overwrites, what is stored (the CA, being one, is always overwritten). The CA pair is
     * checked before anything changes. The window is rebuilt when the settings change (the
     * language may differ).
     */
    private void importData(Parts chosen) {
        if (!chosen.any()) return;
        tab.flushAll();
        String title = ctx.m("app.data.import.title");
        JFileChooser fc = new JFileChooser();
        fc.setAcceptAllFileFilterUsed(false);
        fc.setFileFilter(new FileNameExtensionFilter("*.zip", "zip"));
        if (fc.showOpenDialog(ctx.frame()) != JFileChooser.APPROVE_OPTION) return;
        Backup.Contents b;
        try {
            b = Backup.read(fc.getSelectedFile().toPath());
        } catch (Exception e) {
            OeDialog.info(ctx, title, ctx.m("app.data.import.error.format"));
            return;
        }
        Parts p = new Parts(chosen.hosts() && b.hasHosts(), chosen.vhosts() && b.hasVhosts(),
                chosen.settings() && b.hasSettings(), chosen.ca() && b.hasCa());
        if (!p.any()) {
            OeDialog.info(ctx, title, ctx.m("app.data.import.error.none"));
            return;
        }
        Parts missing = new Parts(chosen.hosts() && !p.hosts(), chosen.vhosts() && !p.vhosts(),
                chosen.settings() && !p.settings(), chosen.ca() && !p.ca());
        Boolean replace = askMode(title, p, missing);
        if (replace == null) return;
        if (p.ca() && !importCa(title, b)) return;
        if (p.data()) {
            boolean[] failed = {false};
            boolean saved = ctx.storeRun(s -> {
                try {
                    if (replace) s.restoreParts(b.data(), p.hosts(), p.vhosts(), p.settings());
                    else s.mergeParts(b.data(), p.hosts(), p.vhosts(), p.settings());
                } catch (IOException e) {
                    failed[0] = true;
                }
            });
            if (!saved) return;
            if (failed[0]) {
                OeDialog.info(ctx, title, ctx.m("app.data.import.error.format"));
                return;
            }
        }
        if (p.vhosts() || p.settings() || p.ca()) ProxyRuntime.restart();
        if (p.settings()) {
            OeDialog.info(ctx, title, ctx.m("app.data.imported.reload"));
            tab.host().relaunch();
        } else {
            tab.host().reloadAll();
            ctx.toast(ctx.m("app.data.imported"));
        }
    }

    /** Replaces the CA with the backup's; false (after a message) when the pair is refused. */
    private boolean importCa(String title, Backup.Contents b) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("oehostsapp-restore");
            Path cer = Files.write(dir.resolve(Ca.CERT_FILE), b.cert()), key = Files.write(dir.resolve(Ca.KEY_FILE), b.key());
            Ca.importInto(ProxyRuntime.caDir(), cer, key, "");
            return true;
        } catch (Ca.ImportException e) {
            OeDialog.info(ctx, title, ctx.m(e.code));
        } catch (Exception e) {
            OeDialog.info(ctx, title, ctx.m("app.import.failed"));
        } finally {
            deleteTree(dir);
        }
        return false;
    }

    /**
     * Asks how to import the parts {@code p}: add to the current data (the default) or overwrite it.
     * True to overwrite, false to add, null when cancelled. Overwriting asks once more.
     */
    private Boolean askMode(String title, Parts p, Parts missing) {
        String text = ctx.m("app.data.import.parts", names(p));
        if (missing.any()) text += " " + ctx.m("app.data.import.missing", names(missing));
        if (p.ca()) text += " " + ctx.m("app.data.import.ca.always");
        JComponent note = OeDialog.note(text);
        Dialogs.fixWidth(note, 440);
        JRadioButton add = new JRadioButton(ctx.m("app.data.import.mode.add"), true);
        JRadioButton overwrite = new JRadioButton(ctx.m("app.data.import.mode.replace"));
        ButtonGroup g = new ButtonGroup();
        for (JRadioButton r : new JRadioButton[]{add, overwrite}) {
            r.setOpaque(false);
            r.setFocusable(false);
            r.setFont(Ui.font(Font.PLAIN, 13f));
            g.add(r);
        }
        Boolean[] result = {null};
        OeDialog.open(ctx, title, d -> {
            d.body(OeDialog.stack(8, note, add, overwrite));
            d.button(ctx.m("btn.import"), true, () -> {
                result[0] = overwrite.isSelected();
                d.dispose();
            });
            d.button(ctx.m("btn.cancel"), false, d::dispose);
        });
        if (Boolean.TRUE.equals(result[0])
                && !OeDialog.confirm(ctx, title, ctx.m("app.data.import.confirm", names(p)))) return null;
        return result[0];
    }

    private static void deleteTree(Path dir) {
        if (dir == null) return;
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) Files.deleteIfExists(p);
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
            // temp files: left to the OS
        }
    }
}
