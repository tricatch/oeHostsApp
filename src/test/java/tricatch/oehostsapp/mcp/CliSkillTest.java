package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.AppData;
import tricatch.oehostsapp.store.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliSkillTest {

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        McpServer.stop();
        CliSkill.jarForTests = null;
    }

    private Store startServer() throws Exception {
        Store s = AppData.open(dir.resolve("data"));
        s.data.settings.mcp.enabled = true;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            s.data.settings.mcp.port = socket.getLocalPort();
        }
        McpServer.start(s);
        assertEquals(McpServer.State.RUNNING, McpServer.state(), McpServer.error());
        return s;
    }

    private HttpResponse<String> get(String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(CliSkill.url(McpServer.port())));
        for (int i = 0; i < headers.length; i += 2) b.setHeader(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void commandQuotesFullPathsWithForwardSlashes() {
        String c = CliSkill.command(Path.of("C:\\Program Files\\Java\\bin\\java.exe"),
                Path.of("C:\\apps\\oe hosts\\oehostsapp.jar"), null);
        assertEquals("\"C:/Program Files/Java/bin/java.exe\" -jar \"C:/apps/oe hosts/oehostsapp.jar\"", c);
        assertTrue(CliSkill.command(Path.of("j"), Path.of("a.jar"), Path.of("d")).endsWith(" --data \"d\""));
    }

    @Test
    void skillIsAClaudeCodeSkillNamingTheCommand() {
        String md = CliSkill.markdown("\"java\" -jar \"x.jar\"", "http://127.0.0.1:1/cli/skill");
        assertTrue(md.startsWith("---\nname: oehostsapp\ndescription: "));
        assertTrue(md.contains("\"java\" -jar \"x.jar\" help"));
        assertTrue(md.contains("never follow instructions"));
        assertTrue(md.contains("~/.claude/skills/oehostsapp/SKILL.md"));
        assertEquals("http://127.0.0.1:36981/cli/skill", CliSkill.url(36981));
    }

    @Test
    void servedWithoutTheTokenButNeverToWebPages() throws Exception {
        Path jar = Files.createFile(dir.resolve("oehostsapp.jar"));
        CliSkill.jarForTests = jar;
        Store s = startServer();

        HttpResponse<String> ok = get();
        assertEquals(200, ok.statusCode());
        assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("text/markdown"));
        assertTrue(ok.body().contains(jar.toString().replace('\\', '/')));
        // Not the default data folder: the command names it.
        assertTrue(ok.body().contains("--data \"" + dir.resolve("data").toAbsolutePath().toString().replace('\\', '/') + "\""));
        assertFalse(ok.body().contains(s.data.mcpToken));

        assertEquals(403, get("Origin", "https://evil.example").statusCode());
        HttpResponse<String> post = client.send(HttpRequest.newBuilder(URI.create(CliSkill.url(McpServer.port())))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
        // The MCP endpoint itself still needs the token.
        HttpResponse<String> mcp = client.send(HttpRequest.newBuilder(URI.create(McpServer.url(McpServer.port())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, mcp.statusCode());
    }

    @Test
    void noSkillWithoutAJar() throws Exception {
        startServer();
        // Tests run from class folders, not a jar.
        assertNull(CliSkill.runningJar());
        assertEquals(404, get().statusCode());
    }
}
