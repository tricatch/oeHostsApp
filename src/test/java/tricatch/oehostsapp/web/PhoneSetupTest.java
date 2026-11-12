package tricatch.oehostsapp.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhoneSetupTest {

    @Test
    void picksThePhonePageLanguage() {
        assertEquals("ko", PhoneSetup.lang("ko-KR,ko;q=0.9,en;q=0.8"));
        assertEquals("en", PhoneSetup.lang("en-US,ko;q=0.5"));
        assertEquals("en", PhoneSetup.lang("ja-JP"));
        assertEquals("en", PhoneSetup.lang(null));
        assertTrue(PhoneSetup.looksVirtual("vEthernet (WSL)"));
        assertFalse(PhoneSetup.looksVirtual("Intel(R) Wi-Fi 6E AX211 160MHz"));
        assertTrue(PhoneSetup.isOwnAddress("127.0.0.1"));
        assertTrue(PhoneSetup.isOwnAddress("localhost"));
        assertFalse(PhoneSetup.isOwnAddress("203.0.113.7"));
        assertFalse(PhoneSetup.isOwnAddress("example.com"));
    }
}
