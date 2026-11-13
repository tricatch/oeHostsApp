package tricatch.oehostsapp.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * This PC's network addresses and the binding values the servers share (forward proxy, reverse
 * proxy, web service): {@link #LOOPBACK} (this PC only), one of this PC's IPv4 addresses (one
 * network card), or {@link #ALL} (every interface).
 */
public final class Network {

    /** Bind address: this PC only. */
    public static final String LOOPBACK = "127.0.0.1";
    /** Bind address: every network interface. */
    public static final String ALL = "0.0.0.0";

    private static final Logger log = LoggerFactory.getLogger(Network.class);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private Network() {}

    /** An IPv4 address of this PC and the network interface it belongs to. */
    public record Lan(String ip, String iface, boolean virtual) {}

    /** Whether a binding reaches beyond this PC. */
    public static boolean isLan(String bind) {
        return !LOOPBACK.equals(bind);
    }

    /** Whether a server bound to {@code bind} answers on {@code ip}. */
    public static boolean answersOn(String bind, String ip) {
        return ALL.equals(bind) || bind.equals(ip);
    }

    /**
     * The IPv4 addresses a phone on the same network may reach: interfaces that are up, not loopback
     * and not link-local. Private (site-local) addresses of real adapters come first; adapters that
     * look virtual (Hyper-V, WSL, Docker, VMware, VirtualBox, VPNs) go last.
     */
    public static List<Lan> addresses() {
        List<Lan> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getDisplayName() == null ? ni.getName() : ni.getDisplayName();
                boolean virtual = ni.isVirtual() || looksVirtual(name) || looksVirtual(ni.getName());
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        out.add(new Lan(a.getHostAddress(), name, virtual || !a.isSiteLocalAddress()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("network interfaces: {}", e.getMessage());
        }
        out.sort(Comparator.comparing(Lan::virtual));
        return out;
    }

    /** The addresses of {@link #addresses} a server bound to {@code bind} answers on. */
    public static List<Lan> addressesFor(String bind) {
        List<Lan> out = new ArrayList<>(addresses());
        out.removeIf(l -> !answersOn(bind, l.ip()));
        return out;
    }

    static boolean looksVirtual(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String w : new String[]{"virtual", "vethernet", "hyper-v", "wsl", "docker", "vmware", "vbox",
                "loopback", "tap-", "tun", "wireguard", "tailscale", "zerotier", "vpn", "utun", "bridge"}) {
            if (n.contains(w)) return true;
        }
        return false;
    }

    /** Whether {@code host} (an IPv4 literal or localhost) is one of this PC's addresses. */
    public static boolean isOwnAddress(String host) {
        if ("localhost".equalsIgnoreCase(host)) return true;
        if (!IPV4.matcher(host).matches()) return false;
        try {
            InetAddress a = InetAddress.getByName(host);
            return a.isLoopbackAddress() || NetworkInterface.getByInetAddress(a) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
