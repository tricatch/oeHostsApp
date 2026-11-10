package tricatch.oehubapp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoreTest {

    @TempDir
    Path dir;

    private Store open() throws Exception {
        return Store.open(dir.resolve("oehosts-app.json"), null);
    }

    private static JsonNode json(String s) throws Exception {
        return Store.MAPPER.readTree(s);
    }

    @Test
    void createRenameUniqueNamesAndPersist() throws Exception {
        Store s = open();
        Store.Item a = s.create(Store.Kind.HOSTS, "en");
        Store.Item b = s.create(Store.Kind.HOSTS, "ko");
        assertEquals("new hosts", a.name);
        assertEquals("new hosts (2)", b.name);
        assertFalse(a.content.isEmpty());
        Store.StoreException e = assertThrows(Store.StoreException.class, () -> s.rename(Store.Kind.HOSTS, b.id, "NEW HOSTS"));
        assertEquals(409, e.status);
        assertEquals("dev", s.rename(Store.Kind.HOSTS, b.id, " dev ").name);
        assertTrue(s.toggleSelected(Store.Kind.HOSTS, b.id).selected);
        assertTrue(Files.exists(dir.resolve("oehosts-app.json.bak")));

        Store again = open();
        assertEquals(2, again.data.hosts.size());
        assertEquals(List.of(b.content), again.selectedHostsContents());
    }

    @Test
    void treeOrderAndFolderDelete() throws Exception {
        Store s = open();
        Store.Item a = s.create(Store.Kind.VHOSTS, "en");
        Store.Item b = s.create(Store.Kind.VHOSTS, "en");
        Store.Folder f = s.createFolder(Store.Kind.VHOSTS);
        s.saveTreeOrder(Store.Kind.VHOSTS, json("[{\"type\":\"folder\",\"id\":\"" + f.id + "\"},"
                + "{\"type\":\"item\",\"id\":\"" + b.id + "\",\"folderId\":\"" + f.id + "\"},"
                + "{\"type\":\"item\",\"id\":\"" + a.id + "\",\"folderId\":null}]"));
        assertEquals(List.of(b.id, a.id), s.list(Store.Kind.VHOSTS).stream().map(i -> i.id).toList());
        assertThrows(Store.StoreException.class,
                () -> s.saveTreeOrder(Store.Kind.VHOSTS, json("[{\"type\":\"item\",\"id\":\"nope\"}]")));
        assertEquals(409, assertThrows(Store.StoreException.class, () -> {
            Store.Folder g = s.createFolder(Store.Kind.VHOSTS);
            s.updateFolder(Store.Kind.VHOSTS, g.id, "NEW FOLDER", null);
        }).status);
        s.deleteFolder(Store.Kind.VHOSTS, f.id);
        assertTrue(s.data.vhosts.stream().allMatch(i -> i.folderId == null));
    }

    @Test
    void migratesLegacyFileWithoutTouchingIt() throws Exception {
        Path legacy = dir.resolve("oehosts.json");
        String text = "{\"version\":1,\"profiles\":["
                + "{\"id\":1,\"name\":\"a\",\"content\":\"1.1.1.1 a.com\",\"selected\":true},"
                + "{\"id\":2,\"name\":\"b\",\"content\":\"2.2.2.2 b.com\",\"selected\":false},"
                + "{\"id\":3,\"name\":\"c\",\"content\":\"\",\"selected\":false}],"
                + "\"items\":[{\"type\":\"folder\",\"id\":10,\"name\":\"f\",\"collapsed\":true,\"items\":[{\"type\":\"profile\",\"id\":2}]},"
                + "{\"type\":\"profile\",\"id\":1}],"
                + "\"settings\":{\"incognito\":true,\"openUrl\":\"https://x\",\"proxySvr\":\"10.0.0.1\"},"
                + "\"presets\":{\"userAgents\":[{\"name\":\"m\",\"value\":\"Mobile\"}],\"urls\":[]}}";
        Files.writeString(legacy, text);
        Store s = Store.open(dir.resolve("oehosts-app.json"), legacy);
        assertEquals(List.of("b", "a", "c"), s.list(Store.Kind.HOSTS).stream().map(i -> i.name).toList());
        assertTrue(s.data.hostsFolders.get(0).collapsed);
        assertEquals("true", s.data.hostsConf.get("incognito"));
        assertEquals("10.0.0.1", s.data.settings.proxySvr);
        assertEquals("Mobile", s.data.settings.uaPresets.get(0).value);
        assertEquals(text, Files.readString(legacy));
    }

    @Test
    void refusesToOverwriteAnUnreadableDataFile() throws Exception {
        Files.writeString(dir.resolve("oehosts-app.json"), "{broken");
        assertThrows(Exception.class, this::open);
        assertEquals("{broken", Files.readString(dir.resolve("oehosts-app.json")));
    }

    @Test
    void backupRestoresOnlyChosenParts() throws Exception {
        Store s = open();
        s.create(Store.Kind.HOSTS, "en");
        s.create(Store.Kind.VHOSTS, "en");
        s.data.settings.fwdproxyPort = 4321;
        Path ca = Files.createDirectories(dir.resolve("ca"));
        Files.writeString(ca.resolve("ca.cer"), "CERT");
        Files.writeString(ca.resolve("ca.pfx"), "KEY");

        // Everything, CA included.
        Path all = dir.resolve("all.zip");
        Backup.write(Store.MAPPER.writeValueAsBytes(s.exportParts(true, true, true)), ca, all);
        Backup.Contents b = Backup.read(all);
        assertTrue(b.hasHosts() && b.hasVhosts() && b.hasSettings() && b.hasCa());
        assertEquals("CERT", new String(b.cert()));
        assertEquals("KEY", new String(b.key()));
        Store other = Store.open(dir.resolve("other.json"), null);
        other.create(Store.Kind.HOSTS, "en");
        other.create(Store.Kind.HOSTS, "en");
        other.restoreParts(b.data(), false, true, true);
        assertEquals(2, other.data.hosts.size(), "hosts not chosen: kept");
        assertEquals(1, other.data.vhosts.size());
        assertEquals(4321, other.data.settings.fwdproxyPort);

        // oeHosts only, no CA.
        Path hostsOnly = dir.resolve("hosts.zip");
        Backup.write(Store.MAPPER.writeValueAsBytes(s.exportParts(true, false, false)), null, hostsOnly);
        b = Backup.read(hostsOnly);
        assertTrue(b.hasHosts());
        assertFalse(b.hasVhosts() || b.hasSettings() || b.hasCa());
        other.restoreParts(b.data(), true, false, false);
        assertEquals(1, other.data.hosts.size());
        assertEquals(1, other.data.vhosts.size());

        // Add mode keeps what is there: names get a suffix, folders match by name, presets are appended once.
        Store keep = Store.open(dir.resolve("keep.json"), null);
        Store.Item mine = keep.create(Store.Kind.HOSTS, "en");
        keep.data.settings.uaPresets.add(new Store.Preset("a", "1"));
        keep.data.settings.uaPresetsText = "# mine\na | 1";
        keep.data.settings.fwdproxyPort = 1111;
        s.data.settings.uaPresets.add(new Store.Preset("a", "1"));
        s.data.settings.uaPresets.add(new Store.Preset("b", "2"));
        Store.Folder f = s.createFolder(Store.Kind.VHOSTS);
        s.moveItem(Store.Kind.VHOSTS, s.data.vhosts.get(0).id, f.id);
        keep.mergeParts(s.exportParts(true, true, true), true, true, true);
        keep.mergeParts(s.exportParts(true, true, true), false, false, true);
        assertEquals(2, keep.data.hosts.size());
        assertEquals(mine.name, keep.data.hosts.get(0).name);
        assertEquals(mine.name + " (2)", keep.data.hosts.get(1).name);
        assertEquals(1, keep.data.vhostFolders.size());
        assertEquals(keep.data.vhostFolders.get(0).id, keep.data.vhosts.get(0).folderId);
        assertEquals(2, keep.data.settings.uaPresets.size());
        assertEquals("# mine\na | 1\nb | 2\n", keep.data.settings.uaPresetsText);
        assertEquals(1111, keep.data.settings.fwdproxyPort);

        Files.writeString(dir.resolve("not.zip"), "plain text");
        assertThrows(java.io.IOException.class, () -> Backup.read(dir.resolve("not.zip")));
    }
}
