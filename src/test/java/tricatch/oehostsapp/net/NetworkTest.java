package tricatch.oehostsapp.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkTest {

    @Test
    void tellsVirtualAdaptersAndOwnAddresses() {
        assertTrue(Network.looksVirtual("vEthernet (WSL)"));
        assertFalse(Network.looksVirtual("Intel(R) Wi-Fi 6E AX211 160MHz"));
        assertTrue(Network.isOwnAddress("127.0.0.1"));
        assertTrue(Network.isOwnAddress("localhost"));
        assertFalse(Network.isOwnAddress("203.0.113.7"));
        assertFalse(Network.isOwnAddress("example.com"));
    }

    @Test
    void bindingsAnswerOnTheirAddresses() {
        assertFalse(Network.isLan(Network.LOOPBACK));
        assertTrue(Network.isLan(Network.ALL));
        assertTrue(Network.answersOn(Network.ALL, "192.168.0.10"));
        assertTrue(Network.answersOn("192.168.0.10", "192.168.0.10"));
        assertFalse(Network.answersOn("192.168.0.10", "10.0.0.1"));
        for (Network.Lan l : Network.addressesFor(Network.LOOPBACK)) fail("127.0.0.1 answers on no card: " + l);
    }
}
