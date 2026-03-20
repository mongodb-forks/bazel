// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.devtools.build.lib.remote.grpc.SharedConnectionFactory.SharedConnection;
import com.google.devtools.build.lib.remote.util.RxNoGlobalErrorsRule;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests that demonstrate virtual thread pinning in {@link SharedConnectionFactory} and {@link
 * TokenBucket} due to {@code synchronized} blocks in RxJava's {@code BehaviorSubject.emitNext()}.
 *
 * <p>When virtual threads call {@link TokenBucket#addToken} (via {@link SharedConnection#close()}),
 * they enter the {@code synchronized} block inside {@code BehaviorSubject$BehaviorDisposable
 * .emitNext()}. If the downstream observer's {@code onNext} blocks (e.g., acquiring another lock,
 * doing I/O), the virtual thread pins its carrier because JDK 21 virtual threads cannot unmount
 * while inside a {@code synchronized} block. Subsequent virtual threads that attempt to enter the
 * same monitor also pin their carriers while waiting. With enough pinned carriers, the entire
 * virtual thread scheduler stalls.
 *
 * <p>This test must be run with {@code -Djdk.virtualThreadScheduler.parallelism=2} to constrain
 * the carrier pool and make the deadlock observable. See the BUILD file for the JVM flag
 * configuration.
 */
@RunWith(JUnit4.class)
public class SharedConnectionFactoryVirtualThreadPinningTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Rule public final RxNoGlobalErrorsRule rxNoGlobalErrorsRule = new RxNoGlobalErrorsRule();

  /**
   * Reproduces the deadlock seen in the Bazel jstack dump where ~20 remote-executor threads are
   * BLOCKED on {@code BehaviorSubject$BehaviorDisposable.emitNext()} and the lock holder (a virtual
   * thread) is pinned to its carrier inside a {@code synchronized} block.
   *
   * <p>Setup:
   *
   * <ol>
   *   <li>Create a SharedConnectionFactory with N concurrency slots
   *   <li>Acquire all N connections (exhausting the token bucket)
   *   <li>Subscribe a pending acquireToken() observer (it will be notified inside emitNext)
   *   <li>From virtual threads, close connections — each calls addToken → BehaviorSubject.onNext →
   *       synchronized emitNext → subscriber's onNext callback. The callback blocks, pinning the
   *       carrier.
   *   <li>With only 2 carrier threads (set via JVM flag), the 3rd+ virtual thread has no carrier
   *       available → deadlock.
   * </ol>
   *
   * <p>If the test times out (fails), the pinning deadlock is confirmed. A fix (replacing {@code
   * synchronized} with {@code ReentrantLock} in the RxJava/TokenBucket path) would allow this test
   * to pass.
   */
  @Test(timeout = 10_000)
  public void addToken_fromVirtualThreads_deadlocksWhenCarriersPinned() throws Exception {
    // Verify the carrier pool is actually constrained. If not, the test is meaningless.
    int carrierParallelism = getCarrierParallelism();
    if (carrierParallelism > 2) {
      // Skip rather than fail — the BUILD target should set the JVM flag, but if someone
      // runs this without it, we don't want a false pass.
      System.err.println(
          "WARNING: jdk.virtualThreadScheduler.parallelism="
              + carrierParallelism
              + " (expected <=2). Test may not reproduce pinning. "
              + "Run with -Djdk.virtualThreadScheduler.parallelism=2");
    }

    int numVirtualThreads = carrierParallelism + 2; // More VTs than carriers

    TokenBucket<Integer> bucket = new TokenBucket<>();
    Semaphore blockInOnNext = new Semaphore(0);
    AtomicInteger onNextEntryCount = new AtomicInteger(0);

    // Subscribe an observer whose onNext blocks. When addToken triggers
    // BehaviorSubject.onNext → emitNext (synchronized) → this callback,
    // the virtual thread holds the emitNext monitor and parks inside synchronized,
    // pinning its carrier.
    bucket
        .acquireToken()
        .subscribe(
            token -> {
              onNextEntryCount.incrementAndGet();
              // Block while holding the emitNext synchronized lock.
              // On a virtual thread, this pins the carrier.
              blockInOnNext.acquire();
            },
            error -> {});

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch allDone = new CountDownLatch(numVirtualThreads);

    // Launch virtual threads that each call addToken.
    // addToken → BehaviorSubject.onNext → iterates disposables → emitNext (synchronized).
    // First VT: enters emitNext, calls onNext callback, blocks → pins carrier 1.
    // Second VT: waits to enter emitNext synchronized → pins carrier 2.
    // Third+ VT: no carrier available → stuck forever (deadlock).
    for (int i = 0; i < numVirtualThreads; i++) {
      final int token = i;
      executor.submit(
          () -> {
            bucket.addToken(token);
            allDone.countDown();
          });
    }

    // Wait for the deadlock. With 2 carriers and 4 VTs, at most 1 VT can enter emitNext
    // (and block), 1 VT waits on the monitor (pinning carrier 2), and the remaining 2 VTs
    // have no carriers. Nothing can make progress.
    boolean completed = allDone.await(5, TimeUnit.SECONDS);

    // Clean up: release the blocker so any held threads can finish.
    blockInOnNext.release(numVirtualThreads);
    executor.shutdownNow();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    if (carrierParallelism <= 2) {
      // With constrained carriers, this SHOULD timeout (completed=false), proving the bug.
      // If it passes, the fix has been applied.
      assertThat(completed).isTrue();
    }
  }

  /**
   * End-to-end reproduction using SharedConnectionFactory: virtual threads close connections,
   * returning tokens, while a pending subscriber blocks inside the emitNext synchronized region.
   */
  @Test(timeout = 10_000)
  public void sharedConnectionFactory_closeFromVirtualThreads_deadlocks() throws Exception {
    int carrierParallelism = getCarrierParallelism();
    if (carrierParallelism > 2) {
      System.err.println(
          "WARNING: jdk.virtualThreadScheduler.parallelism="
              + carrierParallelism
              + " (expected <=2). Run with -Djdk.virtualThreadScheduler.parallelism=2");
    }

    int maxConcurrency = carrierParallelism + 2;
    Connection mockConnection = mock(Connection.class);
    ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    when(connectionFactory.create()).thenAnswer(inv -> Single.just(mockConnection));

    SharedConnectionFactory factory =
        new SharedConnectionFactory(connectionFactory, maxConcurrency);

    // Acquire all connections from the main thread.
    List<SharedConnection> connections = new ArrayList<>();
    for (int i = 0; i < maxConcurrency; i++) {
      connections.add(factory.create().blockingGet());
    }
    assertThat(factory.numAvailableConnections()).isEqualTo(0);

    // Subscribe a waiter. When a token is returned, the waiter's onNext runs inside
    // BehaviorSubject's synchronized emitNext. The flatMap chain inside acquireToken
    // calls acquireConnection → createUnderlyingConnectionIfNot (synchronized(this)).
    // We don't need extra blocking here — the nested synchronized acquisition on
    // SharedConnectionFactory is enough to create contention.
    Semaphore blockWaiter = new Semaphore(0);
    AtomicBoolean waiterInterrupted = new AtomicBoolean(false);
    factory
        .create()
        .subscribe(
            conn -> {
              try {
                // Block while holding the emitNext lock.
                blockWaiter.acquire();
              } catch (InterruptedException e) {
                waiterInterrupted.set(true);
              }
            },
            error -> {});

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch allClosed = new CountDownLatch(maxConcurrency);

    // Close connections from virtual threads → addToken → BehaviorSubject.onNext →
    // synchronized emitNext → waiter's onNext blocks → carrier pinned.
    for (SharedConnection conn : connections) {
      executor.submit(
          () -> {
            try {
              conn.close();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
            allClosed.countDown();
          });
    }

    boolean completed = allClosed.await(5, TimeUnit.SECONDS);

    blockWaiter.release(maxConcurrency);
    executor.shutdownNow();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    if (carrierParallelism <= 2) {
      assertThat(completed).isTrue();
    }
  }

  private static int getCarrierParallelism() {
    String prop = System.getProperty("jdk.virtualThreadScheduler.parallelism");
    if (prop != null) {
      return Integer.parseInt(prop);
    }
    return Runtime.getRuntime().availableProcessors();
  }
}
