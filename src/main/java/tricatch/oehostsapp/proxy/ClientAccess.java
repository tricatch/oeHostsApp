package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.Listeners;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Who may use the proxy service. It listens on every network interface, so instead of a binding
 * or a password the user decides per device: a client on this PC (loopback or one of this PC's own
 * addresses) is always let in; any other address is asked about once, on screen, with its IP and
 * User-Agent ({@link #setAsker}), and the answer holds for that address until the app quits.
 * Requests that arrive before the answer, and every request after a refusal, are refused.
 */
public final class ClientAccess {

    public enum Decision { ALLOWED, DENIED, PENDING }

    /** A device asking to connect: its address and the User-Agent of its first request (may be null). */
    public record Request(String ip, String userAgent) {}

    /** A decision taken on screen, in the order the devices asked. */
    public record Entry(String ip, String userAgent, boolean allowed) {}

    private static final Listeners listeners = new Listeners();
    private static final Map<String, Entry> decided = new LinkedHashMap<>();
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();
    /** Addresses already found to be this PC's own (or not), so the interfaces are not listed per request. */
    private static final Map<InetAddress, Boolean> own = new ConcurrentHashMap<>();
    private static volatile Consumer<Request> asker;

    private ClientAccess() {}

    /** Who shows the question (the main window); called on the proxy's thread. */
    public static void setAsker(Consumer<Request> a) {
        asker = a;
    }

    /** Runs {@code r} whenever a decision is taken or the list is cleared. */
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

    /** The user's answer for {@code ip}. */
    public static void decide(Request r, boolean allow) {
        synchronized (decided) {
            decided.put(r.ip(), new Entry(r.ip(), r.userAgent(), allow));
        }
        pending.remove(r.ip());
        listeners.fire();
    }

    /** The decisions taken so far, oldest first. */
    public static java.util.List<Entry> decisions() {
        synchronized (decided) {
            return java.util.List.copyOf(decided.values());
        }
    }

    /** Forgets every decision: each device is asked again on its next request. */
    public static void clear() {
        synchronized (decided) {
            decided.clear();
        }
        pending.clear();
        own.clear();
        listeners.fire();
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
