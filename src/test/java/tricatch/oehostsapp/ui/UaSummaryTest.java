package tricatch.oehostsapp.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UaSummaryTest {

    @Test
    void phones() {
        assertEquals("SM-S918N (Android 14) · Chrome", UaSummary.of(
                "Mozilla/5.0 (Linux; Android 14; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"));
        assertEquals("SM-G991N (Android 13) · Samsung Internet", UaSummary.of(
                "Mozilla/5.0 (Linux; Android 13; SM-G991N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/23.0 Chrome/115.0.0.0 Mobile Safari/537.36"));
        // Chrome's reduced User-Agent hides the model behind "K".
        assertEquals("Android 10 · Chrome", UaSummary.of(
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"));
        assertEquals("iPhone (iOS 18.1) · Safari", UaSummary.of(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"));
        assertEquals("iPad (iOS 17.5) · Chrome", UaSummary.of(
                "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0 Mobile/15E148 Safari/604.1"));
    }

    @Test
    void desktops() {
        assertEquals("Mac · Firefox", UaSummary.of(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.5; rv:132.0) Gecko/20100101 Firefox/132.0"));
        assertEquals("Windows · Edge", UaSummary.of(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"));
    }

    @Test
    void toolsAndUnknown() {
        assertEquals("curl 8.7.1", UaSummary.of("curl/8.7.1"));
        assertEquals("Java-http-client 21.0.5", UaSummary.of("Java-http-client/21.0.5"));
        assertEquals("-", UaSummary.of(null));
        assertEquals("-", UaSummary.of(" "));
    }
}
