package tricatch.oehostsapp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Change listeners of an app-wide service (proxy, web service, update check...). Adding one returns
 * a {@link Subscription} that removes it again, so a screen that is rebuilt (language change, data
 * import) can drop its listeners instead of leaving them, and the old screen with them, behind.
 */
public final class Listeners {

    /** Removes the listener it was returned for; closing twice does nothing. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final List<Runnable> list = new CopyOnWriteArrayList<>();

    /** Adds {@code r}; it runs on the thread that calls {@link #fire}. */
    public Subscription add(Runnable r) {
        list.add(r);
        return () -> list.remove(r);
    }

    public void fire() {
        for (Runnable r : list) r.run();
    }

    /** How many listeners are registered (tests). */
    int size() {
        return list.size();
    }
}
