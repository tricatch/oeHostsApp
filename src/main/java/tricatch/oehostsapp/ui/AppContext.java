package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.Messages;
import tricatch.oehostsapp.store.Settings;
import tricatch.oehostsapp.store.Store;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What every screen of one window build shares: the data store (all access goes through
 * {@link #store} / {@link #read} so the store's lock is held), the UI language and its messages,
 * "settings changed" listeners, and the subscriptions to the app's services, which
 * {@link #dispose} drops when the screens are rebuilt.
 */
public final class AppContext {

    private final Store store;
    private final String lang;
    private final Map<String, String> msg;
    private final Listeners settingsListeners = new Listeners();
    private final List<Listeners.Subscription> subscriptions = new ArrayList<>();
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
    public <T> T store(Function<Store, T> op) {
        try {
            synchronized (store) {
                return op.apply(store);
            }
        } catch (Store.StoreException e) {
            toast(m("name_taken".equals(e.code) ? "toast.profile.name.taken" : "status.save.failed"));
            return null;
        }
    }

    /** Runs {@code op} with the store locked; false (after a message) when it failed. */
    public boolean storeRun(Consumer<Store> op) {
        return store(s -> {
            op.accept(s);
            return Boolean.TRUE;
        }) != null;
    }

    /** Changes the settings with the store locked and saves them; false (after a message) when it failed. */
    public boolean saveSettings(Consumer<Settings> change) {
        return storeRun(s -> {
            change.accept(s.data.settings);
            s.save();
        });
    }

    public <T> T read(Supplier<T> op) {
        synchronized (store) {
            return op.get();
        }
    }

    /** Reads the settings with the store locked. */
    public <T> T settings(Function<Settings, T> op) {
        return read(() -> op.apply(store.data.settings));
    }

    public Store rawStore() {
        return store;
    }

    /**
     * Subscribes {@code onEdt} to a service's changes ({@code ShareServer::onChange} and the like):
     * it runs on the event thread, and only until {@link #dispose}.
     */
    public void listen(Function<Runnable, Listeners.Subscription> source, Runnable onEdt) {
        subscriptions.add(source.apply(() -> SwingUtilities.invokeLater(onEdt)));
    }

    public void onSettingsChanged(Runnable r) {
        subscriptions.add(settingsListeners.add(r));
    }

    public void fireSettingsChanged() {
        settingsListeners.fire();
    }

    /** Drops every subscription made through this context (the screens are being replaced). */
    public void dispose() {
        for (Listeners.Subscription s : subscriptions) s.close();
        subscriptions.clear();
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
