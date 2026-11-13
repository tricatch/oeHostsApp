package tricatch.oehostsapp.store;

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
        return Store.open(dir.resolve("oehostsapp.json"));
    }

    @Test
    void idsAreBase32() {
        String idPattern = "[0-9a-hjkmnp-tv-z]{14}";
        assertTrue(Store.newId().matches(idPattern));
        assertNotEquals(Store.newId(), Store.newId());
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
        assertEquals("name_taken", e.code);
        assertEquals("dev", s.rename(Store.Kind.HOSTS, b.id, " dev ").name);
        long rev = s.revision();
        assertTrue(s.toggleSelected(Store.Kind.HOSTS, b.id).selected);
        assertEquals(rev + 1, s.revision());
        assertTrue(Files.exists(dir.resolve("oehostsapp.json.bak")));

        Store again = open();
        assertEquals(2, again.data.hosts.size());
        assertEquals(List.of(b.content), again.selectedContents(Store.Kind.HOSTS));
        assertTrue(again.hasSelected(Store.Kind.HOSTS));
        assertFalse(again.hasSelected(Store.Kind.VHOSTS));
    }

    @Test
    void treeOrderAndFolderDelete() throws Exception {
        Store s = open();
        Store.Item a = s.create(Store.Kind.VHOSTS, "en");
        Store.Item b = s.create(Store.Kind.VHOSTS, "en");
        Store.Folder f = s.createFolder(Store.Kind.VHOSTS);
        s.updateFolder(Store.Kind.VHOSTS, f.id, null, true);
        s.saveTreeOrder(Store.Kind.VHOSTS, List.of(new Store.Placement(f.id, true, null),
                new Store.Placement(b.id, false, f.id), new Store.Placement(a.id, false, null)), f.id);
        assertEquals(List.of(b.id, a.id), s.list(Store.Kind.VHOSTS).stream().map(i -> i.id).toList());
        assertFalse(s.data.vhostFolders.get(0).collapsed, "dropping into a closed folder opens it");
        List<Store.Node> tree = s.tree(Store.Kind.VHOSTS);
        assertEquals(2, tree.size());
        assertEquals(f.id, tree.get(0).folder().id);
        assertEquals(List.of(b.id), tree.get(0).children().stream().map(i -> i.id).toList());
        assertEquals(a.id, tree.get(1).item().id);
        assertThrows(Store.StoreException.class,
                () -> s.saveTreeOrder(Store.Kind.VHOSTS, List.of(new Store.Placement("nope", false, null)), null));
        assertEquals("name_taken", assertThrows(Store.StoreException.class, () -> {
            Store.Folder g = s.createFolder(Store.Kind.VHOSTS);
            s.updateFolder(Store.Kind.VHOSTS, g.id, "NEW FOLDER", null);
        }).code);
        s.deleteFolder(Store.Kind.VHOSTS, f.id);
        assertTrue(s.data.vhosts.stream().allMatch(i -> i.folderId == null));
    }

    @Test
    void fillsWhatAnOlderFileLeavesOut() throws Exception {
        Files.writeString(dir.resolve("oehostsapp.json"), "{\"version\":1,\"settings\":{\"lang\":\"ko\",\"fwdproxy\":null}}");
        Store s = open();
        assertEquals("ko", s.data.settings.lang);
        assertEquals(Settings.DEFAULT_FWDPROXY_PORT, s.data.settings.fwdproxy.port);
        assertEquals("0.0.0.0", s.data.settings.reverseProxy.bind);
        assertTrue(s.data.launcher.openUrlEnabled);
        assertEquals(LauncherOptions.DEFAULT_EXTRA_ARGS, s.data.launcher.extraArgs);
    }

    @Test
    void opensAFileOfTheOlderFlatFormat() throws Exception {
        // Old keys are ignored, never a reason to refuse the file: profiles stay, those settings start over.
        Files.writeString(dir.resolve("oehostsapp.json"), "{\"version\":1,"
                + "\"hosts\":[{\"id\":\"aaaaaaaaaaaaa0\",\"name\":\"dev\",\"content\":\"1.1.1.1 a.test\",\"selected\":true}],"
                + "\"hostsConf\":{\"incognito\":\"true\"},\"proxyConf\":{\"vhost\":\"x\"},"
                + "\"settings\":{\"lang\":\"ko\",\"fwdproxyPort\":1234,\"fwdproxyLan\":true,\"windowX\":5,"
                + "\"uaPresets\":[{\"name\":\"m\",\"value\":\"Mobile\"}],\"urlPresets\":[]}}");
        Store s = open();
        assertEquals(List.of("1.1.1.1 a.test"), s.selectedContents(Store.Kind.HOSTS));
        assertEquals("ko", s.data.settings.lang);
        assertEquals(Settings.DEFAULT_FWDPROXY_PORT, s.data.settings.fwdproxy.port);
        assertTrue(s.data.settings.userAgentPresets.items.isEmpty());
    }

    @Test
    void refusesToOverwriteAnUnreadableDataFile() throws Exception {
        Files.writeString(dir.resolve("oehostsapp.json"), "{broken");
        assertThrows(Exception.class, this::open);
        assertEquals("{broken", Files.readString(dir.resolve("oehostsapp.json")));
    }

    @Test
    void backupRestoresOnlyChosenParts() throws Exception {
        Store s = open();
        s.create(Store.Kind.HOSTS, "en");
        s.create(Store.Kind.VHOSTS, "en");
        s.data.settings.fwdproxy.port = 4321;
        s.data.launcher.incognito = true;
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
        Store other = Store.open(dir.resolve("other.json"));
        other.create(Store.Kind.HOSTS, "en");
        other.create(Store.Kind.HOSTS, "en");
        other.restoreParts(b.data(), false, true, true);
        assertEquals(2, other.data.hosts.size(), "hosts not chosen: kept");
        assertFalse(other.data.launcher.incognito, "launcher options go with oeHosts");
        assertEquals(1, other.data.vhosts.size());
        assertEquals(4321, other.data.settings.fwdproxy.port);

        // oeHosts only, no CA.
        Path hostsOnly = dir.resolve("hosts.zip");
        Backup.write(Store.MAPPER.writeValueAsBytes(s.exportParts(true, false, false)), null, hostsOnly);
        b = Backup.read(hostsOnly);
        assertTrue(b.hasHosts());
        assertFalse(b.hasVhosts() || b.hasSettings() || b.hasCa());
        other.restoreParts(b.data(), true, false, false);
        assertEquals(1, other.data.hosts.size());
        assertTrue(other.data.launcher.incognito);
        assertEquals(1, other.data.vhosts.size());

        // Add mode keeps what is there: names get a suffix, folders match by name, presets are appended once.
        Store keep = Store.open(dir.resolve("keep.json"));
        Store.Item mine = keep.create(Store.Kind.HOSTS, "en");
        keep.data.settings.userAgentPresets.items.add(new Settings.Preset("a", "1"));
        keep.data.settings.userAgentPresets.text = "# mine\na | 1";
        keep.data.settings.fwdproxy.port = 1111;
        s.data.settings.userAgentPresets.items.add(new Settings.Preset("a", "1"));
        s.data.settings.userAgentPresets.items.add(new Settings.Preset("b", "2"));
        Store.Folder f = s.createFolder(Store.Kind.VHOSTS);
        s.moveItem(Store.Kind.VHOSTS, s.data.vhosts.get(0).id, f.id);
        keep.mergeParts(s.exportParts(true, true, true), true, true, true);
        keep.mergeParts(s.exportParts(true, true, true), false, false, true);
        assertEquals(2, keep.data.hosts.size());
        assertEquals(mine.name, keep.data.hosts.get(0).name);
        assertEquals(mine.name + " (2)", keep.data.hosts.get(1).name);
        assertEquals(1, keep.data.vhostFolders.size());
        assertEquals(keep.data.vhostFolders.get(0).id, keep.data.vhosts.get(0).folderId);
        assertEquals(2, keep.data.settings.userAgentPresets.items.size());
        assertEquals("# mine\na | 1\nb | 2\n", keep.data.settings.userAgentPresets.text);
        assertEquals(1111, keep.data.settings.fwdproxy.port);
        assertFalse(keep.data.launcher.incognito, "add mode keeps the launcher options");

        Files.writeString(dir.resolve("not.zip"), "plain text");
        assertThrows(java.io.IOException.class, () -> Backup.read(dir.resolve("not.zip")));
    }
}
