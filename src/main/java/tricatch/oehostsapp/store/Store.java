package tricatch.oehostsapp.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Local data: hosts profiles, vhosts, their folders, the Chrome launcher options and app settings,
 * in one JSON file. Every change is written right away (temp file + move; the previous version is
 * kept as {@code <file>.bak}). Callers synchronize on the store.
 */
public final class Store {

    /** Crockford base32, lower case (no i, l, o, u). */
    private static final String BASE32 = "0123456789abcdefghjkmnpqrstvwxyz";

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** The two list screens share one implementation. */
    public enum Kind {
        HOSTS("new hosts", "example/hosts_example_%s.txt"),
        VHOSTS("new vhost", "example/vhost_example_%s.yaml");

        // Same base names as oeHub (HostsProfService / ProxyVhostService).
        final String defaultName;
        final String exampleAsset;

        Kind(String defaultName, String exampleAsset) {
            this.defaultName = defaultName;
            this.exampleAsset = exampleAsset;
        }
    }

    public static class Item {
        public String id;
        public String name = "";
        public String content = "";
        public boolean selected;
        /** oeHosts only: served by the web service at {@code /hosts/<shareToken>} (for hosts switcher programs with remote sources). */
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

    public static class Data {
        public int version = 1;
        public List<Item> hosts = new ArrayList<>();
        public List<Folder> hostsFolders = new ArrayList<>();
        public List<Item> vhosts = new ArrayList<>();
        public List<Folder> vhostFolders = new ArrayList<>();
        /** Chrome launcher options of the oeHosts screen. */
        public LauncherOptions launcher = new LauncherOptions();
        public Settings settings = new Settings();
        /**
         * Key of the share URLs' HMAC ({@link #shareToken}), created on first use. Kept out of
         * backups ({@link #exportParts} has no part for it), so URLs never work with another
         * installation's data.
         */
        public String shareKey;

        /** Fills parts a data file left out, so callers never see null. */
        void fillDefaults() {
            if (hosts == null) hosts = new ArrayList<>();
            if (hostsFolders == null) hostsFolders = new ArrayList<>();
            if (vhosts == null) vhosts = new ArrayList<>();
            if (vhostFolders == null) vhostFolders = new ArrayList<>();
            if (launcher == null) launcher = new LauncherOptions();
            if (settings == null) settings = new Settings();
            launcher.fillDefaults();
            settings.fillDefaults();
        }
    }

    /** A refused change; {@link #code} tells why ({@code not_found}, {@code name_taken}, {@code io}...). */
    public static class StoreException extends RuntimeException {
        public final String code;

        StoreException(String code, String detail) {
            super(detail == null ? code : code + ": " + detail);
            this.code = code;
        }

        static StoreException notFound() { return new StoreException("not_found", null); }
        static StoreException nameTaken() { return new StoreException("name_taken", null); }
        static StoreException invalid(String what) { return new StoreException("invalid", what); }
    }

    /** One top-level entry of a list in display order: a folder with its items, or an item at the root. */
    public record Node(Folder folder, Item item, List<Item> children) {
        public long sortOrder() {
            return folder != null ? folder.sortOrder : item.sortOrder;
        }
    }

    /** One row of {@link #saveTreeOrder}: a folder ({@code folderId} unused) or an item and the folder it is in (null: the root). */
    public record Placement(String id, boolean folder, String folderId) {}

    private final Path path;
    public final Data data;
    /** Counts saves, so readers can cache what they derive from the data. */
    private volatile long revision;

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
     * Opens {@code path}, starting empty when it does not exist yet. A data file that does not parse
     * stops the app instead of being overwritten.
     */
    public static Store open(Path path) throws IOException {
        Data data = Files.exists(path) ? MAPPER.readValue(path.toFile(), Data.class) : new Data();
        data.version = 1;
        data.fillDefaults();
        Store store = new Store(path, data);
        if (!Files.exists(path)) store.save();
        return store;
    }

    public Path path() {
        return path;
    }

    /** Incremented by every {@link #save}. */
    public long revision() {
        return revision;
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
            revision++;
        } catch (IOException e) {
            throw new StoreException("io", e.getMessage());
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
        List<Item> out = new ArrayList<>();
        for (Node n : tree(kind)) {
            if (n.item() != null) out.add(n.item());
            else out.addAll(n.children());
        }
        return out;
    }

    /**
     * The list as drawn: folders and root items interleaved by sort order, each folder with its
     * items in order. An item whose folder is gone shows at the root.
     */
    public List<Node> tree(Kind kind) {
        Map<String, Node> byFolder = new LinkedHashMap<>();
        for (Folder f : folders(kind)) byFolder.put(f.id, new Node(f, null, new ArrayList<>()));
        List<Node> top = new ArrayList<>(byFolder.values());
        List<Item> items = new ArrayList<>(items(kind));
        items.sort(Comparator.comparingLong(i -> i.sortOrder));
        for (Item i : items) {
            Node parent = i.folderId == null ? null : byFolder.get(i.folderId);
            if (parent != null) parent.children().add(i);
            else top.add(new Node(null, i, null));
        }
        top.sort(Comparator.comparingLong(Node::sortOrder));
        return top;
    }

    /** The selected items in merge order. */
    public List<Item> selected(Kind kind) {
        return list(kind).stream().filter(i -> i.selected).toList();
    }

    /** The selected items' contents in merge order. */
    public List<String> selectedContents(Kind kind) {
        return selected(kind).stream().map(i -> i.content).toList();
    }

    public boolean hasSelected(Kind kind) {
        return items(kind).stream().anyMatch(i -> i.selected);
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

    /** A new item holding the example for {@code locale}. */
    public Item create(Kind kind, String locale) {
        return createFrom(kind, kind.defaultName, example(kind, locale));
    }

    /** A new item (an imported file, a merge); the name gets a " (n)" suffix if it is already taken. */
    public Item createFrom(Kind kind, String name, String content) {
        String base = name == null ? "" : name.strip();
        if (base.length() > 190) base = base.substring(0, 190).strip();
        if (base.isEmpty()) base = kind.defaultName;
        Item i = newItem(kind, nextUniqueName(takenNames(kind, null), base), content, false, null);
        items(kind).add(i);
        save();
        return i;
    }

    /** The item with {@code id}, or null. */
    public Item find(Kind kind, String id) {
        if (id == null) return null;
        for (Item i : items(kind)) if (i.id.equals(id)) return i;
        return null;
    }

    private Item item(Kind kind, String id) {
        Item i = find(kind, id);
        if (i == null) throw StoreException.notFound();
        return i;
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
            data.shareKey = Base64.getUrlEncoder().withoutPadding().encodeToString(k);
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
        if (!MessageDigest.isEqual(expected, given)) return null;
        return sharedHostsContent(id);
    }

    private String shareTag(String id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(data.shareKey), "HmacSHA256"));
            byte[] h = mac.doFinal(id.getBytes(StandardCharsets.UTF_8));
            long bits = 0;
            for (int k = 0; k < 5; k++) bits = (bits << 8) | (h[k] & 0xff);
            char[] out = new char[8];
            for (int k = 7; k >= 0; k--, bits >>>= 5) out[k] = BASE32.charAt((int) (bits & 31));
            return new String(out);
        } catch (GeneralSecurityException e) {
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
     * Saves the whole tree order, {@code entries} in display order, and opens folder {@code expand}
     * (null: none) in the same save. Unknown ids are refused, so a stale view cannot drop rows.
     */
    public void saveTreeOrder(Kind kind, List<Placement> entries, String expand) {
        Map<String, Folder> folders = new HashMap<>();
        for (Folder f : folders(kind)) folders.put(f.id, f);
        Map<String, Item> items = new HashMap<>();
        for (Item i : items(kind)) items.put(i.id, i);
        for (Placement p : entries) {
            boolean known = p.folder() ? folders.containsKey(p.id()) : items.containsKey(p.id());
            if (!known) throw StoreException.notFound();
            if (!p.folder() && p.folderId() != null && !folders.containsKey(p.folderId())) throw StoreException.invalid("folderId");
        }
        long n = 0;
        for (Placement p : entries) {
            if (p.folder()) {
                folders.get(p.id()).sortOrder = n++;
            } else {
                Item i = items.get(p.id());
                i.sortOrder = n++;
                i.folderId = p.folderId();
            }
        }
        Folder open = expand == null ? null : folders.get(expand);
        if (open != null && open.collapsed) {
            open.collapsed = false;
            open.updatedAt = now();
        }
        save();
    }

    // ── Export / backup restore ─────────────────────────────────

    /**
     * The chosen parts of the data for a backup's {@code data.json}: oeHosts (profiles, folders,
     * launcher options), oeProxy (vhosts, folders) and settings. A part left out has no key, so
     * {@link #restoreParts} and {@link Backup.Contents} can tell it was not exported.
     */
    public JsonNode exportParts(boolean hosts, boolean vhosts, boolean settings) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", data.version);
        if (hosts) {
            root.set("hosts", MAPPER.valueToTree(data.hosts));
            root.set("hostsFolders", MAPPER.valueToTree(data.hostsFolders));
            root.set("launcher", MAPPER.valueToTree(data.launcher));
        }
        if (vhosts) {
            root.set("vhosts", MAPPER.valueToTree(data.vhosts));
            root.set("vhostFolders", MAPPER.valueToTree(data.vhostFolders));
        }
        if (settings) root.set("settings", MAPPER.valueToTree(data.settings));
        return root;
    }

    private static Data parse(JsonNode json) throws IOException {
        Data from = MAPPER.treeToValue(json, Data.class);
        from.fillDefaults();
        return from;
    }

    /** Replaces the chosen parts (see {@link #exportParts}) with those of a backup's {@code data.json}, and saves. */
    public void restoreParts(JsonNode json, boolean hosts, boolean vhosts, boolean settings) throws IOException {
        Data from = parse(json);
        if (hosts) {
            data.hosts = from.hosts;
            data.hostsFolders = from.hostsFolders;
            data.launcher = from.launcher;
        }
        if (vhosts) {
            data.vhosts = from.vhosts;
            data.vhostFolders = from.vhostFolders;
        }
        if (settings) data.settings = from.settings;
        save();
    }

    /**
     * Adds the chosen parts of a backup's {@code data.json} to what is stored, keeping everything
     * already there: items are added (a taken name gets a " (n)" suffix) into folders matched by
     * name, and settings only gain the presets they lack. The launcher options and the other
     * settings stay as they are. Saves once at the end.
     */
    public void mergeParts(JsonNode json, boolean hosts, boolean vhosts, boolean settings) throws IOException {
        Data from = parse(json);
        if (hosts) mergeItems(Kind.HOSTS, from.hosts, from.hostsFolders);
        if (vhosts) mergeItems(Kind.VHOSTS, from.vhosts, from.vhostFolders);
        if (settings) {
            data.settings.userAgentPresets.merge(from.settings.userAgentPresets.items);
            data.settings.openUrlPresets.merge(from.settings.openUrlPresets.items);
        }
        save();
    }

    private void mergeItems(Kind kind, List<Item> add, List<Folder> addFolders) {
        Map<String, String> folderNames = new HashMap<>();
        List<Folder> srcFolders = new ArrayList<>(addFolders);
        srcFolders.sort(Comparator.comparingLong(f -> f.sortOrder));
        for (Folder f : srcFolders) {
            if (f.id == null || f.name == null || f.name.isBlank()) continue;
            folderNames.put(f.id, folderByName(kind, f.name.strip()));
        }
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

    private String folderByName(Kind kind, String name) {
        for (Folder f : folders(kind)) if (f.name.equalsIgnoreCase(name)) return f.id;
        Folder f = newFolder(kind, name);
        folders(kind).add(f);
        return f.id;
    }
}
