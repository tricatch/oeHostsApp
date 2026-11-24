package tricatch.oehostsapp.store;

import tricatch.oehostsapp.net.Network;

import java.util.ArrayList;
import java.util.List;

/** App settings, the {@code settings} part of the data file. */
public final class Settings {

    public static final int DEFAULT_FWDPROXY_PORT = 36980;
    public static final int DEFAULT_MCP_PORT = 36981;

    /**
     * The proxy service (proxy for Chrome and phones, and the shared hosts / phone setup pages): its
     * port. It always listens on every interface; other devices are allowed one by one on screen.
     * Older files' binding, Basic authentication and separate web service are ignored.
     */
    public static class ForwardProxy {
        public int port = DEFAULT_FWDPROXY_PORT;
        /** Devices answered on screen, oldest first; kept until removed on the proxy service page. */
        public List<Device> devices = new ArrayList<>();
    }

    /** A device other than this PC and the answer given for it. */
    public static class Device {
        public String ip;
        /** The User-Agent of its first request (may be null). */
        public String userAgent;
        public boolean allowed;

        public Device() {}

        public Device(String ip, String userAgent, boolean allowed) {
            this.ip = ip;
            this.userAgent = userAgent;
            this.allowed = allowed;
        }
    }

    /** The embedded reverse proxy's binding (a network card's address is bound together with 127.0.0.1). */
    public static class ReverseProxy {
        public String bind = Network.ALL;
    }

    /** The SSL proxy: its switch and the domains it decrypts, as typed ({@code proxy.SslRules}' lines). */
    public static class SslProxy {
        public boolean enabled;
        public String domains = "";
    }

    /**
     * The MCP server for AI coding tools ({@code mcp.McpServer}): on by default, read-only, and only
     * on 127.0.0.1; a client needs the access token ({@code Store.Data.mcpToken}, kept out of the
     * settings so a backup never carries it), which only this PC's settings screen shows.
     */
    public static class Mcp {
        public boolean enabled = true;
        public int port = DEFAULT_MCP_PORT;
        /** Whether the tools also offer what the Network tab captured (secrets replaced by tags); on by default. */
        public boolean traffic = true;
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

    /** The main window's last normal bounds and state, and the shared list/panel sizes. */
    public static class Window {
        /** Null until the window has been moved or resized. */
        public Integer x, y, width, height;
        public boolean maximized;
        /** Left list width shared by every screen, once the user has dragged it. */
        public Integer sidebarWidth;
        /** Width of the right-hand panel (oeHosts launcher, oeProxy routes), shared likewise. */
        public Integer rightPanelWidth;
        /** Height of the list search panel (oeHosts, oeProxy), shared likewise; null: 30% of the list. */
        public Integer searchHeight;
    }

    /** The Network tab's detail switches, kept across starts. */
    public static class Monitor {
        /** Headers, cookies and form/JSON members by name instead of as sent. */
        public boolean sortByName;
        /** Values of those lines start in one column. */
        public boolean alignValues;
    }

    public ForwardProxy fwdproxy = new ForwardProxy();
    public ReverseProxy reverseProxy = new ReverseProxy();
    public SslProxy sslProxy = new SslProxy();
    public Mcp mcp = new Mcp();
    public PresetList userAgentPresets = new PresetList();
    public PresetList openUrlPresets = new PresetList();
    public Window window = new Window();
    public Monitor monitor = new Monitor();
    /** UI language ("en"/"ko"); null follows the OS locale. */
    public String lang;
    /**
     * Font size the whole UI is scaled to (FlatLaf zoom: text, icons, spacing alike), in points of
     * the look and feel's default font; null keeps the default size.
     */
    public Integer fontSize;
    /** Folder the CA import dialog last picked a file from. */
    public String caImportDir;

    /** Fills parts a data file left out (an older or hand-edited file), so callers never see null. */
    void fillDefaults() {
        if (fwdproxy == null) fwdproxy = new ForwardProxy();
        if (fwdproxy.devices == null) fwdproxy.devices = new ArrayList<>();
        fwdproxy.devices.removeIf(d -> d == null || d.ip == null || d.ip.isBlank());
        if (reverseProxy == null) reverseProxy = new ReverseProxy();
        if (sslProxy == null) sslProxy = new SslProxy();
        if (sslProxy.domains == null) sslProxy.domains = "";
        if (mcp == null) mcp = new Mcp();
        if (userAgentPresets == null) userAgentPresets = new PresetList();
        if (openUrlPresets == null) openUrlPresets = new PresetList();
        if (userAgentPresets.items == null) userAgentPresets.items = new ArrayList<>();
        if (openUrlPresets.items == null) openUrlPresets.items = new ArrayList<>();
        if (window == null) window = new Window();
        if (monitor == null) monitor = new Monitor();
        if (reverseProxy.bind == null || reverseProxy.bind.isBlank()) reverseProxy.bind = Network.ALL;
        reverseProxy.bind = reverseProxy.bind.strip();
    }
}
