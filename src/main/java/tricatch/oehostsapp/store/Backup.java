package tricatch.oehostsapp.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The data backup: one ZIP with {@code data.json} and, when chosen, the CA pair
 * ({@code root-ca/ca.cer}, {@code root-ca/ca.pfx}, the key unencrypted, as
 * {@link tricatch.oe.proxy.standalone.CaGenerator} writes it). {@code data.json} holds only the
 * parts chosen at export ({@link Store#exportParts}); a part is present when its key is.
 *
 * <p>Reading takes only those three names, each up to a size limit, so a crafted archive can
 * neither write anywhere (nothing is extracted to disk by name) nor exhaust memory.
 */
public final class Backup {

    static final String DATA = "data.json";
    static final String CERT = "root-ca/ca.cer";
    static final String KEY = "root-ca/ca.pfx";
    private static final int DATA_MAX = 64 * 1024 * 1024;
    private static final int CA_MAX = 1024 * 1024;

    /** What a backup holds: its {@code data.json}; {@code cert}/{@code key} are null when it has no CA. */
    public record Contents(JsonNode data, byte[] cert, byte[] key) {
        public boolean hasHosts() {
            return data.has("hosts");
        }

        public boolean hasVhosts() {
            return data.has("vhosts");
        }

        public boolean hasSettings() {
            return data.has("settings");
        }

        public boolean hasCa() {
            return cert != null;
        }
    }

    private Backup() {}

    /** Writes {@code dataJson}, and the CA files found in {@code caDir} unless it is null, to {@code out}. */
    public static void write(byte[] dataJson, Path caDir, Path out) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(tmp))) {
            z.putNextEntry(new ZipEntry(DATA));
            z.write(dataJson);
            z.closeEntry();
            Path cert = caDir == null ? null : caDir.resolve("ca.cer");
            Path key = caDir == null ? null : caDir.resolve("ca.pfx");
            if (cert != null && Files.isRegularFile(cert) && Files.isRegularFile(key)) {
                z.putNextEntry(new ZipEntry(CERT));
                Files.copy(cert, z);
                z.closeEntry();
                z.putNextEntry(new ZipEntry(KEY));
                Files.copy(key, z);
                z.closeEntry();
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Reads a backup; an IOException when the file is not one (no readable {@code data.json}). */
    public static Contents read(Path file) throws IOException {
        byte[] data = null, cert = null, key = null;
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(file))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                switch (e.getName()) {
                    case DATA -> data = capped(z, DATA_MAX);
                    case CERT -> cert = capped(z, CA_MAX);
                    case KEY -> key = capped(z, CA_MAX);
                    default -> { /* not ours: ignored */ }
                }
            }
        }
        if (data == null) throw new IOException("no " + DATA);
        JsonNode root = Store.MAPPER.readTree(data);
        if (root == null || !root.isObject()) throw new IOException("empty " + DATA);
        boolean hasCa = cert != null && key != null;
        return new Contents(root, hasCa ? cert : null, hasCa ? key : null);
    }

    private static byte[] capped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            if (out.size() + n > max) throw new IOException("entry too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
