package tricatch.oehostsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarInputStream;

/**
 * oeHosts-Agent ({@code -javaagent:oehosts-agent.jar=<share URL>}), kept up to date in two ways:
 * <ol>
 * <li>The app jar bundles a copy ({@code /oehosts-agent.jar}); every start installs it unless the
 *     installed agent is the same version or newer. So the agent is there even offline.</li>
 * <li>Then, in the background, the latest release of the agent's own repository
 *     ({@code releases/latest}, asset {@code oehosts-agent.jar} checked against
 *     {@code SHA256SUMS.txt}) replaces it when newer. The agent is released on its own.</li>
 * </ol>
 * The file name never changes, so a {@code -javaagent} option a user put in an IDE or a script
 * keeps working across updates. It sits next to the installed app jar (the installers' folder),
 * or in the data folder when the app does not run from a writable jar (IDE, Gradle). On Windows a
 * running JVM that uses the agent keeps it locked; the replacement then waits for the next start.
 * Nothing here may fail the app start.
 */
public final class HostsAgent {

    private static final Logger log = LoggerFactory.getLogger(HostsAgent.class);

    static final String FILE_NAME = "oehosts-agent.jar";
    private static final String RESOURCE = "/" + FILE_NAME;
    /** Releases of the agent; {@code -Doehostsapp.agent.repo=<url>} points at a test server. */
    static volatile String repo = System.getProperty("oehostsapp.agent.repo", "https://github.com/tricatch/oeHostsAgent");

    /** What is at {@link #path}. */
    public enum State { MISSING, INSTALLED, IN_USE, FAILED }

    /** The background check against the agent's releases. */
    public enum Check { IDLE, CHECKING, LATEST, DOWNLOADING, UPDATED, PENDING, FAILED }

    private static volatile Path path;
    private static volatile State state = State.MISSING;
    private static volatile String installedVersion;
    private static volatile String error;
    private static volatile Check check = Check.IDLE;
    private static volatile String latest;
    private static volatile String checkError;
    private static volatile Runnable listener = () -> {};

    private HostsAgent() {}

    /** Where the agent is (or would be) installed; set by {@link #install}. */
    public static Path path() {
        return path;
    }

    public static State state() {
        return state;
    }

    /** The version of the agent at {@link #path}; null when there is none. */
    public static String version() {
        return installedVersion;
    }

    public static String error() {
        return error;
    }

    public static Check check() {
        return check;
    }

    /** The agent's latest release, once checked. */
    public static String latest() {
        return latest;
    }

    public static String checkError() {
        return checkError;
    }

    /** Called (on any thread) whenever the state or the check changes; the last set wins. */
    public static void setListener(Runnable r) {
        listener = r == null ? () -> {} : r;
    }

    private static void changed() {
        listener.run();
    }

    /** Installs the bundled agent, then checks the agent's releases in the background. */
    public static void start(Path dataDir) {
        install(dataDir);
        Thread.ofVirtual().name("agent-update").start(HostsAgent::checkForUpdate);
    }

    /**
     * Copies the bundled agent to its place, unless the agent there is the same version or newer
     * (a release downloaded earlier is not replaced by an older bundled copy).
     */
    static synchronized void install(Path dataDir) {
        Path jar = Update.installedJar();
        Path target = (jar != null ? jar.getParent() : dataDir).resolve(FILE_NAME).toAbsolutePath();
        path = target;
        String current = Files.isRegularFile(target) ? versionOf(target) : null;
        installedVersion = current;
        state = current != null ? State.INSTALLED : Files.isRegularFile(target) ? State.FAILED : State.MISSING;
        byte[] bundled;
        try (InputStream in = HostsAgent.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return;
            bundled = in.readAllBytes();
        } catch (IOException e) {
            return;
        }
        String bundledVersion = versionOf(bundled);
        if (current != null && !Update.isNewer(bundledVersion, current)) return;
        try {
            Path tmp = target.resolveSibling(FILE_NAME + ".tmp");
            Files.write(tmp, bundled);
            replace(tmp, target, bundledVersion);
        } catch (IOException e) {
            log.warn("oeHosts-Agent not installed at {}: {}", target, e.toString());
            if (state != State.INSTALLED) state = State.FAILED;
            error = e.getMessage();
        }
    }

    /** Looks for a newer release of the agent and installs it. */
    static void checkForUpdate() {
        if (path == null) return;
        check = Check.CHECKING;
        checkError = null;
        changed();
        try {
            String tag = Update.latestVersion(repo);
            latest = tag;
            String current = installedVersion;
            if (current != null && !Update.isNewer(tag, current)) {
                check = Check.LATEST;
                changed();
                return;
            }
            check = Check.DOWNLOADING;
            changed();
            Path dl = path.resolveSibling(FILE_NAME + ".download");
            Update.fetchAsset(repo, tag, FILE_NAME, dl, p -> {});
            String v = versionOf(dl);
            if (!tag.equals(v)) {
                Files.deleteIfExists(dl);
                throw new IOException("agent version " + v + " is not " + tag);
            }
            synchronized (HostsAgent.class) {
                replace(dl, path, tag);
            }
            // In use: the old agent stays and the next start downloads the release again.
            check = state == State.IN_USE ? Check.PENDING : Check.UPDATED;
            changed();
        } catch (Exception e) {
            log.warn("oeHosts-Agent update check failed: {}", Update.reason(e));
            check = Check.FAILED;
            checkError = Update.reason(e);
            changed();
        }
    }

    /**
     * Moves {@code fresh} over {@code target}. When a JVM holds the old agent (Windows), the old one
     * stays (state IN_USE) and {@code fresh} is deleted; the next start tries again.
     */
    private static void replace(Path fresh, Path target, String version) throws IOException {
        try {
            Files.move(fresh, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(fresh);
            if (!Files.isRegularFile(target)) throw e;
            log.warn("oeHosts-Agent {} not installed, {} is in use: {}", version, target, e.toString());
            state = State.IN_USE;
            error = e.getMessage();
            return;
        }
        log.info("oeHosts-Agent {} installed at {}", version, target);
        installedVersion = version;
        state = State.INSTALLED;
        error = null;
    }

    /**
     * The JVM option that loads the agent with {@code url}. Quoted as a whole when the path has a
     * space, which both a shell and IntelliJ's VM options field accept.
     */
    public static String option(Path agent, String url) {
        String opt = "-javaagent:" + agent + "=" + url;
        return agent.toString().contains(" ") ? "\"" + opt + "\"" : opt;
    }

    private static String versionOf(Path jar) {
        try {
            return versionOf(Files.readAllBytes(jar));
        } catch (IOException e) {
            return null;
        }
    }

    private static String versionOf(byte[] jar) {
        try (JarInputStream in = new JarInputStream(new java.io.ByteArrayInputStream(jar))) {
            var mf = in.getManifest();
            return mf == null ? null : mf.getMainAttributes().getValue("Implementation-Version");
        } catch (IOException e) {
            return null;
        }
    }
}
