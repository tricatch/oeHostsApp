package tricatch.oehubapp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceTest {

    @TempDir
    Path dir;

    @Test
    void secondStartIsRefusedAndAsksTheFirstForFocus() throws Exception {
        try (SingleInstance first = SingleInstance.acquire(dir)) {
            assertNotNull(first);
            CountDownLatch focused = new CountDownLatch(1);
            first.watchFocusRequests(focused::countDown);

            assertNull(SingleInstance.acquire(dir));
            assertTrue(focused.await(10, TimeUnit.SECONDS), "the running instance was not asked to show itself");
        }
    }

    @Test
    void lockIsFreeAgainAfterClose() throws Exception {
        SingleInstance first = SingleInstance.acquire(dir);
        assertNotNull(first);
        first.close();
        try (SingleInstance again = SingleInstance.acquire(dir)) {
            assertNotNull(again);
        }
    }

    @Test
    void otherDataDirectoriesAreIndependent(@TempDir Path other) throws Exception {
        try (SingleInstance a = SingleInstance.acquire(dir); SingleInstance b = SingleInstance.acquire(other)) {
            assertNotNull(a);
            assertNotNull(b);
        }
    }
}
