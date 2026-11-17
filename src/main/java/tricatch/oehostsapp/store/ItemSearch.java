package tricatch.oehostsapp.store;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Finds the items of one list whose name or content contains a text, ignoring case. Plain text
 * only (no patterns); the lists are small, so it scans everything each time.
 */
public final class ItemSearch {

    /** Lines shown per item; the rest are only counted ({@link Hit#moreLines}). */
    public static final int MAX_LINES = 5;

    /**
     * A content line holding the text: its 0-based {@code line} number, the line itself, and the
     * first match as {@code [start, end)} in that line.
     */
    public record Line(int line, String text, int start, int end) {}

    /**
     * An item that matched, with the folder it is listed under (null: the root), the name's first
     * match as {@code [nameStart, nameEnd)} (-1 when only the content matched), its first
     * {@link #MAX_LINES} matching lines and how many more lines matched.
     */
    public record Hit(Store.Item item, Store.Folder folder, int nameStart, int nameEnd, List<Line> lines, int moreLines) {}

    private ItemSearch() {}

    /**
     * The items of {@code tree} (as {@link Store#tree} gives it) matching {@code query}, in list
     * order. {@code contentOf} supplies each item's text (the open item's unsaved edit, say). A blank
     * query finds nothing.
     */
    public static List<Hit> search(List<Store.Node> tree, String query, Function<Store.Item, String> contentOf) {
        String q = query == null ? "" : query.strip();
        List<Hit> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (Store.Node n : tree) {
            if (n.folder() != null) {
                for (Store.Item it : n.children()) add(out, it, n.folder(), q, contentOf);
            } else {
                add(out, n.item(), null, q, contentOf);
            }
        }
        return out;
    }

    private static void add(List<Hit> out, Store.Item it, Store.Folder folder, String q, Function<Store.Item, String> contentOf) {
        int nameAt = indexOfIgnoreCase(it.name == null ? "" : it.name, q);
        List<Line> lines = new ArrayList<>();
        int more = 0;
        String content = contentOf.apply(it);
        if (content != null && !content.isEmpty()) {
            String[] all = content.split("\r\n|\r|\n", -1);
            for (int i = 0; i < all.length; i++) {
                int at = indexOfIgnoreCase(all[i], q);
                if (at < 0) continue;
                if (lines.size() < MAX_LINES) lines.add(new Line(i, all[i], at, at + q.length()));
                else more++;
            }
        }
        if (nameAt < 0 && lines.isEmpty()) return;
        out.add(new Hit(it, folder, nameAt, nameAt < 0 ? -1 : nameAt + q.length(), List.copyOf(lines), more));
    }

    /**
     * Start offsets of every occurrence of {@code query} in {@code text}, ignoring case, left to
     * right and not overlapping (the editor's find bar). An empty query finds nothing.
     */
    public static int[] occurrences(String text, String query) {
        if (text == null || query == null || query.isEmpty()) return new int[0];
        int[] out = new int[8];
        int n = 0;
        for (int at = indexOfIgnoreCase(text, query, 0); at >= 0; at = indexOfIgnoreCase(text, query, at + query.length())) {
            if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
            out[n++] = at;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    static int indexOfIgnoreCase(String s, String q) {
        return indexOfIgnoreCase(s, q, 0);
    }

    /**
     * First index of {@code q} in {@code s} at or after {@code from}, ignoring case, or -1. Compares
     * in place with {@link String#regionMatches(boolean, int, String, int, int)} so the offsets stay
     * those of {@code s} (lower-casing can change a string's length).
     */
    static int indexOfIgnoreCase(String s, String q, int from) {
        int last = s.length() - q.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (s.regionMatches(true, i, q, 0, q.length())) return i;
        }
        return -1;
    }
}
