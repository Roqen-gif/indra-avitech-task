package sk.khmil.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sk.khmil.sessions.dispatch.CommandDispatcher;
import sk.khmil.sessions.persistence.Database;
import sk.khmil.sessions.persistence.JdbcUserRepository;
import sk.khmil.sessions.user.Outcome;
import sk.khmil.sessions.user.UserService;
import sk.khmil.sessions.user.UserStats;

/**
 * Hammers the service from many threads at once. A start latch releases all threads together to
 * maximise contention; nothing here relies on sleeping.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConcurrencyTest {

  private static final int THREADS = 50;
  private static final int USERS = 10;
  private static final int MODIFICATIONS_PER_USER = 100;

  private Database database;
  private UserService service;

  @BeforeEach
  void setUp() {
    database = Database.open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    service = new UserService(new JdbcUserRepository(database, Clock.systemUTC()));
  }

  @AfterEach
  void tearDown() {
    database.close();
  }

  @Test
  void onlyOneOfManyConcurrentLoginsOfSameUserSucceeds() throws InterruptedException {
    int accepted = runConcurrently(1, iteration -> service.login("alice"));

    assertEquals(1, accepted);
    assertEquals(1, service.stats().loggedInUsers());
  }

  @Test
  void onlyOneOfManyConcurrentLogoutsOfSameUserSucceeds() throws InterruptedException {
    service.login("alice");

    int accepted = runConcurrently(1, iteration -> service.logout("alice"));

    assertEquals(1, accepted);
    assertEquals(0, service.stats().loggedInUsers());
  }

  @Test
  void concurrentModificationsAreAllRecorded() throws InterruptedException {
    for (int user = 0; user < USERS; user++) {
      service.login(userId(user));
    }
    int iterationsPerThread = USERS * MODIFICATIONS_PER_USER / THREADS;

    int accepted =
        runConcurrently(iterationsPerThread, iteration -> service.modifyData(userId(iteration)));

    assertEquals(USERS * MODIFICATIONS_PER_USER, accepted);
    assertEquals(expectedStats(), service.stats());
  }

  @Test
  void dispatcherProcessesInterleavedCommandsOfManyUsersCorrectly() {
    CommandDispatcher dispatcher = new CommandDispatcher(4);

    for (int user = 0; user < USERS; user++) {
      String userId = userId(user);
      dispatcher.dispatch(userId, () -> service.login(userId));
    }
    for (int round = 0; round < MODIFICATIONS_PER_USER; round++) {
      for (int user = 0; user < USERS; user++) {
        String userId = userId(user);
        dispatcher.dispatch(userId, () -> service.modifyData(userId));
      }
    }

    assertTrue(dispatcher.shutdown(Duration.ofSeconds(20)));
    assertEquals(expectedStats(), service.stats());
  }

  /**
   * Starts {@value #THREADS} threads that wait on a common latch, so they all begin at the same
   * moment; each then calls {@code action} {@code iterations} times with the iteration index.
   *
   * @return how many outcomes were accepted in total
   */
  private static int runConcurrently(int iterations, IntFunction<Outcome> action)
      throws InterruptedException {
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(THREADS);
    AtomicInteger accepted = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      for (int thread = 0; thread < THREADS; thread++) {
        pool.execute(
            () -> {
              ready.countDown();
              try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                  if (action.apply(i).accepted()) {
                    accepted.incrementAndGet();
                  }
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                finished.countDown();
              }
            });
      }
      ready.await();
      start.countDown();
      finished.await();
      return accepted.get();
    } finally {
      pool.shutdownNow();
    }
  }

  private static UserStats expectedStats() {
    Map<String, Long> perUser = new HashMap<>();
    for (int user = 0; user < USERS; user++) {
      perUser.put(userId(user), (long) MODIFICATIONS_PER_USER);
    }
    return UserStats.of(USERS, perUser);
  }

  private static String userId(int index) {
    return "user-" + index % USERS;
  }
}
