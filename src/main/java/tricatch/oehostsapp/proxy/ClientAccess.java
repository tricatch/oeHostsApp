package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.store.Settings;
import tricatch.oehostsapp.store.Store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Who may use the proxy service. It listens on every network interface, so instead of a binding
 * or a password the user decides per device: a client on this PC (loopback or one of this PC's own
 * addresses) is always let in; any other address is asked about once, on screen, with its IP and
 * User-Agent ({@link #setAsker}). The answer is saved in the data file ({@link #attach}) and holds
 * for that address until it is removed from the list ({@link #forget}, {@link #clear}).
 * Requests that arrive before the answer, and every request after a refusal, are refused.
 */
public final class ClientAccess {

    public enum Decision { ALLOWED, DENIED, PENDING }

    /** A device asking to connect: its address and the User-Agent of its first request (may be null). */
    public record Request(String ip, String userAgent) {}

    /** A decision taken on screen, in the order the devices asked. */
    public record Entry(String ip, String userAgent, boolean allowed) {}

    private static final Logger log = LoggerFactory.getLogger(ClientAccess.class);
    private static final Listeners listeners = new Listeners();
    private static final Map<String, Entry> decided = new LinkedHashMap<>();
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();
    /** Addresses already found to be this PC's own (or not), so the interfaces are not listed per request. */
    private static final Map<InetAddress, Boolean> own = new ConcurrentHashMap<>();
    private static volatile Consumer<Request> asker;
    /** Where the answers are kept; null (tests, before start) keeps them in memory only. */
    private static volatile Store store;

    private ClientAccess() {}

    /**
     * Uses {@code s} for the saved answers: replaces the ones in memory with those in its settings,
     * and saves there from now on. Null detaches (answers stay in memory only).
     */
    public static void attach(Store s) {
        store = s;
        if (s == null) return;
        List<Settings.Device> saved;
        synchronized (s) {
            saved = List.copyOf(s.data.settings.fwdproxy.devices);
        }
        synchronized (decided) {
            decided.clear();
            for (Settings.Device d : saved) decided.put(d.ip, new Entry(d.ip, d.userAgent, d.allowed));
        }
        listeners.fire();
    }

    /** Who shows the question (the main window); called on the proxy's thread. */
    public static void setAsker(Consumer<Request> a) {
        asker = a;
    }

    /** Runs {@code r} whenever a decision is taken, removed or the list is cleared. */
    public static Listeners.Subscription onChange(Runnable r) {
        return listeners.add(r);
    }

    /**
     * Whether a request from {@code client} may go on. A first request from an unknown device asks
     * the user (once; later requests wait for that answer) and is refused meanwhile.
     */
    public static Decision check(InetAddress client, String userAgent) {
        if (client == null || isThisPc(client)) return Decision.ALLOWED;
        String ip = client.getHostAddress();
        synchronized (decided) {
            Entry e = decided.get(ip);
            if (e != null) return e.allowed() ? Decision.ALLOWED : Decision.DENIED;
        }
        if (pending.add(ip)) {
            Consumer<Request> a = asker;
            if (a != null) a.accept(new Request(ip, userAgent));
        }
        return Decision.PENDING;
    }

    /** The user's answer for {@code ip}; saved. */
    public static void decide(Request r, boolean allow) {
        synchronized (decided) {
            decided.put(r.ip(), new Entry(r.ip(), r.userAgent(), allow));
        }
        pending.remove(r.ip());
        persist();
        listeners.fire();
    }

    /** The decisions taken so far, oldest first. */
    public static List<Entry> decisions() {
        synchronized (decided) {
            return List.copyOf(decided.values());
        }
    }

    /** Removes the answer for {@code ip}: that device is asked again on its next request. */
    public static void forget(String ip) {
        synchronized (decided) {
            decided.remove(ip);
        }
        pending.remove(ip);
        persist();
        listeners.fire();
    }

    /** Forgets every decision: each device is asked again on its next request. */
    public static void clear() {
        synchronized (decided) {
            decided.clear();
        }
        pending.clear();
        own.clear();
        persist();
        listeners.fire();
    }

    /** Writes the answers to the data file; a failed save is logged and the answers stay in memory. */
    private static void persist() {
        Store s = store;
        if (s == null) return;
        List<Settings.Device> list = new ArrayList<>();
        for (Entry e : decisions()) list.add(new Settings.Device(e.ip(), e.userAgent(), e.allowed()));
        try {
            synchronized (s) {
                s.data.settings.fwdproxy.devices = list;
                s.save();
            }
        } catch (Store.StoreException e) {
            log.warn("could not save the device answers: {}", e.getMessage());
        }
    }

    /** Loopback, or an address of one of this PC's network interfaces. */
    static boolean isThisPc(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress()) return true;
        return own.computeIfAbsent(a, x -> {
            try {
                return NetworkInterface.getByInetAddress(x) != null;
            } catch (SocketException e) {
                return false;
            }
        });
    }
}
