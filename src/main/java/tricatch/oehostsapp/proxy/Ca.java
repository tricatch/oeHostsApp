package tricatch.oehostsapp.proxy;

import io.github.tricatch.gotpache.cert.KeyTool;
import tricatch.oe.proxy.standalone.CaGenerator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/**
 * The proxy's root CA in a folder ({@code <data>/root-ca}): {@code ca.cer} (PEM certificate) and
 * {@code ca.pfx} (PEM PKCS#8 key, unencrypted), the way {@link CaGenerator} writes them. Making a
 * new one, bringing in an existing one, and reading the one in use.
 */
public final class Ca {

    public static final String DEFAULT_NAME = "oeHostsApp Local CA";
    /** Longest CA name (CN) {@link #nameError} accepts. */
    public static final int NAME_MAX = 64;
    public static final String CERT_FILE = "ca.cer";
    public static final String KEY_FILE = "ca.pfx";

    /** Characters a distinguished name treats specially ({@code X500Name("CN=" + name)} would misread them). */
    private static final String DN_SPECIAL = ",+=\"\\<>;";
    private static final Set<String> WIN_RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private Ca() {}

    /** Makes a CA named {@link #DEFAULT_NAME} in {@code dir} unless it holds both files already. */
    static void ensure(Path dir) throws IOException {
        if (Files.exists(dir.resolve(CERT_FILE)) && Files.exists(dir.resolve(KEY_FILE))) return;
        generate(dir, DEFAULT_NAME);
    }

    /**
     * Why {@code name} cannot be a CA name, as a message key; null when it can. The name becomes the
     * certificate's CN, so it may not hold a character a distinguished name treats specially, a
     * control character, a leading {@code #} or a leading/trailing space.
     */
    public static String nameError(String name) {
        if (name == null || name.isBlank()) return "app.settings.ca.cn.error.required";
        if (name.length() > NAME_MAX) return "app.settings.ca.cn.error.length";
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
    public static String fileName(String cn, String fallback) {
        String s = cn == null ? "" : cn.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        s = s.replaceAll("[. ]+$", "");
        if (WIN_RESERVED.contains(s.toUpperCase(Locale.ROOT))) s += "_";
        return s.isEmpty() ? fallback : s;
    }

    /**
     * Replaces the CA in {@code dir} with a new one whose CN is {@code name} (checked with
     * {@link #nameError}). Certificates trusted for the old CA stop working.
     */
    public static void generate(Path dir, String name) throws IOException {
        if (nameError(name) != null) throw new IllegalArgumentException("invalid CA name");
        try {
            new CaGenerator().generate(dir, name, true);
        } catch (CaGenerator.AlreadyExistsException e) {
            throw new IOException(e.getMessage(), e); // cannot happen with force
        }
    }

    /** The certificate at {@code cert}, or null when there is none or it cannot be read. */
    public static X509Certificate read(Path cert) {
        if (cert == null || !Files.exists(cert)) return null;
        try (InputStream in = Files.newInputStream(cert)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** The CN of the certificate at {@code cert}, or null when there is none or it cannot be read. */
    public static String commonName(Path cert) {
        X509Certificate x = read(cert);
        if (x == null) return null;
        try {
            for (Rdn r : new LdapName(x.getSubjectX500Principal().getName()).getRdns()) {
                if (r.getType().equalsIgnoreCase("CN")) return r.getValue().toString();
            }
        } catch (Exception e) {
            // no name to offer
        }
        return null;
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
     * Replaces the CA in {@code dir} with an existing one (for example oeHub's, so a browser that
     * already trusts it keeps working). The key is a PEM PKCS#8 file, encrypted or not, or a PKCS#12
     * keystore (.pfx/.p12), which may also hold the certificate; then {@code certFile} may be null.
     * The pair is checked (a CA certificate, and the key belongs to it) before anything is replaced,
     * and stored the way {@link CaGenerator} writes it: PEM files, the key unencrypted.
     */
    public static void importInto(Path dir, Path certFile, Path keyFile, String password) throws ImportException {
        byte[] keyBytes;
        try {
            keyBytes = Files.readAllBytes(keyFile);
        } catch (IOException e) {
            throw new ImportException("app.settings.ca.import.error.read", e);
        }
        String pwd = password == null ? "" : password;
        PrivateKey key;
        X509Certificate cert = null;
        String text = new String(keyBytes, StandardCharsets.ISO_8859_1);
        if (text.contains("-----BEGIN")) {
            key = pemKey(text, pwd);
        } else {
            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(new ByteArrayInputStream(keyBytes), pwd.toCharArray());
                key = null;
                for (String alias : Collections.list(ks.aliases())) {
                    if (ks.isKeyEntry(alias)) {
                        key = (PrivateKey) ks.getKey(alias, pwd.toCharArray());
                        if (ks.getCertificate(alias) instanceof X509Certificate x) cert = x;
                        break;
                    }
                }
                if (key == null) throw new ImportException("app.settings.ca.import.error.key", null);
            } catch (ImportException e) {
                throw e;
            } catch (IOException e) {
                // A wrong password and a file that is not PKCS#12 both end up here.
                throw new ImportException(e.getCause() instanceof UnrecoverableKeyException || pwd.isEmpty()
                        ? "app.settings.ca.import.error.password" : "app.settings.ca.import.error.key.format", e);
            } catch (Exception e) {
                throw new ImportException("app.settings.ca.import.error.key", e);
            }
        }
        if (certFile != null) {
            try (InputStream in = Files.newInputStream(certFile)) {
                cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            } catch (Exception e) {
                throw new ImportException("app.settings.ca.import.error.cert", e);
            }
        }
        if (cert == null) throw new ImportException("app.settings.ca.import.error.no.cert", null);
        if (cert.getBasicConstraints() < 0) throw new ImportException("app.settings.ca.import.error.not.ca", null);
        if (!matches(key, cert.getPublicKey())) throw new ImportException("app.settings.ca.import.error.mismatch", null);

        try {
            Files.createDirectories(dir);
            KeyTool keyTool = new KeyTool();
            // Write both next to the old pair first, so a failure never leaves a mismatched pair.
            keyTool.writeCertificate(cert, dir.toString(), CERT_FILE + ".new");
            keyTool.writePrivateKey(key, dir.toString(), KEY_FILE + ".new");
            Files.move(dir.resolve(KEY_FILE + ".new"), dir.resolve(KEY_FILE), StandardCopyOption.REPLACE_EXISTING);
            Files.move(dir.resolve(CERT_FILE + ".new"), dir.resolve(CERT_FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ImportException("app.settings.ca.import.error.write", e);
        }
    }

    /** A PEM private key: PKCS#8, encrypted with {@code pwd} or not. Read with the JDK alone (see below). */
    private static PrivateKey pemKey(String text, String pwd) throws ImportException {
        // The JDK refuses BouncyCastle's ciphers from the unsigned fat jar, so no BouncyCastle here.
        if (text.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            if (pwd.isEmpty()) throw new ImportException("app.settings.ca.import.error.password", null);
            PKCS8EncodedKeySpec spec;
            try {
                var info = new EncryptedPrivateKeyInfo(pem(text, "ENCRYPTED PRIVATE KEY"));
                String alg = info.getAlgParameters() != null ? info.getAlgParameters().toString() : info.getAlgName();
                var secret = SecretKeyFactory.getInstance(alg).generateSecret(new PBEKeySpec(pwd.toCharArray()));
                Cipher c = Cipher.getInstance(alg);
                c.init(Cipher.DECRYPT_MODE, secret, info.getAlgParameters());
                spec = info.getKeySpec(c);
            } catch (NoSuchAlgorithmException | IOException e) {
                throw new ImportException("app.settings.ca.import.error.key.format", e);
            } catch (Exception e) {
                throw new ImportException("app.settings.ca.import.error.password", e);
            }
            return pkcs8(spec);
        }
        if (text.contains("BEGIN PRIVATE KEY")) return pkcs8(new PKCS8EncodedKeySpec(pem(text, "PRIVATE KEY")));
        // e.g. "BEGIN RSA PRIVATE KEY" (PKCS#1): openssl pkcs8 -topk8 converts it.
        throw new ImportException("app.settings.ca.import.error.key.format", null);
    }

    /** The DER bytes of the first PEM block of {@code type}. */
    private static byte[] pem(String text, String type) throws ImportException {
        String begin = "-----BEGIN " + type + "-----", end = "-----END " + type + "-----";
        int i = text.indexOf(begin), j = text.indexOf(end, Math.max(i, 0));
        if (i < 0 || j < 0) throw new ImportException("app.settings.ca.import.error.key", null);
        try {
            return Base64.getMimeDecoder().decode(text.substring(i + begin.length(), j));
        } catch (IllegalArgumentException e) {
            throw new ImportException("app.settings.ca.import.error.key", e);
        }
    }

    /** A PKCS#8 key of any of the algorithms a CA uses. */
    private static PrivateKey pkcs8(PKCS8EncodedKeySpec spec) throws ImportException {
        for (String alg : new String[]{"RSA", "EC", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (Exception ignored) {
                // try the next algorithm
            }
        }
        throw new ImportException("app.settings.ca.import.error.key", null);
    }

    /** Whether {@code key} is the private half of {@code pub}: a signature made with it verifies. */
    private static boolean matches(PrivateKey key, PublicKey pub) {
        if (!key.getAlgorithm().equals(pub.getAlgorithm())) return false;
        String alg = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "Ed25519", "EdDSA" -> "Ed25519";
            default -> null;
        };
        if (alg == null) return false;
        try {
            byte[] data = "oehostsapp-ca-check".getBytes(StandardCharsets.US_ASCII);
            Signature s = Signature.getInstance(alg);
            s.initSign(key);
            s.update(data);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance(alg);
            v.initVerify(pub);
            v.update(data);
            return v.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }
}
