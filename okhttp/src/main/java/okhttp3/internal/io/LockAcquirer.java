package okhttp3.internal.io;

import java.io.File;
import java.io.IOException;

/**
 * Acquires exclusive file locks. The strategy for obtaining exclusive locks varies between
 * platforms.
 */
public interface LockAcquirer {
  /**
   * Acquires an exclusive lock on {@code file}. Callers should hold a strong reference to the file.
   * When a file lock is no longer needed, call {@link #release(File)}}.
   */
  void acquire(File file) throws IOException;

  /** Releases an exclusive lock on {@code file}. */
  void release(File file);
}
