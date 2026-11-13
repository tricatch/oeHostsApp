package tricatch.oehostsapp.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VhostsTest {

    private static final String A = "virtual:\n  - domain: a.test\n    location:\n      - host: http://${LOCAL_SVR}:8080\n        path: [/]\n";
    private static final String A2 = "virtual:\n  - domain: a.test\n    location:\n      - host: http://other:1\n        path: [/]\n";
    private static final String B = "virtual:\n  - domain: b.test\n    location:\n      - host: http://b:2\n        path: [/api]\n";

    @Test
    void checksTheSchema() {
        assertTrue(Vhosts.problems(A).isEmpty());
        assertTrue(Vhosts.problems("  ").isEmpty());
        assertEquals("proxy.validate.virtual.required", Vhosts.problems("x: 1").get(0).key());
        Vhosts.Problem noPath = Vhosts.problems("virtual:\n  - domain: a\n    location:\n      - host: h\n").get(0);
        assertEquals("virtual[0].location[0]", noPath.path());
        assertEquals("proxy.validate.path.required", noPath.key());
        Vhosts.Problem syntax = Vhosts.problems("virtual:\n  - domain: [\n").get(0);
        assertNull(syntax.key());
        assertNotNull(syntax.detail());
    }

    @Test
    void mergesFirstDomainWinsAndFillsPlaceholdersWhenServed() {
        String merged = Vhosts.merge(List.of(A, "not: [valid", A2, B));
        assertTrue(merged.contains("${LOCAL_SVR}"), merged);
        assertFalse(merged.contains("other"), merged);
        assertTrue(merged.contains("b.test"), merged);
        assertTrue(Vhosts.served(List.of(A)).contains("http://127.0.0.1:8080"));
        assertEquals("", Vhosts.merge(List.of()));
    }
}
