package tricatch.oehostsapp;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * One app per data directory: whoever holds an OS lock on {@code oehostsapp.lock} owns the data
 * file. The OS drops the lock when the process ends, so a crash never leaves the app locked out.
 *
 * <p>A second start does not open the store. It writes {@code oehostsapp.focus} next to the lock and
 * quits; the running app watches the directory and brings its window to the front. No network
 * port is involved.
 */
public final class SingleInstance implements AutoCloseable {

    static final String LOCK_FILE = "oehostsapp.lock";
    static final String FOCUS_FILE = "oehostsapp.focus";

    private final Path dir;
    private final RandomAccessFile file;
    private final FileLock lock;
    private WatchService watcher;

    private SingleInstance(Path dir, RandomAccessFile file, FileLock lock) {
        this.dir = dir;
        this.file = file;
        this.lock = lock;
    }

    /**
     * Takes the lock for {@code dir}. Returns null when another instance holds it, after asking
     * that instance to come to the front.
     */
    public static SingleInstance acquire(Path dir) throws IOException {
        Files.createDirectories(dir);
        RandomAccessFile raf = new RandomAccessFile(dir.resolve(LOCK_FILE).toFile(), "rw");
        FileLock lock;
        try {
            FileChannel ch = raf.getChannel();
            lock = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null; // held by this same JVM (tests)
        }
        if (lock == null) {
            raf.close();
            requestFocus(dir);
            return null;
        }
        raf.setLength(0);
        raf.write(String.valueOf(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_8));
        return new SingleInstance(dir, raf, lock);
    }

    static void requestFocus(Path dir) throws IOException {
        Files.writeString(dir.resolve(FOCUS_FILE), String.valueOf(System.currentTimeMillis()), StandardCharsets.UTF_8);
    }

    /** Runs {@code onFocus} (on a background thread) whenever another start asks for the window. */
    public void watchFocusRequests(Runnable onFocus) throws IOException {
        watcher = dir.getFileSystem().newWatchService();
        dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    WatchKey key = watcher.take();
                    boolean focus = false;
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        if (ev.context() instanceof Path p && p.getFileName().toString().equals(FOCUS_FILE)) focus = true;
                    }
                    key.reset();
                    if (focus) onFocus.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ClosedWatchServiceException e) {
                // closed: stop watching
            }
        }, "oehostsapp-single-instance");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() throws IOException {
        if (watcher != null) watcher.close();
        lock.release();
        file.close();
    }
}
