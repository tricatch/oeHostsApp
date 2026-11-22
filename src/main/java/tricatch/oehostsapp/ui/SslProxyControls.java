package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.proxy.Ca;
import tricatch.oehostsapp.proxy.ForwardProxy;
import tricatch.oehostsapp.proxy.ProxyRuntime;
import tricatch.oehostsapp.proxy.SslProxy;
import tricatch.oehostsapp.proxy.SslRules;

import javax.swing.*;
import java.awt.*;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * The SSL proxy in the Network tab's navbar: one button of two halves, {@code [on/off | settings]},
 * all in the lock's shapes. The left half switches at once, the right opens {@link SslProxyDialog}
 * with the domain list. Both halves show the state together: closed locks while off, open ones on
 * white while on, amber when something keeps it from working.
 */
final class SslProxyControls {

    /** Amber: the Network table's color for 4xx. */
    private static final Color PROBLEM = new Color(0xb8860b);

    private final AppContext ctx;
    private final Runnable openCaSettings;
    final JPanel panel;
    private final JButton toggle;
    private final JButton settings;

    SslProxyControls(AppContext ctx, Runnable openCaSettings) {
        this.ctx = ctx;
        this.openCaSettings = openCaSettings;
        toggle = Ui.navPairButton(null, this::toggle);
        settings = Ui.navPairButton(ctx.m("app.ssl.settings"), this::openSettings);
        panel = Ui.navPair(toggle, settings);
        ctx.onSettingsChanged(this::refresh);
        ctx.listen(ForwardProxy::onChange, this::refresh);
        ctx.listen(ProxyRuntime::onChange, this::refresh);
        refresh();
    }

    void openSettings() {
        SslProxyDialog.show(ctx, openCaSettings);
        refresh(); // the list, or the CA's registration, may have changed meanwhile
    }

    /** The right-click menu of a tunnel row: adds its domain to the SSL proxy, or says why it cannot. */
    JPopupMenu domainMenu(String host, int port) {
        boolean on = ctx.settings(st -> st.sslProxy.enabled);
        SslRules listed = SslRules.parse(ctx.settings(st -> st.sslProxy.domains));
        String key;
        boolean can = false;
        if (port != 443 || !SslRules.decryptable(host)) {
            key = "app.ssl.menu.unsupported";
        } else if (listed.excludes(host)) {
            key = "app.ssl.menu.excluded";
        } else if (listed.matches(host)) {
            key = on ? "app.ssl.menu.listed" : "app.ssl.toggle.off";
            can = !on;
        } else {
            key = on ? "app.ssl.menu.add" : "app.ssl.menu.add.on";
            can = true;
        }
        JMenuItem item = new JMenuItem(ctx.m(key), Ui.icon("lock-open", 14, can ? Ui.BC700 : Ui.NAV_ICON_DISABLED));
        item.putClientProperty("FlatLaf.style", "selectionInsets:0,6,0,6; selectionArc:6;"
                + " selectionBackground:#dff2b8; selectionForeground:#3a6b10");
        item.setEnabled(can);
        item.addActionListener(e -> addDomain(host));
        JPopupMenu menu = new JPopupMenu();
        menu.add(item);
        return menu;
    }

    private void addDomain(String host) {
        String name = host.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        String added = name;
        String stored = ctx.settings(st -> st.sslProxy.domains);
        String next = SslRules.withDomain(stored, added);
        SslRules before = SslProxy.effective();
        if (!ctx.saveSettings(st -> {
            st.sslProxy.domains = next;
            st.sslProxy.enabled = true;
        })) {
            return;
        }
        SslProxyDialog.domainAdded(stored, next, added);
        ctx.fireSettingsChanged();
        inBackground(() -> SslProxy.apply(before), () -> {
            List<String> problems = problems(ctx, trusted(), next);
            ctx.toast(problems.isEmpty() ? ctx.m("app.ssl.added") : problems.get(0));
        });
    }

    /** Runs {@code work} off the event thread with the switch disabled, then {@code done} on it. */
    private void inBackground(Runnable work, Runnable done) {
        toggle.setEnabled(false);
        Thread.ofVirtual().name("ssl-proxy-change").start(() -> {
            try {
                work.run();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    toggle.setEnabled(true);
                    refresh();
                    done.run();
                });
            }
        });
    }

    private void toggle() {
        boolean on = !ctx.settings(st -> st.sslProxy.enabled);
        SslRules before = SslProxy.effective();
        if (!ctx.saveSettings(st -> st.sslProxy.enabled = on)) return;
        ctx.fireSettingsChanged();
        inBackground(() -> SslProxy.apply(before), () -> toggled(on));
    }

    private void toggled(boolean on) {
        if (!on) {
            ctx.toast(ctx.m("app.ssl.turned.off"));
        } else if (!SslProxy.active()) {
            openSettings(); // switched on with nothing to decrypt: ask for the domains
        } else {
            List<String> problems = problems(ctx, trusted(), ctx.settings(st -> st.sslProxy.domains));
            ctx.toast(problems.isEmpty() ? ctx.m("app.ssl.turned.on") : problems.get(0));
        }
    }

    /** Shows the stored state; also called when the Network tab is shown. */
    void refresh() {
        boolean on = ctx.settings(st -> st.sslProxy.enabled);
        List<String> problems = on ? problems(ctx, trusted(), ctx.settings(st -> st.sslProxy.domains)) : List.of();
        Color color = !on ? Ui.NAV_ICON : problems.isEmpty() ? Ui.BC700 : PROBLEM;
        Color hover = on ? color : Ui.NAV_ICON_HOVER;
        showIcon(toggle, on ? "lock-open" : "lock", color, hover);
        // The shackle follows the switch: no closed lock next to an open one.
        showIcon(settings, on ? "lock-open-cog" : "lock-cog", color, hover);
        panel.putClientProperty(Ui.NAV_ON, on);
        String tip = ctx.m(on ? "app.ssl.toggle.on" : "app.ssl.toggle.off");
        toggle.setToolTipText(problems.isEmpty() ? tip : "<html>" + tip + "<br>" + String.join("<br>", problems) + "</html>");
        panel.repaint();
    }

    private static void showIcon(JButton b, String icon, Color color, Color hover) {
        b.setIcon(Ui.icon(icon, 15, color));
        b.setRolloverIcon(Ui.icon(icon, 15, hover));
        b.setDisabledIcon(Ui.icon(icon, 15, Ui.NAV_ICON_DISABLED));
    }

    static Boolean trusted() {
        X509Certificate x = Ca.read(ProxyRuntime.caCert());
        return x == null ? null : CaPage.trusted(x);
    }

    /** What keeps the SSL proxy from working with {@code domains}, the most important first. */
    static List<String> problems(AppContext ctx, Boolean trusted, String domains) {
        List<String> out = new ArrayList<>();
        if (!SslRules.parse(domains).selectsAny()) out.add(ctx.m("app.ssl.warn.empty"));
        if (ForwardProxy.state() == ForwardProxy.State.FAILED) out.add(ctx.m("app.ssl.warn.fwd"));
        if (ProxyRuntime.state() == ProxyRuntime.State.FAILED) out.add(ctx.m("app.ssl.warn.proxy"));
        if (Boolean.FALSE.equals(trusted)) out.add(ctx.m("app.ssl.warn.ca"));
        return out;
    }
}
