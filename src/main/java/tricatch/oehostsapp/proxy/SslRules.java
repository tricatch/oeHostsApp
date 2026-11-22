package tricatch.oehostsapp.proxy;

import java.net.IDN;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The SSL proxy's domain list, one entry per line:
 * <ul>
 *   <li>{@code api.example.com}: that name only</li>
 *   <li>{@code *.example.com}: every level below it, not the name itself</li>
 *   <li>{@code *}: every domain</li>
 *   <li>{@code !} before a name or a {@code *.} pattern: left out; this wins wherever the line stands</li>
 *   <li>{@code #} at the start of a line: a comment</li>
 * </ul>
 * Only a name that can be a TLS server name ever matches: the reverse proxy learns the domain of a
 * tunnel from SNI alone, so IP addresses, names with an underscore and localhost stay tunnels.
 */
public record SslRules(boolean all, Set<String> names, Set<String> below, Set<String> exceptNames, Set<String> exceptBelow) {

    /** Selects nothing. */
    public static final SslRules NONE = new SslRules(false, Set.of(), Set.of(), Set.of(), Set.of());

    /** Letters, digits and inner hyphens; the last label is not a number (an IPv4 address). */
    private static final Pattern NAME = Pattern.compile("^(?=.{1,253}$)(?!(?:.*\\.)?[0-9]+$)"
            + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$");

    /** One valid entry; {@code name} is null for {@code *}. */
    private record Entry(boolean except, boolean below, String name) {}

    /** The rules of {@code text}; invalid lines select nothing (the screen does not save them, see {@link #problems}). */
    public static SslRules parse(String text) {
        if (text == null || text.isBlank()) return NONE;
        boolean all = false;
        Set<String> names = new HashSet<>(), below = new HashSet<>(), exceptNames = new HashSet<>(), exceptBelow = new HashSet<>();
        for (String line : text.split("\n")) {
            Entry e = entry(line);
            if (e == null) continue;
            if (e.name() == null) all = true;
            else if (e.except()) (e.below() ? exceptBelow : exceptNames).add(e.name());
            else (e.below() ? below : names).add(e.name());
        }
        return new SslRules(all, Set.copyOf(names), Set.copyOf(below), Set.copyOf(exceptNames), Set.copyOf(exceptBelow));
    }

    /** The numbers (from 1) of the lines that are neither an entry, a comment nor blank. */
    public static List<Integer> problems(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) if (!valid(lines[i])) out.add(i + 1);
        return out;
    }

    /** Whether a line can be saved: an entry, a comment or blank. */
    public static boolean valid(String line) {
        String s = line.strip();
        return s.isEmpty() || s.startsWith("#") || entry(s) != null;
    }

    private static Entry entry(String line) {
        String s = line.strip();
        if (s.isEmpty() || s.startsWith("#")) return null;
        boolean except = s.startsWith("!");
        if (except) s = s.substring(1);
        if (s.equals("*")) return except ? null : new Entry(false, false, null);
        boolean below = s.startsWith("*.");
        if (below) s = s.substring(2);
        String name;
        try {
            // A name typed in Unicode is stored as browsers send it (punycode).
            name = IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        name = withoutDot(name);
        return canBeServerName(name) ? new Entry(except, below, name) : null;
    }

    /** Whether at least one domain can be selected. */
    public boolean selectsAny() {
        return all || !names.isEmpty() || !below.isEmpty();
    }

    /** Whether {@code host} (as in a CONNECT) is selected. */
    public boolean matches(String host) {
        String h = normalize(host);
        if (h == null) return false;
        if (exceptNames.contains(h) || underAny(h, exceptBelow)) return false;
        return all || names.contains(h) || underAny(h, below);
    }

    /** {@code text} with {@code host} on a line of its own at the end, or as it is when that already selects it. */
    public static String withDomain(String text, String host) {
        if (parse(text).matches(host)) return text;
        return text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + host + "\n";
    }

    /** Whether {@code host} can be decrypted at all (a name SNI can carry). */
    public static boolean decryptable(String host) {
        return normalize(host) != null;
    }

    /** Whether {@code host} is left out by a {@code !} line. */
    public boolean excludes(String host) {
        String h = normalize(host);
        return h != null && (exceptNames.contains(h) || underAny(h, exceptBelow));
    }

    /** {@code host} in lower case without the trailing dot, or null when it cannot be a TLS server name. */
    static String normalize(String host) {
        if (host == null) return null;
        // ASCII only, before toLowerCase: it would fold U+212A to "k".
        for (int i = 0; i < host.length(); i++) if (host.charAt(i) >= 0x80) return null;
        String h = withoutDot(host.toLowerCase(Locale.ROOT));
        return canBeServerName(h) ? h : null;
    }

    private static boolean canBeServerName(String name) {
        // The length first: the pattern's look-ahead is not for text of any size.
        return name.length() <= 253 && NAME.matcher(name).matches()
                && !name.equals("localhost") && !name.endsWith(".localhost");
    }

    private static String withoutDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean underAny(String host, Set<String> parents) {
        if (parents.isEmpty()) return false;
        for (int dot = host.indexOf('.'); dot >= 0; dot = host.indexOf('.', dot + 1)) {
            if (parents.contains(host.substring(dot + 1))) return true;
        }
        return false;
    }
}
