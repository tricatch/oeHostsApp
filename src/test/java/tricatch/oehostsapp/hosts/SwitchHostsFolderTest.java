package tricatch.oehostsapp.hosts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tricatch.oehostsapp.store.Store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwitchHostsFolderTest {

    @TempDir
    Path dir;

    private void write(String rel, String text) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @Test
    void readsTheSwitchHosts5Layout() throws Exception {
        write("manifest.json", """
                {"format":"switchhosts-data","schemaVersion":1,"root":[
                  {"id":"0","type":"local","title":"System","isSys":true},
                  {"id":"a","type":"local","title":"Dev","on":true,"contentFile":"entries/a.hosts"},
                  {"id":"f","type":"folder","title":"Team","folder":{"mode":0},"children":[
                    {"id":"r","type":"remote","title":"Remote","source":{"url":"https://x.test/h"}},
                    {"id":"n","type":"local","title":"No file yet"}]},
                  {"id":"..\\\\evil","type":"local","title":"Odd id"}]}
                """);
        write("entries/a.hosts", "1.1.1.1 a.test\r\n");
        write("entries/r.hosts", "2.2.2.2 r.test");
        write("entries/orphan.hosts", "9.9.9.9 o.test");
        write("trashcan.json", "{\"items\":[]}");
        write("evil.hosts", "6.6.6.6 evil.test");

        assertTrue(SwitchHostsFolder.isDataFolder(dir));
        SwitchHostsBackup.Result r = SwitchHostsFolder.read(dir);
        assertEquals(List.of(
                new Store.NewItem(null, "Dev", "1.1.1.1 a.test\n"),
                new Store.NewFolder("Team"),
                new Store.NewItem("Team", "Remote", "# SwitchHosts remote: https://x.test/h\n2.2.2.2 r.test"),
                new Store.NewItem("Team", "No file yet", ""),
                // An id that would leave the entries folder is never used as a file name.
                new Store.NewItem(null, "Odd id", "")), r.entries());
    }

    @Test
    void readsTheSwitchHosts4Layout() throws Exception {
        write("data/list/tree.json", """
                [{"id":"0","is_sys":true},
                 {"id":"1","type":"local","title":"One"},
                 {"id":"g","type":"folder","title":"Grp","children":[{"id":"2","type":"remote","title":"Two","url":"http://y.test"}]}]
                """);
        write("data/collection/hosts/ids.json", "[null, \"k1\", \"k2\", \"../x\", \"k9\", \"kf\"]");
        write("data/collection/hosts/data/k1.json", "{\"id\":\"1\",\"content\":\"1.1.1.1 one.test\",\"_id\":\"k1\"}");
        write("data/collection/hosts/data/k2.json", "{\"id\":\"2\",\"content\":\"2.2.2.2 two.test\",\"_id\":\"k2\"}");
        write("data/collection/hosts/data/kf.json", "{\"id\":\"g\",\"type\":\"folder\",\"_id\":\"kf\"}");
        write("config/dict/cfg.json", "{}");

        List<Store.NewEntry> expected = List.of(
                new Store.NewItem(null, "One", "1.1.1.1 one.test"),
                new Store.NewFolder("Grp"),
                new Store.NewItem("Grp", "Two", "# SwitchHosts remote: http://y.test\n2.2.2.2 two.test"));
        assertEquals(expected, SwitchHostsFolder.read(dir).entries());
        // The PotDb "data" folder itself works too.
        assertEquals(expected, SwitchHostsFolder.read(dir.resolve("data")).entries());
    }

    @Test
    void readsSwitchHosts4RecordsWithoutAnIndex() throws Exception {
        write("data/list/tree.json", "[{\"id\":\"1\",\"title\":\"One\"}]");
        write("data/collection/hosts/data/x.json", "{\"id\":\"1\",\"content\":\"1.1.1.1 one.test\"}");
        assertEquals(List.of(new Store.NewItem(null, "One", "1.1.1.1 one.test")), SwitchHostsFolder.read(dir).entries());
    }

    @Test
    void prefersTheSwitchHosts5DataOverLeftoverVersion4Data() throws Exception {
        write("manifest.json", "{\"schemaVersion\":1,\"root\":[{\"id\":\"a\",\"type\":\"local\",\"title\":\"Five\"}]}");
        write("data/list/tree.json", "[{\"id\":\"1\",\"title\":\"Four\"}]");
        assertEquals("Five", ((Store.NewItem) SwitchHostsFolder.read(dir).entries().get(0)).name());
    }

    @Test
    void findsTheDataFolder() throws Exception {
        Path home = dir.resolve("home"), config = dir.resolve("config");
        Files.createDirectories(home);
        assertNull(SwitchHostsFolder.defaultFolder(home, config));

        // SwitchHosts 4 data at the default place.
        write("home/.SwitchHosts/data/list/tree.json", "[]");
        assertEquals(home.resolve(".SwitchHosts"), SwitchHostsFolder.defaultFolder(home, config));

        // SwitchHosts 4 told to keep its data elsewhere.
        Path v4 = dir.resolve("v4store");
        write("v4store/data/list/tree.json", "[]");
        write("config/SwitchHosts/swh_local/dict/local.json",
                "{\"data_dir\":" + Store.MAPPER.writeValueAsString(v4.toString()) + "}");
        assertEquals(v4, SwitchHostsFolder.defaultFolder(home, config));

        // SwitchHosts 5 at the default place wins over version 4 data.
        write("home/.SwitchHosts/manifest.json", "{\"root\":[]}");
        assertEquals(home.resolve(".SwitchHosts"), SwitchHostsFolder.defaultFolder(home, config));

        // SwitchHosts 5 data moved elsewhere wins; a relative or missing location is ignored.
        Path moved = dir.resolve("moved/SwitchHosts.data");
        write("moved/SwitchHosts.data/manifest.json", "{\"root\":[]}");
        write("config/net.oldj.switchhosts/data_dir.json",
                "{\"format\":\"switchhosts-data-dir-pointer\",\"schemaVersion\":1,\"data_dir\":"
                        + Store.MAPPER.writeValueAsString(moved.toString()) + "}");
        assertEquals(moved, SwitchHostsFolder.defaultFolder(home, config));
        write("config/net.oldj.switchhosts/data_dir.json", "{\"data_dir\":\"relative/dir\"}");
        assertEquals(home.resolve(".SwitchHosts"), SwitchHostsFolder.defaultFolder(home, config));
    }

    @Test
    void refusesOtherFolders() throws Exception {
        assertFalse(SwitchHostsFolder.isDataFolder(dir));
        assertEquals("folder", assertThrows(SwitchHostsBackup.FormatException.class, () -> SwitchHostsFolder.read(dir)).code);
        write("manifest.json", "not json");
        assertEquals("invalid", assertThrows(SwitchHostsBackup.FormatException.class, () -> SwitchHostsFolder.read(dir)).code);
        write("manifest.json", "{\"schemaVersion\":2,\"root\":[]}");
        assertEquals("newer", assertThrows(SwitchHostsBackup.FormatException.class, () -> SwitchHostsFolder.read(dir)).code);
    }
}
