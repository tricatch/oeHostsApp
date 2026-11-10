package tricatch.oehubapp.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.BiFunction;

/**
 * Debounced save of an editor's content, after oeHub's autosave.js:
 * <ul>
 *   <li>an edit is saved {@link #DEBOUNCE_MS} after the last keystroke, or at once on Ctrl+S / Cmd+S
 *       ({@link #bindSaveKey}), when another item is opened, or on {@link #flush} (window closing);</li>
 *   <li>a save that failed for a reason worth retrying ({@link Result#retry}) is retried with
 *       backoff; one that cannot succeed as is ({@link Result#refused}) waits for the next edit;</li>
 *   <li>the editor is outlined amber while an edit is waiting and red while a failed save is being
 *       retried ({@code .oe-autosave-dirty} / {@code .oe-autosave-failed} in oe-panel.css), and the
 *       status label says what is going on.</li>
 * </ul>
 * Saving is local and synchronous, so unlike the web version there is never a save in flight.
 */
final class Autosave {

    static final int DEBOUNCE_MS = 2000;
    private static final int RETRY_MIN_MS = 2000;
    private static final int RETRY_MAX_MS = 30000;
    private static final int SAVED_SHOW_MS = 2000;
    private static final int NOTICE_SHOW_MS = 4000;
    static final Color DIRTY = new Color(0xf0c36d);
    static final Color FAILED = new Color(0xdc3545);

    /** What a save did: saved, refused (stays unsaved until the content changes) or retry later. */
    record Result(boolean saved, boolean retry, String message) {
        static final Result OK = new Result(true, false, null);

        static Result refused(String message) {
            return new Result(false, false, message);
        }

        static Result retry(String message) {
            return new Result(false, true, message);
        }
    }

    private final AppContext ctx;
    private final CodeEditor editor;
    private final JLabel status;
    private final BiFunction<String, String, Result> save;
    private final Timer debounce;
    private final Timer retry;
    private final Timer flash;
    private int retryDelay = RETRY_MIN_MS;
    private String id;
    /** Content the store is known to hold for {@link #id}; null when not known (nothing opened). */
    private String saved;
    /** Latest edit not yet saved, or null. */
    private String pending;
    private boolean failed;
    /** The content a save refused, and why; not tried again until the content changes. */
    private String refusedContent, refusedMessage;
    private String flashText;
    private boolean flashError;

    /** {@code save}: (id, content) -> what happened. */
    Autosave(AppContext ctx, CodeEditor editor, JLabel status, BiFunction<String, String, Result> save) {
        this.ctx = ctx;
        this.editor = editor;
        this.status = status;
        this.save = save;
        debounce = new Timer(DEBOUNCE_MS, e -> flush());
        debounce.setRepeats(false);
        retry = new Timer(RETRY_MIN_MS, e -> flush());
        retry.setRepeats(false);
        flash = new Timer(SAVED_SHOW_MS, e -> {
            flashText = null;
            render();
        });
        flash.setRepeats(false);
    }

    /**
     * Ctrl+S (Cmd+S on macOS) saves at once while the focus is anywhere inside {@code root}.
     * With nothing waiting it still shows "saved", so the keypress does not look ignored.
     */
    void bindSaveKey(JComponent root) {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        Action a = new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (id == null) return;
                boolean nothingToSave = !dirty();
                flush();
                if (nothingToSave) showFlash(ctx.m("status.saved"), false);
            }
        };
        for (JComponent c : new JComponent[]{root, editor.area}) {
            int when = c == root ? JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT : JComponent.WHEN_FOCUSED;
            c.getInputMap(when).put(ks, "oe-save");
            c.getActionMap().put("oe-save", a);
        }
    }

    /**
     * Switches to another item (null: nothing editable is shown), saving the previous one's
     * pending edit first. {@code content} is what the store holds for the new item.
     */
    void activate(String newId, String content) {
        flush();
        id = newId;
        saved = newId == null ? null : content;
        pending = null;
        failed = false;
        refusedContent = refusedMessage = null;
        flashText = null;
        retry.stop();
        render();
    }

    /** Call on every edit of the active item. */
    void changed(String content) {
        if (id == null) return;
        pending = content;
        flashText = null;
        render();
        debounce.restart();
    }

    /** Drops a pending edit (the item is being deleted). */
    void discard() {
        debounce.stop();
        retry.stop();
        pending = null;
        failed = false;
        render();
    }

    /** Saves a waiting edit now. */
    void flush() {
        debounce.stop();
        retry.stop();
        if (id == null || pending == null) return;
        String content = pending;
        if (content.equals(saved)) {
            pending = null;
            failed = false;
            render();
            return;
        }
        if (content.equals(refusedContent)) return;
        Result r = save.apply(id, content);
        if (r.saved()) {
            saved = content;
            if (content.equals(pending)) pending = null;
            failed = false;
            refusedContent = refusedMessage = null;
            retryDelay = RETRY_MIN_MS;
            showFlash(r.message() != null ? r.message() : ctx.m("status.saved"), r.message() != null);
        } else if (r.retry()) {
            failed = true;
            retry.setInitialDelay(retryDelay);
            retry.restart();
            retryDelay = Math.min(retryDelay * 2, RETRY_MAX_MS);
            render();
        } else {
            failed = false;
            refusedContent = content;
            refusedMessage = r.message();
            render();
        }
    }

    private boolean dirty() {
        return id != null && pending != null && !pending.equals(saved);
    }

    private void showFlash(String text, boolean error) {
        flashText = text;
        flashError = error;
        flash.setInitialDelay(error ? NOTICE_SHOW_MS : SAVED_SHOW_MS);
        flash.restart();
        render();
    }

    private void render() {
        boolean dirty = dirty();
        boolean isFailed = dirty && failed;
        boolean isRefused = dirty && pending.equals(refusedContent);
        String text = " ";
        boolean error = false;
        if (isFailed) {
            text = ctx.m("status.save.retrying");
            error = true;
        } else if (isRefused) {
            text = refusedMessage;
            error = true;
        } else if (dirty) {
            text = ctx.m("status.unsaved");
        } else if (flashText != null) {
            // "Saved" (or a save's notice) for a moment, then nothing.
            text = flashText;
            error = flashError;
        }
        editor.setOutline(isFailed ? FAILED : dirty ? DIRTY : null);
        status.setForeground(error ? Ui.DANGER : Ui.MUTED);
        status.setText(text);
    }

}
