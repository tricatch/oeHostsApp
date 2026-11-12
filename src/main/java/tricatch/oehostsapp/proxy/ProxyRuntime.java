package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.hosts.HostsRules;
import tricatch.oehostsapp.store.Store;

import tricatch.oe.proxy.ReverseProxyServer;
import io.github.tricatch.gotpache.cert.KeyTool;
import tricatch.oe.proxy.standalone.CaGenerator;
import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.exception.GatewayTimeoutException;
import tricatch.oe.proxy.spi.ClientHints;
import tricatch.oe.proxy.spi.ErrorPageRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The oe-proxy reverse proxy, embedded in this process the way oeHub embeds it (oeHub's
 * {@code ReverseProxySettings.init} + {@code ReverseProxyServer.startSslPassServer}): it starts
 * with the app and serves the merged YAML of the selected vhosts on https port 443.
 *
 * <p>Single owner: every request resolves to {@link #OWNER} through {@code setDefaultOwner}. This
 * app never names an identifier header ({@code setIdentifierHeaderName}), so the library reads none
 * and oeHub's oeOID extension header {@code X-OeHub-Oid} is ignored. The routes are read lazily
 * through the {@code VirtualHostsLoader} SPI; {@link #reload} drops the cached table after an edit
 * and the next request reads the store again.
 */
public final class ProxyRuntime {

    /** The only owner id; the library's standalone mode uses the same value. */
    public static final long OWNER = 0L;
    public static final String CA_NAME = "oeHostsApp Local CA";

    public enum State { STARTING, RUNNING, FAILED }

    private static volatile State state = State.STARTING;
    private static volatile String error;
    private static volatile Path caCert;
    private static volatile Path dataDir;
    private static volatile Store store;
    /** Whether startSslPassServer has succeeded once (later starts go through its own restart). */
    private static volatile boolean started;
    /** The binding of the last (re)start: 127.0.0.1, one IPv4 address, or 0.0.0.0. */
    private static volatile String bind = ForwardProxy.ALL;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Logger log = LoggerFactory.getLogger(ProxyRuntime.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ProxyRuntime() {}

    public static State state() {
        return state;
    }

    /** Why the start failed (only meaningful in {@link State#FAILED}). */
    public static String error() {
        return error;
    }

    /** The CA certificate users install in their trust store (null until the start has run). */
    public static Path caCert() {
        return caCert;
    }

    public static int port() {
        return ReverseProxyServer.HTTPS_PORT;
    }

    /** Whether the last (re)start bound every network interface rather than 127.0.0.1. */
    public static boolean lan() {
        return !ForwardProxy.LOOPBACK.equals(bind);
    }

    /** The binding of the last (re)start: 127.0.0.1, one IPv4 address, or 0.0.0.0. */
    public static String bind() {
        return bind;
    }

    /**
     * Applies the stored binding (settings.reverseProxyBind) to the library before a (re)start:
     * every interface, 127.0.0.1 only, or one network card's address together with 127.0.0.1 (the
     * hosts profiles' {@code 127.0.0.1 domain} lines reach the proxy there). Returns the addresses
     * to check before starting.
     */
    private static String[] applyBinding(Store store) throws IOException {
        String b;
        synchronized (store) {
            b = store.data.settings.reverseProxyBindAddress();
        }
        bind = b;
        java.net.InetAddress lo = java.net.InetAddress.getLoopbackAddress();
        if (ForwardProxy.ALL.equals(b)) {
            ReverseProxyServer.setBindAddresses();
            return new String[]{"0.0.0.0", "::"};
        }
        if (ForwardProxy.LOOPBACK.equals(b)) {
            ReverseProxyServer.setBindAddresses(lo);
            return new String[]{ForwardProxy.LOOPBACK};
        }
        ReverseProxyServer.setBindAddresses(lo, java.net.InetAddress.getByName(b));
        return new String[]{ForwardProxy.LOOPBACK, b};
    }

    /** {@code r} runs (on the starting thread) whenever the state changes. */
    public static void onChange(Runnable r) {
        listeners.add(r);
    }

    /** Starts the proxy in the background so a slow CA generation never holds up the window. */
    public static void start(Path dataDir, Store store) {
        ProxyRuntime.dataDir = dataDir;
        ProxyRuntime.store = store;
        background(() -> run(dataDir, store));
    }

    /**
     * Restarts the proxy in the background (after a new CA, or to retry a failed start). A server
     * that once started is restarted in place, which also reloads the CA.
     */
    public static void restart() {
        background(() -> {
            if (!started) {
                run(dataDir, store);
                return;
            }
            set(State.STARTING, null);
            try {
                applyBinding(store);
                ReverseProxyServer.startSslPassServer();
                set(State.RUNNING, null);
            } catch (Exception e) {
                set(State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /** Folder of the CA files ({@code <data>/root-ca}). */
    public static Path caDir() {
        return dataDir.resolve("root-ca");
    }

    /** Longest CA name (CN) {@link #caNameError} accepts. */
    public static final int CA_NAME_MAX = 64;
    /** Characters a distinguished name treats specially ({@code X500Name("CN=" + name)} would misread them). */
    private static final String DN_SPECIAL = ",+=\"\\<>;";
    private static final java.util.Set<String> WIN_RESERVED = java.util.Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /**
     * Why {@code name} cannot be a CA name, as a message key; null when it can. The name becomes the
     * certificate's CN, so it may not hold a character a distinguished name treats specially, a
     * control character, a leading {@code #} or a leading/trailing space.
     */
    public static String caNameError(String name) {
        if (name == null || name.isBlank()) return "app.settings.ca.cn.error.required";
        if (name.length() > CA_NAME_MAX) return "app.settings.ca.cn.error.length";
        if (name.chars().anyMatch(c -> Character.isISOControl(c) || DN_SPECIAL.indexOf(c) >= 0)
                || name.startsWith("#") || name.startsWith(" ") || name.endsWith(" ")) {
            return "app.settings.ca.cn.error.chars";
        }
        return null;
    }

    /**
     * A file name for the CA certificate made from its CN: characters a file system refuses become
     * {@code _}, trailing dots/spaces go, a Windows device name gets a {@code _}; {@code fallback}
     * when nothing usable is left.
     */
    public static String caFileName(String cn, String fallback) {
        String s = cn == null ? "" : cn.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        s = s.replaceAll("[. ]+$", "");
        if (WIN_RESERVED.contains(s.toUpperCase(java.util.Locale.ROOT))) s += "_";
        return s.isEmpty() ? fallback : s;
    }

    /**
     * Replaces the CA with a new one whose CN is {@code name} (checked with {@link #caNameError}).
     * Certificates trusted for the old CA stop working.
     */
    public static void regenerateCa(String name) throws IOException {
        if (caNameError(name) != null) throw new IllegalArgumentException("invalid CA name");
        try {
            new CaGenerator().generate(caDir(), name, true);
        } catch (CaGenerator.AlreadyExistsException e) {
            throw new IOException(e.getMessage(), e); // cannot happen with force
        }
        caCert = caDir().resolve("ca.cer");
    }

    /** A CA import that was refused; {@link #code} is the message key telling why. */
    public static final class ImportException extends Exception {
        public final String code;

        ImportException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }
    }

    /**
     * Replaces the CA with an existing one (for example oeHub's, so a browser that already trusts it
     * keeps working). The key is a PEM PKCS#8 file, encrypted or not, or a PKCS#12 keystore
     * (.pfx/.p12), which may also hold the certificate; then {@code certFile} may be null.
     * The pair is checked (a CA certificate, and the key belongs to it) before anything is replaced,
     * and stored the way {@link CaGenerator} writes it: PEM files, the key unencrypted.
     */
    public static void importCa(Path certFile, Path keyFile, String password) throws ImportException {
        byte[] keyBytes;
        try {
            keyBytes = Files.readAllBytes(keyFile);
        } catch (IOException e) {
            throw new ImportException("app.settings.ca.import.error.read", e);
        }
        String pwd = password == null ? "" : password;
        java.security.PrivateKey key;
        java.security.cert.X509Certificate cert = null;
        String text = new String(keyBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        KeyTool keyTool = new KeyTool();
        if (text.contains("-----BEGIN")) {
            // Read with the JDK alone: the JDK refuses BouncyCastle's ciphers from the unsigned fat jar.
            if (text.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
                if (pwd.isEmpty()) throw new ImportException("app.settings.ca.import.error.password", null);
                java.security.spec.PKCS8EncodedKeySpec spec;
                try {
                    var info = new javax.crypto.EncryptedPrivateKeyInfo(pem(text, "ENCRYPTED PRIVATE KEY"));
                    String alg = info.getAlgParameters() != null ? info.getAlgParameters().toString() : info.getAlgName();
                    var secret = javax.crypto.SecretKeyFactory.getInstance(alg)
                            .generateSecret(new javax.crypto.spec.PBEKeySpec(pwd.toCharArray()));
                    javax.crypto.Cipher c = javax.crypto.Cipher.getInstance(alg);
                    c.init(javax.crypto.Cipher.DECRYPT_MODE, secret, info.getAlgParameters());
                    spec = info.getKeySpec(c);
                } catch (java.security.NoSuchAlgorithmException | IOException e) {
                    throw new ImportException("app.settings.ca.import.error.key.format", e);
                } catch (Exception e) {
                    throw new ImportException("app.settings.ca.import.error.password", e);
                }
                key = pkcs8(spec);
            } else if (text.contains("BEGIN PRIVATE KEY")) {
                key = pkcs8(new java.security.spec.PKCS8EncodedKeySpec(pem(text, "PRIVATE KEY")));
            } else {
                // e.g. "BEGIN RSA PRIVATE KEY" (PKCS#1): openssl pkcs8 -topk8 converts it.
                throw new ImportException("app.settings.ca.import.error.key.format", null);
            }
        } else {
            try {
                java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
                ks.load(new java.io.ByteArrayInputStream(keyBytes), pwd.toCharArray());
                key = null;
                for (String alias : java.util.Collections.list(ks.aliases())) {
                    if (ks.isKeyEntry(alias)) {
                        key = (java.security.PrivateKey) ks.getKey(alias, pwd.toCharArray());
                        if (ks.getCertificate(alias) instanceof java.security.cert.X509Certificate x) cert = x;
                        break;
                    }
                }
                if (key == null) throw new ImportException("app.settings.ca.import.error.key", null);
            } catch (ImportException e) {
                throw e;
            } catch (IOException e) {
                // A wrong password and a file that is not PKCS#12 both end up here.
                throw new ImportException(e.getCause() instanceof java.security.UnrecoverableKeyException || pwd.isEmpty()
                        ? "app.settings.ca.import.error.password" : "app.settings.ca.import.error.key.format", e);
            } catch (Exception e) {
                throw new ImportException("app.settings.ca.import.error.key", e);
            }
        }
        if (certFile != null) {
            try (var in = Files.newInputStream(certFile)) {
                cert = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
            } catch (Exception e) {
                throw new ImportException("app.settings.ca.import.error.cert", e);
            }
        }
        if (cert == null) throw new ImportException("app.settings.ca.import.error.no.cert", null);
        if (cert.getBasicConstraints() < 0) throw new ImportException("app.settings.ca.import.error.not.ca", null);
        if (!matches(key, cert.getPublicKey())) throw new ImportException("app.settings.ca.import.error.mismatch", null);

        try {
            Path dir = caDir();
            Files.createDirectories(dir);
            // Write both next to the old pair first, so a failure never leaves a mismatched pair.
            keyTool.writeCertificate(cert, dir.toString(), "ca.cer.new");
            keyTool.writePrivateKey(key, dir.toString(), "ca.pfx.new");
            Files.move(dir.resolve("ca.pfx.new"), dir.resolve("ca.pfx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(dir.resolve("ca.cer.new"), dir.resolve("ca.cer"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ImportException("app.settings.ca.import.error.write", e);
        }
        caCert = caDir().resolve("ca.cer");
    }

    /** The DER bytes of the first PEM block of {@code type}. */
    private static byte[] pem(String text, String type) throws ImportException {
        String begin = "-----BEGIN " + type + "-----", end = "-----END " + type + "-----";
        int i = text.indexOf(begin), j = text.indexOf(end, Math.max(i, 0));
        if (i < 0 || j < 0) throw new ImportException("app.settings.ca.import.error.key", null);
        try {
            return java.util.Base64.getMimeDecoder().decode(text.substring(i + begin.length(), j));
        } catch (IllegalArgumentException e) {
            throw new ImportException("app.settings.ca.import.error.key", e);
        }
    }

    /** A PKCS#8 key of any of the algorithms a CA uses. */
    private static java.security.PrivateKey pkcs8(java.security.spec.PKCS8EncodedKeySpec spec) throws ImportException {
        for (String alg : new String[]{"RSA", "EC", "Ed25519"}) {
            try {
                return java.security.KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (Exception ignored) {
                // try the next algorithm
            }
        }
        throw new ImportException("app.settings.ca.import.error.key", null);
    }

    /** Whether {@code key} is the private half of {@code pub}: a signature made with it verifies. */
    private static boolean matches(java.security.PrivateKey key, java.security.PublicKey pub) {
        if (!key.getAlgorithm().equals(pub.getAlgorithm())) return false;
        String alg = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "Ed25519", "EdDSA" -> "Ed25519";
            default -> null;
        };
        if (alg == null) return false;
        try {
            byte[] data = "oehostsapp-ca-check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            java.security.Signature s = java.security.Signature.getInstance(alg);
            s.initSign(key);
            s.update(data);
            byte[] sig = s.sign();
            java.security.Signature v = java.security.Signature.getInstance(alg);
            v.initVerify(pub);
            v.update(data);
            return v.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    private static void background(Runnable r) {
        Thread t = new Thread(r, "oe-proxy-start");
        t.setDaemon(true);
        t.start();
    }

    /** Drops the cached routes; the next request reloads them from the store. */
    public static void reload() {
        log.info("routes changed; reloading on the next request");
        ReverseProxyServer.invalidateVirtualHosts(OWNER);
    }

    private static void run(Path dataDir, Store store) {
        try {
            Path caDir = dataDir.resolve("root-ca");
            Path cert = caDir.resolve("ca.cer"), key = caDir.resolve("ca.pfx");
            if (!Files.exists(cert) || !Files.exists(key)) new CaGenerator().generate(caDir, CA_NAME, true);
            caCert = cert;

            // The proxy, Chrome and the backends share this PC, and the vhosts are the user's own:
            // allow external backends (oeHub's example routes one to a staging server) and trust
            // their internal certificates.
            ReverseProxyServer.setInternalOnlyUpstream(false);
            ReverseProxyServer.setTrustInternalCertEnabled(true);
            // CaGenerator writes the key unencrypted (blank password), as oeHub's setup wizard does.
            ReverseProxyServer.setCaFiles(cert, key, "");
            ReverseProxyServer.setDefaultOwner(OWNER);
            ReverseProxyServer.setVirtualHostsLoader((owner, clientIp) -> loadVirtualHosts(store));
            ReverseProxyServer.setErrorPageRenderer(new LoggingErrors(store));

            // SSLPassServer binds on an executor thread and only logs a failure there, so check the
            // port first to be able to show why the proxy is not running.
            String[] addresses = applyBinding(store);
            log.info("proxy starting on {}:{} (CA {})", String.join(", ", addresses), port(), cert.toAbsolutePath());
            set(State.STARTING, null);
            probePort(port(), addresses);
            ReverseProxyServer.startSslPassServer();
            started = true;
            set(State.RUNNING, null);
        } catch (Exception e) {
            set(State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** The merged YAML of the selected vhosts (kept up to date by the oeProxy tab), placeholders filled in. */
    static String loadVirtualHosts(Store store) {
        String yaml = mergedYaml(store);
        if (yaml == null) log.info("routes loaded: none (no vhost is switched on in the oeProxy list)");
        else log.info("routes loaded: {}", routeSummary(yaml));
        return yaml;
    }

    private static String mergedYaml(Store store) {
        String yaml;
        synchronized (store) {
            yaml = store.data.proxyConf.get("vhost");
        }
        return yaml == null || yaml.isBlank() ? null : HostsRules.resolvePlaceholders(yaml);
    }

    /** "domain -> host path, path; ..." of the merged YAML, for the console log. */
    static String routeSummary(String yaml) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode root = YAML.readTree(yaml);
            for (JsonNode v : root.path("virtual")) {
                List<String> locs = new ArrayList<>();
                for (JsonNode l : v.path("location")) {
                    List<String> paths = new ArrayList<>();
                    for (JsonNode p : l.path("path")) paths.add(p.asText());
                    locs.add(l.path("host").asText() + " " + String.join(",", paths));
                }
                out.add(v.path("domain").asText() + " -> " + String.join(" | ", locs));
            }
        } catch (Exception e) {
            return "(unreadable YAML: " + e.getMessage() + ")";
        }
        return out.size() + " domain(s): " + String.join("; ", out);
    }

    /**
     * Logs every error the proxy answers with, so a 404 or 502 can be traced from the console.
     * Returning null keeps the library's plain pages.
     */
    private record LoggingErrors(Store store) implements ErrorPageRenderer {
        @Override
        public String notFoundVhost(String host, String path, ClientHints hints) {
            String yaml = mergedYaml(store);
            log.warn("404 no route: host={} path={}; current routes: {}", host, path,
                    yaml == null ? "none (no vhost is switched on)" : routeSummary(yaml));
            return null;
        }

        @Override
        public String noVhosts(String clientIp, String identifierHeader, boolean ipIdentifierEnabled, ClientHints hints) {
            log.warn("503 no routes for client {}: no vhost is switched on in the oeProxy list", clientIp);
            return null;
        }

        @Override
        public String badGateway(BadGatewayException ex, ClientHints hints) {
            log.warn("502 backend unreachable: {}", ex.getMessage());
            return null;
        }

        @Override
        public String gatewayTimeout(GatewayTimeoutException ex, ClientHints hints) {
            log.warn("504 backend timeout: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Fails when another program already listens on the port. IPv4 and IPv6 are tried one at a
     * time: on macOS an IPv4-only listener (oeHub runs with -Djava.net.preferIPv4Stack=true) and
     * this JVM's dual-stack [::] socket can both bind, and IPv4 connections to 127.0.0.1 then reach
     * the other program while this proxy looks healthy.
     */
    private static void probePort(int port, String[] addresses) throws IOException {
        for (String addr : addresses) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(java.net.InetAddress.getByName(addr), port));
            } catch (BindException e) {
                if ("::".equals(addr) && !hasIpv6()) continue;
                throw new IOException("port " + port + " is in use on " + ("::".equals(addr) ? "IPv6" : "IPv4")
                        + " by another program (" + e.getMessage() + ")", e);
            } catch (java.net.SocketException e) {
                // the address family is not available on this machine (IPv6 disabled, IPv4-only JVM)
            }
        }
    }

    private static boolean hasIpv6() {
        try {
            return java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).stream()
                    .flatMap(i -> java.util.Collections.list(i.getInetAddresses()).stream())
                    .anyMatch(a -> a instanceof java.net.Inet6Address);
        } catch (java.net.SocketException e) {
            return false;
        }
    }

    private static void set(State s, String err) {
        if (s == State.RUNNING) log.info("proxy running on port {}", port());
        else if (s == State.FAILED) log.error("proxy failed to start: {}", err);
        state = s;
        error = err;
        for (Runnable r : listeners) r.run();
    }
}
