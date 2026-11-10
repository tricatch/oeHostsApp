package tricatch.oehubapp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hosts profile parsing and merging, server side. Mirrors oeHub's {@code parseHostsToMap}
 * (static/js/util.js) and the page's {@code mergeSelectedProfiles}: the page does its own merge
 * for the Chrome launch, this one feeds the JVM agent endpoint ({@code GET /oeHosts}), so both
 * must resolve a profile to the same rules.
 */
public final class HostsRules {

    public static final String PROXY_SVR_PLACEHOLDER = "${PROXY_SVR}";
    public static final String LOCAL_SVR_PLACEHOLDER = "${LOCAL_SVR}";
    /** What both placeholders mean in the desktop app: the proxy, Chrome and the backend share this PC. */
    public static final String THIS_PC = "127.0.0.1";

    /**
     * Fills in oeHub's {@code ${PROXY_SVR}} and {@code ${LOCAL_SVR}}, which existing data and oeHub
     * imports may still contain. oeHub needed them because its server and each developer's PC had
     * different addresses; here whatever connects (Chrome, or this PC's proxy on behalf of a phone)
     * runs on this PC, so both are always {@link #THIS_PC}.
     */
    public static String resolvePlaceholders(String s) {
        return s.replace(PROXY_SVR_PLACEHOLDER, THIS_PC).replace(LOCAL_SVR_PLACEHOLDER, THIS_PC);
    }

    /** {@code HOSTS_RULE_HOST} / {@code HOSTS_RULE_IP} in util.js. */
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9_.*-]+$");
    private static final Pattern IP = Pattern.compile("^(?:\\$\\{PROXY_SVR\\}|[0-9A-Fa-f:.]+)$");
    private static final Pattern WS = Pattern.compile("[\\s\\uFEFF]+");

    public record Rule(String host, String ip) {}

    private HostsRules() {}

    /** {@code IP host} lines; the first line for a host wins, invalid lines are dropped. */
    public static List<Rule> parse(String content) {
        List<Rule> rules = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.replaceAll("^[\\s\\uFEFF]+|[\\s\\uFEFF]+$", "");
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] w = WS.split(trimmed);
            if (w.length < 2 || seen.contains(w[1])) continue;
            if (!IP.matcher(w[0]).matches() || !HOST.matcher(w[1]).matches()) continue;
            seen.add(w[1]);
            rules.add(new Rule(w[1], w[0]));
        }
        return rules;
    }

    /** Merges contents in selection order with {@code ${PROXY_SVR}} substituted; earlier wins. */
    public static List<Rule> mergeActive(List<String> contents, String proxySvr) {
        Set<String> seen = new HashSet<>();
        List<Rule> out = new ArrayList<>();
        for (String c : contents) {
            for (Rule r : parse(c.replace(PROXY_SVR_PLACEHOLDER, proxySvr))) {
                if (seen.add(r.host())) out.add(r);
            }
        }
        return out;
    }
}
