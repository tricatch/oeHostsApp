package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.proxy.Ca;
import tricatch.oehostsapp.proxy.ForwardProxy;
import tricatch.oehostsapp.proxy.ProxyRuntime;
import tricatch.oehostsapp.web.PhoneSetup;
import tricatch.oehostsapp.web.ShareServer;

import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/**
 * Settings → oeProxy → CA certificate: the CA in use and its files, registering it with the OS,
 * exporting, importing or making another one (left), and installing it on a mobile device (right).
 */
final class CaPage {

    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final SettingsTab tab;
    private final AppContext ctx;

    CaPage(SettingsTab tab) {
        this.tab = tab;
        this.ctx = tab.ctx();
    }

    JComponent build() {
        // The certificate's details and its files in one table; refresh fills it (details 0-3, files 4-5).
        JTextField[] v = new JTextField[6];
        JPanel info = table(new String[][]{
                {ctx.m("app.settings.ca.subject"), "-"},
                {ctx.m("app.settings.ca.valid"), "-"},
                {ctx.m("app.settings.ca.sha256"), "-"},
                {ctx.m("app.settings.ca.trust"), "-"},
                {ctx.m("app.settings.ca.file"), "-"},
                {ctx.m("app.settings.ca.key"), "-"}}, v);
        // Messages go to the bottom bar; the buttons sit at the bottom right, the main action last.
        JLabel st = tab.status("ca");
        JButton install = primary(ctx.m("app.settings.ca.install"), "shield-check", null);
        JButton copyCmd = Ui.secondary(ctx.m("app.settings.ca.copy.cmd"), "copy", () -> {
            OeDialog.copy(installCommand());
            ctx.toast(ctx.m("app.settings.copied"));
        });
        // Registering is offered only while the OS does not trust the CA yet.
        Runnable refresh = () -> {
            boolean registered = Boolean.TRUE.equals(fill(v));
            install.setVisible(WIN && !registered);
            copyCmd.setVisible(!registered);
        };
        install.addActionListener(e -> install(st, refresh));
        JButton folder = Ui.secondary(ctx.m("app.settings.open.folder"), "folder-open", () -> openFolder(ctx, ProxyRuntime.caDir()));
        JButton export = Ui.secondary(ctx.m("app.settings.ca.export"), "file-down", this::export);
        JButton regen = Ui.secondary(ctx.m("app.settings.ca.regenerate"), "refresh-cw", () -> regenerate(st, refresh));
        JButton imp = Ui.secondary(ctx.m("app.settings.ca.import"), "key-round", () -> importCa(st, refresh));
        refresh.run();
        return fullPage(halves(
                column(desc(ctx.m("app.settings.ca.desc")), line(), info, line(), rightAligned(folder, copyCmd, install),
                        rightAligned(export, imp, regen)),
                mobileSection()));
    }

    /** Fills the CA rows; returns whether the OS trusts the CA (null: no CA, or cannot be told). */
    private Boolean fill(JTextField[] v) {
        Path cert = ProxyRuntime.caCert();
        X509Certificate x = Ca.read(cert);
        if (x == null) {
            v[4].setText(ctx.m("app.settings.ca.missing"));
            return null;
        }
        v[4].setText(cert.toAbsolutePath().toString());
        Boolean trusted = null;
        try {
            SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd");
            v[0].setText(x.getSubjectX500Principal().getName());
            v[1].setText(d.format(x.getNotBefore()) + " ~ " + d.format(x.getNotAfter()));
            v[2].setText(HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(x.getEncoded())));
            trusted = trusted(x);
            v[3].setText(trusted == null ? ctx.m("app.settings.ca.trust.unknown")
                    : ctx.m(trusted ? "app.settings.ca.trust.yes" : "app.settings.ca.trust.no"));
            v[3].setForeground(trusted == null ? Ui.MUTED : trusted ? Ui.BC700 : Ui.DANGER);
            v[3].setFont(Ui.font(Font.BOLD, 12.5f));
        } catch (Exception e) {
            v[0].setText(e.getMessage());
        }
        v[5].setText(ProxyRuntime.caDir().resolve(Ca.KEY_FILE).toAbsolutePath().toString());
        return trusted;
    }

    /** Whether the OS trusts the CA (Windows certificate store only); null when it cannot be told. */
    private static Boolean trusted(X509Certificate x) {
        if (!WIN) return null;
        try {
            KeyStore ks = KeyStore.getInstance("Windows-ROOT");
            ks.load(null, null);
            return ks.getCertificateAlias(x) != null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Adds the CA to the current user's Windows trust store ({@code certutil -user}): no
     * administrator rights needed; Windows asks the user to confirm.
     */
    private void install(JLabel st, Runnable refresh) {
        Path cert = ProxyRuntime.caCert();
        if (cert == null) return;
        st.setForeground(Ui.MUTED);
        st.setText(ctx.m("app.settings.ca.installing"));
        Thread.ofVirtual().name("ca-install").start(() -> {
            int code;
            try {
                Process p = new ProcessBuilder("certutil", "-user", "-addstore", "Root", cert.toAbsolutePath().toString())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                code = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
            } catch (Exception e) {
                code = -1;
            }
            int c = code;
            SwingUtilities.invokeLater(() -> {
                if (c == 0) ok(st, ctx.m("app.settings.ca.installed"));
                else error(st, ctx.m("app.settings.ca.install.failed"));
                refresh.run();
            });
        });
    }

    private String installCommand() {
        String path = ProxyRuntime.caCert() == null ? Ca.CERT_FILE : ProxyRuntime.caCert().toAbsolutePath().toString();
        return WIN ? "certutil -addstore -f Root \"" + path + "\""
                : "sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain \"" + path + "\"";
    }

    /**
     * Brings in an existing CA (e.g. oeHub's, already trusted by the browser): a certificate file
     * and its private key (PEM PKCS#8, or a PKCS#12 keystore that may hold the certificate too).
     */
    private void importCa(JLabel st, Runnable refresh) {
        JTextField cert = fileField(), key = fileField();
        JPasswordField pwd = Ui.formField(new JPasswordField(), "");
        JLabel error = new JLabel(" ");
        error.setForeground(Ui.DANGER);
        JComponent note = OeDialog.note(ctx.plain("app.settings.ca.import.desc"));
        Dialogs.fixWidth(note, 520);
        OeDialog.open(ctx, ctx.m("app.settings.ca.import"), d -> {
            d.body(OeDialog.stack(8, note,
                    label(ctx.m("app.settings.ca.import.cert")), fileRow(cert, "cer", "crt", "pem"),
                    label(ctx.m("app.settings.ca.import.key")), fileRow(key, "pfx", "p12", "key", "pem"),
                    label(ctx.m("app.settings.ca.import.password")), pwd, error));
            d.button(ctx.m("btn.cancel"), false, d::dispose);
            d.button(ctx.m("btn.import"), true, () -> {
                if (key.getText().isEmpty()) {
                    error.setText(ctx.m("app.settings.ca.import.error.no.key"));
                    return;
                }
                if (!OeDialog.confirm(ctx, ctx.m("app.settings.ca.import"), ctx.m("app.settings.ca.import.confirm"))) return;
                try {
                    Ca.importInto(ProxyRuntime.caDir(), cert.getText().isEmpty() ? null : Path.of(cert.getText()),
                            Path.of(key.getText()), new String(pwd.getPassword()));
                } catch (Ca.ImportException e) {
                    error.setText(ctx.m(e.code));
                    return;
                }
                d.dispose();
                ProxyRuntime.restart();
                refresh.run();
                ok(st, ctx.m("app.settings.ca.imported"));
            });
        });
    }

    private static JTextField fileField() {
        JTextField f = new JTextField();
        f.setEditable(false);
        f.setFont(Ui.font(Font.PLAIN, 13f));
        f.putClientProperty("FlatLaf.style", "arc:6; margin:4,8,4,8");
        return f;
    }

    private JComponent fileRow(JTextField f, String... ext) {
        JButton browse = new JButton("...");
        browse.setFocusable(false);
        browse.addActionListener(e -> {
            // Only the given extensions: no "All files" choice, and a typed name of another type is refused.
            var filter = new FileNameExtensionFilter(String.join(", ", Arrays.stream(ext).map(x -> "*." + x).toList()), ext);
            // Opens where the last certificate or key was picked (shared by both rows, kept across restarts).
            String lastDir = ctx.settings(st -> st.caImportDir);
            File start = lastDir == null ? null : new File(lastDir);
            JFileChooser fc = new JFileChooser(start != null && start.isDirectory() ? start : null);
            fc.setAcceptAllFileFilterUsed(false);
            fc.setFileFilter(filter);
            if (fc.showOpenDialog(ctx.frame()) != JFileChooser.APPROVE_OPTION) return;
            File file = fc.getSelectedFile();
            File dir = fc.getCurrentDirectory();
            if (dir != null && !dir.getAbsolutePath().equals(lastDir)) ctx.saveSettings(st -> st.caImportDir = dir.getAbsolutePath());
            if (file.isDirectory() || !filter.accept(file)) {
                ctx.toast(ctx.m("app.settings.ca.import.error.type", filter.getDescription()));
                return;
            }
            f.setText(file.getAbsolutePath());
        });
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(f);
        row.add(browse, BorderLayout.EAST);
        return row;
    }

    /** Asks for the new CA's name (CN), then replaces the CA with a new one and restarts the proxy. */
    private void regenerate(JLabel st, Runnable refresh) {
        JTextField cn = Ui.formField(new JTextField(Objects.requireNonNullElse(Ca.commonName(ProxyRuntime.caCert()), Ca.DEFAULT_NAME)), "");
        JLabel error = new JLabel(" ");
        error.setForeground(Ui.DANGER);
        JComponent note = OeDialog.note(ctx.plain("app.settings.ca.regenerate.confirm"));
        Dialogs.fixWidth(note, 440);
        OeDialog.open(ctx, ctx.m("app.settings.ca.regenerate"), d -> {
            d.body(OeDialog.stack(8, note, label(ctx.m("app.settings.ca.cn", Ca.NAME_MAX)), cn, error));
            d.button(ctx.m("btn.cancel"), false, d::dispose);
            JButton create = d.button(ctx.m("app.settings.ca.regenerate"), true, () -> {
                String name = cn.getText().strip();
                String err = Ca.nameError(name);
                if (err != null) {
                    error.setText(ctx.m(err, Ca.NAME_MAX));
                    return;
                }
                d.dispose();
                try {
                    Ca.generate(ProxyRuntime.caDir(), name);
                    ProxyRuntime.restart();
                    refresh.run();
                    ok(st, ctx.m("app.settings.ca.regenerated"));
                } catch (Exception e) {
                    error(st, e.getMessage());
                }
            });
            // Required field: nothing to submit while it is empty; the message is cleared on edit.
            Runnable sync = () -> {
                create.setEnabled(!cn.getText().isBlank());
                error.setText(" ");
            };
            cn.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                    sync.run();
                }

                public void removeUpdate(DocumentEvent e) {
                    sync.run();
                }

                public void changedUpdate(DocumentEvent e) {
                    sync.run();
                }
            });
            sync.run();
            cn.selectAll();
        });
    }

    private void export() {
        Path cert = ProxyRuntime.caCert();
        if (cert == null) return;
        JFileChooser fc = new JFileChooser();
        // Named after the CA (its CN), made safe for the file system.
        fc.setSelectedFile(new File(Ca.fileName(Ca.commonName(cert), "oeHostsApp-ca") + ".cer"));
        if (fc.showSaveDialog(ctx.frame()) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.copy(cert, fc.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            ctx.toast(ctx.m("app.settings.ca.exported"));
        } catch (Exception e) {
            ctx.toast(ctx.m("status.save.failed"));
        }
    }

    // ── Mobile devices ──────────────────────────────────────────

    /**
     * The right half: installing the CA on a phone. A QR code of the web service's certificate page
     * ({@link PhoneSetup}), which the phone opens through the forward proxy
     * ({@code ForwardProxy.webService}). Only the download; nothing is configured here.
     */
    private JComponent mobileSection() {
        // The phone reaches the page through the forward proxy, so the proxy has to be open to the
        // phone's network and the web service has to run (on 127.0.0.1 at least).
        JComponent fwdOff = OeDialog.warn(ctx.m("app.settings.phone.off"), null);
        JPanel fwdOffButtons = buttons(Ui.secondary(ctx.m("app.settings.phone.open.fwd"), "settings", () -> tab.showPage("fwd")));
        JComponent webOff = OeDialog.warn(ctx.m("app.settings.phone.web.stopped"), null);
        JPanel webOffButtons = buttons(Ui.secondary(ctx.m("app.settings.phone.open.web"), "settings", () -> tab.showPage("web")));

        JComboBox<Network.Lan> address = Ui.combo();
        address.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, value, i, sel, focus);
                if (value instanceof Network.Lan a) setText(ctx.m("app.settings.bind.iface", a.ip(), a.iface()));
                return this;
            }
        });
        JLabel addressLabel = label(ctx.m("app.settings.phone.address"));
        // The list takes the rest of the row and shrinks with it (a long adapter name must not push it to a new line).
        JPanel addressRow = formRow(addressLabel, address);
        address.setMinimumSize(new Dimension(0, address.getPreferredSize().height));
        JComponent noAddress = OeDialog.warn(ctx.m("app.settings.phone.no.address"), null);

        JTextField[] v = new JTextField[1];
        JPanel info = table(new String[][]{{ctx.m("app.settings.phone.ca.url"), "-"}}, v);
        alignWithTable(info, ROW_GAP, addressLabel);
        JButton copyUrl = Ui.secondary(ctx.m("app.settings.phone.copy.url"), "copy", () -> {
            OeDialog.copy(v[0].getText());
            ctx.toast(ctx.m("app.settings.copied"));
        });
        QrCode qr = new QrCode(196);
        qr.setBorder(new LineBorder(Ui.BORDER));
        // The QR code at its own size, centered under the address.
        JPanel qrWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        qrWrap.setOpaque(false);
        qrWrap.add(qr);
        // The copy button at the right end of the URL row.
        JPanel urlRow = new JPanel(new BorderLayout(10, 0));
        urlRow.setOpaque(false);
        urlRow.add(info, BorderLayout.CENTER);
        JPanel copyWrap = new JPanel(new GridBagLayout());
        copyWrap.setOpaque(false);
        copyWrap.add(copyUrl);
        urlRow.add(copyWrap, BorderLayout.EAST);
        JComponent ready = OeDialog.stack(14, addressRow, urlRow);

        Runnable fill = () -> {
            String ip = address.getSelectedItem() instanceof Network.Lan a ? a.ip() : null;
            int wp = ShareServer.port();
            String url = ip == null || wp <= 0 ? null : PhoneSetup.pageUrl(ip, wp);
            v[0].setText(url == null ? "-" : url);
            copyUrl.setEnabled(url != null);
            qr.setText(url);
        };
        Runnable refresh = () -> {
            boolean fwdOn = ForwardProxy.state() == ForwardProxy.State.RUNNING && ForwardProxy.lan();
            boolean webOn = ShareServer.state() == ShareServer.State.RUNNING;
            // The addresses the proxy answers on: the phone sets one of them as its Wi-Fi proxy.
            String prevIp = address.getSelectedItem() instanceof Network.Lan a ? a.ip() : null;
            List<Network.Lan> reachable = Network.addressesFor(ForwardProxy.bind());
            address.removeAllItems();
            for (Network.Lan a : reachable) address.addItem(a);
            for (Network.Lan a : reachable) if (a.ip().equals(prevIp)) address.setSelectedItem(a);
            fwdOff.setVisible(!fwdOn);
            fwdOffButtons.setVisible(!fwdOn);
            webOff.setVisible(!webOn);
            webOffButtons.setVisible(!webOn);
            noAddress.setVisible(fwdOn && webOn && reachable.isEmpty());
            ready.setVisible(fwdOn && webOn && !reachable.isEmpty());
            qrWrap.setVisible(ready.isVisible());
            fill.run();
            ready.revalidate();
        };
        address.addActionListener(e -> fill.run());
        ctx.listen(ShareServer::onChange, refresh);
        ctx.listen(ForwardProxy::onChange, refresh);
        refresh.run();
        JLabel heading = new JLabel(ctx.m("app.settings.phone.title"));
        heading.setFont(Ui.font(Font.BOLD, 13.5f));
        heading.setForeground(Ui.TEXT);
        return column(heading, desc(ctx.m("app.settings.phone.desc")), line(), fwdOff, fwdOffButtons, webOff,
                webOffButtons, noAddress, ready, qrWrap);
    }
}
