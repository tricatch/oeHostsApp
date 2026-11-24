package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.AppData;
import tricatch.oehostsapp.store.Settings;
import tricatch.oehostsapp.store.Store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The command line for AI coding tools (and people): {@code java -jar oehostsapp.jar [--data DIR] COMMAND [OPTIONS]}.
 * A thin client of the running app's MCP server ({@link McpServer}): every command is one of its tools
 * ({@code list_traffic} becomes {@code list-traffic}, an argument {@code contentOnly} the option
 * {@code --content-only}), so tools, their checks and the redaction of traffic stay in one place, and
 * the CLI answers only while the app runs with the MCP server on. It reads the port and the access
 * token from the data file of this PC's user, never prints the token, and starts no window.
 *
 * <p>The result of a command is its tool's {@code structuredContent} as JSON on stdout; everything
 * else goes to stderr. Exit codes: {@link #OK}, {@link #FAILED}, {@link #USAGE}, {@link #UNAVAILABLE},
 * {@link #REFUSED}.
 */
public final class McpCli {

    public static final int OK = 0;
    /** The tool refused the call (unknown id, traffic switched off...); its message is on stderr. */
    public static final int FAILED = 1;
    public static final int USAGE = 2;
    /** No data file, the MCP server switched off, or the app not answering. */
    public static final int UNAVAILABLE = 3;
    /** The server refused the token or the request (another data folder than the running app's?). */
    public static final int REFUSED = 4;

    static final String USAGE_LINE = "usage: java -jar oehostsapp.jar [--data DIR] COMMAND [OPTIONS]";

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** A command that cannot go on; {@link #code} is the exit code, the message goes to stderr. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;

        Failure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final Path dataDir;
    private final PrintStream out;
    private final PrintStream err;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private int rpcId;

    private McpCli(Path dataDir, PrintStream out, PrintStream err) {
        this.dataDir = dataDir;
        this.out = out;
        this.err = err;
    }

    /** Runs one command line ({@code args} starts with the command) and returns the exit code. */
    public static int run(Path dataDir, List<String> args, PrintStream out, PrintStream err) {
        McpCli cli = new McpCli(dataDir, out, err);
        try {
            return cli.dispatch(args);
        } catch (Failure f) {
            err.println(f.getMessage());
            if (f.code == USAGE) err.println(USAGE_LINE + "  (see: java -jar oehostsapp.jar help)");
            return f.code;
        }
    }

    private int dispatch(List<String> args) throws Failure {
        String command = args.get(0);
        List<String> rest = args.subList(1, args.size());
        switch (command) {
            case "help", "--help", "-h" -> {
                if (rest.isEmpty()) return help();
                if (rest.size() > 1) throw new Failure(USAGE, "help takes at most one command");
                out.print(commandHelp(tool(rest.get(0))));
                return OK;
            }
            case "tools" -> {
                if (!rest.isEmpty()) throw new Failure(USAGE, "tools takes no options");
                ArrayNode list = JSON.arrayNode();
                for (JsonNode t : tools()) list.add(describe(t));
                print(list);
                return OK;
            }
            default -> {
                if (command.startsWith("-")) throw new Failure(USAGE, "Unknown option " + command);
                JsonNode tool = tool(command);
                if (rest.contains("--help") || rest.contains("-h")) {
                    out.print(commandHelp(tool));
                    return OK;
                }
                return call(tool, arguments(tool, rest));
            }
        }
    }

    // ── Commands ──────────────────────────────────────────────

    private int help() {
        StringBuilder b = new StringBuilder();
        b.append(USAGE_LINE).append("\n\n");
        b.append("Reads oeHostsApp through the MCP server of the running app (Settings > General > MCP);\n");
        b.append("read-only. Prints the result as JSON on stdout (UTF-8), messages on stderr.\n");
        b.append("Tool names in the descriptions are the commands below with '-' for '_'.\n\n");
        b.append(McpProtocol.INSTRUCTIONS).append("\n\n");
        b.append("Commands:\n");
        int code = OK;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"help [COMMAND]", "This text, or the options of one command."});
        rows.add(new String[] {"tools", "The commands and their options as JSON."});
        String note = null;
        try {
            for (JsonNode t : tools()) rows.add(new String[] {command(t), t.path("title").asText("")});
        } catch (Failure f) {
            note = f.getMessage();
            code = f.code;
        }
        int w = rows.stream().mapToInt(r -> r[0].length()).max().orElse(0) + 2;
        for (String[] r : rows) b.append("  ").append(pad(r[0], w)).append(r[1]).append('\n');
        if (note != null) b.append("  (the other commands come from the running app: ").append(note).append(")\n");
        b.append("\nOptions of a command: COMMAND --help. --data DIR: the data folder of the app to read\n");
        b.append("(default: ").append(AppData.defaultDir()).append(").\n\n");
        b.append("Exit codes: 0 done, 1 the command failed (message on stderr), 2 usage error,\n");
        b.append("3 oeHostsApp or its MCP server is not available, 4 the access token was refused.\n");
        out.print(b);
        return code;
    }

    private int call(JsonNode tool, ObjectNode arguments) throws Failure {
        ObjectNode params = JSON.objectNode();
        params.put("name", tool.path("name").asText());
        params.set("arguments", arguments);
        JsonNode result = rpc("tools/call", params);
        if (result.path("isError").asBoolean(false)) {
            StringBuilder b = new StringBuilder();
            for (JsonNode c : result.path("content")) {
                if (!b.isEmpty()) b.append('\n');
                b.append(c.path("text").asText(""));
            }
            err.println(b.isEmpty() ? "The command failed" : b);
            return FAILED;
        }
        JsonNode structured = result.get("structuredContent");
        if (structured != null) {
            print(structured);
        } else {
            for (JsonNode c : result.path("content")) out.println(c.path("text").asText(""));
        }
        return OK;
    }

    // ── Commands from the tools ───────────────────────────────

    private List<JsonNode> tools() throws Failure {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode t : rpc("tools/list", JSON.objectNode()).path("tools")) list.add(t);
        return list;
    }

    /** The tool {@code command} names, as {@code list-traffic} or as the tool's own {@code list_traffic}. */
    private JsonNode tool(String command) throws Failure {
        List<JsonNode> all = tools();
        for (JsonNode t : all) {
            if (command.equals(command(t)) || command.equals(t.path("name").asText())) return t;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode t : all) names.add(command(t));
        throw new Failure(USAGE, "Unknown command: " + command + ". Commands: help, tools, " + String.join(", ", names));
    }

    static String command(JsonNode tool) {
        return tool.path("name").asText().replace('_', '-');
    }

    /** {@code contentOnly} → {@code content-only}. */
    static String option(String property) {
        StringBuilder b = new StringBuilder();
        for (char c : property.toCharArray()) {
            if (Character.isUpperCase(c)) b.append('-').append(Character.toLowerCase(c));
            else b.append(c == '_' ? '-' : c);
        }
        return b.toString();
    }

    /** The tool's arguments from {@code --name value}, {@code --name=value} and {@code --flag} options. */
    static ObjectNode arguments(JsonNode tool, List<String> args) throws Failure {
        JsonNode schema = tool.path("inputSchema");
        Map<String, String> properties = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : schema.path("properties").properties()) properties.put(option(e.getKey()), e.getKey());
        List<String> required = new ArrayList<>();
        for (JsonNode r : schema.path("required")) required.add(r.asText());
        String command = command(tool);
        ObjectNode out = JSON.objectNode();
        Set<String> seen = new HashSet<>();
        String positional = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!a.startsWith("--")) {
                // One bare value fills the only required argument: get-traffic 12.
                if (required.size() != 1 || positional != null) {
                    throw new Failure(USAGE, "Unexpected argument for " + command + ": " + a);
                }
                positional = a;
                continue;
            }
            String name = a.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            String property = properties.get(name);
            if (property == null) {
                throw new Failure(USAGE, "Unknown option --" + name + " for " + command
                        + (properties.isEmpty() ? " (it takes none)" : "; options: --" + String.join(", --", properties.keySet())));
            }
            if (!seen.add(property)) throw new Failure(USAGE, "Option --" + name + " given twice");
            String type = schema.path("properties").path(property).path("type").asText("string");
            if ("boolean".equals(type)) {
                if (value == null || value.equalsIgnoreCase("true")) out.put(property, true);
                else if (value.equalsIgnoreCase("false")) out.put(property, false);
                else throw new Failure(USAGE, "Option --" + name + " is true or false, not " + value);
                continue;
            }
            if (value == null) {
                if (i + 1 >= args.size()) throw new Failure(USAGE, "Option --" + name + " needs a value");
                value = args.get(++i);
            }
            if ("integer".equals(type)) {
                try {
                    out.put(property, Integer.parseInt(value.strip()));
                } catch (NumberFormatException e) {
                    throw new Failure(USAGE, "Option --" + name + " needs a whole number, not " + value);
                }
            } else {
                out.put(property, value);
            }
        }
        if (positional != null) {
            String r = required.get(0);
            if (seen.contains(r)) throw new Failure(USAGE, "Option --" + option(r) + " given twice");
            out.put(r, positional);
        }
        for (String r : required) {
            if (!out.has(r)) throw new Failure(USAGE, "Missing option --" + option(r) + " for " + command);
        }
        return out;
    }

    private static ObjectNode describe(JsonNode tool) {
        ObjectNode n = JSON.objectNode();
        n.put("command", command(tool));
        n.put("tool", tool.path("name").asText());
        n.put("title", tool.path("title").asText(""));
        n.put("description", tool.path("description").asText(""));
        ArrayNode options = n.putArray("options");
        Set<String> required = new HashSet<>();
        for (JsonNode r : tool.path("inputSchema").path("required")) required.add(r.asText());
        for (Map.Entry<String, JsonNode> e : tool.path("inputSchema").path("properties").properties()) {
            ObjectNode o = options.addObject();
            o.put("option", "--" + option(e.getKey()));
            o.put("type", e.getValue().path("type").asText("string"));
            o.put("required", required.contains(e.getKey()));
            o.put("description", e.getValue().path("description").asText(""));
        }
        return n;
    }

    static String commandHelp(JsonNode tool) {
        StringBuilder b = new StringBuilder();
        String command = command(tool);
        JsonNode props = tool.path("inputSchema").path("properties");
        List<String> required = new ArrayList<>();
        for (JsonNode r : tool.path("inputSchema").path("required")) required.add(r.asText());
        b.append("usage: java -jar oehostsapp.jar ").append(command);
        for (String r : required) b.append(" --").append(option(r)).append(' ').append(r.toUpperCase(Locale.ROOT));
        if (props.size() > required.size()) b.append(" [OPTIONS]");
        b.append("\n\n").append(tool.path("title").asText("")).append("\n\n");
        b.append(tool.path("description").asText("")).append('\n');
        if (props.size() > 0) {
            b.append("\nOptions:\n");
            List<String[]> rows = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : props.properties()) {
                String type = e.getValue().path("type").asText("string");
                String left = "--" + option(e.getKey())
                        + ("boolean".equals(type) ? "" : " " + ("integer".equals(type) ? "N" : e.getKey().toUpperCase(Locale.ROOT)));
                String right = e.getValue().path("description").asText("")
                        + (required.contains(e.getKey()) ? " (required)" : "");
                rows.add(new String[] {left, right});
            }
            int w = rows.stream().mapToInt(r -> r[0].length()).max().orElse(0) + 2;
            for (String[] r : rows) b.append("  ").append(pad(r[0], w)).append(r[1]).append('\n');
        }
        if (required.size() == 1) {
            b.append("\nThe ").append(required.get(0)).append(" can also be given alone: ").append(command)
                    .append(' ').append(required.get(0).toUpperCase(Locale.ROOT)).append('\n');
        }
        return b.toString();
    }

    // ── The MCP server ────────────────────────────────────────

    private record Target(int port, String token) {}

    /** Port and token of the MCP server, from the data file; never written to. */
    private Target target() throws Failure {
        Path file = dataDir.resolve(AppData.FILE_NAME);
        if (!Files.isRegularFile(file)) {
            throw new Failure(UNAVAILABLE, "No oeHostsApp data in " + dataDir.toAbsolutePath()
                    + ": start oeHostsApp once, or give its folder with --data DIR");
        }
        JsonNode root;
        try {
            root = Store.MAPPER.readTree(file.toFile());
        } catch (IOException e) {
            throw new Failure(UNAVAILABLE, "Cannot read " + file.toAbsolutePath() + ": " + e.getMessage());
        }
        JsonNode mcp = root == null ? JSON.missingNode() : root.path("settings").path("mcp");
        if (!mcp.path("enabled").asBoolean(true)) {
            throw new Failure(UNAVAILABLE, "The MCP server of oeHostsApp is switched off: turn it on in Settings > General > MCP");
        }
        String token = root == null ? null : root.path("mcpToken").asText(null);
        if (token == null || token.isBlank()) {
            throw new Failure(UNAVAILABLE, "oeHostsApp has no MCP access token yet: start oeHostsApp with the MCP server on");
        }
        return new Target(mcp.path("port").asInt(Settings.DEFAULT_MCP_PORT), token);
    }

    /** The {@code result} of one JSON-RPC request. */
    private JsonNode rpc(String method, ObjectNode params) throws Failure {
        Target t = target();
        ObjectNode msg = JSON.objectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", ++rpcId);
        msg.put("method", method);
        msg.set("params", params);
        String url = McpServer.url(t.port());
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("MCP-Protocol-Version", McpProtocol.VERSIONS.get(0))
                    .header("Authorization", "Bearer " + t.token())
                    .POST(HttpRequest.BodyPublishers.ofString(PRETTY.writeValueAsString(msg), StandardCharsets.UTF_8))
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new Failure(UNAVAILABLE, "oeHostsApp is not answering at " + url
                    + ": start oeHostsApp (Settings > General > MCP shows the server state)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(UNAVAILABLE, "Interrupted");
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new Failure(REFUSED, "The MCP server at " + url + " refused the access token from "
                    + dataDir.toAbsolutePath() + " (HTTP " + res.statusCode()
                    + "): is the running oeHostsApp using another data folder (--data DIR)?");
        }
        if (res.statusCode() != 200) {
            throw new Failure(UNAVAILABLE, "The MCP server at " + url + " answered HTTP " + res.statusCode() + ": "
                    + res.body().strip());
        }
        JsonNode reply;
        try {
            reply = PRETTY.readTree(res.body());
        } catch (IOException e) {
            throw new Failure(UNAVAILABLE, "The MCP server at " + url + " sent a reply that is not JSON");
        }
        if (reply.has("error")) {
            throw new Failure(FAILED, reply.path("error").path("message").asText("The MCP server reported an error"));
        }
        return reply.path("result");
    }

    // ── Output ────────────────────────────────────────────────

    private void print(JsonNode n) {
        try {
            out.println(PRETTY.writeValueAsString(n));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + "  " : s + " ".repeat(width - s.length());
    }
}
