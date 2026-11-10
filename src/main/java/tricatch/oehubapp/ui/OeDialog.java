package tricatch.oehubapp.ui;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * {@code .oe-modal}: a modal dialog with the BC100 header, a body and a footer of
 * {@code .oe-modal-btn} buttons. Undecorated so it looks the same as on the web pages; the header
 * drags the dialog and Esc closes it.
 */
public final class OeDialog extends JDialog {

    private static final Color LINE = new Color(0xc4e89a);
    private final JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 10));
    private final JPanel footerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 12));
    private final JPanel body = new JPanel(new BorderLayout());

    public OeDialog(Window owner, String title) {
        super(owner, ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.WHITE);
        root.setBorder(new LineBorder(new Color(0xb9c0b0), 1));

        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(Ui.BC100);
        head.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(12, 16, 12, 10)));
        JLabel t = new JLabel(title);
        t.setFont(Ui.font(Font.BOLD, 15f));
        t.setForeground(new Color(0x1e4a0a));
        head.add(t, BorderLayout.CENTER);
        JButton close = Ui.iconButton("x", null, false, this::dispose);
        close.setIcon(Ui.icon("x", 18, new Color(0, 0, 0, 115)));
        close.setRolloverIcon(Ui.icon("x", 18, new Color(0, 0, 0, 178)));
        head.add(close, BorderLayout.EAST);
        MouseAdapter drag = new MouseAdapter() {
            Point start;

            @Override
            public void mousePressed(MouseEvent e) {
                start = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = getLocation();
                setLocation(p.x + e.getX() - start.x, p.y + e.getY() - start.y);
            }
        };
        head.addMouseListener(drag);
        head.addMouseMotionListener(drag);
        t.addMouseListener(drag);
        t.addMouseMotionListener(drag);

        body.setOpaque(false);
        body.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, LINE), new EmptyBorder(0, 16, 0, 10)));
        footer.setOpaque(false);
        footerLeft.setOpaque(false);
        foot.add(footerLeft, BorderLayout.WEST);
        foot.add(footer, BorderLayout.EAST);

        root.add(head, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    public OeDialog body(JComponent c) {
        body.add(c, BorderLayout.CENTER);
        return this;
    }

    public OeDialog footerLeft(JComponent c) {
        footerLeft.add(c);
        return this;
    }

    /** {@code .oe-modal-btn} (green) or {@code .oe-modal-btn-plain}. */
    public JButton button(String text, boolean primary, Runnable action) {
        JButton b = new JButton(text);
        b.setFont(Ui.font(Font.BOLD, 13f));
        b.setFocusable(false);
        if (primary) {
            b.setBackground(Ui.BC100);
            b.setForeground(new Color(0x4a7a1e));
            b.putClientProperty("FlatLaf.style", "arc:6; borderColor:#c4e89a; hoverBackground:#a8d878; hoverBorderColor:#8ec45a; pressedBackground:#a8d878");
        } else {
            b.setBackground(Color.WHITE);
            b.setForeground(Ui.MUTED);
            b.putClientProperty("FlatLaf.style", "arc:6; borderColor:#dee2e6; hoverBackground:#f5fbec; hoverBorderColor:#c4e89a");
        }
        b.addActionListener(e -> action.run());
        footer.add(b);
        return b;
    }

    public void showCentered() {
        pack();
        Dimension d = getSize();
        setSize(Math.max(d.width, 420), d.height);
        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    // ── Body building blocks ────────────────────────────────────

    /** Small gray wrapped text ({@code .small.text-muted}). */
    public static JComponent note(String text) {
        JTextArea a = Ui.copyable(new JTextArea(text));
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setFont(Ui.font(Font.PLAIN, 12.5f));
        a.setForeground(Ui.MUTED);
        a.setBorder(new EmptyBorder(0, 0, 0, 0));
        return a;
    }

    /** {@code .chrome-cmd-warn}: red box. */
    public static JComponent warn(String text, String strong) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(new Color(0xfdecee));
        p.setBorder(new CompoundBorder(new LineBorder(new Color(0xf1aeb5), 1, true), new EmptyBorder(8, 10, 8, 10)));
        JComponent a = note(text);
        a.setForeground(new Color(0x842029));
        p.add(a);
        if (strong != null && !strong.isEmpty()) {
            JComponent b = note(strong);
            b.setForeground(new Color(0x842029));
            b.setFont(Ui.font(Font.BOLD, 12.5f));
            p.add(Box.createVerticalStrut(4));
            p.add(b);
        }
        return p;
    }

    /** {@code .chrome-cmd-script}: monospace command box. */
    public static JTextArea script(String text) {
        JTextArea a = new JTextArea(text);
        a.setLineWrap(true);
        a.setEditable(false);
        a.setFont(Ui.mono(12f));
        a.setBackground(Ui.META_BG);
        a.setBorder(new CompoundBorder(new LineBorder(Ui.BORDER, 1, true), new EmptyBorder(8, 10, 8, 10)));
        a.setColumns(60);
        return a;
    }

    public static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /** Vertical stack with gaps. */
    public static JPanel stack(int gap, JComponent... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) p.add(Box.createVerticalStrut(gap));
            cs[i].setAlignmentX(LEFT_ALIGNMENT);
            p.add(cs[i]);
        }
        return p;
    }

    /** Asks with OK/Cancel; returns true on OK. */
    public static boolean confirm(AppContext ctx, String title, String text) {
        boolean[] ok = {false};
        OeDialog d = new OeDialog(ctx.frame(), title);
        JComponent n = note(text);
        Dialogs.fixWidth(n, 440);
        d.body(n);
        d.button(ctx.m("btn.confirm"), true, () -> {
            ok[0] = true;
            d.dispose();
        });
        d.button(ctx.m("btn.cancel"), false, d::dispose);
        d.showCentered();
        return ok[0];
    }

    public static void info(AppContext ctx, String title, String text) {
        OeDialog d = new OeDialog(ctx.frame(), title);
        JComponent n = note(text);
        Dialogs.fixWidth(n, 440);
        d.body(n);
        d.button(ctx.m("btn.close"), false, d::dispose);
        d.showCentered();
    }

    /** Lets a caller tweak the dialog before it is shown. */
    public static void open(AppContext ctx, String title, Consumer<OeDialog> build) {
        OeDialog d = new OeDialog(ctx.frame(), title);
        build.accept(d);
        d.showCentered();
    }
}
