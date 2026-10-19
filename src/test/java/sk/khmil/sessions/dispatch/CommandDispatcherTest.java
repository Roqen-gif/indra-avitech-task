package sk.khmil.sessions.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class CommandDispatcherTest {

  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

  private final CommandDispatcher dispatcher = new CommandDispatcher(4);

  @AfterEach
  void tearDown() {
    dispatcher.shutdown(SHUTDOWN_TIMEOUT);
  }

  @Test
  void tasksWithSameKeyRunInSubmissionOrder() {
    List<Integer> executed = Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < 1_000; i++) {
      int index = i;
      dispatcher.dispatch("alice", () -> executed.add(index));
    }

    assertTrue(dispatcher.shutdown(SHUTDOWN_TIMEOUT));
    assertEquals(IntStream.range(0, 1_000).boxed().toList(), executed);
  }

  @Test
  void loginIsAlwaysProcessedBeforeLaterDataModifyOfSameUser() {
    Set<String> loggedIn = ConcurrentHashMap.newKeySet();
    AtomicInteger acceptedModifications = new AtomicInteger();

    for (int user = 0; user < 100; user++) {
      String userId = "user-" + user;
      dispatcher.dispatch(userId, () -> loggedIn.add(userId));
      dispatcher.dispatch(
          userId,
          () -> {
            if (loggedIn.contains(userId)) {
              acceptedModifications.incrementAndGet();
            }
          });
    }

    assertTrue(dispatcher.shutdown(SHUTDOWN_TIMEOUT));
    assertEquals(100, acceptedModifications.get());
  }

  @Test
  void tasksWithSameKeyRunOnSameThread() {
    Set<String> threadNames = ConcurrentHashMap.newKeySet();

    for (int i = 0; i < 100; i++) {
      dispatcher.dispatch("alice", () -> threadNames.add(Thread.currentThread().getName()));
    }

    assertTrue(dispatcher.shutdown(SHUTDOWN_TIMEOUT));
    assertEquals(1, threadNames.size());
    assertTrue(threadNames.iterator().next().startsWith("worker-"));
  }

  @Test
  void failingTaskDoesNotStopLaterTasksOfSameWorker() {
    AtomicInteger completed = new AtomicInteger();

    dispatcher.dispatch(
        "alice",
        () -> {
          throw new IllegalStateException("boom");
        });
    dispatcher.dispatch("alice", completed::incrementAndGet);
    dispatcher.dispatch("bob", completed::incrementAndGet);

    assertTrue(dispatcher.shutdown(SHUTDOWN_TIMEOUT));
    assertEquals(2, completed.get());
  }

  @Test
  void exclusiveActionSeesExactlyTasksSubmittedBeforeIt() {
    AtomicInteger counter = new AtomicInteger();
    AtomicInteger snapshot = new AtomicInteger(-1);

    for (int i = 0; i < 500; i++) {
      dispatcher.dispatch("user-" + i, counter::incrementAndGet);
    }
    dispatcher.runExclusively(() -> snapshot.set(counter.get()));
    for (int i = 0; i < 500; i++) {
      dispatcher.dispatch("user-" + i, counter::incrementAndGet);
    }

    assertTrue(dispatcher.shutdown(SHUTDOWN_TIMEOUT));
    assertEquals(500, snapshot.get());
    assertEquals(1_000, counter.get());
  }

  @Test
  void failingExclusiveActionDoesNotBlockWorkers() {
    AtomicInteger completed = new AtomicInteger();

    dispatcher.runExclusively(
        () -> {
          throw new IllegalStateException("boom");
        });
    dispatcher.dispatch("alice", completed::incrementAndGet);

    assertTrue(dispatcher.shutdown(SHUTDOWN_TIMEOUT));
    assertEquals(1, completed.get());
  }

  @Test
  void exclusiveActionRunsWithoutWaitingForShutdown() throws InterruptedException {
    CountDownLatch actionRan = new CountDownLatch(1);

    dispatcher.runExclusively(actionRan::countDown);

    actionRan.await();
  }

  @Test
  void shutdownCompletesAllQueuedTasks() {
    AtomicInteger completed = new AtomicInteger();

    for (int i = 0; i < 10_000; i++) {
      dispatcher.dispatch("user-" + (i % 37), completed::incrementAndGet);
    }

    assertTrue(dispatcher.shutdown(SHUTDOWN_TIMEOUT));
    assertEquals(10_000, completed.get());
  }

  @Test
  void shutdownReturnsFalseWhenTasksDoNotFinishInTime() throws InterruptedException {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch neverReleased = new CountDownLatch(1);
    dispatcher.dispatch(
        "alice",
        () -> {
          started.countDown();
          try {
            neverReleased.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    started.await();

    assertFalse(dispatcher.shutdown(Duration.ofMillis(100)));
  }

  @Test
  void rejectsNonPositiveWorkerCount() {
    assertThrows(IllegalArgumentException.class, () -> new CommandDispatcher(0));
  }
}
