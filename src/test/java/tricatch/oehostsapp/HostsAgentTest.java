package tricatch.oehostsapp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostsAgentTest {

    @Test
    void optionQuotesOnlyPathsWithSpaces() {
        String url = "http://127.0.0.1:36981/hosts/ABC-def/dev";
        assertEquals("-javaagent:" + Path.of("/opt/oe/oehosts-agent.jar") + "=" + url,
                HostsAgent.option(Path.of("/opt/oe/oehosts-agent.jar"), url));
        assertEquals("\"-javaagent:" + Path.of("/opt/my apps/oehosts-agent.jar") + "=" + url + "\"",
                HostsAgent.option(Path.of("/opt/my apps/oehosts-agent.jar"), url));
    }
}
