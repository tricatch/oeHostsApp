package tricatch.oehostsapp.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SortByNameTest {

    @Test
    void headersByNameCaseInsensitiveAndStable() {
        assertEquals(List.of("accept: */*", "Cache-Control: no-cache", "Set-Cookie: b=2", "Set-Cookie: a=1", "user-agent: x"),
                MonitorTab.byName(List.of("user-agent: x", "Set-Cookie: b=2", "Cache-Control: no-cache", "accept: */*",
                        "Set-Cookie: a=1")));
    }

    @Test
    void valuesStartInOneColumn() {
        assertEquals(List.of("Host          : a.test", "Cache-Control : no-cache", "X             : 1", "no colon"),
                MonitorTab.alignAfterName(List.of("Host: a.test", "Cache-Control: no-cache", "X:1", "no colon")));
    }

    @Test
    void cookiesByNameUpToTheEqualsSign() {
        assertEquals(List.of("a=9", "B=1; Path=/", "token=x:y"),
                MonitorTab.byName(List.of("token=x:y", "B=1; Path=/", "a=9")));
    }
}
