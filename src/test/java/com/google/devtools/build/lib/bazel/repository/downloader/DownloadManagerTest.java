// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository.downloader;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the retry behavior of {@link DownloadManager}. */
@RunWith(JUnit4.class)
public class DownloadManagerTest {

  @Rule public final TemporaryFolder workingDir = new TemporaryFolder();

  private final ExecutorService executor = Executors.newFixedThreadPool(1);
  private final AtomicInteger attempts = new AtomicInteger();

  @After
  public void after() {
    executor.shutdown();
  }

  /** A {@link Downloader} that counts its invocations and always fails with the given exception. */
  private Downloader throwingDownloader(Supplier<IOException> exception) {
    return (urls,
        headers,
        credentials,
        checksum,
        canonicalId,
        output,
        eventHandler,
        clientEnv,
        type,
        context) -> {
      attempts.incrementAndGet();
      throw exception.get();
    };
  }

  private DownloadManager newDownloadManagerForTest(Downloader downloader, int retries) {
    DownloadCache downloadCache = mock(DownloadCache.class);
    when(downloadCache.isEnabled()).thenReturn(false);
    HttpDownloader bzlmodHttpDownloader = mock(HttpDownloader.class);
    DownloadManager downloadManager =
        new DownloadManager(
            downloadCache, downloader, bzlmodHttpDownloader, mock(ExtendedEventHandler.class));
    downloadManager.setRetries(retries);
    return downloadManager;
  }

  private Future<Path> startDownload(DownloadManager downloadManager) throws IOException {
    JavaIoFileSystem fs = new JavaIoFileSystem(DigestHashFunction.SHA256);
    Path out = fs.getPath(workingDir.newFile().getAbsolutePath());
    return downloadManager.startDownload(
        executor,
        ImmutableList.of(URI.create("http://example.invalid/file")),
        ImmutableMap.of(),
        ImmutableMap.of(),
        Optional.empty(),
        "canonical",
        Optional.empty(),
        out,
        ImmutableMap.of(),
        "ctx",
        new Phaser(),
        /* mayHardlink= */ false);
  }

  @Test
  public void retriesOnGenericIOException() throws Exception {
    DownloadManager downloadManager =
        newDownloadManagerForTest(throwingDownloader(() -> new IOException("boom")), 2);

    Future<Path> f = startDownload(downloadManager);

    assertThrows(IOException.class, () -> downloadManager.finalizeDownload(f));
    assertThat(attempts.get()).isEqualTo(3); // 1 initial + 2 retries
  }

  @Test
  public void doesNotRetryOnUnrecoverableHttpException() throws Exception {
    DownloadManager downloadManager =
        newDownloadManagerForTest(
            throwingDownloader(() -> new UnrecoverableHttpException("nope")), 5);

    Future<Path> f = startDownload(downloadManager);

    assertThrows(IOException.class, () -> downloadManager.finalizeDownload(f));
    assertThat(attempts.get()).isEqualTo(1);
  }

  @Test
  public void doesNotRetryOnFileNotFoundException() throws Exception {
    DownloadManager downloadManager =
        newDownloadManagerForTest(
            throwingDownloader(() -> new FileNotFoundException("missing")), 5);

    Future<Path> f = startDownload(downloadManager);

    assertThrows(IOException.class, () -> downloadManager.finalizeDownload(f));
    assertThat(attempts.get()).isEqualTo(1);
  }

  @Test
  public void retriesOnSocketTimeoutException() throws Exception {
    DownloadManager downloadManager =
        newDownloadManagerForTest(
            throwingDownloader(() -> new SocketTimeoutException("timed out")), 2);

    Future<Path> f = startDownload(downloadManager);

    assertThrows(IOException.class, () -> downloadManager.finalizeDownload(f));
    assertThat(attempts.get()).isEqualTo(3); // 1 initial + 2 retries
  }
}
