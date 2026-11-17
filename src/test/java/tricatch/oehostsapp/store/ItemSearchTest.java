package tricatch.oehostsapp.store;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemSearchTest {

    private static Store.Item item(String name, String content) {
        Store.Item it = new Store.Item();
        it.id = name;
        it.name = name;
        it.content = content;
        return it;
    }

    private static Store.Folder folder(String name) {
        Store.Folder f = new Store.Folder();
        f.id = name;
        f.name = name;
        return f;
    }

    @Test
    void matchesNameAndContentInListOrderIgnoringCase() {
        Store.Item a = item("dev", "127.0.0.1 api.foo.com\n# none\n10.0.0.1 API.FOO.COM www");
        Store.Item b = item("Foo staging", "127.0.0.1 bar.com");
        Store.Item c = item("other", "127.0.0.1 bar.com");
        Store.Folder f = folder("team");
        List<Store.Node> tree = List.of(
                new Store.Node(f, null, new ArrayList<>(List.of(b))),
                new Store.Node(null, a, null),
                new Store.Node(null, c, null));

        List<ItemSearch.Hit> hits = ItemSearch.search(tree, " foo ", it -> it.content);

        assertEquals(2, hits.size());
        ItemSearch.Hit h0 = hits.get(0);
        assertSame(b, h0.item());
        assertSame(f, h0.folder());
        assertEquals(0, h0.nameStart());
        assertEquals(3, h0.nameEnd());
        assertTrue(h0.lines().isEmpty());

        ItemSearch.Hit h1 = hits.get(1);
        assertSame(a, h1.item());
        assertNull(h1.folder());
        assertEquals(-1, h1.nameStart());
        assertEquals(List.of(new ItemSearch.Line(0, "127.0.0.1 api.foo.com", 14, 17),
                new ItemSearch.Line(2, "10.0.0.1 API.FOO.COM www", 13, 16)), h1.lines());
        assertEquals(0, h1.moreLines());
    }

    @Test
    void blankQueryFindsNothing() {
        List<Store.Node> tree = List.of(new Store.Node(null, item("a", "a"), null));
        assertTrue(ItemSearch.search(tree, "  ", it -> it.content).isEmpty());
        assertTrue(ItemSearch.search(tree, null, it -> it.content).isEmpty());
    }

    @Test
    void usesSuppliedContentAndCapsLines() {
        Store.Item a = item("a", "stored only");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ItemSearch.MAX_LINES + 3; i++) sb.append("x host\r\n");
        Map<String, String> edits = Map.of("a", sb.toString());
        List<Store.Node> tree = List.of(new Store.Node(null, a, null));

        assertTrue(ItemSearch.search(tree, "stored", it -> edits.get(it.id)).isEmpty());
        ItemSearch.Hit h = ItemSearch.search(tree, "HOST", it -> edits.get(it.id)).get(0);
        assertEquals(ItemSearch.MAX_LINES, h.lines().size());
        assertEquals(3, h.moreLines());
        assertEquals(4, h.lines().get(4).line());
    }

    @Test
    void occurrencesAreAllNonOverlappingMatches() {
        assertArrayEquals(new int[]{0, 7, 11}, ItemSearch.occurrences("Foo.x  foo fOO", "foo"));
        assertArrayEquals(new int[]{0, 2}, ItemSearch.occurrences("aaaaa", "aa"));
        assertArrayEquals(new int[0], ItemSearch.occurrences("abc", ""));
        assertArrayEquals(new int[0], ItemSearch.occurrences("abc", "x"));
    }

    @Test
    void offsetsStayThoseOfTheOriginalText() {
        // "İ" lower-cases to two chars; the match offset must still point into the original line.
        assertEquals(2, ItemSearch.indexOfIgnoreCase("İ-ab", "AB"));
        assertEquals(-1, ItemSearch.indexOfIgnoreCase("a", "ab"));
    }
}
