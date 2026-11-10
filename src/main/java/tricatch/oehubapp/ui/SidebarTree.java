package tricatch.oehubapp.ui;

import tricatch.oehubapp.Store;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The sidebar list of one kind (hosts profiles or vhosts) with one level of folders, drawn like
 * list-folders.js: 36px rounded rows, BC100 for the selected row, a BC300 switch per item, an
 * "on/total" badge per folder and a guide line under an open folder.
 *
 * <p>Click a folder to open/close it, double-click a name to rename it, right-click for move and
 * delete, drag a row to reorder it or to drop an item into a folder.
 */
public final class SidebarTree extends JPanel {

    public interface Listener {
        void activated(Store.Item item);

        void toggled(Store.Item item);

        void renamed(Store.Item item);

        void deleteRequested(Store.Item item);

        /** Something about the list changed (folder moved, deleted...). */
        void changed();
    }

    private final AppContext ctx;
    private final Store.Kind kind;
    private final Listener listener;
    private final JPanel rows = new JPanel();
    private String activeId;

    /** A rendered row, the folder or item it shows, and the folder it is drawn in (null: the root). */
    private record Target(Row row, Object entry, String parentId) {}

    private final List<Target> targets = new ArrayList<>();
    private Object dragSrc;
    private Point dragStart;
    private boolean dragging;
    /** Where the drag would land: a row (null = below the last row) and "before"/"after"/"into". */
    private Target dropTarget;
    private String dropPos;
    /** Event time of the release that ended a drag; the click it may produce is ignored. */
    private long dragEndedAt;

    public SidebarTree(AppContext ctx, Store.Kind kind, Listener listener) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.kind = kind;
        this.listener = listener;
        setBackground(Color.WHITE);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBackground(Color.WHITE);
        rows.setBorder(new EmptyBorder(12, 12, 16, 12));
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Color.WHITE);
        top.add(rows, BorderLayout.NORTH);
        add(Ui.plainScroll(top), BorderLayout.CENTER);
    }

    public void setActive(String id) {
        activeId = id;
        render();
    }

    public String activeId() {
        return activeId;
    }

    public void render() {
        rows.removeAll();
        targets.clear();
        List<Store.Folder> folders = ctx.read(() -> ctx.rawStore().listFolders(kind));
        List<Store.Item> items = ctx.read(() -> ctx.rawStore().list(kind));
        Map<String, List<Store.Item>> byFolder = new LinkedHashMap<>();
        for (Store.Folder f : folders) byFolder.put(f.id, new ArrayList<>());
        List<Object> top = new ArrayList<>();
        for (Store.Item i : items) {
            if (i.folderId != null && byFolder.containsKey(i.folderId)) byFolder.get(i.folderId).add(i);
        }
        // Folders and root items interleave by sortOrder (Store.list's order).
        List<Object> all = new ArrayList<>(folders);
        for (Store.Item i : items) if (i.folderId == null || !byFolder.containsKey(i.folderId)) all.add(i);
        all.sort((a, b) -> Long.compare(order(a), order(b)));
        top.addAll(all);
        boolean first = true;
        for (Object o : top) {
            if (!first) rows.add(Box.createVerticalStrut(4));
            first = false;
            if (o instanceof Store.Folder f) {
                List<Store.Item> kids = byFolder.get(f.id);
                rows.add(folderRow(f, kids));
                if (!f.collapsed && !kids.isEmpty()) {
                    JPanel group = new JPanel();
                    group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
                    group.setOpaque(false);
                    group.setBorder(BorderFactory.createCompoundBorder(
                            new EmptyBorder(2, 14, 6, 0),
                            BorderFactory.createCompoundBorder(new MatteBorder(0, 1, 0, 0, Ui.GUIDE), new EmptyBorder(0, 4, 0, 0))));
                    for (int k = 0; k < kids.size(); k++) {
                        if (k > 0) group.add(Box.createVerticalStrut(2));
                        group.add(itemRow(kids.get(k), folders, f.id));
                    }
                    group.setAlignmentX(LEFT_ALIGNMENT);
                    rows.add(group);
                }
            } else {
                rows.add(itemRow((Store.Item) o, folders, null));
            }
        }
        rows.revalidate();
        rows.repaint();
    }

    private static long order(Object o) {
        return o instanceof Store.Folder f ? f.sortOrder : ((Store.Item) o).sortOrder;
    }

    /** A rounded 36px row that paints its own hover/selected background. */
    private class Row extends JPanel {
        boolean hover, active;
        /** Drop mark while dragging: "before", "after", "into" or null. */
        String mark;

        Row() {
            super(new BorderLayout(8, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            setAlignmentX(LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 36);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, 36);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if ("into".equals(mark)) {
                // oe-panel.css .drop-into: BC100 fill with a 1px BC300 outline.
                g2.setColor(Ui.BC100);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.setColor(Ui.BC300);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            } else if (active || hover) {
                g2.setColor(active ? Ui.BC100 : Ui.HOVER);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            }
            if ("before".equals(mark) || "after".equals(mark)) {
                // .drop-before / .drop-after: a 2px BC300 line on that edge.
                g2.setColor(Ui.BC300);
                g2.fillRect(0, "before".equals(mark) ? 0 : getHeight() - 2, getWidth(), 2);
            }
            g2.dispose();
            super.paintComponent(g);
        }

        void hoverOn(Component... cs) {
            MouseAdapter h = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (dragging) return; // rows passed over during a drag show only the drop mark
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), Row.this);
                    if (!Row.this.contains(p)) {
                        hover = false;
                        repaint();
                    }
                }
            };
            addMouseListener(h);
            for (Component c : cs) c.addMouseListener(h);
        }
    }

    private JComponent itemRow(Store.Item it, List<Store.Folder> folders, String parentId) {
        Row row = new Row();
        row.active = it.id.equals(activeId);
        JLabel icon = new JLabel(Ui.icon("file", 15, new Color(0x8a8d83)));
        JLabel name = new JLabel(it.name);
        name.setFont(Ui.font(row.active ? Font.BOLD : Font.PLAIN, 13f));
        name.setForeground(row.active ? Ui.TEXT_STRONG : Ui.TEXT);
        Switch sw = new Switch(it.selected, Ui.BC300);
        sw.onChange(on -> listener.toggled(it));
        JPanel center = new JPanel(new CardLayout());
        center.setOpaque(false);
        center.add(name, "label");
        row.add(icon, BorderLayout.WEST);
        row.add(center, BorderLayout.CENTER);
        row.add(sw, BorderLayout.EAST);
        row.hoverOn(name, icon);
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getWhen() <= dragEndedAt) return;
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    listener.activated(it);
                } else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    startRename(center, it.name, n -> {
                        Store.Item r = ctx.store(s -> s.rename(kind, it.id, n));
                        if (r != null) listener.renamed(r);
                        render();
                    });
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                JPopupMenu m = new JPopupMenu();
                JMenuItem rename = new JMenuItem(ctx.m("app.menu.rename"));
                rename.addActionListener(a -> startRename(center, it.name, n -> {
                    Store.Item r = ctx.store(s -> s.rename(kind, it.id, n));
                    if (r != null) listener.renamed(r);
                    render();
                }));
                m.add(rename);
                JMenu move = new JMenu(ctx.m("app.menu.move"));
                JMenuItem root = new JMenuItem(ctx.m("app.menu.move.root"));
                root.setEnabled(it.folderId != null);
                root.addActionListener(a -> moveTo(it, null));
                move.add(root);
                if (!folders.isEmpty()) move.addSeparator();
                for (Store.Folder f : folders) {
                    JMenuItem fi = new JMenuItem(f.name, Ui.icon("folder", 14, Ui.MUTED));
                    fi.setEnabled(!f.id.equals(it.folderId));
                    fi.addActionListener(a -> moveTo(it, f.id));
                    move.add(fi);
                }
                m.add(move);
                m.addSeparator();
                JMenuItem del = new JMenuItem(ctx.m("app.menu.delete"), Ui.icon("trash", 14, Ui.DANGER));
                del.addActionListener(a -> listener.deleteRequested(it));
                m.add(del);
                m.show(e.getComponent(), e.getX(), e.getY());
            }
        };
        row.addMouseListener(click);
        name.addMouseListener(click);
        icon.addMouseListener(click);
        draggable(row, it, parentId, name, icon);
        return row;
    }

    // ── Drag & drop (list-folders.js) ─────────────────────────
    // Over an item: the upper half drops before it, the lower half after it (an item joins that
    // item's folder or the root). Over a folder: an item goes into it unless it is near the top
    // edge (then before the folder); a folder goes before/after it by half. A folder dropped on
    // another folder's item lands next to that folder. Below the last row: the end of the root.

    /** The whole row is the drag handle; the switch and the delete button keep their clicks. */
    private void draggable(Row row, Object entry, String parentId, Component... parts) {
        targets.add(new Target(row, entry, parentId));
        MouseAdapter h = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                dragSrc = entry;
                dragStart = e.getLocationOnScreen();
                dragging = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragSrc != entry) return;
                if (!dragging) {
                    if (dragStart.distance(e.getLocationOnScreen()) < 5) return;
                    dragging = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    for (Target t : targets) {
                        if (t.row().hover) {
                            t.row().hover = false;
                            t.row().repaint();
                        }
                    }
                }
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), rows);
                rows.scrollRectToVisible(new Rectangle(p.x, p.y - 18, 1, 36));
                updateDrop(p);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragSrc != entry) return;
                if (dragging) dragEndedAt = e.getWhen();
                boolean drop = dragging && dropPos != null;
                Target t = dropTarget;
                String pos = dropPos;
                endDrag();
                if (drop) move(entry, t == null ? null : t.entry(), pos);
            }
        };
        row.addMouseListener(h);
        row.addMouseMotionListener(h);
        for (Component c : parts) {
            c.addMouseListener(h);
            c.addMouseMotionListener(h);
        }
    }

    private Rectangle boundsOf(Row row) {
        return SwingUtilities.convertRectangle(row.getParent(), row.getBounds(), rows);
    }

    private void updateDrop(Point p) {
        if (dragSrc instanceof Store.Folder) {
            updateFolderDrop(p);
            return;
        }
        Target hit = null;
        Rectangle hitBounds = null;
        int lastBottom = 0;
        for (Target t : targets) {
            Rectangle r = boundsOf(t.row());
            lastBottom = Math.max(lastBottom, r.y + r.height);
            if (p.y >= r.y && p.y < r.y + r.height) {
                hit = t;
                hitBounds = r;
            }
        }
        String pos = null;
        if (hit != null) {
            double y = (p.y - hitBounds.y) / (double) hitBounds.height;
            if (hit.entry() instanceof Store.Folder) pos = y < 0.3 ? "before" : "into";
            else pos = y < 0.5 ? "before" : "after";
        } else if (p.y >= lastBottom) {
            pos = "after"; // the end of the root
        }
        dropTarget = hit;
        dropPos = pos;
        showMark(hit == null ? null : hit.row(), pos);
    }

    /**
     * A folder only goes between root entries, so the items inside folders take no part: passing
     * over them marks nothing and dropping there does nothing. Over a folder row or a root item
     * the upper half drops before it and the lower half after it, except an open folder with
     * items, whose whole row drops before it (its lower edge would sit above its own items).
     */
    private void updateFolderDrop(Point p) {
        Target hit = null;
        String pos = null;
        int lastBottom = 0;
        for (Target t : targets) {
            Rectangle r = boundsOf(t.row());
            lastBottom = Math.max(lastBottom, r.y + r.height);
            if (t.parentId() != null || p.y < r.y || p.y >= r.y + r.height) continue;
            hit = t;
            boolean openWithItems = t.entry() instanceof Store.Folder f
                    && targets.stream().anyMatch(k -> f.id.equals(k.parentId()));
            pos = openWithItems || p.y < r.y + r.height / 2 ? "before" : "after";
        }
        if (hit == null && p.y >= lastBottom) pos = "after"; // the end of the root
        dropTarget = hit;
        dropPos = pos;
        showMark(hit == null ? null : hit.row(), pos);
    }

    private void showMark(Row markRow, String pos) {
        for (Target t : targets) {
            String m = t.row() == markRow ? pos : null;
            if (!java.util.Objects.equals(t.row().mark, m)) {
                t.row().mark = m;
                t.row().repaint();
            }
        }
    }

    private void endDrag() {
        dragSrc = null;
        dragging = false;
        dropTarget = null;
        dropPos = null;
        setCursor(null);
        for (Target t : targets) {
            if (t.row().mark != null) {
                t.row().mark = null;
                t.row().repaint();
            }
        }
    }

    /** A root entry: a folder with its items, or an item outside any folder. */
    private record Node(Store.Folder folder, Store.Item item, List<Store.Item> children) {}

    /**
     * Moves {@code src} next to or into {@code target} (null: the end of the root) and saves the
     * whole order, as list-folders.js move() does.
     */
    private void move(Object src, Object target, String pos) {
        List<Store.Folder> folders = ctx.read(() -> ctx.rawStore().listFolders(kind));
        List<Store.Item> items = ctx.read(() -> ctx.rawStore().list(kind));
        Map<String, Node> byFolder = new LinkedHashMap<>();
        for (Store.Folder f : folders) byFolder.put(f.id, new Node(f, null, new ArrayList<>()));
        List<Node> tree = new ArrayList<>(byFolder.values());
        for (Store.Item i : items) {
            Node parent = i.folderId == null ? null : byFolder.get(i.folderId);
            if (parent != null) parent.children().add(i);
            else tree.add(new Node(null, i, null));
        }
        tree.sort((a, b) -> Long.compare(a.folder() != null ? a.folder().sortOrder : a.item().sortOrder,
                b.folder() != null ? b.folder().sortOrder : b.item().sortOrder));

        // Take the dragged entry out of the tree.
        Node rootEntry;
        Store.Item moving = null;
        if (src instanceof Store.Folder sf) {
            if (target instanceof Store.Folder tf && tf.id.equals(sf.id)) return;
            if (target instanceof Store.Item ti && sf.id.equals(ti.folderId)) return; // onto its own item
            int i = indexOf(tree, n -> n.folder() != null && n.folder().id.equals(sf.id));
            if (i < 0) return;
            rootEntry = tree.remove(i);
        } else {
            Store.Item si = (Store.Item) src;
            if (target instanceof Store.Item ti && ti.id.equals(si.id)) return;
            for (int i = 0; i < tree.size() && moving == null; i++) {
                Node n = tree.get(i);
                if (n.item() != null && n.item().id.equals(si.id)) moving = tree.remove(i).item();
                else if (n.folder() != null) {
                    for (int j = 0; j < n.children().size(); j++) {
                        if (n.children().get(j).id.equals(si.id)) {
                            moving = n.children().remove(j);
                            break;
                        }
                    }
                }
            }
            if (moving == null) return;
            rootEntry = new Node(null, moving, null);
        }

        String expand = null;
        if (target == null) {
            tree.add(rootEntry);
        } else if (target instanceof Store.Folder tf) {
            int i = indexOf(tree, n -> n.folder() != null && n.folder().id.equals(tf.id));
            if (i < 0) return;
            if ("into".equals(pos)) {
                tree.get(i).children().add(moving);
                if (tf.collapsed) expand = tf.id;
            } else {
                tree.add("before".equals(pos) ? i : i + 1, rootEntry);
            }
        } else {
            Store.Item ti = (Store.Item) target;
            int pi = indexOf(tree, n -> n.folder() != null && n.children().stream().anyMatch(c -> c.id.equals(ti.id)));
            if (pi >= 0 && moving != null) {
                List<Store.Item> kids = tree.get(pi).children();
                int j = indexOf(kids, c -> c.id.equals(ti.id));
                kids.add("before".equals(pos) ? j : j + 1, moving);
            } else if (pi >= 0) {
                // A folder can't go inside a folder: put it next to the folder holding the target.
                tree.add("before".equals(pos) ? pi : pi + 1, rootEntry);
            } else {
                int i = indexOf(tree, n -> n.item() != null && n.item().id.equals(ti.id));
                if (i < 0) return;
                tree.add("before".equals(pos) ? i : i + 1, rootEntry);
            }
        }

        com.fasterxml.jackson.databind.node.ArrayNode entries = Store.MAPPER.createArrayNode();
        for (Node n : tree) {
            if (n.folder() != null) {
                entries.addObject().put("type", "folder").put("id", n.folder().id);
                for (Store.Item c : n.children()) {
                    entries.addObject().put("type", "item").put("id", c.id).put("folderId", n.folder().id);
                }
            } else {
                entries.addObject().put("type", "item").put("id", n.item().id).putNull("folderId");
            }
        }
        String open = expand;
        ctx.storeRun(s -> {
            s.saveTreeOrder(kind, entries);
            if (open != null) s.updateFolder(kind, open, null, false);
        });
        render();
        listener.changed();
    }

    private static <T> int indexOf(List<T> list, java.util.function.Predicate<T> p) {
        for (int i = 0; i < list.size(); i++) if (p.test(list.get(i))) return i;
        return -1;
    }

    private void moveTo(Store.Item it, String folderId) {
        ctx.store(s -> s.moveItem(kind, it.id, folderId));
        render();
        listener.changed();
    }

    private JComponent folderRow(Store.Folder f, List<Store.Item> kids) {
        Row row = new Row();
        long on = kids.stream().filter(i -> i.selected).count();
        JLabel icon = new JLabel(Ui.icon(f.collapsed ? "folder" : "folder-open", 16, on > 0 ? Ui.BC500 : new Color(0x5e6158)));
        JLabel name = new JLabel(f.name);
        name.setFont(Ui.font(Font.PLAIN, 13f));
        name.setForeground(Ui.TEXT);
        JLabel badge = new JLabel(on + "/" + kids.size()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(on > 0 ? Ui.BC100 : new Color(0xeeefea));
                g2.fillRoundRect(0, (getHeight() - 20) / 2, getWidth(), 20, 20, 20);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setFont(Ui.mono(11f));
        badge.setForeground(on > 0 ? new Color(0x23251f) : new Color(0x5e6158));
        badge.setBorder(new EmptyBorder(0, 7, 0, 7));
        badge.setHorizontalAlignment(SwingConstants.CENTER);
        JButton del = Ui.iconButton("trash", ctx.m("tooltip.delete.folder"), true, () -> {
            if (!ctx.confirm(this, ctx.m("confirm.delete.folder"))) return;
            ctx.storeRun(s -> s.deleteFolder(kind, f.id));
            render();
            listener.changed();
        });
        del.setPreferredSize(new Dimension(26, 26));
        del.setVisible(false);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 8));
        east.setOpaque(false);
        east.add(badge);
        east.add(del);
        JPanel center = new JPanel(new CardLayout());
        center.setOpaque(false);
        center.add(name, "label");
        row.add(icon, BorderLayout.WEST);
        row.add(center, BorderLayout.CENTER);
        row.add(east, BorderLayout.EAST);
        row.hoverOn(name, icon, badge, del);
        // The delete button shows while the pointer is over the row, as in the web list.
        MouseAdapter showDel = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!dragging) del.setVisible(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), row);
                if (!row.contains(p)) del.setVisible(false);
            }
        };
        for (Component c : new Component[]{row, name, icon, badge, del}) c.addMouseListener(showDel);
        // A click toggles the folder; wait so a double-click (rename) is not also a toggle.
        Timer toggle = new Timer(250, e -> {
            ctx.store(s -> s.updateFolder(kind, f.id, null, !f.collapsed));
            render();
        });
        toggle.setRepeats(false);
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getWhen() <= dragEndedAt) return;
                if (e.getClickCount() == 1) {
                    toggle.restart();
                } else {
                    toggle.stop();
                    startRename(center, f.name, n -> {
                        ctx.store(s -> s.updateFolder(kind, f.id, n, null));
                        render();
                    });
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                JPopupMenu m = new JPopupMenu();
                JMenuItem rename = new JMenuItem(ctx.m("app.menu.rename"));
                rename.addActionListener(a -> startRename(center, f.name, n -> {
                    ctx.store(s -> s.updateFolder(kind, f.id, n, null));
                    render();
                }));
                m.add(rename);
                m.addSeparator();
                JMenuItem d = new JMenuItem(ctx.m("tooltip.delete.folder"), Ui.icon("trash", 14, Ui.DANGER));
                d.addActionListener(a -> del.doClick());
                m.add(d);
                m.show(e.getComponent(), e.getX(), e.getY());
            }
        };
        for (Component c : new Component[]{row, name, icon, badge}) c.addMouseListener(click);
        draggable(row, f, null, name, icon, badge);
        return row;
    }

    /** Swaps the name label for a text field; Enter or focus loss commits, Esc cancels. */
    private void startRename(JPanel holder, String current, Consumer<String> commit) {
        JTextField field = new JTextField(current);
        field.setFont(Ui.font(Font.BOLD, 13f));
        field.putClientProperty("FlatLaf.style", "arc:4; focusedBorderColor:#a8d878; borderColor:#a8d878");
        field.setBackground(Ui.BC100);
        holder.add(field, "edit");
        ((CardLayout) holder.getLayout()).show(holder, "edit");
        field.selectAll();
        field.requestFocusInWindow();
        boolean[] done = {false};
        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            String n = field.getText().strip();
            if (n.isEmpty() || n.equals(current)) render();
            else commit.accept(n);
        };
        field.addActionListener(e -> finish.run());
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                finish.run();
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    done[0] = true;
                    render();
                }
            }
        });
    }
}
