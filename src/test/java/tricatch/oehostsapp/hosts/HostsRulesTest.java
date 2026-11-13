package tricatch.oehostsapp.hosts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HostsRulesTest {

    @Test
    void parsesAndDropsInvalidLines() {
        List<HostsRules.Rule> rules = HostsRules.parse("# c\n1.2.3.4 a.com\n\n5.6.7.8 A.COM\nbad\n1.1.1.1 \"x\"\n${PROXY_SVR} *.w.com extra\n");
        assertEquals(List.of(new HostsRules.Rule("a.com", "1.2.3.4"), new HostsRules.Rule("*.w.com", "${PROXY_SVR}")), rules);
    }

    @Test
    void mergeKeepsFirstProfileAndSubstitutes() {
        List<String> contents = List.of("1.1.1.1 a.com\n${PROXY_SVR} b.com", "2.2.2.2 A.com\n3.3.3.3 c.com");
        assertEquals(List.of("1.1.1.1 a.com", "127.0.0.1 b.com", "3.3.3.3 c.com"),
                HostsRules.active(contents).stream().map(r -> r.ip() + " " + r.host()).toList());
        // The later A.com is kept, struck through, for the preview.
        List<HostsRules.Entry> all = HostsRules.merge(contents);
        assertEquals(4, all.size());
        assertFalse(all.get(2).active());
    }
}
