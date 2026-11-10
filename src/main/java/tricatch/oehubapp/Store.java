package tricatch.oehubapp;

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

    public static String newId() {
        return String.format("%016x%04x", RANDOM.nextLong(), SEQ.getAndIncrement() & 0xffff);
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

    // ── Import ────────────────────────────────────────────────

    /** Rows imported, and rows skipped (encrypted or without a name). */
    public record ImportResult(int imported, int skipped) {}

    /**
     * Imports one kind from an export file, this app's or oeHub's ({@link #exportKind}'s shape):
     * the {@code hosts} or {@code vhosts} array of rows with name/content, {@code selected} and
     * optionally {@code folderName}. Encrypted rows (an oeHub wrappedContentKey) cannot be read
     * here and are skipped. With {@code replace}, that kind's items and folders are cleared first.
     * A file without that array is refused.
     */
    public ImportResult importKind(JsonNode root, boolean replace, Kind kind) {
        JsonNode rows = root == null ? null : root.get(kind == Kind.HOSTS ? "hosts" : "vhosts");
        if (rows == null || !rows.isArray()) {
            throw StoreException.invalid(kind == Kind.HOSTS ? "app.data.import.error.no.hosts" : "app.data.import.error.no.vhosts");
        }
        if (replace) {
            items(kind).clear();
            folders(kind).clear();
        }
        int imported = 0, skipped = 0;
        for (JsonNode row : rows) {
            String name = row.path(kind.nameKey).asText("").strip();
            boolean encrypted = !row.path("wrappedContentKey").asText("").isEmpty();
            if (name.isEmpty() || encrypted) {
                skipped++;
                continue;
            }
            String content = row.path(kind.contentKey).asText("").replace("\r\n", "\n");
            String folderName = row.path("folderName").asText("").strip();
            String folderId = folderName.isEmpty() ? null : folderByName(kind, folderName);
            items(kind).add(newItem(kind, nextUniqueName(takenNames(kind, null), name), content,
                    row.path("selected").asBoolean(false), folderId));
            imported++;
        }
        save();
        return new ImportResult(imported, skipped);
    }
    // ── Export / full restore ─────────────────────────────────

    /**
     * One kind's items in oeHub's export shape ({@code /api/hosts/export}, {@code /api/proxy/vhosts/export}):
     * {@code {"version":1, "exportedAt", "hosts"|"vhosts": [{name, content, selected, folderName, sortOrder}]}},
     * in tree order, so {@link #importKind} here and oeHub's own import both read it back.
     * Empty folders and the collapsed state are not part of that shape.
     */
    public JsonNode exportKind(Kind kind) {
        Map<String, String> folderNames = new HashMap<>();
        for (Folder f : folders(kind)) folderNames.put(f.id, f.name);
        com.fasterxml.jackson.databind.node.ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("exportedAt", now());
        com.fasterxml.jackson.databind.node.ArrayNode rows = root.putArray(kind == Kind.HOSTS ? "hosts" : "vhosts");
        long order = 0;
        for (Item it : list(kind)) {
            com.fasterxml.jackson.databind.node.ObjectNode r = rows.addObject();
            r.put(kind.nameKey, it.name);
            r.put(kind.contentKey, it.content);
            r.put("selected", it.selected);
            String folder = it.folderId == null ? null : folderNames.get(it.folderId);
            if (folder != null) r.put("folderName", folder);
            r.put("sortOrder", order++);
        }
        return root;
    }

    /** Replaces everything stored with {@code from} (a full backup) and saves. */
    public void replaceAll(Data from) {
        data.hosts = from.hosts != null ? from.hosts : new ArrayList<>();
        data.hostsFolders = from.hostsFolders != null ? from.hostsFolders : new ArrayList<>();
        data.vhosts = from.vhosts != null ? from.vhosts : new ArrayList<>();
        data.vhostFolders = from.vhostFolders != null ? from.vhostFolders : new ArrayList<>();
        data.hostsConf = from.hostsConf != null ? new TreeMap<>(from.hostsConf) : new TreeMap<>();
        data.proxyConf = from.proxyConf != null ? new TreeMap<>(from.proxyConf) : new TreeMap<>();
        data.settings = from.settings != null ? from.settings : new Settings();
        save();
    }

    private String folderByName(Kind kind, String name) {
        for (Folder f : folders(kind)) if (f.name.equalsIgnoreCase(name)) return f.id;
        Folder f = newFolder(kind, name);
        folders(kind).add(f);
        return f.id;
    }
}
