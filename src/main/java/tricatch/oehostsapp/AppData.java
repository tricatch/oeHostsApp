package tricatch.oehostsapp;

import tricatch.oehostsapp.store.Store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where oeHostsApp keeps its data and how the data file is opened: {@code oehostsapp.json} in the
 * per-user app directory. On first start the file of the previous oeHosts app
 * ({@code oeHosts/oehosts-app.json}, same format) is copied in; failing that, the old egui app's
 * {@code oeHosts/oehosts.json} is migrated. Neither old file is ever written.
 */
public final class AppData {

    public static final String FILE_NAME = "oehostsapp.json";

    private AppData() {}

    /** Windows: %APPDATA%\&lt;name&gt;, macOS: ~/Library/Application Support/&lt;name&gt;, else ~/.config/&lt;name&gt;. */
    static Path userAppDir(String name) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home, name);
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", name);
        return Path.of(home, ".config", name);
    }

    public static Path defaultDir() {
        return userAppDir("oeHostsApp");
    }

    /** Opens (creating if needed) the data file in {@code dir}. */
    public static Store open(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE_NAME);
        // Only the default location inherits the old apps' data; an explicit --data dir starts empty.
        if (!dir.equals(defaultDir())) return Store.open(file, null);
        Path oldDir = userAppDir("oeHosts");
        Path previous = oldDir.resolve("oehosts-app.json");
        if (!Files.exists(file) && Files.isRegularFile(previous)) Files.copy(previous, file);
        return Store.open(file, oldDir.resolve("oehosts.json"));
    }
}
