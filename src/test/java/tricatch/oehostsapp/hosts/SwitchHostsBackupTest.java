package tricatch.oehostsapp.hosts;

import org.junit.jupiter.api.Test;
import tricatch.oehostsapp.store.Store;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwitchHostsBackupTest {

    private static SwitchHostsBackup.Result parse(String json) throws Exception {
        return SwitchHostsBackup.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Store.NewItem> profiles(SwitchHostsBackup.Result r) {
        return r.entries().stream().filter(e -> e instanceof Store.NewItem).map(e -> (Store.NewItem) e).toList();
    }

    private static String code(String json) {
        return assertThrows(SwitchHostsBackup.FormatException.class, () -> parse(json)).code;
    }

    @Test
    void readsV5InBothNodeShapes() throws Exception {
        // What SwitchHosts 5's export writes: the manifest root in the on-disk shape, contents in "entries".
        SwitchHostsBackup.Result r = parse("""
                {"format":"switchhosts-backup","schemaVersion":1,"version":[5,0,0,0],
                 "manifest":{"format":"switchhosts-data","schemaVersion":1,"root":[
                   {"id":"0","type":"local","title":"System","isSys":true},
                   {"id":"a","type":"local","title":"Dev","on":true,"contentFile":"entries/a.hosts"},
                   {"id":"f","type":"folder","title":"Team","folder":{"mode":1},"children":[
                     {"id":"r","type":"remote","title":"Remote","source":{"url":"https://x.test/h","refreshIntervalSec":3600}},
                     {"id":"f2","type":"folder","title":"Inner","children":[
                       {"id":"b","title":"Renderer shape","url":"ignored-for-local"}]}]},
                   {"id":"g","type":"group","title":"Both","group":{"include":["a","missing","r"]}},
                   {"id":"g2","type":"group","title":"Renderer group","include":["b"]},
                   {"id":"e","type":"folder","title":"Empty"}]},
                 "entries":{"a":"1.1.1.1 a.test\\r\\n","r":"2.2.2.2 r.test","b":"3.3.3.3 b.test","orphan":"9.9.9.9 o.test"},
                 "trashcan":{"format":"switchhosts-trashcan","schemaVersion":1,"items":[]}}
                """);
        // Tree order, folders where they stand; nested folders flattened to one level.
        assertEquals(List.of(
                new Store.NewItem(null, "Dev", "1.1.1.1 a.test\n"),
                new Store.NewFolder("Team"),
                new Store.NewItem("Team", "Remote", "# SwitchHosts remote: https://x.test/h\n2.2.2.2 r.test"),
                new Store.NewFolder("Team / Inner"),
                new Store.NewItem("Team / Inner", "Renderer shape", "3.3.3.3 b.test"),
                new Store.NewItem(null, "Both", "# Dev\n1.1.1.1 a.test\n# Remote\n2.2.2.2 r.test"),
                new Store.NewItem(null, "Renderer group", "# Renderer shape\n3.3.3.3 b.test"),
                new Store.NewFolder("Empty")), r.entries());
        assertEquals(5, r.profiles());
        assertEquals(0, r.commentedLines());
    }

    @Test
    void readsV4WithSparseContentAndNumericIds() throws Exception {
        SwitchHostsBackup.Result r = parse("""
                {"version":[4,2,0,6140],"data":{
                  "list":{"tree":[
                    {"id":1,"type":"local","title":"One"},
                    {"id":"2","type":"remote","title":"Two","url":"http://y.test/h","refresh_interval":60},
                    {"id":"3","type":"local","title":"Untouched"}]},
                  "collection":{"hosts":{"data":[{"id":1,"content":"1.1.1.1 one.test"},{"id":"2","content":"2.2.2.2 two.test"}]}}}}
                """);
        assertEquals(List.of(
                new Store.NewItem(null, "One", "1.1.1.1 one.test"),
                new Store.NewItem(null, "Two", "# SwitchHosts remote: http://y.test/h\n2.2.2.2 two.test"),
                new Store.NewItem(null, "Untouched", "")), profiles(r));
    }

    @Test
    void readsV3WithTypeInWhereAndInlineContent() throws Exception {
        SwitchHostsBackup.Result r = parse("""
                {"version":[3,5,4],"list":[
                  {"id":"x","where":"local","title":"Local","content":"1.1.1.1 l.test"},
                  {"id":"y","where":"remote","title":"Remote","url":"http://z.test","content":"2.2.2.2 r.test","refresh_interval":1},
                  {"id":"z","where":"group","title":"Group","include":["x","y"]}]}
                """);
        assertEquals(List.of(
                new Store.NewItem(null, "Local", "1.1.1.1 l.test"),
                new Store.NewItem(null, "Remote", "# SwitchHosts remote: http://z.test\n2.2.2.2 r.test"),
                new Store.NewItem(null, "Group", "# Local\n1.1.1.1 l.test\n# Remote\n2.2.2.2 r.test")), profiles(r));
    }

    @Test
    void fixesLinesOeHostsWouldRefuse() throws Exception {
        SwitchHostsBackup.Result r = parse("""
                {"version":[3],"list":[{"id":"x","title":"T","content":"\\uFEFF# c\\n0.0.0.0 a.test b.test # ads\\n1.2.3.4 a.test#x\\nbad line\\n1.2.3.4\\n1.2.3.4 # only\\n  ::1 v6.test\\n1.2.3.4 ok.test \\"q\\"\\n"}]}
                """);
        String c = profiles(r).get(0).content();
        assertEquals("""
                # c
                0.0.0.0 a.test # ads
                0.0.0.0 b.test
                1.2.3.4 a.test #x
                # bad line
                # 1.2.3.4
                # 1.2.3.4 # only
                  ::1 v6.test
                # 1.2.3.4 ok.test "q"
                """, c);
        assertEquals(4, r.commentedLines());
        assertEquals(List.of(), HostsRules.problems(c));
    }

    @Test
    void namesFromTheFileStayOnOneLine() throws Exception {
        SwitchHostsBackup.Result r = parse("""
                {"version":[3],"list":[{"id":"x","where":"remote","title":"a\\nb","url":"http://u.test\\n1.1.1.1 evil.test","content":""}]}
                """);
        assertEquals(new Store.NewItem(null, "a b", "# SwitchHosts remote: http://u.test 1.1.1.1 evil.test\n"), profiles(r).get(0));
        assertEquals(List.of(), HostsRules.active(List.of(profiles(r).get(0).content())));
    }

    @Test
    void refusesWhatIsNotAReadableBackup() {
        assertEquals("format", code("not json"));
        assertEquals("format", code("[]"));
        assertEquals("format", code("{\"hosts\":[]}"));
        assertEquals("format", code("{\"version\":[2],\"list\":[]}"));
        assertEquals("newer", code("{\"version\":[6],\"list\":[]}"));
        assertEquals("newer", code("{\"format\":\"switchhosts-backup\",\"schemaVersion\":2,\"manifest\":{\"root\":[]},\"entries\":{}}"));
        assertEquals("newer", code("{\"format\":\"switchhosts-backup\",\"schemaVersion\":1,\"manifest\":{\"schemaVersion\":2,\"root\":[]},\"entries\":{}}"));
        assertEquals("invalid", code("{\"format\":\"switchhosts-backup\",\"schemaVersion\":1,\"manifest\":{},\"entries\":{}}"));
        assertEquals("invalid", code("{\"format\":\"switchhosts-backup\",\"schemaVersion\":1,\"manifest\":{\"root\":[]},\"entries\":{\"a\":1}}"));
        // A v4 backup without its content collection was cut short.
        assertEquals("invalid", code("{\"version\":[4],\"data\":{\"list\":{\"tree\":[{\"id\":\"a\",\"title\":\"A\"}]}}}"));
        assertEquals("invalid", code("{\"version\":[3],\"list\":[{\"title\":\"no id\"}]}"));
        assertEquals("empty", code("{\"version\":[3],\"list\":[{\"id\":\"0\",\"is_sys\":true,\"content\":\"1.1.1.1 a\"}]}"));
    }

    @Test
    void refusesTooDeepTrees() {
        StringBuilder b = new StringBuilder("{\"version\":[3],\"list\":");
        for (int i = 0; i < 70; i++) b.append("[{\"id\":\"f").append(i).append("\",\"where\":\"folder\",\"children\":");
        b.append("[]");
        for (int i = 0; i < 70; i++) b.append("}]");
        b.append('}');
        assertEquals("invalid", code(b.toString()));
    }
}
