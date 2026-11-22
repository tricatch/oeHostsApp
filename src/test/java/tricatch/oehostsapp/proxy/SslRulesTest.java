package tricatch.oehostsapp.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SslRulesTest {

    @Test
    void anExactNameIsOnlyThatName() {
        SslRules r = SslRules.parse("api.example.com\n");
        assertTrue(r.matches("api.example.com"));
        assertTrue(r.matches("API.Example.com."));
        assertFalse(r.matches("x.api.example.com"));
        assertFalse(r.matches("example.com"));
    }

    @Test
    void aWildcardIsEveryLevelBelowButNotTheNameItself() {
        SslRules r = SslRules.parse("*.example.com");
        assertTrue(r.matches("a.example.com"));
        assertTrue(r.matches("a.b.c.example.com"));
        assertFalse(r.matches("example.com"));
        assertFalse(r.matches("notexample.com"));
    }

    @Test
    void aStarIsEveryDomain() {
        SslRules r = SslRules.parse("*");
        assertTrue(r.matches("www.example.org"));
        assertTrue(r.matches("intranet"));
    }

    @Test
    void anExclusionWinsWhereverItStands() {
        for (String text : List.of("!auth.example.com\n*.example.com", "*.example.com\n!auth.example.com")) {
            SslRules r = SslRules.parse(text);
            assertFalse(r.matches("auth.example.com"), text);
            assertTrue(r.matches("api.example.com"), text);
        }
        SslRules below = SslRules.parse("*\n!*.bank.example");
        assertFalse(below.matches("www.bank.example"));
        assertTrue(below.matches("bank.example"));
    }

    @Test
    void whatCannotBeASniIsNeverMatched_evenByAStar() {
        SslRules r = SslRules.parse("*");
        for (String host : List.of("127.0.0.1", "10.0.0.5", "::1", "[::1]", "my_service.test", "localhost", "a.localhost",
                "", "-a.test", "K.test", "a".repeat(64) + ".test", "a b.test")) {
            assertFalse(r.matches(host), host);
        }
        assertFalse(r.matches(null));
    }

    @Test
    void blankLinesCommentsCaseAndUnicodeNamesAreUnderstood() {
        SslRules r = SslRules.parse("# comment\n\n  API.Example.COM  \r\n한글.kr\n");
        assertTrue(r.matches("api.example.com"));
        assertTrue(r.matches(java.net.IDN.toASCII("한글.kr")));
        assertTrue(SslRules.problems("# c\n\n  \nok.test\r\n한글.kr").isEmpty());
    }

    @Test
    void badLinesAreReportedByTheirNumber() {
        String text = String.join("\n", "ok.test", "https://a.test", "a.test:443", "a.test/path", "10.0.0.1",
                "my_service.test", "a.*.test", "*.", "!", "!*", "a.test # trailing", "localhost");
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), SslRules.problems(text));
    }

    @Test
    void addingADomainKeepsWhatWasTyped() {
        assertEquals("a.test\n", SslRules.withDomain("", "a.test"));
        assertEquals("# mine\nb.test\na.test\n", SslRules.withDomain("# mine\nb.test", "a.test"));
        assertEquals("b.test\na.test\n", SslRules.withDomain("b.test\n", "a.test"));
        assertEquals("*.test\n", SslRules.withDomain("*.test\n", "a.test"), "already selected");
    }

    @Test
    void theSameListWrittenDifferentlyIsEqual() {
        assertEquals(SslRules.parse("b.test\na.test"), SslRules.parse("# c\nA.test\nb.test\n"));
        assertFalse(SslRules.parse("a.test").equals(SslRules.parse("a.test\n!b.test")));
        assertFalse(SslRules.parse("!only.exclusions.test").selectsAny());
        assertTrue(SslRules.parse("*").selectsAny());
    }
}
