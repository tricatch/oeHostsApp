package tricatch.oehostsapp.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestArrangeTest {

    @AfterEach
    void reset() {
        MonitorTab.sortByName = false;
        MonitorTab.alignValues = false;
    }

    @Test
    void formAsSentUnlessSwitchedOn() {
        assertEquals("q = a b\nlang = ko\n", MonitorTab.form("q=a+b&lang=ko"));
        MonitorTab.sortByName = true;
        assertEquals("lang = ko\nq = a b\n", MonitorTab.form("q=a+b&lang=ko"));
        MonitorTab.alignValues = true;
        assertEquals("lang = ko\nq    = a b\n", MonitorTab.form("q=a+b&lang=ko"));
    }

    @Test
    void jsonKeysSortedAtEveryDepth() throws Exception {
        JsonNode n = new ObjectMapper().readTree("{\"b\":1,\"A\":{\"z\":1,\"c\":2},\"list\":[{\"y\":1,\"x\":2}]}");
        MonitorTab.sortKeys(n);
        assertEquals("{\"A\":{\"c\":2,\"z\":1},\"b\":1,\"list\":[{\"x\":2,\"y\":1}]}", n.toString());
    }

    @Test
    void jsonColonsLinedUpPerObject() {
        String in = String.join("\n",
                "{",
                "  \"id\" : 7,",
                "  \"profile\" : {",
                "    \"name\" : \"Kim\",",
                "    \"x\" : 1",
                "  },",
                "  \"ok\" : true",
                "}");
        String out = String.join("\n",
                "{",
                "  \"id\"      : 7,",
                "  \"profile\" : {",
                "    \"name\" : \"Kim\",",
                "    \"x\"    : 1",
                "  },",
                "  \"ok\"      : true",
                "}");
        assertEquals(out, MonitorTab.alignJsonKeys(in));
    }
}
