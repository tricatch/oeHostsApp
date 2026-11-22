package tricatch.oehostsapp.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecryptedRequestsTest {

    @Test
    void aTunnelWithARequestInsideIsRepresentedByThatRequest() {
        DecryptedRequests r = new DecryptedRequests();
        r.saw("App.Example.test:443", 1_200);
        assertFalse(r.leftNoRequest("app.example.test", 1_000, 500));
        assertFalse(r.leftNoRequest("app.example.test.", 1_200, 500));
    }

    @Test
    void aTunnelWithoutOneIsShown_anAppThatRefusedTheCertificateForExample() {
        DecryptedRequests r = new DecryptedRequests();
        r.saw("other.example.test", 1_200);
        r.saw("app.example.test", 900); // an earlier tunnel's
        assertTrue(r.leftNoRequest("app.example.test", 1_000, 500));
    }

    @Test
    void aTunnelThatCarriedNothingIsNeverShown() {
        assertFalse(new DecryptedRequests().leftNoRequest("app.example.test", 1_000, 0));
    }

    @Test
    void eventsOutOfOrderKeepTheLatestRequest() {
        DecryptedRequests r = new DecryptedRequests();
        r.saw("app.example.test", 2_000);
        r.saw("app.example.test", 900);
        assertFalse(r.leftNoRequest("app.example.test", 1_000, 500));
    }
}
