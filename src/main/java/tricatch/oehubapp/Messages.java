package tricatch.oehubapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UI messages: oeHub's {@code messages_<lang>.properties} (copied unchanged) with this app's
 * {@code oehubapp_<lang>.properties} layered on top. Both are UTF-8.
 */
public final class Messages {

    public static final List<String> LOCALES = List.of("en", "ko");

    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private Messages() {}

    public static String normalize(String locale) {
        return "ko".equals(locale) ? "ko" : "en";
    }

    public static Map<String, String> of(String locale) {
        return CACHE.computeIfAbsent(normalize(locale), lang -> {
            Map<String, String> m = new TreeMap<>(load("/i18n/messages_" + lang + ".properties"));
            m.putAll(load("/i18n/oehubapp_" + lang + ".properties"));
            return Collections.unmodifiableMap(m);
        });
    }

    static Map<String, String> load(String resource) {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> m = new TreeMap<>();
        for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
        return m;
    }
}
