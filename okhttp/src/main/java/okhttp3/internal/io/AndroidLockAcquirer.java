package okhttp3.internal.io;

import android.os.FileObserver;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import okhttp3.internal.Internal;

/**
 * The {@link java.nio.channels.FileLock} implementation suffers from 2 bugs that make it difficult
 * to use reliably on Android. Instead, we use {@link android.os.FileObserver} to accomplish the
 * same idea. In a nutshell, each cache will contain a single lock file that we monitor. The
 * protocol looks like:
 *
 * <ul>
 *   <li>Open FileObserver and start watching for events</li>
 *   <li>Open and close the lock file (this generates some events)</li>
 * </ul>
 *
 * <p>If we receive more than 1 close event, we can assert that more than 1 cache is using the lock
 * file and explode, notifying the user they are trying to do something that is unsafe.
 */
public class AndroidLockAcquirer implements LockAcquirer {

  private static final Object lock = new Object();

  /** Guarded by lock. */
  private static final Map<String, LockFileObserver> locksInUse = new LinkedHashMap<>();
  private static final Set<LockFileReference> instances = new LinkedHashSet<>();

  static class LockFileReference extends WeakReference<File> {
    final String path;
    public LockFileReference(File referent) {
      super(referent);
      path = referent.getAbsolutePath();
    }
  }

  static class LockFileObserver extends FileObserver {
    /** This is thread-confined to a single thread used by {@link android.os.FileObserver}. */
    private boolean seenCloseEvent;

    public LockFileObserver(String path) {
      super(path);
    }

    @Override public void onEvent(int event, final String path) {
      if (seenCloseEvent) {
        // we can't throw here because the FileObserver implementation catches Throwable!
        new Thread() {
          @Override public void run() {
            throw new IllegalStateException("multiple instances are trying to use the same file"
                + "simultaneously: " + path);
          }
        }.start();

        Internal.logger.warning("multiple instances are trying to use the same file: " + path);
        return;
      }

      if (event == FileObserver.CLOSE_NOWRITE) {
        seenCloseEvent = true;
      }
    }
  }

  @Override public void acquire(File file) throws IOException {
    synchronized (lock) {
      // release any previous observers on files that may have been leaked by application
      Iterator<LockFileReference> references = instances.iterator();
      while (references.hasNext()) {
        LockFileReference reference = references.next();
        if (reference.get() == null) {
          references.remove();

          if (locksInUse.containsKey(reference.path)) {
            Internal.logger.warning("leaked instance");
            release(reference.path);
          }
        }
      }

      String absolutePath = file.getAbsolutePath();
      if (locksInUse.containsKey(absolutePath)) {
        throw new IllegalStateException("multiple instances are trying to use same path:"
            + absolutePath);
      }

      try {
        file.createNewFile();
      } catch (FileNotFoundException e) {
        file.getParentFile().mkdirs();
        file.createNewFile();
      }

      LockFileObserver lockFileObserver = new LockFileObserver(absolutePath);
      locksInUse.put(absolutePath, lockFileObserver);
      lockFileObserver.startWatching();

      boolean closed = false;
      try {
        FileInputStream fis = new FileInputStream(file);
        fis.close();
        closed = true;
      } finally {
        if (!closed) {
          release(absolutePath);
        }
      }
    }
  }

  private void release(String file) {
    assert Thread.holdsLock(lock);

    LockFileObserver lockFileObserver = locksInUse.remove(file);
    lockFileObserver.stopWatching();
  }

  @Override public void release(File file) {
    synchronized (lock) {
      release(file.getAbsoluteFile());
    }
  }
}
