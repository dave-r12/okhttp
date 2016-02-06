/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.io;

import java.io.IOException;

import static okhttp3.internal.Util.closeQuietly;

/**
 * A minimal, pared down, testable FileLock based on {@link java.nio.channels.FileLock}.
 */
public interface FileLock {
  class NioFileLock implements FileLock {
    private final java.nio.channels.FileLock fileLock;

    public NioFileLock(java.nio.channels.FileLock fileLock) {
      this.fileLock = fileLock;
    }

    @Override public void release() {
      try {
        fileLock.release();
      } catch (IOException ignored) {
      }
      closeQuietly(fileLock.channel());
    }
  }

  void release();
}
