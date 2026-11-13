package tricatch.oehostsapp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ListenersTest {

    @Test
    void aClosedSubscriptionNoLongerRuns() {
        Listeners l = new Listeners();
        AtomicInteger a = new AtomicInteger(), b = new AtomicInteger();
        Listeners.Subscription sa = l.add(a::incrementAndGet);
        l.add(b::incrementAndGet);
        l.fire();
        sa.close();
        sa.close();
        l.fire();
        assertEquals(1, a.get());
        assertEquals(2, b.get());
        assertEquals(1, l.size());
    }
}
