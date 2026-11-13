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
    }
}
