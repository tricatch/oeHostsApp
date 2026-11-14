package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.hosts.HostsRules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The vhost YAML of the oeProxy screen: the schema check of proxy.pebble ({@code validateSchema})
 * and the merge of the selected vhosts ({@code buildMergedVirtualYaml}) that the embedded proxy
 * serves. An invalid vhost is never stored, so the merge only skips what does not parse.
 */
public final class Vhosts {

    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                    .build())
            .build();

    private Vhosts() {}

    /**
     * Why a vhost is invalid, at {@code line} (0-based): a YAML syntax error ({@code key} null,
     * {@code detail} the parser's message) or a rule ({@code key}, a message key; {@code detail}
     * its argument, if any) for the entry at {@code path} such as {@code virtual[0].location[1]}
     * (null for the document itself).
     */
    public record Problem(int line, String path, String key, String detail) {}

    /**
     * A host name: labels of letters, digits and inner hyphens, the last one not all digits (so
     * neither an IP address nor something like {@code 127.45245.2452}); no scheme, port or path.
     */
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)(?!(?:.*\\.)?[0-9]+$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");
    /** An HTTP header name (RFC 9110 token). */
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Set<String> ROOT_KEYS = Set.of("virtual");
    private static final Set<String> VHOST_KEYS = Set.of("domain", "location");
    private static final Set<String> LOCATION_KEYS = Set.of("host", "path", "header");

    /**
     * The problems of {@code content}, in document order; empty when it is valid (or blank).
     * Beyond proxy.pebble's {@code validateSchema} it checks what oe-proxy would otherwise ignore
     * or reject when it loads the routes ({@code VirtualHostUtil}, {@code PassRequestExecutor}):
     * unknown or repeated keys, a domain that is not a bare host name or appears twice, a host that
     * is not an http/https origin, a path not starting with {@code /}, a malformed header rule.
     */
    public static List<Problem> problems(String content) {
        List<Problem> out = new ArrayList<>();
        if (content.isBlank()) return out;
        Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(content));
        } catch (MarkedYAMLException e) {
            Mark m = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
            out.add(new Problem(m == null ? 0 : m.getLine(), null, null, e.getProblem() != null ? e.getProblem() : e.getMessage()));
            return out;
        } catch (YAMLException e) {
            out.add(new Problem(0, null, null, e.getMessage()));
            return out;
        }
        // Without a "virtual" list that is the only problem worth naming, not the other root keys.
        List<Problem> rootKeys = new ArrayList<>();
        Map<String, Node> top = root instanceof MappingNode m ? keys(m, null, ROOT_KEYS, rootKeys) : Map.of();
        if (!(top.get("virtual") instanceof SequenceNode virtual)) {
            out.add(new Problem(line(top.getOrDefault("virtual", root)), null, "proxy.validate.virtual.required", null));
            return out;
        }
        out.addAll(rootKeys);
        Set<String> domains = new HashSet<>();
        List<Node> vhosts = virtual.getValue();
        for (int i = 0; i < vhosts.size(); i++) {
            String p = "virtual[" + i + "]";
            Node v = vhosts.get(i);
            Map<String, Node> vk = v instanceof MappingNode m ? keys(m, p, VHOST_KEYS, out) : Map.of();
            Node domain = vk.get("domain");
            String d = text(domain);
            if (d == null) {
                out.add(new Problem(line(domain != null ? domain : v), p, "proxy.validate.domain.required", null));
            } else if (!DOMAIN.matcher(d).matches()) {
                out.add(new Problem(line(domain), p, "app.proxy.invalid.domain", null));
            } else if (!domains.add(d.toLowerCase(Locale.ROOT))) {
                out.add(new Problem(line(domain), p, "app.proxy.duplicate.domain", d));
            }
            Node loc = vk.get("location");
            if (!(loc instanceof SequenceNode locs) || locs.getValue().isEmpty()) {
                out.add(new Problem(line(loc != null ? loc : v), p, "proxy.validate.location.required", null));
                continue;
            }
            for (int j = 0; j < locs.getValue().size(); j++) {
                checkLocation(locs.getValue().get(j), p + ".location[" + j + "]", out);
            }
        }
        return out;
    }

    private static void checkLocation(Node l, String lp, List<Problem> out) {
        Map<String, Node> lk = l instanceof MappingNode m ? keys(m, lp, LOCATION_KEYS, out) : Map.of();
        Node host = lk.get("host");
        String h = text(host);
        if (h == null) out.add(new Problem(line(host != null ? host : l), lp, "proxy.validate.host.required", null));
        else if (!isOrigin(HostsRules.resolvePlaceholders(h))) out.add(new Problem(line(host), lp, "app.proxy.invalid.host", null));

        Node path = lk.get("path");
        if (!(path instanceof SequenceNode paths) || paths.getValue().isEmpty()) {
            out.add(new Problem(line(path != null ? path : l), lp, "proxy.validate.path.required", null));
        } else {
            for (Node e : paths.getValue()) {
                String s = text(e);
                if (s == null || !s.startsWith("/")) out.add(new Problem(line(e), lp, "app.proxy.invalid.path", null));
            }
        }

        Node header = lk.get("header");
        if (header == null) return;
        if (!(header instanceof SequenceNode headers)) {
            out.add(new Problem(line(header), lp, "proxy.validate.header.array", null));
            return;
        }
        for (Node e : headers.getValue()) {
            String s = text(e);
            // "--Name" removes a header, "Name: value" sets one (how PassRequestExecutor applies them).
            boolean ok = s != null && (s.startsWith("--")
                    ? HEADER_NAME.matcher(s.substring(2).trim()).matches()
                    : s.indexOf(':') > 0 && HEADER_NAME.matcher(s.substring(0, s.indexOf(':')).trim()).matches());
            if (!ok) out.add(new Problem(line(e), lp, "app.proxy.invalid.header", null));
        }
    }

    /**
     * The entries of {@code m} by key, reporting keys outside {@code allowed} and repeated keys
     * (the merge would keep only the last); the first entry of a repeated key is returned.
     */
    private static Map<String, Node> keys(MappingNode m, String path, Set<String> allowed, List<Problem> out) {
        Map<String, Node> map = new HashMap<>();
        for (NodeTuple t : m.getValue()) {
            String k = t.getKeyNode() instanceof ScalarNode s ? s.getValue() : null;
            if (k == null || !allowed.contains(k)) {
                out.add(new Problem(line(t.getKeyNode()), path, "app.proxy.invalid.key", k == null ? "?" : k));
            } else if (map.putIfAbsent(k, t.getValueNode()) != null) {
                out.add(new Problem(line(t.getKeyNode()), path, "app.proxy.duplicate.key", k));
            }
        }
        return map;
    }

    /** The node's text when it is a non-empty string scalar, else null. */
    private static String text(Node n) {
        return n instanceof ScalarNode s && Tag.STR.equals(s.getTag()) && !s.getValue().isEmpty() ? s.getValue() : null;
    }

    private static int line(Node n) {
        return n.getStartMark() == null ? 0 : n.getStartMark().getLine();
    }

    /** {@code http(s)://host[:port]}, optionally with a bare {@code /}: oe-proxy uses only the scheme, host and port. */
    static boolean isOrigin(String s) {
        try {
            URI u = new URI(s);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return false;
            if (u.getHost() == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) return false;
            String path = u.getRawPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) return false;
            u.toURL();
            return true;
        } catch (URISyntaxException | java.net.MalformedURLException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The selected vhosts' entries in one YAML document, the first entry per domain winning;
     * placeholders are left as they are. Empty when there is nothing to serve.
     */
    public static String merge(List<String> contents) {
        ArrayNode all = JsonNodeFactory.instance.arrayNode();
        Set<String> seen = new HashSet<>();
        for (String c : contents) {
            if (c.isBlank()) continue;
            try {
                JsonNode parsed = YAML.readTree(c);
                if (parsed == null || !parsed.path("virtual").isArray()) continue;
                for (JsonNode e : parsed.get("virtual")) {
                    String d = e.path("domain").isTextual() ? e.path("domain").asText() : null;
                    if (d != null && !d.isEmpty() && !seen.add(d)) continue;
                    all.add(e);
                }
            } catch (JsonProcessingException ignored) {
                // skip what does not parse
            }
        }
        if (all.isEmpty()) return "";
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("virtual", all);
        try {
            return YAML.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /** {@link #merge} with oeHub's placeholders filled in: what the proxy serves. */
    public static String served(List<String> contents) {
        return HostsRules.resolvePlaceholders(merge(contents));
    }
}
