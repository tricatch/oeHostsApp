package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.Messages;
import tricatch.oehostsapp.hosts.HostsRules;
import tricatch.oehostsapp.proxy.ForwardProxy;
import tricatch.oehostsapp.proxy.ProxyRuntime;
import tricatch.oehostsapp.proxy.SslProxy;
import tricatch.oehostsapp.proxy.Vhosts;
import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.store.Store.Kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The MCP server's tools. All of them only read: the hosts profiles and vhosts as stored, what is in
 * force (through {@link HostsRules#merge} and {@link Vhosts#served}, the same merge the proxy and the
 * Chrome launcher use) and the state of the proxies. Nothing captured on the Network tab is offered,
 * and no secret (the CA's private key, the share key, the MCP token) is ever part of a result.
 */
final class McpTools {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** A tool's definition for {@code tools/list} and what it does for {@code tools/call}. */
    record Tool(String name, String title, String description, ObjectNode inputSchema, Function<JsonNode, JsonNode> run) {}

    /** A call that cannot be answered as asked (missing argument, unknown id): reported with {@code isError}. */
    static final class ToolError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ToolError(String message) {
            super(message);
        }
    }

    private final Store store;
    private final Map<String, String> msg = Messages.of("en");
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    McpTools(Store store) {
        this.store = store;
        add("list_hosts_profiles", "List oeHosts profiles",
                "Lists the oeHosts profiles (hosts files the user keeps in oeHostsApp) in the order shown in the app, "
                        + "which is also their merge order. Each entry has its id, name, folder, whether it is selected "
                        + "(in force for the proxy, the Chrome launcher and oeHosts-Agent), whether it is shared by URL, "
                        + "and its number of rules.",
                schema(), a -> list(Kind.HOSTS));
        add("get_hosts_profile", "Read an oeHosts profile",
                "Returns one oeHosts profile with its full text (\"IP host\" lines, # comments) and the lines the app "
                        + "reports as invalid (1-based line numbers).",
                schema("id", "The profile id from list_hosts_profiles."), a -> item(Kind.HOSTS, id(a)));
        add("get_active_hosts", "Hosts mappings in force",
                "Returns the host-to-IP mappings in force: the rules of the selected oeHosts profiles merged in order, "
                        + "placeholders resolved, the first profile that lists a host winning. Overridden rules are listed "
                        + "separately. With \"host\", only the rules for that host name are returned, and \"resolvedIp\" "
                        + "tells where the proxy sends it (null: normal DNS).",
                schemaOptional("host", "A host name to look up (case-insensitive)."), this::activeHosts);
        add("list_vhosts", "List oeProxy virtual hosts",
                "Lists the oeProxy virtual host documents (YAML routes of the embedded HTTPS reverse proxy) in the order "
                        + "shown in the app, which is also their merge order, with id, name, folder and whether each is selected.",
                schema(), a -> list(Kind.VHOSTS));
        add("get_vhost", "Read an oeProxy virtual host",
                "Returns one oeProxy virtual host document with its full YAML and the problems the app reports in it "
                        + "(1-based line numbers).",
                schema("id", "The vhost id from list_vhosts."), a -> item(Kind.VHOSTS, id(a)));
        add("get_served_vhosts", "Routes the reverse proxy serves",
                "Returns the YAML the embedded reverse proxy serves: the selected virtual hosts merged in order "
                        + "(the first entry per domain winning), placeholders resolved.",
                schema(), a -> servedVhosts());
        add("get_proxy_status", "Proxy status",
                "Returns the state of the embedded reverse proxy (HTTPS, port 443), the proxy service (HTTP proxy for "
                        + "Chrome and phones) and the SSL proxy (whether decryption is on and its domain list).",
                schema(), a -> status());
    }

    List<Tool> all() {
        return List.copyOf(tools.values());
    }

    /** The tool called {@code name}, or null. */
    Tool find(String name) {
        return name == null ? null : tools.get(name);
    }

    private void add(String name, String title, String description, ObjectNode schema, Function<JsonNode, JsonNode> run) {
        tools.put(name, new Tool(name, title, description, schema, run));
    }

    // ── Tools ─────────────────────────────────────────────────

    private JsonNode list(Kind kind) {
        ObjectNode out = JSON.objectNode();
        ArrayNode items = out.putArray(kind == Kind.HOSTS ? "profiles" : "vhosts");
        synchronized (store) {
            Map<String, String> folders = folderNames(kind);
            for (Store.Item i : store.list(kind)) {
                ObjectNode n = summary(kind, i, folders);
                if (kind == Kind.HOSTS) n.put("rules", HostsRules.parse(i.content).size());
                items.add(n);
            }
        }
        return out;
    }

    private JsonNode item(Kind kind, String id) {
        synchronized (store) {
            Store.Item i = store.find(kind, id);
            if (i == null) {
                throw new ToolError("No " + (kind == Kind.HOSTS ? "oeHosts profile" : "vhost") + " with id " + id
                        + "; call " + (kind == Kind.HOSTS ? "list_hosts_profiles" : "list_vhosts") + " for the ids.");
            }
            ObjectNode n = summary(kind, i, folderNames(kind));
            n.put("content", i.content);
            ArrayNode problems = n.putArray("problems");
            if (kind == Kind.HOSTS) {
                for (HostsRules.Problem p : HostsRules.problems(i.content)) problems.add(problem(p.line(), message(p.key(), null)));
            } else {
                for (Vhosts.Problem p : Vhosts.problems(i.content)) {
                    ObjectNode q = problem(p.line(), p.key() == null ? p.detail() : message(p.key(), p.detail()));
                    if (p.path() != null) q.put("path", p.path());
                    problems.add(q);
                }
            }
            return n;
        }
    }

    private JsonNode activeHosts(JsonNode args) {
        String host = optionalText(args, "host");
        List<String> names;
        List<HostsRules.Entry> entries;
        synchronized (store) {
            List<Store.Item> sel = store.selected(Kind.HOSTS);
            names = sel.stream().map(i -> i.name).toList();
            entries = HostsRules.merge(sel.stream().map(i -> i.content).toList());
        }
        ObjectNode out = JSON.objectNode();
        ArrayNode selected = out.putArray("selectedProfiles");
        names.forEach(selected::add);
        ArrayNode active = out.putArray("rules");
        ArrayNode overridden = out.putArray("overridden");
        String resolved = null;
        for (HostsRules.Entry e : entries) {
            if (host != null && !e.host().equalsIgnoreCase(host)) continue;
            ObjectNode r = JSON.objectNode();
            r.put("host", e.host());
            r.put("ip", e.ip());
            (e.active() ? active : overridden).add(r);
            if (e.active() && host != null) resolved = e.ip();
        }
        if (host != null) {
            out.put("host", host);
            out.put("resolvedIp", resolved);
        }
        return out;
    }

    private JsonNode servedVhosts() {
        List<Store.Item> sel;
        List<String> contents;
        synchronized (store) {
            sel = store.selected(Kind.VHOSTS);
            contents = sel.stream().map(i -> i.content).toList();
        }
        ObjectNode out = JSON.objectNode();
        ArrayNode selected = out.putArray("selectedVhosts");
        sel.forEach(i -> selected.add(i.name));
        out.put("yaml", Vhosts.served(contents));
        return out;
    }

    private JsonNode status() {
        ObjectNode out = JSON.objectNode();
        ObjectNode rp = out.putObject("reverseProxy");
        rp.put("state", ProxyRuntime.state().name().toLowerCase(Locale.ROOT));
        rp.put("port", ProxyRuntime.port());
        rp.put("bind", ProxyRuntime.bind());
        if (ProxyRuntime.state() == ProxyRuntime.State.FAILED) rp.put("error", ProxyRuntime.error());
        Path cert = ProxyRuntime.caCert();
        if (cert != null) rp.put("caCertificate", cert.toAbsolutePath().toString());

        ObjectNode fp = out.putObject("proxyService");
        fp.put("state", ForwardProxy.state().name().toLowerCase(Locale.ROOT));
        fp.put("port", ForwardProxy.port());
        if (ForwardProxy.error() != null) fp.put("error", ForwardProxy.error());

        ObjectNode ssl = out.putObject("sslProxy");
        boolean on;
        String domains;
        synchronized (store) {
            on = store.data.settings.sslProxy.enabled;
            domains = store.data.settings.sslProxy.domains;
        }
        ssl.put("enabled", on);
        ssl.put("decrypting", SslProxy.active());
        ssl.put("domains", domains);
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────

    private Map<String, String> folderNames(Kind kind) {
        Map<String, String> out = new HashMap<>();
        for (Store.Node n : store.tree(kind)) if (n.folder() != null) out.put(n.folder().id, n.folder().name);
        return out;
    }

    private static ObjectNode summary(Kind kind, Store.Item i, Map<String, String> folders) {
        ObjectNode n = JSON.objectNode();
        n.put("id", i.id);
        n.put("name", i.name);
        n.put("folder", i.folderId == null ? null : folders.get(i.folderId));
        n.put("selected", i.selected);
        if (kind == Kind.HOSTS) n.put("shared", i.shared);
        n.put("updatedAt", i.updatedAt);
        return n;
    }

    private static ObjectNode problem(int line, String message) {
        ObjectNode q = JSON.objectNode();
        q.put("line", line + 1);
        q.put("message", message);
        return q;
    }

    /** The English text of a problem's message key, its argument filled in. */
    private String message(String key, String arg) {
        String s = msg.getOrDefault(key, key);
        return arg == null ? s : s.replace("{0}", arg);
    }

    private static String id(JsonNode args) {
        String id = optionalText(args, "id");
        if (id == null) throw new ToolError("Missing argument: id");
        return id;
    }

    private static String optionalText(JsonNode args, String name) {
        JsonNode v = args == null ? null : args.get(name);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw new ToolError("Argument " + name + " must be a string");
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static ObjectNode schema() {
        ObjectNode s = JSON.objectNode();
        s.put("type", "object");
        s.putObject("properties");
        s.put("additionalProperties", false);
        return s;
    }

    private static ObjectNode schema(String required, String description) {
        ObjectNode s = schemaOptional(required, description);
        s.putArray("required").add(required);
        return s;
    }

    private static ObjectNode schemaOptional(String name, String description) {
        ObjectNode s = schema();
        ObjectNode p = ((ObjectNode) s.get("properties")).putObject(name);
        p.put("type", "string");
        p.put("description", description);
        return s;
    }

    /** Names of every tool (tests). */
    List<String> names() {
        return new ArrayList<>(tools.keySet());
    }
}
