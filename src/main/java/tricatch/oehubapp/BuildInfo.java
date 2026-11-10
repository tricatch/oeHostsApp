package tricatch.oehubapp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * The app version and the versions of the bundled libraries, written into the jar at build time
 * ({@code /oehubapp-build.properties}, the {@code buildInfo} Gradle task), so they are exact in the
 * shadow jar as well as in an IDE run.
 */
public final class BuildInfo {

    private static final Properties PROPS = load();

    private BuildInfo() {}

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/oehubapp-build.properties")) {
            if (in != null) p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // no build info: versions show as unknown
        }
        return p;
    }

    /** The app version; null when the build info is missing. */
    public static String version() {
        return PROPS.getProperty("version");
    }

    /** The bundled version of {@code group:name}, or null when it is not bundled. */
    public static String dependency(String coordinate) {
        return PROPS.getProperty("dep." + coordinate.replace(':', '/'));
    }
}
