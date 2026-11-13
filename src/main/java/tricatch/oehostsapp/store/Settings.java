package tricatch.oehostsapp.store;

import tricatch.oehostsapp.net.Network;

import java.util.ArrayList;
import java.util.List;

/** App settings, the {@code settings} part of the data file. */
public final class Settings {

    public static final int DEFAULT_FWDPROXY_PORT = 36980;
    public static final int DEFAULT_WEB_PORT = 36981;

    /**
     * The forward proxy Chrome (and phones) use: its port, its binding ({@link Network}), and an
     * optional Basic username and password other devices must send (127.0.0.1 is never asked).
     */
    public static class ForwardProxy {
        public int port = DEFAULT_FWDPROXY_PORT;
        public String bind = Network.LOOPBACK;
        public boolean auth;
        /** Kept when authentication is turned off again. */
        public String user;
        public String password;

        /** The username and password the forward proxy asks for, or null when it asks for none. */
        public String[] credentials() {
            if (!auth || user == null || user.isEmpty() || password == null || password.isEmpty()) return null;
            return new String[]{user, password};
        }
    }

    /** The embedded reverse proxy's binding (a network card's address is bound together with 127.0.0.1). */
    public static class ReverseProxy {
        public String bind = Network.ALL;
    }

    /** The web service (hosts sharing, /active-hosts, phone setup page). */
    public static class WebService {
        public int port = DEFAULT_WEB_PORT;
        public String bind = Network.LOOPBACK;
    }

    /** One preset: a name and the value it puts into a launcher field. */
    public static class Preset {
        public String name;
        public String value;

        public Preset() {}

        public Preset(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /** A preset editor's text as typed (comments included) and the entries parsed from it. */
    public static class PresetList {
        public List<Preset> items = new ArrayList<>();
        /** Null until the editor saved once; the text is then rebuilt from {@link #items}. */
        public String text;

        /** The editor text: as typed, or one line per entry. */
        public String editorText() {
            if (text != null) return text;
            StringBuilder b = new StringBuilder();
            for (Preset p : items) b.append(p.name).append(" | ").append(p.value).append('\n');
            return b.toString();
        }

        /**
         * Appends the entries of {@code add} this list lacks (same name and value), as lines of the
         * editor text too.
         */
        void merge(List<Preset> add) {
            if (add == null) return;
            StringBuilder more = new StringBuilder();
            for (Preset p : add) {
                if (p == null || p.name == null || p.value == null) continue;
                if (items.stream().anyMatch(q -> p.name.equals(q.name) && p.value.equals(q.value))) continue;
                items.add(new Preset(p.name, p.value));
                more.append(p.name).append(" | ").append(p.value).append('\n');
            }
            if (text == null || more.isEmpty()) return;
            text = (text.isEmpty() || text.endsWith("\n") ? text : text + "\n") + more;
        }
    }

    /** The main window's last normal bounds and state, and the shared list/panel widths. */
    public static class Window {
        /** Null until the window has been moved or resized. */
        public Integer x, y, width, height;
        public boolean maximized;
        /** Left list width shared by every screen, once the user has dragged it. */
        public Integer sidebarWidth;
        /** Width of the right-hand panel (oeHosts launcher, oeProxy routes), shared likewise. */
        public Integer rightPanelWidth;
    }

    public ForwardProxy fwdproxy = new ForwardProxy();
    public ReverseProxy reverseProxy = new ReverseProxy();
    public WebService web = new WebService();
    public PresetList userAgentPresets = new PresetList();
    public PresetList openUrlPresets = new PresetList();
    public Window window = new Window();
    /** UI language ("en"/"ko"); null follows the OS locale. */
    public String lang;
    /**
     * Font size the whole UI is scaled to (FlatLaf zoom: text, icons, spacing alike), in points of
     * the look and feel's default font; null keeps the default size.
     */
    public Integer fontSize;
    /** Folder the CA import dialog last picked a file from. */
    public String caImportDir;
    /** The newer release the "new version" toast was last shown for, so it shows once per release. */
    public String updateToasted;

    /** Fills parts a data file left out (an older or hand-edited file), so callers never see null. */
    void fillDefaults() {
        if (fwdproxy == null) fwdproxy = new ForwardProxy();
        if (reverseProxy == null) reverseProxy = new ReverseProxy();
        if (web == null) web = new WebService();
        if (userAgentPresets == null) userAgentPresets = new PresetList();
        if (openUrlPresets == null) openUrlPresets = new PresetList();
        if (userAgentPresets.items == null) userAgentPresets.items = new ArrayList<>();
        if (openUrlPresets.items == null) openUrlPresets.items = new ArrayList<>();
        if (window == null) window = new Window();
        if (fwdproxy.bind == null || fwdproxy.bind.isBlank()) fwdproxy.bind = Network.LOOPBACK;
        if (reverseProxy.bind == null || reverseProxy.bind.isBlank()) reverseProxy.bind = Network.ALL;
        if (web.bind == null || web.bind.isBlank()) web.bind = Network.LOOPBACK;
        fwdproxy.bind = fwdproxy.bind.strip();
        reverseProxy.bind = reverseProxy.bind.strip();
        web.bind = web.bind.strip();
    }
}
