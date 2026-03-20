// Copyright 2024 The Bazel Authors. All rights reserved.
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
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.devtools.build.lib.remote.grpc.SharedConnectionFactory.SharedConnection;
import com.google.devtools.build.lib.remote.util.RxNoGlobalErrorsRule;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests that reproduce virtual thread pinning in {@link SharedConnectionFactory} / {@link
 * TokenBucket}.
 *
 * <p>The root cause is an interaction between RxJava3's {@code BehaviorSubject} internals and
 * virtual thread pinning on JDK 21. Specifically:
 *
 * <ul>
 *   <li>{@code BehaviorSubject.onNext()} acquires a <b>write lock</b> (in {@code setCurrent()}),
 *       then iterates over subscribers calling {@code emitNext()} on each.
 *   <li>{@code BehaviorDisposable.emitFirst()} (called when a new subscriber is created) enters a
 *       {@code synchronized(this)} block and then tries to acquire the <b>read lock</b> inside it.
 *   <li>When a virtual thread runs {@code emitFirst()}, it enters the {@code synchronized} block
 *       (pinning itself to its carrier thread), then tries to acquire the read lock. If the write
 *       lock is held by another thread in {@code onNext()}, the virtual thread blocks while pinned.
 *   <li>The thread holding the write lock (in {@code onNext()}) then tries to call {@code
 *       emitNext()} on the same subscriber, which needs the {@code synchronized} monitor held by
 *       the pinned virtual thread.
 * </ul>
 *
 * <p>This creates a deadlock: the virtual thread holds the monitor and waits for the read lock; the
 * other thread holds the write lock and waits for the monitor.
 *
 * <p><b>Important:</b> This test must be run with {@code
 * -Djdk.virtualThreadScheduler.parallelism=2} (set in the BUILD file) to constrain the virtual
 * thread carrier pool, making the deadlock deterministic.
 */
@RunWith(JUnit4.class)
public class SharedConnectionFactoryVirtualThreadPinningTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Rule public final RxNoGlobalErrorsRule rxNoGlobalErrorsRule = new RxNoGlobalErrorsRule();

  private ExecutorService virtualThreadExecutor;

  @After
  public void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdownNow();
    }
  }

  /**
   * Reproduces the deadlock by racing token acquisition (subscribe → emitFirst) against token
   * release (addToken → onNext → setCurrent/emitNext).
   *
   * <p>The deadlock requires two concurrent operations on the same BehaviorSubject:
   *
   * <ol>
   *   <li><b>Subscribe path</b> (virtual thread): {@code acquireToken().subscribe()} → {@code
   *       BehaviorSubject.subscribeActual()} → {@code emitFirst()} → {@code synchronized(this)} →
   *       {@code readLock.lock()} (blocks if write lock held, while pinned)
   *   <li><b>Emit path</b> (any thread): {@code addToken()} → {@code onNext()} → {@code
   *       setCurrent()} acquires write lock → iterates subscribers → {@code emitNext()} → needs
   *       {@code synchronized(this)} on the subscriber from step 1
   * </ol>
   *
   * <p>When these race, the virtual thread in step 1 holds the monitor and waits for the read lock
   * (pinned), while the thread in step 2 holds the write lock and waits for the monitor. Classic
   * lock-ordering deadlock, made possible by virtual thread pinning preventing the carrier from
   * being reused.
   */
  @Test
  public void concurrentSubscribeAndEmit_shouldNotDeadlock() throws Exception {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    Connection connection =
        new Connection() {
          @Override
          public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> call(
              io.grpc.MethodDescriptor<ReqT, RespT> method, io.grpc.CallOptions options) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close() {}
        };
    ConnectionFactory connectionFactory = () -> Single.just(connection);

    // Repeat internally — the deadlock is a race condition so we give it many attempts.
    for (int attempt = 0; attempt < 20; attempt++) {
      // maxConcurrency=1 maximizes the race: only one token exists, so every acquire must
      // wait for a release, forcing concurrent subscribe (emitFirst) and emit (onNext).
      int maxConcurrency = 1;
      SharedConnectionFactory factory =
          new SharedConnectionFactory(connectionFactory, maxConcurrency);

      int totalOperations = 200;
      CountDownLatch done = new CountDownLatch(totalOperations);
      ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
      AtomicInteger completed = new AtomicInteger(0);

      for (int i = 0; i < totalOperations; i++) {
        virtualThreadExecutor.submit(
            () -> {
              try {
                // acquire → subscribe to BehaviorSubject (emitFirst path)
                TestObserver<SharedConnection> observer = factory.create().test();
                observer.await(10, TimeUnit.SECONDS);

                if (observer.values().isEmpty()) {
                  throw new AssertionError(
                      "No connection received — deadlock in BehaviorSubject "
                          + "(emitFirst holds monitor, waits for readLock; "
                          + "onNext holds writeLock, waits for monitor)");
                }

                // release → addToken → BehaviorSubject.onNext (emit path)
                // This races with other virtual threads' subscribe (emitFirst path).
                observer.values().get(0).close();

                completed.incrementAndGet();
              } catch (Throwable t) {
                errors.add(t);
              } finally {
                done.countDown();
              }
            });
      }

      boolean finished = done.await(10, TimeUnit.SECONDS);

      assertWithMessage(
              "Attempt %s: Timed out — %s/%s completed. "
                  + "Deadlock: emitFirst() holds synchronized monitor and waits for readLock "
                  + "(pinned on carrier), while onNext/setCurrent holds writeLock and waits "
                  + "for the same monitor. Errors: %s",
              attempt, completed.get(), totalOperations, errors)
          .that(finished)
          .isTrue();

      assertThat(errors).isEmpty();
      assertThat(completed.get()).isEqualTo(totalOperations);
    }
  }

  /**
   * Same deadlock but with more concurrency tokens, matching the production scenario where multiple
   * remote-executor threads simultaneously return tokens while new Skyframe virtual threads
   * subscribe for new tokens.
   */
  @Test
  public void concurrentSubscribeAndEmit_multipleTokens_shouldNotDeadlock() throws Exception {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    Connection connection =
        new Connection() {
          @Override
          public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> call(
              io.grpc.MethodDescriptor<ReqT, RespT> method, io.grpc.CallOptions options) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close() {}
        };
    ConnectionFactory connectionFactory = () -> Single.just(connection);

    for (int attempt = 0; attempt < 10; attempt++) {
      int maxConcurrency = 4;
      SharedConnectionFactory factory =
          new SharedConnectionFactory(connectionFactory, maxConcurrency);

      // More operations than tokens to force queuing and maximize the subscribe/emit race.
      int totalOperations = 500;
      CountDownLatch allStarted = new CountDownLatch(totalOperations);
      CountDownLatch done = new CountDownLatch(totalOperations);
      ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
      AtomicInteger completed = new AtomicInteger(0);

      for (int i = 0; i < totalOperations; i++) {
        virtualThreadExecutor.submit(
            () -> {
              try {
                allStarted.countDown();
                allStarted.await(10, TimeUnit.SECONDS);

                TestObserver<SharedConnection> observer = factory.create().test();
                observer.await(10, TimeUnit.SECONDS);

                if (observer.values().isEmpty()) {
                  throw new AssertionError(
                      "No connection — deadlock between emitFirst and onNext");
                }

                // Small sleep to increase the chance that close() races with other
                // threads' subscribe() calls.
                Thread.sleep(1);

                observer.values().get(0).close();
                completed.incrementAndGet();
              } catch (Throwable t) {
                errors.add(t);
              } finally {
                done.countDown();
              }
            });
      }

      boolean finished = done.await(15, TimeUnit.SECONDS);

      assertWithMessage(
              "Attempt %s: Timed out — %s/%s completed. "
                  + "Deadlock from virtual thread pinning in BehaviorSubject. Errors: %s",
              attempt, completed.get(), totalOperations, errors)
          .that(finished)
          .isTrue();

      assertThat(errors).isEmpty();
      assertThat(completed.get()).isEqualTo(totalOperations);
    }
  }
}
