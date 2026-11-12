package tricatch.oehostsapp.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class UpdateTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String savedRepo;
    private byte[] jar;
    private String sums;

    @BeforeEach
    void start() throws Exception {
        jar = jar("9.9.9", true);
        sums = sha256(jar) + "  oehostsapp.jar\n";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/o/r/releases/latest", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/o/r/releases/tag/9.9.9");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        // Like GitHub: the asset URL redirects to the storage host.
        server.createContext("/o/r/releases/download/9.9.9/oehostsapp.jar", ex -> {
            ex.getResponseHeaders().add("Location", "/blob");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/blob", ex -> {
            ex.sendResponseHeaders(200, jar.length);
            ex.getResponseBody().write(jar);
            ex.close();
        });
        server.createContext("/o/r/releases/download/9.9.9/SHA256SUMS.txt", ex -> {
            byte[] b = sums.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        savedRepo = Update.repo;
        Update.repo = "http://127.0.0.1:" + server.getAddress().getPort() + "/o/r";
    }

    @AfterEach
    void stop() {
        Update.repo = savedRepo;
        server.stop(0);
    }

    @Test
    void comparesVersions() {
        assertTrue(Update.isNewer("0.1.3", "0.1.2"));
        assertTrue(Update.isNewer("0.10.0", "0.9.9"));
        assertTrue(Update.isNewer("1.0", "0.9.9"));
        assertTrue(Update.isNewer("0.1.2.1", "0.1.2"));
        assertFalse(Update.isNewer("0.1.2", "0.1.2"));
        assertFalse(Update.isNewer("0.1.2", "0.1.2.0"));
        assertFalse(Update.isNewer("0.1.1", "0.1.2"));
        assertFalse(Update.isNewer("0.1.3", "dev"));
        assertFalse(Update.isNewer(null, "0.1.2"));
    }

    @Test
    void readsChecksumList() {
        String h = "a".repeat(64);
        assertEquals(h, Update.expectedSha256(h.toUpperCase() + "  oehostsapp.jar\r\n", "oehostsapp.jar"));
        assertEquals(h, Update.expectedSha256("b".repeat(64) + "  other.txt\n" + h + " *oehostsapp.jar\n", "oehostsapp.jar"));
        assertEquals(h, Update.expectedSha256("b".repeat(64) + "  oehostsapp.jar\n" + h + "  oehosts-agent.jar\n", "oehosts-agent.jar"));
        assertNull(Update.expectedSha256(h + "  my-oehostsapp.jar\n", "oehostsapp.jar"));
        assertNull(Update.expectedSha256("nothing here\n", "oehostsapp.jar"));
    }

    @Test
    void findsLatestReleaseFromRedirect() throws Exception {
        assertEquals("9.9.9", Update.latestVersion());
    }

    @Test
    void downloadsAndVerifiesJar() throws Exception {
        List<Integer> progress = new ArrayList<>();
        Path out = Update.download("9.9.9", dir, progress::add);
        assertEquals(dir.resolve("oehostsapp.jar.new"), out);
        assertArrayEquals(jar, Files.readAllBytes(out));
        assertEquals(100, progress.getLast());
    }

    @Test
    void rejectsWrongChecksum() {
        sums = "0".repeat(64) + "  oehostsapp.jar\n";
        IOException e = assertThrows(IOException.class, () -> Update.download("9.9.9", dir, p -> {}));
        assertEquals("checksum mismatch", e.getMessage());
        assertFalse(Files.exists(dir.resolve("oehostsapp.jar.new")));
    }

    @Test
    void rejectsJarOfAnotherVersion() throws Exception {
        jar = jar("9.9.8", true);
        sums = sha256(jar) + "  oehostsapp.jar\n";
        assertThrows(IOException.class, () -> Update.download("9.9.9", dir, p -> {}));
        assertFalse(Files.exists(dir.resolve("oehostsapp.jar.new")));
    }

    @Test
    void rejectsJarWithoutHelper() throws Exception {
        jar = jar("9.9.9", false);
        sums = sha256(jar) + "  oehostsapp.jar\n";
        assertThrows(IOException.class, () -> Update.download("9.9.9", dir, p -> {}));
    }

    private static byte[] jar(String version, boolean withHelper) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bos, mf)) {
            if (withHelper) {
                jos.putNextEntry(new JarEntry("tricatch/oehostsapp/UpdateHelper.class"));
                jos.write(new byte[]{1, 2, 3});
                jos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
