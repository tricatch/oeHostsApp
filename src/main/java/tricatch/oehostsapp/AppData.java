package tricatch.oehostsapp;

import tricatch.oehostsapp.store.Store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Where oeHostsApp keeps its data: {@code oehostsapp.json} in the per-user app directory. */
public final class AppData {

    public static final String FILE_NAME = "oehostsapp.json";

    private AppData() {}

    /** Windows: %APPDATA%\oeHostsApp, macOS: ~/Library/Application Support/oeHostsApp, else ~/.config/oeHostsApp. */
    public static Path defaultDir() {
        String name = "oeHostsApp";
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home, name);
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", name);
        return Path.of(home, ".config", name);
    }

    /** Opens (creating if needed) the data file in {@code dir}. */
    public static Store open(Path dir) throws IOException {
        Files.createDirectories(dir);
        return Store.open(dir.resolve(FILE_NAME));
    }
}
