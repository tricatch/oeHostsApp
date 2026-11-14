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

    @Test
    void problemsFlagLinesNotStartingWithAnIp() {
        String ok = """
                # comment

                1.2.3.4 a.com
                  10.0.0.1\tb.com c.com # x
                ::1 v6.com
                fe80::1 *.w.com
                ${PROXY_SVR} p.com
                ${LOCAL_SVR} l.com
                """;
        assertEquals(List.of(), HostsRules.problems(ok));
        assertEquals(List.of(), HostsRules.problems(""));

        List<HostsRules.Problem> p = HostsRules.problems("""
                a.com 1.2.3.4
                1.2.3.4 a.com
                256.1.1.1 b.com
                1.2.3 c.com
                cafe.be d.com
                1::2::3 e.com
                1.2.3.4
                1.2.3.4 "x"
                1.2.3.4 # only a comment
                """);
        assertEquals(List.of(
                new HostsRules.Problem(0, HostsRules.PROBLEM_IP),
                new HostsRules.Problem(2, HostsRules.PROBLEM_IP),
                new HostsRules.Problem(3, HostsRules.PROBLEM_IP),
                new HostsRules.Problem(4, HostsRules.PROBLEM_IP),
                new HostsRules.Problem(5, HostsRules.PROBLEM_IP),
                new HostsRules.Problem(6, HostsRules.PROBLEM_HOST),
                new HostsRules.Problem(7, HostsRules.PROBLEM_HOST),
                new HostsRules.Problem(8, HostsRules.PROBLEM_HOST)), p);
    }

    @Test
    void bundledExamplesAreValid() throws Exception {
        for (String lang : List.of("en", "ko")) {
            try (var in = HostsRules.class.getResourceAsStream("/example/hosts_example_" + lang + ".txt")) {
                assertEquals(List.of(), HostsRules.problems(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }
}
