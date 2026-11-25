package tricatch.oehostsapp.hosts;

import com.fasterxml.jackson.databind.JsonNode;
import tricatch.oehostsapp.store.Store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a SwitchHosts backup (the JSON its "Export" writes) as oeHosts profiles. It accepts the
 * three formats SwitchHosts 5 itself imports (oldj/SwitchHosts, {@code src-tauri/src/import_export/preview.rs}):
 * <ul>
 *   <li>v5: {@code {"format":"switchhosts-backup","schemaVersion":1,"manifest":{"root":[...]},"entries":{id:content}}};
 *       nodes either in the on-disk shape ({@code source.url}, {@code group.include}) or the renderer's ({@code url}, {@code include}).</li>
 *   <li>v4: {@code {"version":[4,...],"data":{"list":{"tree":[...]},"collection":{"hosts":{"data":[{id,content}]}}}}};
 *       a node without a content record is empty (SwitchHosts creates them lazily).</li>
 *   <li>v3: {@code {"version":[3,...],"list":[...]}}, the node type in {@code where} and its content inline.</li>
 * </ul>
 * A folder becomes an oeHosts folder (nested folders are flattened to one level, named
 * {@code Parent / Child}); a local item becomes a profile; a remote item a profile holding the
 * content last downloaded, its URL in a comment (oeHosts does not refresh it); a group a profile
 * holding its items' contents in order. The system hosts entry, the trash, and which items were
 * on are not imported. Lines that {@link HostsRules#problems} would refuse are commented out, not
 * dropped, and a line with several host names is split into one line per name, because oeHosts
 * reads only the first name of a line.
 */
public final class SwitchHostsBackup {

    /** Larger files are refused before reading. */
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 10_000;
    private static final String DEFAULT_FOLDER = "new folder";

    /** The same whitespace as {@link HostsRules}. */
    private static final Pattern WS = Pattern.compile("[\\s\\uFEFF]+");
    private static final Pattern TRIM = Pattern.compile("^[\\s\\uFEFF]+|[\\s\\uFEFF]+$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]+");

    /** Why a file cannot be imported; {@link #code} is the last part of its message key. */
    public static final class FormatException extends Exception {
        private static final long serialVersionUID = 1L;
        /**
         * {@code format} (not a SwitchHosts backup), {@code folder} (not a SwitchHosts data folder),
         * {@code newer}, {@code invalid} (damaged), {@code empty}, {@code large}.
         */
        public final String code;

        FormatException(String code) {
            super(code);
            this.code = code;
        }
    }

    /**
     * What a backup holds: its folders (also the empty ones) and profiles in tree order, how many
     * profiles there are, and how many lines were commented out.
     */
    public record Result(List<Store.NewEntry> entries, int profiles, int commentedLines) {}

    private enum Version { V3, V4, V5 }

    /** A local, remote or group node, in tree order. */
    private record Leaf(String folder, String title, String type, String id, String url, List<String> include) {}

    private final Version version;
    private final Map<String, String> contents;
    /** Folders ({@link Store.NewFolder}) and leaves ({@link Leaf}) in tree order. */
    private final List<Object> order = new ArrayList<>();
    private final List<Leaf> leaves = new ArrayList<>();
    private int nodes;
    private int commented;

    private SwitchHostsBackup(Version version, Map<String, String> contents) {
        this.version = version;
        this.contents = contents;
    }

    /** Reads the backup file {@code file}. */
    public static Result read(Path file) throws IOException, FormatException {
        if (Files.size(file) > MAX_BYTES) throw new FormatException("large");
        return parse(Files.readAllBytes(file));
    }

    /** Reads a backup's bytes (UTF-8 JSON). */
    public static Result parse(byte[] json) throws FormatException {
        JsonNode data;
        try {
            data = Store.MAPPER.readTree(json);
        } catch (IOException e) {
            throw new FormatException("format");
        }
        return parse(data);
    }

    /** Reads a backup already parsed as JSON ({@link SwitchHostsFolder} builds one from a data folder). */
    static Result parse(JsonNode data) throws FormatException {
        if (data == null || !data.isObject()) throw new FormatException("format");

        Map<String, String> contents = new HashMap<>();
        JsonNode tree;
        Version version;
        if ("switchhosts-backup".equals(data.path("format").asText(null))) {
            if (isNot1(data.get("schemaVersion")) || isNot1(data.path("manifest").get("schemaVersion"))) {
                throw new FormatException("newer");
            }
            tree = data.path("manifest").path("root");
            JsonNode entries = data.path("entries");
            if (!entries.isObject()) throw new FormatException("invalid");
            for (Map.Entry<String, JsonNode> e : entries.properties()) {
                if (!e.getValue().isTextual()) throw new FormatException("invalid");
                contents.put(e.getKey(), e.getValue().asText());
            }
            version = Version.V5;
        } else {
            JsonNode major = data.path("version").path(0);
            if (!major.isIntegralNumber()) throw new FormatException("format");
            long n = major.asLong();
            if (n == 3) {
                tree = data.path("list");
                version = Version.V3;
            } else if (n == 4) {
                JsonNode inner = data.path("data");
                tree = inner.path("list").path("tree");
                JsonNode rows = inner.path("collection").path("hosts").path("data");
                // SwitchHosts refuses a backup without the content collection: its items were not exported.
                if (!rows.isArray()) throw new FormatException("invalid");
                for (JsonNode row : rows) {
                    String id = idOf(row.get("id"));
                    if (id == null || !row.path("content").isTextual()) throw new FormatException("invalid");
                    contents.put(id, row.get("content").asText());
                }
                version = Version.V4;
            } else if (n > 4) {
                throw new FormatException("newer");
            } else {
                throw new FormatException("format");
            }
        }
        if (!tree.isArray()) throw new FormatException("invalid");

        SwitchHostsBackup b = new SwitchHostsBackup(version, contents);
        b.walk(tree, null, 0);
        List<Store.NewEntry> entries = b.entries();
        if (entries.isEmpty()) throw new FormatException("empty");
        return new Result(entries, b.leaves.size(), b.commented);
    }

    private static boolean isNot1(JsonNode v) {
        return v != null && !(v.isIntegralNumber() && v.asLong() == 1);
    }

    /** A node id: a non-empty string or a number; null otherwise. */
    private static String idOf(JsonNode v) {
        if (v == null) return null;
        if (v.isTextual() && !v.asText().isEmpty()) return v.asText();
        if (v.isIntegralNumber()) return v.asText();
        return null;
    }

    /** One line of text from the file: control characters become a space. */
    private static String oneLine(String s) {
        return s == null ? "" : CONTROL.matcher(s).replaceAll(" ").strip();
    }

    private void walk(JsonNode list, String folder, int depth) throws FormatException {
        if (depth > MAX_DEPTH) throw new FormatException("invalid");
        for (JsonNode node : list) {
            if (!node.isObject()) throw new FormatException("invalid");
            if (++nodes > MAX_NODES) throw new FormatException("invalid");
            String id = idOf(node.get("id"));
            if (id == null) throw new FormatException("invalid");
            // The system hosts entry.
            if (id.equals("0") || node.path("is_sys").asBoolean(false) || node.path("isSys").asBoolean(false)) continue;
            JsonNode typeField = version == Version.V3 && node.has("where") ? node.get("where") : node.get("type");
            String type = typeField == null || typeField.isNull() ? "local" : typeField.asText();
            String title = oneLine(node.path("title").asText(""));
            switch (type) {
                case "folder" -> {
                    String name = title.isEmpty() ? DEFAULT_FOLDER : title;
                    String path = folder == null ? name : folder + " / " + name;
                    order.add(new Store.NewFolder(path));
                    JsonNode children = node.path("children");
                    if (!children.isMissingNode() && !children.isNull()) {
                        if (!children.isArray()) throw new FormatException("invalid");
                        walk(children, path, depth + 1);
                    }
                }
                case "local", "remote", "group" -> {
                    if (version == Version.V3 && !type.equals("group")) {
                        contents.put(id, node.path("content").asText(""));
                    }
                    Leaf leaf = new Leaf(folder, title, type, id, url(node), include(node));
                    leaves.add(leaf);
                    order.add(leaf);
                }
                default -> {
                    // A type this reader does not know is left out.
                }
            }
        }
    }

    private static String url(JsonNode node) {
        if (node.path("url").isTextual()) return oneLine(node.get("url").asText());
        JsonNode source = node.path("source");
        if (source.isObject() && source.path("url").isTextual()) return oneLine(source.get("url").asText());
        return "";
    }

    private static List<String> include(JsonNode node) {
        JsonNode inc = node.path("include");
        if (!inc.isArray()) inc = node.path("group").path("include");
        List<String> out = new ArrayList<>();
        for (JsonNode v : inc) {
            String id = idOf(v);
            if (id != null) out.add(id);
        }
        return out;
    }

    private String content(String id) {
        String c = contents.getOrDefault(id, "");
        if (c.startsWith("﻿")) c = c.substring(1);
        return c.replace("\r\n", "\n").replace('\r', '\n');
    }

    private List<Store.NewEntry> entries() {
        Map<String, Leaf> byId = new HashMap<>();
        for (Leaf l : leaves) byId.put(l.id(), l);
        List<Store.NewEntry> out = new ArrayList<>();
        for (Object o : order) {
            if (o instanceof Store.NewFolder f) {
                out.add(f);
                continue;
            }
            Leaf l = (Leaf) o;
            String text = switch (l.type()) {
                case "remote" -> (l.url().isEmpty() ? "" : "# SwitchHosts remote: " + l.url() + "\n") + content(l.id());
                case "group" -> group(l, byId);
                default -> content(l.id());
            };
            out.add(new Store.NewItem(l.folder(), l.title(), fixLines(text)));
        }
        return out;
    }

    /** The contents of a group's items in its order, each under a comment with the item's title. */
    private String group(Leaf g, Map<String, Leaf> byId) {
        StringBuilder b = new StringBuilder();
        for (String id : g.include()) {
            Leaf l = byId.get(id);
            if (l == null || l.type().equals("group")) continue;
            String c = content(id);
            if (!b.isEmpty() && b.charAt(b.length() - 1) != '\n') b.append('\n');
            b.append("# ").append(l.title().isEmpty() ? id : l.title()).append('\n').append(c);
        }
        return b.toString();
    }

    /**
     * Makes every line acceptable to {@link HostsRules#problems}: a line with several host names
     * becomes one line per name (the first keeps the comment), and a line that is still not
     * {@code IP host} is commented out.
     */
    String fixLines(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder(content.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(fixLine(lines[i]));
        }
        return out.toString();
    }

    private String fixLine(String line) {
        String trimmed = TRIM.matcher(line).replaceAll("");
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return line;
        int hash = line.indexOf('#');
        String head = hash < 0 ? line : line.substring(0, hash);
        String comment = hash < 0 ? "" : line.substring(hash);
        String[] w = WS.split(TRIM.matcher(head).replaceAll(""));
        boolean ok = w.length >= 2 && HostsRules.isAddress(w[0]);
        for (int k = 1; ok && k < w.length; k++) ok = HostsRules.isHost(w[k]);
        if (!ok) {
            commented++;
            return "# " + line;
        }
        if (w.length == 2 && HostsRules.problems(line).isEmpty()) return line;
        StringBuilder b = new StringBuilder();
        for (int k = 1; k < w.length; k++) {
            if (k > 1) b.append('\n');
            b.append(w[0]).append(' ').append(w[k]);
            if (k == 1 && !comment.isEmpty()) b.append(' ').append(comment);
        }
        return b.toString();
    }
}
