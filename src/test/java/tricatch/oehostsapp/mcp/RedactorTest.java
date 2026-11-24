package tricatch.oehostsapp.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedactorTest {

    private final Redactor r = new Redactor(new byte[32]);

    @Test
    void tagsAreStableKeyedAndShort() {
        String t = r.tag("admin");
        assertTrue(t.matches("hmac:[0-9a-f]{12}"), t);
        assertEquals(t, r.tag("admin"));
        assertNotEquals(t, r.tag("admin2"));
        // Another key, another tag: a short value cannot be found by hashing candidates.
        assertNotEquals(t, new Redactor(new byte[]{1, 2, 3}).tag("admin"));
        assertEquals("", r.tag(""));
    }

    @Test
    void namesThatLookSecret() {
        for (String n : List.of("access_token", "X-Api-Key", "password", "SESSIONID", "sid", "code", "X-CSRF-Token",
                "client_secret", "Authorization", "jwt")) {
            assertTrue(Redactor.sensitiveName(n), n);
        }
        for (String n : List.of("Content-Type", "page", "user", "Accept", "q", "lang")) {
            assertFalse(Redactor.sensitiveName(n), n);
        }
    }

    @Test
    void headersHideCookiesCredentialsAndSecretNames() {
        List<String> out = r.headers(List.of(
                "GET /api/items?page=2&access_token=abc123 HTTP/1.1",
                "Host: api.example.test",
                "Cookie: SESSION=s3cr3t; lang=ko",
                "Authorization: Bearer abc.def",
                "X-Api-Key: k-123",
                "Referer: https://app.example.test/cb?code=xyz&state=1",
                "Accept: application/json"));
        assertEquals("GET /api/items?page=2&access_token=" + r.tag("abc123") + " HTTP/1.1", out.get(0));
        assertEquals("Host: api.example.test", out.get(1));
        assertEquals("Cookie: SESSION=" + r.tag("s3cr3t") + "; lang=" + r.tag("ko"), out.get(2));
        assertEquals("Authorization: Bearer " + r.tag("abc.def"), out.get(3));
        assertEquals("X-Api-Key: " + r.tag("k-123"), out.get(4));
        assertEquals("Referer: https://app.example.test/cb?code=" + r.tag("xyz") + "&state=1", out.get(5));
        assertEquals("Accept: application/json", out.get(6));

        assertEquals("HTTP/1.1 200 OK", r.headers(List.of("HTTP/1.1 200 OK")).get(0));
        assertEquals("Set-Cookie: SESSION=" + r.tag("s3cr3t") + "; Path=/; HttpOnly",
                r.header("Set-Cookie: SESSION=s3cr3t; Path=/; HttpOnly"));
    }

    @Test
    void bodiesHideSecretFieldsAndJwts() {
        assertEquals("user=kim&password=" + r.tag("p@ss"), r.body("user=kim&password=p%40ss", "application/x-www-form-urlencoded"));

        String json = r.body("{\"user\":\"kim\",\"password\":\"pw\",\"auth\":{\"id\":7,\"note\":\"x\"},\"n\":3,"
                + "\"items\":[{\"refresh_token\":\"rt\"}],\"none\":null}", "application/json");
        assertTrue(json.contains("\"user\" : \"kim\""), json);
        assertTrue(json.contains(r.tag("pw")), json);
        assertTrue(json.contains("\"id\" : \"" + r.tag("7") + "\""), json); // everything under a secret member
        assertTrue(json.contains(r.tag("rt")), json);
        assertTrue(json.contains("\"n\" : 3") && json.contains("\"none\" : null"), json);
        assertFalse(json.contains("\"pw\"") || json.contains("\"rt\""), json);

        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl";
        assertEquals("token is " + r.tag(jwt) + "!", r.body("token is " + jwt + "!", "text/plain"));
        assertEquals("not json {", r.body("not json {", "application/json"));
    }
}
