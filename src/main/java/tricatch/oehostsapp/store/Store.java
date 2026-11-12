package tricatch.oehostsapp.store;

import tricatch.oehostsapp.proxy.ForwardProxy;
import tricatch.oehostsapp.web.ShareServer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local data: hosts profiles, vhosts, their folders, per-screen options and app settings, in one
 * JSON file. Every change is written right away (temp file + move; the previous version is kept
 * as {@code <file>.bak}). Callers synchronize on the store.
 */
public final class Store {

    public static final String DEFAULT_PROXY_SVR = "127.0.0.1";
    public static final int DEFAULT_FWDPROXY_PORT = 36980;
    public static final int DEFAULT_SHARE_PORT = 36981;
    /** Crockford base32, lower case (no i, l, o, u). */
    private static final String BASE32 = "0123456789abcdefghjkmnpqrstvwxyz";

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** The two list screens share one implementation; these are their field names in an oeHub export. */
    public enum Kind {
        HOSTS("hostsProfile", "hostsContent", "new hosts", "example/hosts_example_%s.txt"),
        VHOSTS("vhostProfile", "vhostContent", "new vhost", "example/vhost_example_%s.yaml");

        public final String nameKey, contentKey;
        // Same base names as oeHub (HostsProfService / ProxyVhostService).
        final String defaultName;
        final String exampleAsset;

        Kind(String nameKey, String contentKey, String defaultName, String exampleAsset) {
            this.nameKey = nameKey;
            this.contentKey = contentKey;
            this.defaultName = defaultName;
            this.exampleAsset = exampleAsset;
        }
    }

    public static class Item {
        public String id;
        public String name = "";
        public String content = "";
        public boolean selected;
        /** oeHosts only: served by {@link ShareServer} at {@code /hosts/<shareToken>} (for SwitchHosts and the like). */
        public boolean shared;
        public String folderId;
        public long sortOrder;
        public String createAt;
        public String updatedAt;
    }

    public static class Folder {
        public String id;
        public String name = "";
        public boolean collapsed;
        public long sortOrder;
        public String createAt;
        public String updatedAt;
    }

    public static class Preset {
        public String name;
        public String value;

        public Preset() {}

        public Preset(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    public static class Settings {
        public String proxySvr = DEFAULT_PROXY_SVR;
        public int fwdproxyPort = DEFAULT_FWDPROXY_PORT;
        /**
         * Older data: forward proxy on every network interface (0.0.0.0) instead of 127.0.0.1 only. Read
         * when {@link #fwdproxyBind} is null, and kept in step with it so an older version still opens
         * the proxy to the network when this one does.
         */
        public boolean fwdproxyLan;
        /**
         * Desktop app: the address the forward proxy listens on: {@code 127.0.0.1}, one of this PC's
         * IPv4 addresses (one network card), or {@code 0.0.0.0} (every interface). Null in older data.
         */
        public String fwdproxyBind;

        /**
         * Desktop app: whether the forward proxy asks other devices for {@link #fwdproxyUser} and
         * {@link #fwdproxyPassword} (Basic proxy authentication). Requests to 127.0.0.1 are never asked.
         */
        public boolean fwdproxyAuth;
        /** Desktop app: the forward proxy username and password; kept when authentication is turned off again. */
        public String fwdproxyUser;
        public String fwdproxyPassword;

        /** The username and password the forward proxy asks for, or null when it asks for none. */
        public String[] fwdproxyCredentials() {
            if (!fwdproxyAuth || fwdproxyUser == null || fwdproxyUser.isEmpty()
                    || fwdproxyPassword == null || fwdproxyPassword.isEmpty()) {
                return null;
            }
            return new String[]{fwdproxyUser, fwdproxyPassword};
        }

        /** {@link #fwdproxyBind}, or what the older {@link #fwdproxyLan} meant when it is not set. */
        public String fwdproxyBindAddress() {
            if (fwdproxyBind != null && !fwdproxyBind.isBlank()) return fwdproxyBind.strip();
            return fwdproxyLan ? ForwardProxy.ALL : ForwardProxy.LOOPBACK;
        }

        /** Stores the forward proxy address, and the older flag next to it. */
        public void putFwdproxyBind(String bind) {
            fwdproxyBind = bind;
            fwdproxyLan = !ForwardProxy.LOOPBACK.equals(bind);
        }
        /** Desktop app: reverse proxy on every network interface (the default) instead of 127.0.0.1 only. */
        public boolean reverseProxyLan = true;
        /**
         * Desktop app: the reverse proxy's binding: 127.0.0.1, one of this PC's IPv4 addresses
         * (127.0.0.1 is opened next to it), or 0.0.0.0. Null in older data, which
         * {@link #reverseProxyLan} then decides (every interface by default).
         */
        public String reverseProxyBind;

        /** {@link #reverseProxyBind}, or what the older {@link #reverseProxyLan} meant when it is not set. */
        public String reverseProxyBindAddress() {
            if (reverseProxyBind != null && !reverseProxyBind.isBlank()) return reverseProxyBind.strip();
            return reverseProxyLan ? ForwardProxy.ALL : ForwardProxy.LOOPBACK;
        }

        /** Stores the reverse proxy address, and the older flag next to it for older versions. */
        public void putReverseProxyBind(String bind) {
            reverseProxyBind = bind;
            reverseProxyLan = !ForwardProxy.LOOPBACK.equals(bind);
        }
        /** Desktop app: port of the web service ({@link ShareServer}: hosts sharing, phone setup page). */
        public int shareServerPort = DEFAULT_SHARE_PORT;
        /**
         * Desktop app: the web service's binding: 127.0.0.1 (the default), one of this PC's IPv4
         * addresses, or 0.0.0.0. Null in older data, which means 127.0.0.1.
         */
        public String shareServerBind;

        /** {@link #shareServerBind}, or 127.0.0.1 when it is not set. */
        public String shareServerBindAddress() {
            return shareServerBind == null || shareServerBind.isBlank() ? ForwardProxy.LOOPBACK : shareServerBind.strip();
        }
        public List<Preset> uaPresets = new ArrayList<>();
        public List<Preset> urlPresets = new ArrayList<>();
        /**
         * The preset editors' text as typed, comments included; the lists above are parsed from it.
         * Null (older data) shows text rebuilt from the list.
         */
        public String uaPresetsText;
        public String urlPresetsText;
        /** Desktop app: folder the CA import dialog last picked a file from. */
        public String caImportDir;
        /** Desktop app UI language ("en"/"ko"); null follows the OS locale. */
        public String lang;
        /** Desktop app: left list width shared by every screen, once the user has dragged it. */
        public Integer sidebarWidth;
        /** Desktop app: width of the right-hand panel (oeHosts launcher, oeProxy routes), shared likewise. */
        public Integer rightPanelWidth;
        /**
         * Desktop app: the main window's last normal (not maximized) bounds and whether it was
         * maximized, restored at the next start. Null until the window has been moved or resized.
         */
        public Integer windowX;
        public Integer windowY;
        public Integer windowWidth;
        public Integer windowHeight;
        public boolean windowMaximized;
    }

    public static class Data {
        public int version = 1;
        public List<Item> hosts = new ArrayList<>();
        public List<Folder> hostsFolders = new ArrayList<>();
        public List<Item> vhosts = new ArrayList<>();
        public List<Folder> vhostFolders = new ArrayList<>();
        /** Chrome launcher options of the hosts screen (oeHub's HOSTS_CONF names). */
        public Map<String, String> hostsConf = new TreeMap<>();
        /** oeProxy screen options (local_svr, the last merged vhost config). */
        public Map<String, String> proxyConf = new TreeMap<>();
        public Settings settings = new Settings();
        /**
         * Key of the share URLs' HMAC ({@link #shareToken}), created on first use. Kept out of
         * backups ({@link #exportParts} has no part for it), so URLs never work with another
         * installation's data.
         */
        public String shareKey;
    }

    /** Store failures, mapped to HTTP statuses by the server. */
    public static class StoreException extends RuntimeException {
        public final int status;
        public final String code;

        StoreException(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }

        static StoreException notFound() { return new StoreException(404, "not_found"); }
        static StoreException nameTaken() { return new StoreException(409, "name_taken"); }
        static StoreException invalid(String code) { return new StoreException(400, code); }
    }

    private final Path path;
    public final Data data;

    private Store(Path path, Data data) {
        this.path = path;
        this.data = data;
    }

    public static String now() {
        return LocalDateTime.now().format(TS);
    }

    /**
     * 14 Crockford base32 digits: 64 random bits (13 digits) and one digit of a counter. The random
     * part alone makes a clash practically impossible; the counter only separates ids made in the
     * same instant.
     */
    public static String newId() {
        long r = RANDOM.nextLong();
        char[] out = new char[14];
        for (int k = 12; k >= 0; k--, r >>>= 5) out[k] = BASE32.charAt((int) (r & 31));
        out[13] = BASE32.charAt(SEQ.getAndIncrement() & 31);
        return new String(out);
    }

    /**
     * Opens {@code path}; when it does not exist yet, starts from {@code legacy} (the old oeHosts
     * egui app's file) if that one is there, otherwise empty. The legacy file is never written,
     * and a data file that does not parse stops the app instead of being overwritten.
     */
    public static Store open(Path path, Path legacy) throws IOException {
        Data data;
        if (Files.exists(path)) {
            data = MAPPER.readValue(path.toFile(), Data.class);
            if (data.settings == null) data.settings = new Settings();
        } else if (legacy != null && Files.isRegularFile(legacy)) {
            Data migrated;
            try {
                migrated = LegacyMigration.migrate(MAPPER.readTree(legacy.toFile()));
            } catch (IOException e) {
                migrated = new Data();
            }
            data = migrated;
        } else {
            data = new Data();
        }
        data.version = 1;
        Store store = new Store(path, data);
        if (!Files.exists(path)) store.save();
        return store;
    }

    public Path path() {
        return path;
    }

    public void save() {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), data);
            if (Files.exists(path)) {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StoreException(500, "io: " + e.getMessage());
        }
    }

    // ── Lists ─────────────────────────────────────────────────

    List<Item> items(Kind kind) {
        return kind == Kind.HOSTS ? data.hosts : data.vhosts;
    }

    List<Folder> folders(Kind kind) {
        return kind == Kind.HOSTS ? data.hostsFolders : data.vhostFolders;
    }

    private long nextSortOrder(Kind kind) {
        long a = items(kind).stream().mapToLong(i -> i.sortOrder).max().orElse(-1);
        long b = folders(kind).stream().mapToLong(f -> f.sortOrder).max().orElse(-1);
        return Math.max(a, b) + 1;
    }

    /** Items in tree order, which is also the merge order of the selected ones. */
    public List<Item> list(Kind kind) {
        Map<String, Long> folderOrder = new HashMap<>();
        for (Folder f : folders(kind)) folderOrder.put(f.id, f.sortOrder);
        List<Item> out = new ArrayList<>(items(kind));
        out.sort(Comparator.<Item>comparingLong(i -> i.folderId != null && folderOrder.containsKey(i.folderId)
                        ? folderOrder.get(i.folderId) : i.sortOrder)
                .thenComparingLong(i -> i.sortOrder));
        return out;
    }

    public List<Folder> listFolders(Kind kind) {
        List<Folder> out = new ArrayList<>(folders(kind));
        out.sort(Comparator.comparingLong(f -> f.sortOrder));
        return out;
    }

    /** {@code base}, or {@code base (2)}, {@code base (3)}, ... - the first one not taken, as oeHub does. */
    static String nextUniqueName(Set<String> takenLower, String base) {
        if (!takenLower.contains(base.toLowerCase(Locale.ROOT))) return base;
        for (int i = 2; i <= 999; i++) {
            String candidate = base + " (" + i + ")";
            if (!takenLower.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return base + " (" + newId() + ")";
    }

    private Set<String> takenNames(Kind kind, String exceptId) {
        Set<String> out = new HashSet<>();
        for (Item i : items(kind)) if (!i.id.equals(exceptId)) out.add(i.name.toLowerCase(Locale.ROOT));
        return out;
    }

    private static String example(Kind kind, String locale) {
        String res = "/" + String.format(kind.exampleAsset, "ko".equals(locale) ? "ko" : "en");
        try (InputStream in = Store.class.getResourceAsStream(res)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            return "";
        }
    }

    private Item newItem(Kind kind, String name, String content, boolean selected, String folderId) {
        Item i = new Item();
        i.id = newId();
        i.name = name;
        i.content = content;
        i.selected = selected;
        i.folderId = folderId;
        i.sortOrder = nextSortOrder(kind);
        i.createAt = now();
        i.updatedAt = i.createAt;
        return i;
    }

    // ── Items ─────────────────────────────────────────────────

    public Item create(Kind kind, String locale) {
        Item i = newItem(kind, nextUniqueName(takenNames(kind, null), kind.defaultName), example(kind, locale), false, null);
        items(kind).add(i);
        save();
        return i;
    }

    /** A new item from an imported file; the name gets a " (n)" suffix if it is already taken. */
    public Item createFrom(Kind kind, String name, String content) {
        String base = name == null ? "" : name.strip();
        if (base.length() > 190) base = base.substring(0, 190).strip();
        if (base.isEmpty()) base = kind.defaultName;
        Item i = newItem(kind, nextUniqueName(takenNames(kind, null), base), content, false, null);
        items(kind).add(i);
        save();
        return i;
    }

    private Item item(Kind kind, String id) {
        return items(kind).stream().filter(i -> i.id.equals(id)).findFirst().orElseThrow(StoreException::notFound);
    }

    public Item updateContent(Kind kind, String id, String content) {
        Item i = item(kind, id);
        i.content = content;
        i.updatedAt = now();
        save();
        return i;
    }

    public Item rename(Kind kind, String id, String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 200) throw StoreException.invalid("name");
        Item i = item(kind, id);
        if (takenNames(kind, id).contains(n.toLowerCase(Locale.ROOT))) throw StoreException.nameTaken();
        i.name = n;
        i.updatedAt = now();
        save();
        return i;
    }

    public Item toggleSelected(Kind kind, String id) {
        Item i = item(kind, id);
        i.selected = !i.selected;
        i.updatedAt = now();
        save();
        return i;
    }

    /** Turns sharing of an oeHosts profile on or off (not an edit of the profile: updatedAt stays). */
    public Item setShared(String id, boolean shared) {
        Item i = item(Kind.HOSTS, id);
        i.shared = shared;
        save();
        return i;
    }

    /** The content of a shared oeHosts profile, or null when there is none with that id or it is not shared. */
    public String sharedHostsContent(String id) {
        for (Item i : data.hosts) if (i.shared && i.id.equals(id)) return i.content;
        return null;
    }

    /**
     * The share URL's token for an oeHosts profile: {@code <id>-<tag>}, where the tag is the first 40
     * bits of HMAC-SHA256(shareKey, id) in 8 Crockford base32 digits. Without the key a valid token
     * cannot be made up from an id (and the id itself is 64 random bits), so trying ids gets nowhere.
     * Creates the key on first use.
     */
    public String shareToken(String id) {
        if (data.shareKey == null) {
            byte[] k = new byte[32];
            RANDOM.nextBytes(k);
            data.shareKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(k);
            save();
        }
        return id + "-" + shareTag(id);
    }

    /**
     * A profile name as the share URL's readable last segment: letters and digits (any script),
     * {@code -} and {@code .} are kept, every run of anything else becomes one {@code _}. Only for
     * people to tell URLs apart; the server ignores it.
     */
    public static String shareSlug(String name) {
        StringBuilder b = new StringBuilder();
        String n = name == null ? "" : name.strip();
        for (int k = 0; k < n.length(); ) {
            int c = n.codePointAt(k);
            k += Character.charCount(c);
            boolean keep = Character.isLetterOrDigit(c) || c == '-' || c == '.';
            if (keep) b.appendCodePoint(c);
            else if (b.isEmpty() || b.charAt(b.length() - 1) != '_') b.append('_');
            if (b.length() >= 60) break;
        }
        return b.isEmpty() ? "_" : b.toString();
    }

    /** The content of the shared profile a {@link #shareToken} names, or null when the tag does not match. */
    public String sharedHostsContentByToken(String token) {
        int dash = token.lastIndexOf('-');
        if (dash <= 0 || data.shareKey == null) return null;
        String id = token.substring(0, dash);
        byte[] expected = shareTag(id).getBytes(StandardCharsets.US_ASCII);
        byte[] given = token.substring(dash + 1).getBytes(StandardCharsets.US_ASCII);
        if (!java.security.MessageDigest.isEqual(expected, given)) return null;
        return sharedHostsContent(id);
    }

    private String shareTag(String id) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(java.util.Base64.getUrlDecoder().decode(data.shareKey), "HmacSHA256"));
            byte[] h = mac.doFinal(id.getBytes(StandardCharsets.UTF_8));
            long bits = 0;
            for (int k = 0; k < 5; k++) bits = (bits << 8) | (h[k] & 0xff);
            char[] out = new char[8];
            for (int k = 7; k >= 0; k--, bits >>>= 5) out[k] = BASE32.charAt((int) (bits & 31));
            return new String(out);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void delete(Kind kind, String id) {
        if (!items(kind).removeIf(i -> i.id.equals(id))) throw StoreException.notFound();
        save();
    }

    // ── Folders ───────────────────────────────────────────────

    private Folder newFolder(Kind kind, String name) {
        Folder f = new Folder();
        f.id = newId();
        f.name = name;
        f.sortOrder = nextSortOrder(kind);
        f.createAt = now();
        f.updatedAt = f.createAt;
        return f;
    }

    public Folder createFolder(Kind kind) {
        Set<String> taken = new HashSet<>();
        for (Folder f : folders(kind)) taken.add(f.name.toLowerCase(Locale.ROOT));
        Folder f = newFolder(kind, nextUniqueName(taken, "new folder"));
        folders(kind).add(f);
        save();
        return f;
    }

    public Folder updateFolder(Kind kind, String id, String name, Boolean collapsed) {
        Folder f = folders(kind).stream().filter(x -> x.id.equals(id)).findFirst().orElseThrow(StoreException::notFound);
        if (name != null) {
            String n = name.strip();
            if (n.isEmpty() || n.length() > 200) throw StoreException.invalid("name");
            boolean taken = folders(kind).stream().anyMatch(x -> x != f && x.name.equalsIgnoreCase(n));
            if (taken) throw StoreException.nameTaken();
            f.name = n;
        }
        if (collapsed != null) f.collapsed = collapsed;
        f.updatedAt = now();
        save();
        return f;
    }

    /** Moves an item into {@code folderId} (null = root), placing it last. */
    public Item moveItem(Kind kind, String id, String folderId) {
        Item i = item(kind, id);
        if (folderId != null && folders(kind).stream().noneMatch(f -> f.id.equals(folderId))) throw StoreException.notFound();
        i.folderId = folderId;
        i.sortOrder = nextSortOrder(kind);
        save();
        return i;
    }

    /** Deletes a folder; its items move to the root (oeHub: FK ON DELETE SET NULL). */
    public void deleteFolder(Kind kind, String id) {
        if (!folders(kind).removeIf(f -> f.id.equals(id))) throw StoreException.notFound();
        for (Item i : items(kind)) if (id.equals(i.folderId)) i.folderId = null;
        save();
    }

    /**
     * Saves the whole tree order: {@code [{type: 'folder', id} | {type: 'item', id, folderId}]} in
     * display order. Unknown ids are refused so a stale page cannot drop rows.
     */
    public void saveTreeOrder(Kind kind, JsonNode entries) {
        if (entries == null || !entries.isArray()) throw StoreException.invalid("entries");
        Map<String, Folder> folders = new HashMap<>();
        for (Folder f : folders(kind)) folders.put(f.id, f);
        Map<String, Item> items = new HashMap<>();
        for (Item i : items(kind)) items.put(i.id, i);
        record Step(Folder folder, Item item, String folderId, long order) {}
        List<Step> plan = new ArrayList<>();
        long n = 0;
        for (JsonNode e : entries) {
            String id = e.path("id").asText(null);
            String type = e.path("type").asText("");
            if ("folder".equals(type) && folders.containsKey(id)) {
                plan.add(new Step(folders.get(id), null, null, n++));
            } else if ("item".equals(type) && items.containsKey(id)) {
                JsonNode fid = e.get("folderId");
                String folderId = fid == null || fid.isNull() ? null : fid.asText();
                if (folderId != null && !folders.containsKey(folderId)) throw StoreException.invalid("folderId");
                plan.add(new Step(null, items.get(id), folderId, n++));
            } else {
                throw StoreException.invalid("entry");
            }
        }
        for (Step s : plan) {
            if (s.folder() != null) {
                s.folder().sortOrder = s.order();
            } else {
                s.item().sortOrder = s.order();
                s.item().folderId = s.folderId();
            }
        }
        save();
    }

    /** Selected hosts contents in merge order (for the JVM agent endpoint). */
    public List<String> selectedHostsContents() {
        return list(Kind.HOSTS).stream().filter(i -> i.selected).map(i -> i.content).toList();
    }

    // ── Export / backup restore ─────────────────────────────────

    /**
     * The chosen parts of the data for a backup's {@code data.json}: oeHosts (profiles, folders,
     * launcher options), oeProxy (vhosts, folders, proxy options) and settings. A part left out has
     * no key, so {@link #restoreParts} and {@link Backup.Contents} can tell it was not exported.
     */
    public JsonNode exportParts(boolean hosts, boolean vhosts, boolean settings) {
        com.fasterxml.jackson.databind.node.ObjectNode root = MAPPER.createObjectNode();
        root.put("version", data.version);
        if (hosts) {
            root.set("hosts", MAPPER.valueToTree(data.hosts));
            root.set("hostsFolders", MAPPER.valueToTree(data.hostsFolders));
            root.set("hostsConf", MAPPER.valueToTree(data.hostsConf));
        }
        if (vhosts) {
            root.set("vhosts", MAPPER.valueToTree(data.vhosts));
            root.set("vhostFolders", MAPPER.valueToTree(data.vhostFolders));
            root.set("proxyConf", MAPPER.valueToTree(data.proxyConf));
        }
        if (settings) root.set("settings", MAPPER.valueToTree(data.settings));
        return root;
    }

    /** Replaces the chosen parts (see {@link #exportParts}) with those of a backup's {@code data.json}, and saves. */
    public void restoreParts(JsonNode json, boolean hosts, boolean vhosts, boolean settings) throws IOException {
        Data from = MAPPER.treeToValue(json, Data.class);
        if (hosts) {
            data.hosts = from.hosts != null ? from.hosts : new ArrayList<>();
            data.hostsFolders = from.hostsFolders != null ? from.hostsFolders : new ArrayList<>();
            data.hostsConf = from.hostsConf != null ? new TreeMap<>(from.hostsConf) : new TreeMap<>();
        }
        if (vhosts) {
            data.vhosts = from.vhosts != null ? from.vhosts : new ArrayList<>();
            data.vhostFolders = from.vhostFolders != null ? from.vhostFolders : new ArrayList<>();
            data.proxyConf = from.proxyConf != null ? new TreeMap<>(from.proxyConf) : new TreeMap<>();
        }
        if (settings) data.settings = from.settings != null ? from.settings : new Settings();
        save();
    }

    /**
     * Adds the chosen parts of a backup's {@code data.json} to what is stored, keeping everything
     * already there: items are added (a taken name gets a " (n)" suffix) into folders matched by
     * name, options only fill keys not set yet, and settings only gain the presets they lack (the
     * other settings stay as they are). Saves once at the end.
     */
    public void mergeParts(JsonNode json, boolean hosts, boolean vhosts, boolean settings) throws IOException {
        Data from = MAPPER.treeToValue(json, Data.class);
        if (hosts) {
            mergeItems(Kind.HOSTS, from.hosts, from.hostsFolders);
            if (from.hostsConf != null) from.hostsConf.forEach(data.hostsConf::putIfAbsent);
        }
        if (vhosts) {
            mergeItems(Kind.VHOSTS, from.vhosts, from.vhostFolders);
            if (from.proxyConf != null) from.proxyConf.forEach(data.proxyConf::putIfAbsent);
        }
        if (settings && from.settings != null) {
            Settings st = data.settings;
            st.uaPresetsText = mergePresets(st.uaPresets, st.uaPresetsText, from.settings.uaPresets);
            st.urlPresetsText = mergePresets(st.urlPresets, st.urlPresetsText, from.settings.urlPresets);
        }
        save();
    }

    private void mergeItems(Kind kind, List<Item> add, List<Folder> addFolders) {
        Map<String, String> folderNames = new HashMap<>();
        List<Folder> srcFolders = addFolders != null ? new ArrayList<>(addFolders) : new ArrayList<>();
        srcFolders.sort(Comparator.comparingLong(f -> f.sortOrder));
        for (Folder f : srcFolders) {
            if (f.id == null || f.name == null || f.name.isBlank()) continue;
            folderNames.put(f.id, folderByName(kind, f.name.strip()));
        }
        if (add == null) return;
        List<Item> src = new ArrayList<>(add);
        src.sort(Comparator.comparingLong(i -> i.sortOrder));
        for (Item it : src) {
            String name = it.name == null ? "" : it.name.strip();
            if (name.isEmpty()) continue;
            String folderId = it.folderId == null ? null : folderNames.get(it.folderId);
            items(kind).add(newItem(kind, nextUniqueName(takenNames(kind, null), name),
                    it.content == null ? "" : it.content, it.selected, folderId));
        }
    }

    /**
     * Appends to {@code list} the presets of {@code add} it does not have (same name and value), and
     * returns the editor text with those lines appended (null stays null: the text is rebuilt from the list).
     */
    private static String mergePresets(List<Preset> list, String text, List<Preset> add) {
        if (add == null) return text;
        StringBuilder more = new StringBuilder();
        for (Preset p : add) {
            if (p == null || p.name == null || p.value == null) continue;
            boolean has = list.stream().anyMatch(q -> p.name.equals(q.name) && p.value.equals(q.value));
            if (has) continue;
            list.add(new Preset(p.name, p.value));
            more.append(p.name).append(" | ").append(p.value).append('\n');
        }
        if (text == null || more.isEmpty()) return text;
        return (text.isEmpty() || text.endsWith("\n") ? text : text + "\n") + more;
    }

    private String folderByName(Kind kind, String name) {
        for (Folder f : folders(kind)) if (f.name.equalsIgnoreCase(name)) return f.id;
        Folder f = newFolder(kind, name);
        folders(kind).add(f);
        return f.id;
    }
}
