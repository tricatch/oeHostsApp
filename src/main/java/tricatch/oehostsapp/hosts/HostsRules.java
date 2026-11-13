package tricatch.oehostsapp.hosts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hosts profile parsing and merging, after oeHub's {@code parseHostsToMap} (static/js/util.js) and
 * the hosts page's {@code mergeSelectedProfiles}. Every consumer of the selected profiles (the
 * Chrome launcher's {@code --host-resolver-rules}, the forward proxy, the web service's
 * {@code /active-hosts}) goes through {@link #merge}, so they all resolve a host the same way.
 */
public final class HostsRules {

    public static final String PROXY_SVR_PLACEHOLDER = "${PROXY_SVR}";
    public static final String LOCAL_SVR_PLACEHOLDER = "${LOCAL_SVR}";
    /** What both placeholders mean in this app: the proxy, Chrome and the backend share this PC. */
    public static final String THIS_PC = "127.0.0.1";

    /**
     * Fills in oeHub's {@code ${PROXY_SVR}} and {@code ${LOCAL_SVR}}, which profiles copied from oeHub
     * may contain. Here whatever connects (Chrome, or this PC's proxy on behalf of a phone) runs on
     * this PC, so both are always {@link #THIS_PC}.
     */
    public static String resolvePlaceholders(String s) {
        return s.replace(PROXY_SVR_PLACEHOLDER, THIS_PC).replace(LOCAL_SVR_PLACEHOLDER, THIS_PC);
    }

    /** {@code HOSTS_RULE_HOST} / {@code HOSTS_RULE_IP} in util.js. */
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9_.*-]+$");
    private static final Pattern IP = Pattern.compile("^(?:\\$\\{PROXY_SVR\\}|[0-9A-Fa-f:.]+)$");
    private static final Pattern WS = Pattern.compile("[\\s\\uFEFF]+");
    private static final Pattern TRIM = Pattern.compile("^[\\s\\uFEFF]+|[\\s\\uFEFF]+$");

    public record Rule(String host, String ip) {}

    /** A rule of the merged profiles; {@code active} is false when an earlier profile already maps the host. */
    public record Entry(String host, String ip, boolean active) {}

    private HostsRules() {}

    /**
     * {@code IP host} lines; the first line for a host wins, invalid lines are dropped. As in oeHub,
     * only the first host name of a line counts.
     */
    public static List<Rule> parse(String content) {
        List<Rule> rules = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : content.split("\n", -1)) {
            String trimmed = TRIM.matcher(line).replaceAll("");
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] w = WS.split(trimmed);
            if (w.length < 2 || !IP.matcher(w[0]).matches() || !HOST.matcher(w[1]).matches()) continue;
            if (!seen.add(w[1].toLowerCase(Locale.ROOT))) continue;
            rules.add(new Rule(w[1], w[0]));
        }
        return rules;
    }

    /**
     * Every rule of {@code contents} (the selected profiles in merge order), placeholders filled in;
     * the first rule for a host (host names compare case-insensitively) is active, later ones are
     * shown struck through.
     */
    public static List<Entry> merge(List<String> contents) {
        Set<String> seen = new HashSet<>();
        List<Entry> out = new ArrayList<>();
        for (String c : contents) {
            for (Rule r : parse(resolvePlaceholders(c))) {
                out.add(new Entry(r.host(), r.ip(), seen.add(r.host().toLowerCase(Locale.ROOT))));
            }
        }
        return out;
    }

    /** The active rules of {@link #merge}: one per host, the first profile winning. */
    public static List<Rule> active(List<String> contents) {
        return merge(contents).stream().filter(Entry::active).map(e -> new Rule(e.host(), e.ip())).toList();
    }
}
