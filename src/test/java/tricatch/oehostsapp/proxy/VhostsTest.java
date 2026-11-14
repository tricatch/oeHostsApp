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
        Vhosts.Problem noPath = Vhosts.problems("virtual:\n  - domain: a\n    location:\n      - host: http://h\n").get(0);
        assertEquals("virtual[0].location[0]", noPath.path());
        assertEquals("proxy.validate.path.required", noPath.key());
        Vhosts.Problem syntax = Vhosts.problems("virtual:\n  - domain: [\n").get(0);
        assertNull(syntax.key());
        assertNotNull(syntax.detail());
        assertEquals(2, syntax.line()); // where the parser gave up: the end of the text
    }

    @Test
    void marksTheLineOfEachProblem() {
        String bad = """
                virtual:
                  - domain: https://a.test
                    location:
                      - host: http://127.0.0.1:8080/app
                        path:
                          - /ok
                          - api/**
                        header:
                          - "X-Ok: 1"
                          - "--X-Gone"
                          - "no colon"
                        hedaer: []
                  - domain: b.test
                    location: []
                  - domain: B.test
                    domain: c.test
                    location:
                      - host: ftp://b
                        path: [/]
                """;
        List<Vhosts.Problem> problems = Vhosts.problems(bad);
        assertEquals(List.of(
                "1 virtual[0] app.proxy.invalid.domain",
                "11 virtual[0].location[0] app.proxy.invalid.key",
                "3 virtual[0].location[0] app.proxy.invalid.host",
                "6 virtual[0].location[0] app.proxy.invalid.path",
                "10 virtual[0].location[0] app.proxy.invalid.header",
                "13 virtual[1] proxy.validate.location.required",
                "15 virtual[2] app.proxy.duplicate.key",
                "14 virtual[2] app.proxy.duplicate.domain",
                "17 virtual[2].location[0] app.proxy.invalid.host"),
                problems.stream().map(p -> p.line() + " " + p.path() + " " + p.key()).toList());
        assertEquals("hedaer", problems.get(1).detail());
    }

    @Test
    void acceptsOriginsAndTheBundledExamples() throws Exception {
        assertTrue(Vhosts.isOrigin("http://127.0.0.1:8080"));
        assertTrue(Vhosts.isOrigin("https://stg.backend.foo.oe/"));
        assertTrue(Vhosts.isOrigin("http://[::1]:8080"));
        assertFalse(Vhosts.isOrigin("127.0.0.1:8080"));
        assertFalse(Vhosts.isOrigin("http://a/b"));
        assertFalse(Vhosts.isOrigin("http://a?x=1"));
        for (String d : List.of("foo.oe", "localhost", "dev-1.foo.oe", "1.foo.oe", "a.b2")) {
            assertTrue(Vhosts.problems(vhost(d)).isEmpty(), d);
        }
        for (String d : List.of("127.45245.2452", "127.0.0.1", "123", "foo.oe:443", "https://foo.oe", "-a.oe", "a..oe")) {
            assertEquals("app.proxy.invalid.domain", Vhosts.problems(vhost(d)).get(0).key(), d);
        }
        for (String lang : List.of("en", "ko")) {
            try (var in = Vhosts.class.getResourceAsStream("/example/vhost_example_" + lang + ".yaml")) {
                assertEquals(List.of(), Vhosts.problems(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
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

    private static String vhost(String domain) {
        return "virtual:\n  - domain: \"" + domain + "\"\n    location:\n      - host: http://127.0.0.1:1\n        path: [/]\n";
    }
}
