package tricatch.oehostsapp.hosts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tricatch.oehostsapp.store.Store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Reads SwitchHosts' own data folder, as an alternative to its exported backup. The folder is only
 * read, never written. Its content is rebuilt into the backup's JSON and handed to
 * {@link SwitchHostsBackup}, so both imports turn the same data into the same profiles. The two
 * layouts follow oldj/SwitchHosts ({@code src-tauri/src/storage} and {@code src-tauri/src/migration/potdb.rs}):
 * <ul>
 *   <li>SwitchHosts 5: {@code manifest.json} (the tree, nodes in the on-disk shape) and
 *       {@code entries/<id>.hosts} (one content file per local/remote node; a missing file is empty).</li>
 *   <li>SwitchHosts 4 (PotDb): {@code data/list/tree.json} and {@code data/collection/hosts}
 *       ({@code ids.json} giving the record order, {@code data/<_id>.json} holding {@code {id, content}}).
 *       The {@code data} folder itself may be chosen too.</li>
 * </ul>
 * SwitchHosts 5 writes every file atomically (temp file and rename), so reading while it runs
 * sees either the old or the new version of a file.
 */
public final class SwitchHostsFolder {

    private static final int MAX_FILES = 10_000;
    /** A location file is a few hundred bytes; anything larger is not one. */
    private static final long MAX_POINTER_BYTES = 64 * 1024;

    private SwitchHostsFolder() {}

    /** Counts what is read, so a huge or hostile folder cannot exhaust memory. */
    private static final class Budget {
        long bytes;
        int files;

        byte[] read(Path file) throws IOException, SwitchHostsBackup.FormatException {
            if (++files > MAX_FILES) throw new SwitchHostsBackup.FormatException("large");
            bytes += Files.size(file);
            if (bytes > SwitchHostsBackup.MAX_BYTES) throw new SwitchHostsBackup.FormatException("large");
            return Files.readAllBytes(file);
        }
    }

    /** True when {@code dir} looks like a SwitchHosts 5 or 4 data folder. */
    public static boolean isDataFolder(Path dir) {
        return dir != null && (Files.isRegularFile(dir.resolve("manifest.json"))
                || Files.isRegularFile(dir.resolve("data/list/tree.json"))
                || Files.isRegularFile(dir.resolve("list/tree.json")));
    }

    /** Reads the data folder {@code dir}. */
    public static SwitchHostsBackup.Result read(Path dir) throws IOException, SwitchHostsBackup.FormatException {
        Budget budget = new Budget();
        if (Files.isRegularFile(dir.resolve("manifest.json"))) return SwitchHostsBackup.parse(v5(dir, budget));
        if (Files.isRegularFile(dir.resolve("data/list/tree.json"))) return SwitchHostsBackup.parse(v4(dir.resolve("data"), budget));
        if (Files.isRegularFile(dir.resolve("list/tree.json"))) return SwitchHostsBackup.parse(v4(dir, budget));
        throw new SwitchHostsBackup.FormatException("folder");
    }

    /** {@code manifest.json} and the entries its nodes name, as a v5 backup. */
    private static JsonNode v5(Path dir, Budget budget) throws IOException, SwitchHostsBackup.FormatException {
        JsonNode manifest = json(dir.resolve("manifest.json"), budget);
        if (!manifest.isObject()) throw new SwitchHostsBackup.FormatException("invalid");
        ObjectNode backup = Store.MAPPER.createObjectNode();
        backup.put("format", "switchhosts-backup");
        backup.put("schemaVersion", 1);
        backup.set("manifest", manifest);
        ObjectNode entries = backup.putObject("entries");
        List<String> ids = new ArrayList<>();
        collectIds(manifest.path("root"), ids, 0);
        Path entriesDir = dir.resolve("entries");
        for (String id : ids) {
            if (!simpleName(id)) continue;
            Path file = entriesDir.resolve(id + ".hosts");
            if (Files.isRegularFile(file)) entries.put(id, new String(budget.read(file), StandardCharsets.UTF_8));
        }
        return backup;
    }

    /** The ids of every node in the tree (the content files are named after them). */
    private static void collectIds(JsonNode nodes, List<String> out, int depth) throws SwitchHostsBackup.FormatException {
        if (!nodes.isArray()) return;
        if (depth > 64 || out.size() > MAX_FILES) throw new SwitchHostsBackup.FormatException("invalid");
        for (JsonNode n : nodes) {
            JsonNode id = n.path("id");
            if (id.isTextual() || id.isIntegralNumber()) out.add(id.asText());
            collectIds(n.path("children"), out, depth + 1);
        }
    }

    /** {@code list/tree.json} and the {@code collection/hosts} records under PotDb folder {@code db}, as a v4 backup. */
    private static JsonNode v4(Path db, Budget budget) throws IOException, SwitchHostsBackup.FormatException {
        JsonNode tree = json(db.resolve("list/tree.json"), budget);
        if (!tree.isArray()) throw new SwitchHostsBackup.FormatException("invalid");
        ArrayNode rows = Store.MAPPER.createArrayNode();
        for (Path file : hostsRecords(db.resolve("collection/hosts"), budget)) {
            JsonNode r = json(file, budget);
            JsonNode id = r.path("id");
            // Folder and group records (written by the v3 importer) carry no content.
            if (!(id.isTextual() || id.isIntegralNumber()) || !r.path("content").isTextual()) continue;
            ObjectNode row = rows.addObject();
            row.put("id", id.asText());
            row.put("content", r.get("content").asText());
        }
        ObjectNode backup = Store.MAPPER.createObjectNode();
        backup.putArray("version").add(4);
        ObjectNode data = backup.putObject("data");
        data.putObject("list").set("tree", tree);
        data.putObject("collection").putObject("hosts").set("data", rows);
        return backup;
    }

    /** The record files of a PotDb collection, in {@code ids.json} order (else every {@code data/*.json}). */
    private static List<Path> hostsRecords(Path coll, Budget budget) throws IOException, SwitchHostsBackup.FormatException {
        List<Path> out = new ArrayList<>();
        Path data = coll.resolve("data");
        Path ids = coll.resolve("ids.json");
        if (Files.isRegularFile(ids)) {
            JsonNode list = json(ids, budget);
            if (!list.isArray()) throw new SwitchHostsBackup.FormatException("invalid");
            for (JsonNode id : list) {
                // ids.json may hold null slots; only plain names address records in this folder.
                if (!id.isTextual() || !simpleName(id.asText())) continue;
                Path file = data.resolve(id.asText() + ".json");
                if (Files.isRegularFile(file)) out.add(file);
            }
        } else if (Files.isDirectory(data)) {
            try (Stream<Path> files = Files.list(data)) {
                files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                        .sorted().limit(MAX_FILES + 1L).forEach(out::add);
            }
        }
        return out;
    }

    /** A file name part that cannot leave its folder. */
    private static boolean simpleName(String s) {
        return !s.isEmpty() && !s.contains("/") && !s.contains("\\") && !s.contains("\0") && !s.contains("..")
                && !s.contains(":");
    }

    private static JsonNode json(Path file, Budget budget) throws IOException, SwitchHostsBackup.FormatException {
        byte[] bytes = budget.read(file);
        try {
            JsonNode n = Store.MAPPER.readTree(bytes);
            if (n == null) throw new SwitchHostsBackup.FormatException("invalid");
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SwitchHostsBackup.FormatException("invalid");
        }
    }

    // ── Finding the folder ─────────────────────────────────────

    /**
     * Where SwitchHosts keeps its data on this PC, or null: the folder SwitchHosts 5 was told to
     * use, else {@code ~/.SwitchHosts} when it holds SwitchHosts 5 data, else the folder
     * SwitchHosts 4 was told to use, else {@code ~/.SwitchHosts} when it holds SwitchHosts 4 data.
     */
    public static Path defaultFolder() {
        Path home = Path.of(System.getProperty("user.home"));
        return defaultFolder(home, configDir(home));
    }

    /** {@link #defaultFolder()} for home folder {@code home} and config folder {@code config}. */
    static Path defaultFolder(Path home, Path config) {
        Path def = home.resolve(".SwitchHosts");
        Path moved = config == null ? null : pointer(config.resolve("net.oldj.switchhosts").resolve("data_dir.json"));
        if (moved != null && Files.isRegularFile(moved.resolve("manifest.json"))) return moved;
        if (Files.isRegularFile(def.resolve("manifest.json"))) return def;
        Path v4 = config == null ? null : pointer(config.resolve("SwitchHosts").resolve("swh_local").resolve("dict").resolve("local.json"));
        if (isDataFolder(v4)) return v4;
        return isDataFolder(def) ? def : null;
    }

    /** The platform's per-user config folder, where SwitchHosts records a moved data folder. */
    private static Path configDir(Path home) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData == null || appData.isBlank() ? home.resolve("AppData").resolve("Roaming") : Path.of(appData);
        }
        if (os.contains("mac")) return home.resolve("Library").resolve("Application Support");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return xdg == null || xdg.isBlank() ? home.resolve(".config") : Path.of(xdg);
    }

    /** The absolute {@code data_dir} recorded in a SwitchHosts location file, or null. */
    private static Path pointer(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_POINTER_BYTES) return null;
            JsonNode dir = Store.MAPPER.readTree(file.toFile()).path("data_dir");
            if (!dir.isTextual() || dir.asText().isBlank()) return null;
            Path p = Path.of(dir.asText());
            return p.isAbsolute() ? p : null;
        } catch (Exception e) {
            return null;
        }
    }
}
