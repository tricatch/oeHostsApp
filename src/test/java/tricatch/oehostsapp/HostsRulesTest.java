package tricatch.oehostsapp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HostsRulesTest {

    @Test
    void parsesAndDropsInvalidLines() {
        List<HostsRules.Rule> rules = HostsRules.parse("# c\n1.2.3.4 a.com\n\n5.6.7.8 a.com\nbad\n1.1.1.1 \"x\"\n${PROXY_SVR} *.w.com extra\n");
        assertEquals(List.of(new HostsRules.Rule("a.com", "1.2.3.4"), new HostsRules.Rule("*.w.com", "${PROXY_SVR}")), rules);
    }

    @Test
    void mergeKeepsFirstProfileAndSubstitutes() {
        List<HostsRules.Rule> rules = HostsRules.mergeActive(List.of("1.1.1.1 a.com\n${PROXY_SVR} b.com", "2.2.2.2 a.com\n3.3.3.3 c.com"), "9.9.9.9");
        assertEquals(List.of("1.1.1.1 a.com", "9.9.9.9 b.com", "3.3.3.3 c.com"),
                rules.stream().map(r -> r.ip() + " " + r.host()).toList());
    }
}
