package tricatch.oehubapp.ui;

import com.fasterxml.jackson.databind.JsonNode;
import tricatch.oehubapp.ProxyMonitor;
import tricatch.oehubapp.Store;

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
import java.util.List;
import java.util.Map;

/**
 * The oeMonitor screen (monitor.html): request table on top, detail tabs below, fed by the
 * embedded proxy's live traffic events ({@link ProxyMonitor}). Recording subscribes to the proxy;
 * stopping it unsubscribes, so the proxy stops building events.
 */
public final class MonitorTab extends JPanel {

    private static final String[] TABS = {"header", "cookie", "request", "response", "websocket", "event"};
    /** Oldest rows are dropped beyond this. */
    private static final int MAX_ROWS = 2000;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");

    /** Everything seen for one request id. Only touched on the EDT. */
    private static final class Row {
        final String rid;
        long reqTs, resTs;
        String method = "-", host = "-", path = "-";
        List<String> reqHeaders = List.of(), resHeaders = List.of();
        byte[] reqBody, resBody;
        Integer code;
        final List<String> ws = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        Row(String rid) {
            this.rid = rid;
        }
    }

    private final AppContext ctx;
    private final ProxyTab proxy;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Row> byRid = new HashMap<>();
    private final Model model = new Model();
    private final JTable table;
    private final JPanel tableCards = new JPanel(new CardLayout());
    private final JTextArea[] panels = new JTextArea[TABS.length];
    private final JPanel panelCards = new JPanel(new CardLayout());
    private final List<JLabel> tabButtons = new ArrayList<>();
    private final ProxyMonitor monitor = new ProxyMonitor(e -> SwingUtilities.invokeLater(() -> apply(e)));
    private JButton recordBtn;
    private boolean recording = true;
    private Runnable gotoProxy = () -> {};

    public MonitorTab(AppContext ctx, ProxyTab proxy) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.proxy = proxy;
        table = new JTable(model);
        setBackground(Color.WHITE);
        tableCards.setBackground(Color.WHITE);
        panelCards.setBackground(Color.WHITE);

        table.setFont(Ui.font(Font.PLAIN, 12.5f));
        table.setRowHeight(28);
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(0xf3f3f3));
        table.setIntercellSpacing(new Dimension(0, 1));
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
                setBorder(new EmptyBorder(0, 10, 0, 10));
                setFont(c == 2 || c == 6 ? Ui.mono(12f) : t.getFont());
                setForeground(Ui.TEXT);
                if (c == 5 && v instanceof Integer code) setForeground(code >= 500 ? Ui.DANGER : code >= 400 ? new Color(0xb8860b) : Ui.BC700);
                setHorizontalAlignment(c >= 4 ? RIGHT : LEFT);
                return this;
            }
        };
        table.setDefaultRenderer(Object.class, cell);
        table.setDefaultRenderer(Integer.class, cell);
        int[] widths = {80, 220, 480, 110, 90, 70, 90};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) populate(table.getSelectedRow());
        });

        JScrollPane ts = Ui.plainScroll(table);
        ts.getViewport().setBackground(Color.WHITE);
        tableCards.add(ts, "table");
        tableCards.add(inactivePanel(), "inactive");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableCards, buildDetail());
        split.setResizeWeight(0.55);
        split.setDividerSize(5);
        split.setBorder(null);
        split.setContinuousLayout(true);
        Ui.lineDivider(split);
        add(split, BorderLayout.CENTER);
        populate(-1);
        monitor.start();
    }

    public void onGotoProxy(Runnable r) {
        gotoProxy = r;
    }

    private JComponent inactivePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Color.WHITE);
        JLabel t = new JLabel(ctx.m("monitor.proxy.inactive.title"));
        t.setFont(Ui.font(Font.BOLD, 15f));
        t.setForeground(Ui.TEXT);
        JLabel b = new JLabel(ctx.m("monitor.proxy.inactive.body"));
        b.setForeground(Ui.MUTED);
        JButton go = Ui.primary(ctx.m("monitor.proxy.goto.proxy"), null, () -> gotoProxy.run());
        JPanel box = OeDialog.stack(10, t, b, go);
        p.add(box);
        return p;
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
            a.setBorder(new EmptyBorder(10, 14, 10, 14));
            panels[i] = a;
            panelCards.add(Ui.plainScroll(a), TABS[i]);
        }
        bar.add(left, BorderLayout.WEST);
        JPanel tabsWrap = new JPanel(new BorderLayout());
        tabsWrap.setBackground(Color.WHITE);
        tabsWrap.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Ui.BORDER), new EmptyBorder(4, 8, 0, 8)));
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
                    g2.fillRoundRect(0, 0, getWidth(), getHeight() + 8, 12, 12);
                    g2.setColor(Ui.BC300);
                    g2.fillRect(0, getHeight() - 2, getWidth(), 2);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        l.setBorder(new EmptyBorder(7, 14, 7, 14));
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

    /** Navbar actions while the monitor is shown: record and clear. */
    public JComponent navActions() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        p.setOpaque(false);
        recordBtn = navButton("circle-stop", ctx.m("monitor.stop.recording"), this::toggleRecording);
        recordBtn.setIcon(Ui.icon("circle-stop", 15, Ui.DANGER));
        p.add(recordBtn);
        p.add(navButton("brush-cleaning", ctx.m("tooltip.clear"), this::clear));
        return p;
    }

    private static JButton navButton(String icon, String tip, Runnable r) {
        JButton b = Ui.iconButton(icon, tip, false, r);
        b.setIcon(Ui.icon(icon, 15, new Color(0, 0, 0, 115)));
        return b;
    }

    private void toggleRecording() {
        recording = !recording;
        recordBtn.setIcon(Ui.icon(recording ? "circle-stop" : "circle-play", 15, recording ? Ui.DANGER : new Color(0, 0, 0, 115)));
        recordBtn.setRolloverIcon(Ui.icon(recording ? "circle-stop" : "circle-play", 16, recording ? Ui.DANGER : new Color(0, 0, 0, 178)));
        recordBtn.setToolTipText(ctx.m(recording ? "monitor.stop.recording" : "monitor.start.recording"));
        if (recording) monitor.start();
        else monitor.stop();
    }

    private void clear() {
        rows.clear();
        byRid.clear();
        model.fireTableDataChanged();
        populate(-1);
    }

    /** Called when the monitor tab is shown or hidden. */
    void setVisibleTab(boolean v) {
        if (v) ((CardLayout) tableCards.getLayout()).show(tableCards, proxy.hasSelected() || !rows.isEmpty() ? "table" : "inactive");
    }

    void dispose() {
        monitor.stop();
    }

    // ── Events ──────────────────────────────────────────────────

    private void apply(ProxyMonitor.Event e) {
        if (!recording) return;
        Row r = byRid.get(e.rid());
        boolean added = r == null;
        if (added) {
            r = new Row(e.rid());
            r.reqTs = e.timestamp();
            byRid.put(e.rid(), r);
            rows.add(r);
        }
        r.events.add(TIME.format(new Date(e.timestamp())) + "  " + e.type()
                + (e.stream() != null ? "  " + e.stream() : "")
                + (e.body() != null ? "  " + e.body().length + " B" : ""));
        switch (e.type()) {
            case REQ_HEADER -> {
                r.reqTs = e.timestamp();
                r.reqHeaders = e.headers();
                parseRequestLine(r);
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
            case REQ_BODY -> r.reqBody = e.body();
            case RES_BODY -> r.resBody = e.body();
            case WS_FRAME -> {
                String text = e.body() == null ? "" : new String(e.body(), StandardCharsets.UTF_8);
                boolean binary = e.opcode() != null && e.opcode() == 2;
                r.ws.add(TIME.format(new Date(e.timestamp())) + "  " + ("REQ".equals(e.wsDirection()) ? "→" : "←")
                        + "  " + (binary ? ctx.m("app.monitor.binary.body", e.body() == null ? 0 : e.body().length) : text));
            }
        }
        int index = rows.indexOf(r);
        if (added) {
            model.fireTableRowsInserted(index, index);
            trim();
            ((CardLayout) tableCards.getLayout()).show(tableCards, "table");
            if (table.getSelectedRow() < 0) table.scrollRectToVisible(table.getCellRect(rows.size() - 1, 0, true));
        } else {
            model.fireTableRowsUpdated(index, index);
        }
        if (table.getSelectedRow() >= 0 && table.getSelectedRow() == rows.indexOf(r)) populate(table.getSelectedRow());
    }

    private void trim() {
        int extra = rows.size() - MAX_ROWS;
        if (extra <= 0) return;
        for (int i = 0; i < extra; i++) byRid.remove(rows.get(i).rid);
        rows.subList(0, extra).clear();
        model.fireTableRowsDeleted(0, extra - 1);
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
        if (index < 0 || index >= rows.size()) {
            for (int i = 0; i < TABS.length; i++) setPanel(i, empty, true);
            return;
        }
        Row x = rows.get(index);
        StringBuilder h = new StringBuilder();
        h.append(ctx.m("monitor.detail.section.general")).append('\n')
                .append("  ").append(ctx.m("monitor.detail.label.request.id")).append(": ").append(x.rid).append('\n')
                .append("  ").append(ctx.m("monitor.detail.label.request.url")).append(": https://").append(x.host).append(x.path).append('\n')
                .append("  ").append(ctx.m("monitor.detail.label.request.method")).append(": ").append(x.method).append('\n')
                .append("  ").append(ctx.m("monitor.detail.label.status.code")).append(": ").append(x.code == null ? "-" : x.code).append("\n\n")
                .append(ctx.m("monitor.detail.request.headers")).append('\n');
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

        String req = body(x.reqBody, x.reqHeaders);
        if (req == null) req = queryString(x.path);
        setPanel(2, req != null ? req : ctx.m("monitor.detail.no.payload"), req == null);
        String res = body(x.resBody, x.resHeaders);
        setPanel(3, res != null ? res : ctx.m("monitor.detail.no.response"), res == null);
        setPanel(4, x.ws.isEmpty() ? ctx.m("monitor.detail.ws.empty") : String.join("\n", x.ws), x.ws.isEmpty());
        setPanel(5, String.join("\n", x.events), false);
    }

    /** Body for display: pretty text, a "N bytes (binary)" note, or null when there is none. */
    private String body(byte[] body, List<String> headers) {
        if (body == null || body.length == 0) return null;
        String text = ProxyMonitor.bodyText(body, headers);
        return text == null ? ctx.m("app.monitor.binary.body", body.length) : pretty(text);
    }

    private void setPanel(int i, String text, boolean empty) {
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
            return rows.size();
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
            Row x = rows.get(r);
            return switch (c) {
                case 0 -> x.method;
                case 1 -> x.host;
                case 2 -> x.path;
                case 3 -> TIME.format(new Date(x.reqTs));
                case 4 -> x.resTs == 0 ? "…" : Math.max(0, x.resTs - x.reqTs) + " ms";
                case 5 -> x.code;
                default -> size(x);
            };
        }
    }

    private static String size(Row x) {
        long n;
        if (x.resBody != null) n = x.resBody.length;
        else {
            String len = ProxyMonitor.header(x.resHeaders, "Content-Length");
            if (len == null) return "";
            try {
                n = Long.parseLong(len);
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return n < 1024 ? n + " B" : String.format("%.1f KB", n / 1024.0);
    }
}
