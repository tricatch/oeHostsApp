package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.proxy.DecryptedRequests;
import tricatch.oehostsapp.proxy.ForwardMonitor;
import tricatch.oehostsapp.proxy.ProxyMonitor;
import tricatch.oehostsapp.store.Store;

import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The Network screen (request table on top, detail tabs below): everything that passes the two
 * proxies while capturing.
 * <ul>
 *   <li>the reverse proxy's requests ({@link ProxyMonitor}): vhosts and what the SSL proxy decrypts;</li>
 *   <li>the proxy service's traffic ({@link ForwardMonitor}): plain HTTP whole, tunnels as gray
 *   CONNECT rows without content (right-click adds the domain to the SSL proxy).</li>
 * </ul>
 * Both report on their own threads into one queue, which the event thread works off in batches: a
 * page opens dozens of tunnels and hundreds of requests at once. Stopping the capture unsubscribes
 * from both, so neither builds events or collects bodies.
 */
public final class MonitorTab extends JPanel {

    private static final String[] TABS = {"header", "cookie", "request", "response", "websocket", "event"};
    /** The oldest rows are dropped beyond this, counted apart for content and tunnels so tunnels never push content out. */
    private static final int MAX_ROWS = 2000;
    /** The oldest rows' bodies are dropped beyond this many bytes in all. */
    private static final long MAX_BODY_BYTES = 96L * 1024 * 1024;
    private static final int FLUSH_MS = 120;
    /** An open tunnel's counters are repainted this often. */
    private static final int LIVE_MS = 1000;
    /** The reverse proxy's events come from worker threads: a request may be reported just after its tunnel closed. */
    private static final int DECRYPTED_GRACE_MS = 600;
    private static final Color TUNNEL_TEXT = new Color(0x9aa0a6);
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");
    /** The filter "with content only"; static, so a rebuild of the window keeps it. Not saved. */
    private static boolean contentOnly;

    /** Everything seen for one request or tunnel. Only touched on the EDT. */
    private static final class Row {
        final String rid;
        long reqTs, resTs, endTs;
        String method = "-", host = "-", path = "-";
        List<String> reqHeaders = List.of(), resHeaders = List.of();
        byte[] reqBody, resBody;
        Integer code;
        final List<String> ws = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        // Of the proxy service's rows only.
        boolean forward, tunnel, decryptedWithoutRequest, untracked;
        boolean reqTooLarge, resTooLarge;
        int port;
        String url, client;
        ForwardMonitor.Failure failure;
        long sent, received;
        /** While it is open, for the counters; dropped at the end, so the row alone holds the bodies. */
        ForwardMonitor.Exchange live;

        long bodyBytes;
        boolean bodiesDropped;

        Row(String rid) {
            this.rid = rid;
        }

        long sent() {
            return live != null ? live.sent() : sent;
        }

        long received() {
            return live != null ? live.received() : received;
        }
    }

    /** A decrypted tunnel that ended, until it is known whether a request of it was seen. */
    private record Ended(ForwardMonitor.Exchange tunnel, long due) {}

    private final AppContext ctx;
    private final List<Row> rows = new ArrayList<>();
    /** The rows the filter lets through, in the table's order. */
    private final List<Row> visible = new ArrayList<>();
    private final Map<String, Row> byRid = new HashMap<>();
    private final Model model = new Model();
    private final JTable table;
    private final JPanel tableCards = new JPanel(new CardLayout());
    private final JLabel emptyText = new JLabel();
    private final JTextArea[] panels = new JTextArea[TABS.length];
    private final JPanel panelCards = new JPanel(new CardLayout());
    private final List<JLabel> tabButtons = new ArrayList<>();

    private final Queue<Object> incoming = new ConcurrentLinkedQueue<>();
    private final ProxyMonitor monitor = new ProxyMonitor(incoming::add);
    private Listeners.Subscription forwardCapture;
    private final DecryptedRequests decrypted = new DecryptedRequests();
    private final List<Ended> endedDecrypted = new ArrayList<>();
    private final Timer flushTimer = new Timer(FLUSH_MS, e -> flushEvents());
    private final Timer liveTimer = new Timer(LIVE_MS, e -> repaintLive());
    private long bodyBytes;
    private long clearedAt;
    private int contentRows, tunnelRows;

    private JButton recordBtn;
    private JButton filterBtn;
    private boolean recording = true;
    private Runnable gotoCa = () -> {};
    private final SslProxyControls ssl;

    public MonitorTab(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        ssl = new SslProxyControls(ctx, () -> gotoCa.run());
        table = new JTable(model) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int r = rowAtPoint(e.getPoint());
                return r < 0 || r >= visible.size() ? null : Ui.plain(url(visible.get(r)));
            }
        };
        setBackground(Color.WHITE);
        tableCards.setBackground(Color.WHITE);
        panelCards.setBackground(Color.WHITE);

        table.setFont(Ui.font(Font.PLAIN, 12.5f));
        table.setRowHeight(Ui.px(28));
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(0xf3f3f3));
        table.setIntercellSpacing(new Dimension(0, Ui.px(1)));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(new Color(0xf5fbec));
        table.setSelectionForeground(Ui.TEXT);
        table.setFillsViewportHeight(true);
        JTableHeader th = table.getTableHeader();
        th.setReorderingAllowed(false);
        th.setFont(Ui.font(Font.BOLD, 11.5f));
        th.setBackground(new Color(0xf5fbec));
        th.setForeground(Ui.MUTED);
        th.putClientProperty("FlatLaf.style", "separatorColor:#e6f0d8; bottomSeparatorColor:#e6f0d8; height:30");
        DefaultTableCellRenderer cell = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, false, r, c);
                setBorder(Ui.pad(0, 10, 0, 10));
                setFont(c == 2 || c == 6 ? Ui.mono(12f) : t.getFont());
                setForeground(r < visible.size() && visible.get(r).tunnel ? TUNNEL_TEXT : Ui.TEXT);
                if (c == 5 && v instanceof Integer code) setForeground(code >= 500 ? Ui.DANGER : code >= 400 ? new Color(0xb8860b) : Ui.BC700);
                setHorizontalAlignment(c >= 4 ? RIGHT : LEFT);
                return this;
            }
        };
        // Every cell comes from the network: it must stay text.
        cell.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, cell);
        table.setDefaultRenderer(Integer.class, cell);
        int[] widths = {106, 220, 454, 110, 90, 70, 90}; // the method column fits CONNECT in the smallest window
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(Ui.px(widths[i]));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) populate(table.getSelectedRow());
        });

        JScrollPane ts = Ui.plainScroll(table);
        ts.getViewport().setBackground(Color.WHITE);
        tableCards.add(ts, "table");
        tableCards.add(emptyPanel(), "empty");
        java.awt.event.MouseAdapter menu = new java.awt.event.MouseAdapter() {
            // The menu's trigger is the press on macOS and Linux, the release on Windows.
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) tunnelMenu(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) tunnelMenu(e);
            }
        };
        table.addMouseListener(menu);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableCards, buildDetail());
        split.setResizeWeight(0.55);
        split.setDividerSize(Ui.px(5));
        split.setBorder(null);
        split.setContinuousLayout(true);
        Ui.lineDivider(split);
        add(split, BorderLayout.CENTER);
        populate(-1);
        showCard();
        startCapture();
        flushTimer.start();
        liveTimer.start();
    }

    void onGotoCa(Runnable r) {
        gotoCa = r;
    }

    void showSslSettings() {
        ssl.openSettings();
    }

    private JComponent emptyPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Color.WHITE);
        emptyText.setFont(Ui.font(Font.PLAIN, 13.5f));
        emptyText.setForeground(Ui.MUTED);
        p.add(emptyText);
        return p;
    }

    private void tunnelMenu(java.awt.event.MouseEvent e) {
        int r = table.rowAtPoint(e.getPoint());
        if (r < 0 || r >= visible.size() || !visible.get(r).tunnel) return;
        table.setRowSelectionInterval(r, r);
        Row x = visible.get(r);
        ssl.domainMenu(x.host, x.port).show(table, e.getX(), e.getY());
    }

    private JComponent buildDetail() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        for (int i = 0; i < TABS.length; i++) {
            JLabel b = tabButton(i);
            if (i < TABS.length - 1) left.add(b);
            else bar.add(b, BorderLayout.EAST);
            JTextArea a = new JTextArea();
            a.setEditable(false);
            a.setBackground(Color.WHITE);
            a.setFont(Ui.mono(12f));
            a.setForeground(Ui.TEXT);
            a.setBorder(Ui.pad(10, 14, 10, 14));
            panels[i] = a;
            panelCards.add(Ui.plainScroll(a), TABS[i]);
        }
        bar.add(left, BorderLayout.WEST);
        JPanel tabsWrap = new JPanel(new BorderLayout());
        tabsWrap.setBackground(Color.WHITE);
        tabsWrap.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Ui.BORDER), Ui.pad(4, 8, 0, 8)));
        tabsWrap.add(bar);
        p.add(tabsWrap, BorderLayout.NORTH);
        p.add(panelCards, BorderLayout.CENTER);
        selectTab(0);
        return p;
    }

    private JLabel tabButton(int index) {
        JLabel l = new JLabel(ctx.m("monitor.tab." + TABS[index])) {
            @Override
            protected void paintComponent(Graphics g) {
                if (Boolean.TRUE.equals(getClientProperty("active"))) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(0xf5fbec));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight() + Ui.px(8), Ui.px(12), Ui.px(12));
                    g2.setColor(Ui.BC300);
                    g2.fillRect(0, getHeight() - Ui.px(2), getWidth(), Ui.px(2));
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        l.setBorder(Ui.pad(7, 14, 7, 14));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                selectTab(index);
            }
        });
        tabButtons.add(l);
        return l;
    }

    private void selectTab(int index) {
        for (int i = 0; i < tabButtons.size(); i++) {
            JLabel b = tabButtons.get(i);
            boolean a = i == index;
            b.putClientProperty("active", a);
            b.setFont(Ui.font(a ? Font.BOLD : Font.PLAIN, 12f));
            b.setForeground(a ? new Color(0x7ab848) : Ui.MUTED);
            b.repaint();
        }
        ((CardLayout) panelCards.getLayout()).show(panelCards, TABS[index]);
    }

    /** Navbar actions of the Network tab: start/stop capture, clear, the filter, and the SSL proxy's switch and settings. */
    public JComponent navActions() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, Ui.px(6), 0));
        p.setOpaque(false);
        recordBtn = Ui.navIconButton("circle-stop", ctx.m("app.monitor.stop"), this::toggleRecording);
        showRecording(recordBtn);
        p.add(recordBtn);
        p.add(Ui.navIconButton("brush-cleaning", ctx.m("tooltip.clear"), this::clear));
        filterBtn = Ui.navIconButton("list-filter", null, this::toggleFilter);
        showFilter();
        p.add(filterBtn);
        p.add(Box.createHorizontalStrut(Ui.px(8))); // sets the SSL proxy's group apart
        p.add(ssl.panel);
        return p;
    }

    private void showRecording(JButton b) {
        String icon = recording ? "circle-stop" : "circle-play";
        b.setIcon(Ui.icon(icon, 15, recording ? Ui.DANGER : Ui.NAV_ICON));
        b.setRolloverIcon(Ui.icon(icon, 15, recording ? Ui.DANGER : Ui.NAV_ICON_HOVER));
        b.setDisabledIcon(Ui.icon(icon, 15, Ui.NAV_ICON_DISABLED));
        b.setToolTipText(ctx.m(recording ? "app.monitor.stop" : "app.monitor.start"));
    }

    private void startCapture() {
        monitor.start();
        forwardCapture = ForwardMonitor.start(incoming::add);
    }

    private void stopCapture() {
        monitor.stop();
        if (forwardCapture != null) forwardCapture.close();
        forwardCapture = null;
    }

    private void toggleRecording() {
        if (recording) {
            stopCapture();
            flushEvents(); // what happened until now
            recording = false;
            incoming.clear();
            endedDecrypted.clear();
            // Their ends will not be seen: no "connected" that would stay for good.
            for (Row r : rows) {
                if (r.live == null) continue;
                r.sent = r.live.sent();
                r.received = r.live.received();
                r.live = null;
                r.untracked = true;
            }
            if (!visible.isEmpty()) model.fireTableRowsUpdated(0, visible.size() - 1);
        } else {
            recording = true;
            startCapture();
        }
        showRecording(recordBtn);
        showCard();
    }

    private void toggleFilter() {
        contentOnly = !contentOnly;
        showFilter();
        int selected = table.getSelectedRow();
        Row keep = selected >= 0 && selected < visible.size() ? visible.get(selected) : null;
        visible.clear();
        for (Row r : rows) if (shows(r)) visible.add(r);
        model.fireTableDataChanged();
        int again = keep == null ? -1 : visible.indexOf(keep);
        if (again >= 0) table.setRowSelectionInterval(again, again);
        else populate(-1);
        showCard();
    }

    private void showFilter() {
        Color c = contentOnly ? Ui.BC700 : Ui.NAV_ICON;
        filterBtn.setIcon(Ui.icon("list-filter", 15, c));
        filterBtn.setRolloverIcon(Ui.icon("list-filter", 15, contentOnly ? c : Ui.NAV_ICON_HOVER));
        filterBtn.putClientProperty(Ui.NAV_ON, contentOnly);
        filterBtn.setToolTipText(ctx.m(contentOnly ? "app.monitor.filter.content" : "app.monitor.filter.all"));
        filterBtn.repaint();
    }

    private static boolean shows(Row r) {
        return !contentOnly || !r.tunnel;
    }

    private void clear() {
        clearedAt = System.currentTimeMillis();
        incoming.clear();
        endedDecrypted.clear();
        rows.clear();
        visible.clear();
        byRid.clear();
        bodyBytes = 0;
        contentRows = tunnelRows = 0;
        model.fireTableDataChanged();
        populate(-1);
        showCard();
    }

    /** Called when the monitor tab is shown or hidden. */
    void setVisibleTab(boolean v) {
        if (v) ssl.refresh(); // the CA may have been registered since
    }

    private void showCard() {
        emptyText.setText(ctx.m(!recording ? "app.monitor.empty.stopped"
                : !rows.isEmpty() ? "app.monitor.empty.filtered" : "app.monitor.empty"));
        ((CardLayout) tableCards.getLayout()).show(tableCards, visible.isEmpty() ? "empty" : "table");
    }

    void dispose() {
        flushTimer.stop();
        liveTimer.stop();
        stopCapture();
        incoming.clear();
    }

    // ── Events ──────────────────────────────────────────────────

    /** Works off what both monitors reported since the last time, as one change of the table. */
    private void flushEvents() {
        if (!recording || incoming.isEmpty() && endedDecrypted.isEmpty()) return;
        Set<Row> changed = new HashSet<>();
        List<Row> added = new ArrayList<>();
        // Only what is there now: a busy proxy must not keep this loop, and the event thread, forever.
        for (int n = incoming.size(); n > 0; n--) {
            Object o = incoming.poll();
            if (o instanceof ProxyMonitor.Event e) {
                applyProxy(e, changed, added);
            } else if (o instanceof ForwardMonitor.Exchange x) {
                if (x.startedAt() < clearedAt) continue; // of a row that was cleared
                if (x.endsAtReverseProxy()) {
                    if (x.ended()) endedDecrypted.add(new Ended(x, System.currentTimeMillis() + DECRYPTED_GRACE_MS));
                } else {
                    applyForward(x, changed, added);
                }
            }
        }
        long now = System.currentTimeMillis();
        endedDecrypted.removeIf(d -> {
            if (d.due() > now) return false;
            ForwardMonitor.Exchange x = d.tunnel();
            if (decrypted.leftNoRequest(x.host(), x.startedAt(), x.sent())) {
                applyForward(x, changed, added).decryptedWithoutRequest = true;
            }
            return true;
        });
        if (changed.isEmpty()) return;

        int firstNew = visible.size();
        for (Row r : added) if (shows(r)) visible.add(r);
        if (visible.size() > firstNew) model.fireTableRowsInserted(firstNew, visible.size() - 1);
        trimRows();
        trimBodies();
        // Repaints what is on screen, whatever the size of the batch.
        if (!visible.isEmpty()) model.fireTableRowsUpdated(0, visible.size() - 1);
        showCard();
        int selected = table.getSelectedRow();
        if (selected < 0) {
            if (!added.isEmpty() && !visible.isEmpty()) table.scrollRectToVisible(table.getCellRect(visible.size() - 1, 0, true));
        } else if (selected < visible.size() && changed.contains(visible.get(selected))) {
            populate(selected);
        }
    }

    private void repaintLive() {
        if (!isShowing()) return;
        for (Row r : visible) {
            if (r.live != null) {
                table.repaint();
                return;
            }
        }
    }

    private Row row(String rid, long at, boolean tunnel, List<Row> added) {
        Row r = byRid.get(rid);
        if (r != null) return r;
        r = new Row(rid);
        r.reqTs = at;
        r.tunnel = tunnel;
        if (tunnel) tunnelRows++;
        else contentRows++;
        byRid.put(rid, r);
        rows.add(r);
        added.add(r);
        return r;
    }

    private void applyProxy(ProxyMonitor.Event e, Set<Row> changed, List<Row> added) {
        Row r = row(e.rid(), e.timestamp(), false, added);
        changed.add(r);
        r.events.add(TIME.format(new Date(e.timestamp())) + "  " + e.type()
                + (e.stream() != null ? "  " + e.stream() : "")
                + (e.body() != null ? "  " + e.body().length + " B" : ""));
        switch (e.type()) {
            case REQ_HEADER -> {
                r.reqTs = e.timestamp();
                r.reqHeaders = e.headers();
                parseRequestLine(r);
                decrypted.saw(ProxyMonitor.header(e.headers(), "Host"), e.timestamp());
            }
            case RES_HEADER -> {
                r.resTs = e.timestamp();
                r.resHeaders = e.headers();
                String[] p = e.headers().isEmpty() ? new String[0] : e.headers().get(0).split(" ");
                try {
                    if (p.length >= 2) r.code = Integer.parseInt(p[1]);
                } catch (NumberFormatException ignored) {
                    // leave the code empty
                }
            }
            case REQ_BODY -> setBodies(r, e.body(), r.resBody);
            case RES_BODY -> setBodies(r, r.reqBody, e.body());
            case WS_FRAME -> {
                String text = e.body() == null ? "" : new String(e.body(), StandardCharsets.UTF_8);
                boolean binary = e.opcode() != null && e.opcode() == 2;
                r.ws.add(TIME.format(new Date(e.timestamp())) + "  " + ("REQ".equals(e.wsDirection()) ? "→" : "←")
                        + "  " + (binary ? ctx.m("app.monitor.binary.body", e.body() == null ? 0 : e.body().length) : text));
            }
        }
    }

    private Row applyForward(ForwardMonitor.Exchange x, Set<Row> changed, List<Row> added) {
        Row r = row(x.id(), x.startedAt(), x.tunnel(), added);
        changed.add(r);
        boolean ended = x.ended();
        r.forward = true;
        r.method = x.method();
        r.port = x.port();
        String host = x.host().indexOf(':') >= 0 ? "[" + x.host() + "]" : x.host();
        // Port 80 is implied; a tunnel's port is its path.
        r.host = x.tunnel() ? x.host() : x.port() == 80 ? host : host + ":" + x.port();
        r.path = x.path();
        r.url = x.url();
        r.client = x.client();
        r.reqHeaders = x.requestHeaders();
        r.resHeaders = x.responseHeaders();
        r.resTs = x.answeredAt();
        r.endTs = x.endedAt();
        r.code = x.status();
        r.failure = x.failure();
        r.reqTooLarge = x.requestBodyTooLarge();
        r.resTooLarge = x.responseBodyTooLarge();
        r.sent = x.sent();
        r.received = x.received();
        r.live = ended ? null : x;
        setBodies(r, x.requestBody(), x.responseBody());
        r.events.clear();
        r.events.add(TIME.format(new Date(r.reqTs)) + "  " + r.method + "  " + r.url);
        if (r.resTs > 0) r.events.add(TIME.format(new Date(r.resTs)) + "  " + (r.code == null ? "-" : r.code));
        if (r.failure != null) r.events.add("  " + failureText(r.failure));
        if (ended) r.events.add(TIME.format(new Date(r.endTs)) + "  END  " + r.sent + " B / " + r.received + " B");
        return r;
    }

    private void setBodies(Row r, byte[] req, byte[] res) {
        if (r.bodiesDropped) return;
        r.reqBody = req;
        r.resBody = res;
        long n = (req == null ? 0 : req.length) + (long) (res == null ? 0 : res.length);
        bodyBytes += n - r.bodyBytes;
        r.bodyBytes = n;
    }

    private void trimRows() {
        while (contentRows > MAX_ROWS || tunnelRows > MAX_ROWS) {
            boolean tunnel = tunnelRows > MAX_ROWS;
            int i = 0;
            while (rows.get(i).tunnel != tunnel) i++;
            Row r = rows.remove(i);
            byRid.remove(r.rid);
            bodyBytes -= r.bodyBytes;
            if (tunnel) tunnelRows--;
            else contentRows--;
            int at = visible.indexOf(r);
            if (at >= 0) {
                visible.remove(at);
                model.fireTableRowsDeleted(at, at);
            }
        }
    }

    private void trimBodies() {
        if (bodyBytes <= MAX_BODY_BYTES) return;
        for (Row r : rows) {
            if (r.bodyBytes == 0) continue;
            bodyBytes -= r.bodyBytes;
            r.bodyBytes = 0;
            r.reqBody = r.resBody = null;
            r.bodiesDropped = true;
            if (bodyBytes <= MAX_BODY_BYTES) return;
        }
    }

    private static void parseRequestLine(Row r) {
        if (!r.reqHeaders.isEmpty()) {
            String[] p = r.reqHeaders.get(0).split(" ");
            if (p.length >= 2) {
                r.method = p[0];
                r.path = p[1];
            }
        }
        String host = ProxyMonitor.header(r.reqHeaders, "Host");
        if (host != null) r.host = host;
    }

    // ── Detail ──────────────────────────────────────────────────

    private void populate(int index) {
        String empty = ctx.m("monitor.detail.empty");
        if (index < 0 || index >= visible.size()) {
            for (int i = 0; i < TABS.length; i++) setPanel(i, empty, true);
            return;
        }
        Row x = visible.get(index);
        String note = !x.tunnel ? null
                : ctx.m(x.failure != null || x.code != null ? "app.monitor.tunnel.failed"
                : x.decryptedWithoutRequest ? "app.monitor.tunnel.norequest" : "app.monitor.tunnel.note");
        StringBuilder h = new StringBuilder();
        h.append(ctx.m("monitor.detail.section.general")).append('\n')
                .append("  ").append(ctx.m("monitor.detail.label.request.id")).append(": ").append(x.rid).append('\n')
                .append("  ").append(ctx.m("monitor.detail.label.request.url")).append(": ").append(url(x)).append('\n')
                .append("  ").append(ctx.m("monitor.detail.label.request.method")).append(": ").append(x.method).append('\n')
                .append("  ").append(ctx.m("monitor.detail.label.status.code")).append(": ").append(x.code == null ? "-" : x.code).append('\n');
        if (x.forward) {
            field(h, "app.monitor.detail.client", x.client);
            if (x.failure != null) field(h, "app.monitor.detail.failure", failureText(x.failure));
            if (x.tunnel) {
                field(h, "app.monitor.detail.sent", Ui.byteSize(x.sent()));
                field(h, "app.monitor.detail.received", Ui.byteSize(x.received()));
            }
        }
        if (note != null) h.append('\n').append(note).append('\n');
        h.append('\n').append(ctx.m("monitor.detail.request.headers")).append('\n');
        for (int i = 1; i < x.reqHeaders.size(); i++) h.append("  ").append(x.reqHeaders.get(i)).append('\n');
        h.append('\n').append(ctx.m("monitor.detail.response.headers")).append('\n');
        for (int i = 1; i < x.resHeaders.size(); i++) h.append("  ").append(x.resHeaders.get(i)).append('\n');
        setPanel(0, h.toString(), false);

        StringBuilder c = new StringBuilder();
        String cookie = ProxyMonitor.header(x.reqHeaders, "Cookie");
        if (cookie != null) {
            c.append(ctx.m("monitor.detail.request.cookies")).append('\n');
            for (String kv : cookie.split(";")) {
                String[] p = kv.strip().split("=", 2);
                c.append("  ").append(p[0]).append(" : ").append(p.length > 1 ? p[1] : "").append('\n');
            }
        }
        for (int i = 1; i < x.resHeaders.size(); i++) {
            String l = x.resHeaders.get(i);
            if (l.regionMatches(true, 0, "Set-Cookie:", 0, 11)) {
                if (c.indexOf(ctx.m("monitor.detail.response.set.cookie")) < 0) {
                    c.append(c.length() > 0 ? "\n" : "").append(ctx.m("monitor.detail.response.set.cookie")).append('\n');
                }
                c.append("  ").append(l.substring(11).strip()).append('\n');
            }
        }
        setPanel(1, c.length() > 0 ? c.toString() : ctx.m("monitor.detail.no.cookies"), c.length() == 0);

        if (note != null) {
            setPanel(2, note, true);
            setPanel(3, note, true);
        } else {
            String req = body(x.reqBody, x.reqHeaders);
            if (req == null) req = missingBody(x, x.reqTooLarge);
            if (req == null) req = queryString(x.path);
            setPanel(2, req != null ? req : ctx.m("monitor.detail.no.payload"), req == null);
            String res = body(x.resBody, x.resHeaders);
            if (res == null) res = missingBody(x, x.resTooLarge);
            setPanel(3, res != null ? res : ctx.m("monitor.detail.no.response"), res == null);
        }
        setPanel(4, x.ws.isEmpty() ? ctx.m("monitor.detail.ws.empty") : String.join("\n", x.ws), x.ws.isEmpty());
        setPanel(5, String.join("\n", x.events), false);
    }

    /** Body for display: pretty text, a "N bytes (binary)" note, or null when there is none. */
    private String body(byte[] body, List<String> headers) {
        if (body == null || body.length == 0) return null;
        String text = ProxyMonitor.bodyText(body, headers);
        return text == null ? ctx.m("app.monitor.binary.body", body.length) : pretty(text);
    }

    private void field(StringBuilder to, String key, String value) {
        to.append("  ").append(ctx.m(key)).append(": ").append(value).append('\n');
    }

    private String missingBody(Row x, boolean tooLarge) {
        if (tooLarge) return ctx.m("app.monitor.body.toolarge", ForwardMonitor.BODY_LIMIT / 1024 / 1024);
        return x.bodiesDropped ? ctx.m("app.monitor.body.dropped") : null;
    }

    private String failureText(ForwardMonitor.Failure f) {
        return ctx.m(switch (f) {
            case DNS -> "app.monitor.failure.dns";
            case CONNECT -> "app.monitor.failure.connect";
            case TIMEOUT -> "app.monitor.failure.timeout";
        });
    }

    /** The request's URL: as the proxy service was given it, or https for the reverse proxy's. */
    private static String url(Row x) {
        return x.forward ? x.url : "https://" + x.host + x.path;
    }

    private void setPanel(int i, String text, boolean empty) {
        // A batch repopulates the selected row: the same text keeps its scroll position and selection.
        if (text.equals(panels[i].getText())) return;
        panels[i].setFont(empty ? Ui.font(Font.PLAIN, 12.5f) : Ui.mono(12f));
        panels[i].setForeground(empty ? Ui.MUTED : Ui.TEXT);
        panels[i].setText(text);
        panels[i].setCaretPosition(0);
    }

    private static String queryString(String path) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        return form(path.substring(q + 1));
    }

    private static String form(String s) {
        StringBuilder b = new StringBuilder();
        for (String kv : s.split("&")) {
            String[] p = kv.split("=", 2);
            try {
                b.append(URLDecoder.decode(p[0], StandardCharsets.UTF_8)).append(" = ")
                        .append(p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "").append('\n');
            } catch (IllegalArgumentException e) {
                b.append(kv).append('\n');
            }
        }
        return b.toString();
    }

    private static String pretty(String body) {
        String t = body.strip();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                JsonNode n = Store.MAPPER.readTree(t);
                return Store.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n);
            } catch (Exception ignored) {
                return body;
            }
        }
        if (t.contains("=") && !t.contains(" ") && !t.contains("\n")) return form(t);
        return body;
    }

    // ── Table model ─────────────────────────────────────────────

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return visible.size();
        }

        @Override
        public int getColumnCount() {
            return 7;
        }

        @Override
        public String getColumnName(int c) {
            return ctx.m("monitor.col." + new String[]{"method", "host", "path", "start", "duration", "code", "size"}[c]);
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 5 ? Integer.class : Object.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Row x = visible.get(r);
            return switch (c) {
                case 0 -> x.method;
                case 1 -> x.host;
                case 2 -> x.path;
                case 3 -> TIME.format(new Date(x.reqTs));
                case 4 -> duration(x);
                case 5 -> x.code == null && x.forward && (x.tunnel || x.endTs > 0) ? "-" : x.code;
                default -> size(x);
            };
        }
    }

    /** Until the response started; for a tunnel, how long it was open. */
    private String duration(Row x) {
        if (x.untracked && (x.tunnel || x.resTs == 0)) return "-";
        if (x.tunnel) {
            if (x.endTs == 0) return ctx.m("app.monitor.tunnel.open");
            long ms = Math.max(0, x.endTs - x.reqTs);
            return ms < 10_000 ? ms + " ms" : ms / 1000 + " s";
        }
        if (x.resTs > 0) return Math.max(0, x.resTs - x.reqTs) + " ms";
        return x.endTs > 0 ? "-" : "…";
    }

    private static String size(Row x) {
        long n;
        if (x.forward) {
            if (x.tunnel && x.code != null) return "-"; // it never was a tunnel
            n = x.tunnel ? x.sent() + x.received() : x.received();
        } else if (x.resBody != null) n = x.resBody.length;
        else {
            String len = ProxyMonitor.header(x.resHeaders, "Content-Length");
            if (len == null) return "";
            try {
                n = Long.parseLong(len);
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return Ui.byteSize(n);
    }
}
