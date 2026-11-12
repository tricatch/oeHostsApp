package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.Messages;
import tricatch.oehostsapp.Store;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What every screen shares: the data store (all access goes through {@link #store} so the
 * store's lock is held), the UI language and its messages, and "settings changed" listeners.
 */
public final class AppContext {

    private final Store store;
    private final String lang;
    private final Map<String, String> msg;
    private final List<Runnable> settingsListeners = new ArrayList<>();
    private JFrame frame;

    public AppContext(Store store) {
        this.store = store;
        String l;
        synchronized (store) {
            l = store.data.settings.lang;
        }
        if (l == null) l = Locale.getDefault().getLanguage();
        this.lang = Messages.normalize(l);
        this.msg = Messages.of(lang);
    }

    public String lang() {
        return lang;
    }

    /** Message for {@code key}; {0}, {1}... are replaced by {@code args}. Missing keys show as the key. */
    public String m(String key, Object... args) {
        String s = msg.getOrDefault(key, key);
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    /** Message as plain text (the web messages carry a little HTML: {@code <br>}, {@code <code>}). */
    public String plain(String key, Object... args) {
        return m(key, args).replaceAll("<br\\s*/?>", "\n").replaceAll("<[^>]+>", "");
    }

    /** Runs {@code op} with the store locked. A store error is shown as a message, returning null. */
    public <T> T store(java.util.function.Function<Store, T> op) {
        try {
            synchronized (store) {
                return op.apply(store);
            }
        } catch (Store.StoreException e) {
            String key = switch (e.code) {
                case "name_taken" -> "toast.profile.name.taken";
                default -> "status.save.failed";
            };
            toast(m(key));
            return null;
        }
    }

    public void storeRun(java.util.function.Consumer<Store> op) {
        store(s -> {
            op.accept(s);
            return Boolean.TRUE;
        });
    }

    public <T> T read(Supplier<T> op) {
        synchronized (store) {
            return op.get();
        }
    }

    public Store rawStore() {
        return store;
    }

    public void onSettingsChanged(Runnable r) {
        settingsListeners.add(r);
    }

    public void fireSettingsChanged() {
        for (Runnable r : List.copyOf(settingsListeners)) r.run();
    }

    void setFrame(JFrame frame) {
        this.frame = frame;
    }

    public JFrame frame() {
        return frame;
    }

    /** Short notice at the bottom of the window ({@code showToast} in util.js). */
    public void toast(String text) {
        if (frame != null) Toast.show(frame, text);
    }

    public boolean confirm(Component parent, String text) {
        return JOptionPane.showConfirmDialog(parent, text, "oeHostsApp", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }
}
