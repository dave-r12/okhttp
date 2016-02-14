package okhttp3.internal.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import okhttp3.internal.Internal;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Leverages {@link FileLock} to obtain exclusive file locks.
 */
public class RealLockAcquirer implements LockAcquirer {

  private static final Object lock = new Object();

  static class RealLockAcquireReference extends WeakReference<File> {
    final String lockFileAbsolutPath;
    final String lockFileDir;
    RealLockAcquireReference(File referent) {
      super(referent);
      lockFileAbsolutPath = referent.getAbsolutePath();
      lockFileDir = referent.getParentFile().getAbsolutePath();
    }
  }

  /** Guarded by lock. */
  private static final Map<String, FileLock> locksInUse = new LinkedHashMap<>();
  private static final Set<RealLockAcquireReference> instances = new LinkedHashSet<>();

  @Override public void acquire(File file) throws IOException {
    synchronized (lock) {
      // clean up any file locks that may have been leaked by the application
      Iterator<RealLockAcquireReference> itr = instances.iterator();
      while (itr.hasNext()) {
        RealLockAcquireReference reference = itr.next();
        if (reference.get() == null) {
          itr.remove();
          if (locksInUse.containsKey(reference.lockFileAbsolutPath)) {
            Internal.logger.warning("you leaked a reference to " + reference.lockFileDir);
            release(reference.lockFileAbsolutPath);
          }
        }
      }

      if (locksInUse.containsKey(file)) {
        throw new IllegalStateException("multiple instances of are attempting to use the directory"
            + "simulataneously: " + file.getParent());
      }

      try {
        file.createNewFile();
      } catch (FileNotFoundException e) {
        file.getParentFile().mkdirs();
        file.createNewFile();
      }

      FileOutputStream fileOutputStream = null;
      FileLock fileLock = null;
      try {
        fileOutputStream = new FileOutputStream(file);
        fileLock = fileOutputStream.getChannel().tryLock();
        if (fileLock == null) {
          throw new IllegalStateException("multiple processes are attempting to use the directory "
              + "simultaneously: " + file.getParentFile());
        }
        locksInUse.put(file.getAbsolutePath(), fileLock);
        instances.add(new RealLockAcquireReference(file));
      } catch (OverlappingFileLockException e) {
        throw new IllegalStateException("multiple copies of OkHttp are loaded and are attempting "
            + "to use the same directory simultaneously: " + file.getParentFile());
      } finally {
        if (fileLock == null) {
          closeQuietly(fileOutputStream);
        }
      }
    }
  }

  private void release(String file) {
    assert Thread.holdsLock(lock);
    locksInUse.remove(file);
  }

  @Override public void release(File file) {
    synchronized (lock) {
      release(file.getAbsolutePath());
    }
  }
}
