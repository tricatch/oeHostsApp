package tricatch.oehostsapp.store;

/**
 * The Chrome launcher options of the oeHosts screen (oeHub's {@code HOSTS_CONF}): what each switch
 * and field holds. {@code --host-resolver-rules} and {@code --proxy-server} exclude each other; the
 * screen keeps at most one of them on.
 */
public final class LauncherOptions {

    public static final String DEFAULT_EXTRA_ARGS =
            "--disable-background-networking\n--disable-sync\n--disk-cache-size=1\n--media-cache-size=1\n--no-first-run";

    public boolean devTools;
    public boolean incognito;
    public boolean userAgentEnabled;
    public String userAgent = "";
    public boolean userDataDirEnabled;
    public boolean extraArgsEnabled;
    /** One argument per line. */
    public String extraArgs = DEFAULT_EXTRA_ARGS;
    public boolean openUrlEnabled = true;
    public String openUrl = "";
    public boolean hostResolverRules = true;
    public boolean proxyServer;

    /** Fills fields a data file left out. */
    void fillDefaults() {
        if (userAgent == null) userAgent = "";
        if (openUrl == null) openUrl = "";
        if (extraArgs == null || extraArgs.isBlank()) extraArgs = DEFAULT_EXTRA_ARGS;
    }
}
