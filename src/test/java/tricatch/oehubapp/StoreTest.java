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
    void importsOehubExportAndSkipsEncrypted() throws Exception {
        Store s = open();
        Store.ImportResult r = s.importKind(json("{\"version\":1,\"hosts\":["
                + "{\"hostsProfile\":\"dev\",\"hostsContent\":\"1.1.1.1 a.com\",\"selected\":true,\"folderName\":\"team\"},"
                + "{\"hostsProfile\":\"secret\",\"hostsContent\":\"{}\",\"wrappedContentKey\":\"xyz\"}]}"), false, Store.Kind.HOSTS);
        assertEquals(new Store.ImportResult(1, 1), r);
        assertEquals(1, s.data.hostsFolders.size());
        assertEquals(s.data.hostsFolders.get(0).id, s.data.hosts.get(0).folderId);
        assertEquals(400, assertThrows(Store.StoreException.class, () -> s.importKind(json("{\"x\":1}"), false, Store.Kind.HOSTS)).status);
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
    void exportKindRoundTripsThroughImport() throws Exception {
        Store s = open();
        Store.Item a = s.create(Store.Kind.HOSTS, "en");
        Store.Folder f = s.createFolder(Store.Kind.HOSTS);
        s.moveItem(Store.Kind.HOSTS, a.id, f.id);
        s.toggleSelected(Store.Kind.HOSTS, a.id);
        s.create(Store.Kind.VHOSTS, "en");
        JsonNode hosts = s.exportKind(Store.Kind.HOSTS);
        assertEquals(1, hosts.get("hosts").size());
        assertNull(hosts.get("vhosts"));
        assertEquals(f.name, hosts.get("hosts").get(0).get("folderName").asText());

        Store other = Store.open(dir.resolve("other.json"), null);
        assertThrows(Store.StoreException.class, () -> other.importKind(hosts, false, Store.Kind.VHOSTS));
        Store.ImportResult r = other.importKind(hosts, false, Store.Kind.HOSTS);
        assertEquals(1, r.imported());
        Store.Item copy = other.data.hosts.get(0);
        assertEquals(a.content, copy.content);
        assertTrue(copy.selected);
        assertEquals(f.name, other.data.hostsFolders.get(0).name);
        assertTrue(other.data.vhosts.isEmpty());
    }

    @Test
    void fullBackupRoundTrip() throws Exception {
        Store s = open();
        s.create(Store.Kind.HOSTS, "en");
        s.create(Store.Kind.VHOSTS, "en");
        s.data.settings.fwdproxyPort = 4321;
        Path ca = Files.createDirectories(dir.resolve("ca"));
        Files.writeString(ca.resolve("ca.cer"), "CERT");
        Files.writeString(ca.resolve("ca.pfx"), "KEY");
        Path zip = dir.resolve("backup.zip");
        Backup.write(Store.MAPPER.writeValueAsBytes(s.data), ca, zip);

        Backup.Contents b = Backup.read(zip);
        assertEquals("CERT", new String(b.cert()));
        assertEquals("KEY", new String(b.key()));
        Store other = Store.open(dir.resolve("other.json"), null);
        other.replaceAll(b.data());
        assertEquals(1, other.data.hosts.size());
        assertEquals(1, other.data.vhosts.size());
        assertEquals(4321, other.data.settings.fwdproxyPort);

        Files.writeString(dir.resolve("not.zip"), "plain text");
        assertThrows(java.io.IOException.class, () -> Backup.read(dir.resolve("not.zip")));
    }}
