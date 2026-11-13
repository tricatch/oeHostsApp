package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.hosts.HostsRules;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     * Why a vhost is invalid: a YAML syntax error ({@code detail}, the parser's message, at
     * {@code line}, 0-based) or a schema rule ({@code key}, a message key, for the entry at
     * {@code path} such as {@code virtual[0].location[1]}; null for the document itself).
     */
    public record Problem(int line, String path, String key, String detail) {}

    /** The problems of {@code content}, in document order; empty when it is valid (or blank). */
    public static List<Problem> problems(String content) {
        List<Problem> out = new ArrayList<>();
        if (content.isBlank()) return out;
        JsonNode parsed;
        try {
            parsed = YAML.readTree(content);
        } catch (JsonProcessingException e) {
            JsonLocation loc = e.getLocation();
            out.add(new Problem(loc == null ? 0 : Math.max(0, loc.getLineNr() - 1), null, null, e.getOriginalMessage()));
            return out;
        }
        if (parsed == null || !parsed.isObject() || !parsed.path("virtual").isArray()) {
            out.add(new Problem(0, null, "proxy.validate.virtual.required", null));
            return out;
        }
        int i = 0;
        for (JsonNode v : parsed.get("virtual")) {
            String p = "virtual[" + i++ + "]";
            if (!v.path("domain").isTextual() || v.path("domain").asText().isEmpty()) {
                out.add(new Problem(0, p, "proxy.validate.domain.required", null));
            }
            JsonNode loc = v.path("location");
            if (!loc.isArray() || loc.isEmpty()) {
                out.add(new Problem(0, p, "proxy.validate.location.required", null));
                continue;
            }
            int j = 0;
            for (JsonNode l : loc) {
                String lp = p + ".location[" + j++ + "]";
                if (!l.path("host").isTextual() || l.path("host").asText().isEmpty()) {
                    out.add(new Problem(0, lp, "proxy.validate.host.required", null));
                }
                if (!l.path("path").isArray() || l.path("path").isEmpty()) {
                    out.add(new Problem(0, lp, "proxy.validate.path.required", null));
                }
                if (l.has("header") && !l.get("header").isArray()) {
                    out.add(new Problem(0, lp, "proxy.validate.header.array", null));
                }
            }
        }
        return out;
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
