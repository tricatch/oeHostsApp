package tricatch.oehubapp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort import of the old oeHosts (egui) data file {@code oehosts.json}: {@code profiles}
 * [{id, name, content, selected}], {@code items} (the sidebar tree: folders with nested
 * {@code items}, or profile references), {@code settings} and {@code presets} {userAgents, urls}.
 */
final class LegacyMigration {

    private LegacyMigration() {}

    private static final Map<String, String> SETTING_NAMES = Map.ofEntries(
            Map.entry("incognito", "incognito"),
            Map.entry("devTools", "dev_tools"),
            Map.entry("extraArgs", "extra_chrome_args"),
            Map.entry("extraArgsEnabled", "extra_chrome_args_enabled"),
            Map.entry("hostResolverRulesEnabled", "host_resolver_rules_enabled"),
            Map.entry("openUrl", "open_url"),
            Map.entry("openUrlEnabled", "open_url_enabled"),
            Map.entry("proxyServerEnabled", "proxy_server_enabled"),
            Map.entry("userAgent", "user_agent"),
            Map.entry("userAgentEnabled", "user_agent_enabled"),
            Map.entry("userDataDirEnabled", "user_data_dir_enabled"));

    static Store.Data migrate(JsonNode v) {
        Store.Data data = new Store.Data();
        String now = Store.now();
        long[] order = {0};
        List<JsonNode> profiles = new ArrayList<>();
        v.path("profiles").forEach(profiles::add);
        Set<String> placed = new HashSet<>();

        // The tree is one level deep: root entries are profile references or folders, a folder's
        // items are profile references.
        for (JsonNode e : v.path("items")) {
            String folderId = null;
            List<JsonNode> refs = new ArrayList<>();
            if ("folder".equals(e.path("type").asText())) {
                Store.Folder f = new Store.Folder();
                f.id = Store.newId();
                f.name = e.path("name").asText("folder");
                f.collapsed = e.path("collapsed").asBoolean(false);
                f.sortOrder = order[0]++;
                f.createAt = now;
                f.updatedAt = now;
                data.hostsFolders.add(f);
                folderId = f.id;
                e.path("items").forEach(refs::add);
            } else {
                refs.add(e);
            }
            for (JsonNode r : refs) {
                JsonNode id = r.get("id");
                if (id == null) continue;
                for (JsonNode p : profiles) {
                    if (id.equals(p.get("id"))) {
                        placed.add(id.toString());
                        data.hosts.add(item(p, folderId, order[0]++, now));
                        break;
                    }
                }
            }
        }
        for (JsonNode p : profiles) {
            JsonNode id = p.get("id");
            if (id == null || !placed.contains(id.toString())) data.hosts.add(item(p, null, order[0]++, now));
        }

        JsonNode s = v.path("settings");
        SETTING_NAMES.forEach((old, name) -> {
            JsonNode val = s.get(old);
            if (val != null && (val.isBoolean() || val.isTextual() || val.isNumber())) data.hostsConf.put(name, val.asText());
        });
        String proxySvr = s.path("proxySvr").asText("");
        if (!proxySvr.isEmpty()) data.settings.proxySvr = proxySvr;
        data.settings.uaPresets = presets(v.path("presets").path("userAgents"));
        data.settings.urlPresets = presets(v.path("presets").path("urls"));
        return data;
    }

    private static Store.Item item(JsonNode p, String folderId, long order, String now) {
        Store.Item i = new Store.Item();
        i.id = Store.newId();
        i.name = p.path("name").asText("hosts");
        i.content = p.path("content").asText("").replace("\r\n", "\n");
        i.selected = p.path("selected").asBoolean(false);
        i.folderId = folderId;
        i.sortOrder = order;
        i.createAt = now;
        i.updatedAt = now;
        return i;
    }

    private static List<Store.Preset> presets(JsonNode arr) {
        List<Store.Preset> out = new ArrayList<>();
        for (JsonNode e : arr) {
            if (e.path("name").isTextual() && e.path("value").isTextual()) {
                out.add(new Store.Preset(e.get("name").asText(), e.get("value").asText()));
            }
        }
        return out;
    }
}
