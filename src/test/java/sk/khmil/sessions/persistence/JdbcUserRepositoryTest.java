package sk.khmil.sessions.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sk.khmil.sessions.user.RepositoryException;
import sk.khmil.sessions.user.UserStats;

class JdbcUserRepositoryTest {

  private Database database;
  private JdbcUserRepository repository;

  @BeforeEach
  void setUp() {
    database = Database.open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    repository = new JdbcUserRepository(database, Clock.systemUTC());
  }

  @AfterEach
  void tearDown() {
    database.close();
  }

  @Test
  void createSessionSucceedsOnce() {
    assertTrue(repository.createSession("alice"));
    assertFalse(repository.createSession("alice"));
  }

  @Test
  void deleteSessionReportsWhetherSessionExisted() {
    assertFalse(repository.deleteSession("alice"));

    repository.createSession("alice");

    assertTrue(repository.deleteSession("alice"));
    assertFalse(repository.deleteSession("alice"));
  }

  @Test
  void sessionCanBeCreatedAgainAfterDeletion() {
    repository.createSession("alice");
    repository.deleteSession("alice");

    assertTrue(repository.createSession("alice"));
  }

  @Test
  void statsCountSessionsAndModificationsPerUser() {
    repository.createSession("alice");
    repository.createSession("bob");
    repository.addModificationIfLoggedIn("bob");
    repository.addModificationIfLoggedIn("alice");
    repository.addModificationIfLoggedIn("alice");

    UserStats stats = repository.stats();

    assertEquals(UserStats.of(2, 2, Map.of("alice", 2L, "bob", 1L)), stats);
    assertEquals("alice", stats.modificationsPerUser().firstKey());
  }

  @Test
  void modificationIsRecordedOnlyForLoggedInUser() {
    assertFalse(repository.addModificationIfLoggedIn("alice"));

    repository.createSession("alice");

    assertTrue(repository.addModificationIfLoggedIn("alice"));
    assertEquals(UserStats.of(1, 1, Map.of("alice", 1L)), repository.stats());
  }

  @Test
  void usersModifyingDataCountsOnlyModificationsInCurrentSession() {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
    JdbcUserRepository timed = new JdbcUserRepository(database, clock);
    timed.createSession("alice");
    timed.addModificationIfLoggedIn("alice");
    timed.createSession("bob");
    timed.addModificationIfLoggedIn("bob");
    clock.advance(Duration.ofMinutes(1));
    timed.deleteSession("alice");
    timed.createSession("alice");
    timed.createSession("carol");

    assertEquals(UserStats.of(3, 1, Map.of("alice", 1L, "bob", 1L)), timed.stats());
  }

  @Test
  void statsOfEmptyDatabaseAreZero() {
    assertEquals(UserStats.of(0, 0, Map.of()), repository.stats());
  }

  @Test
  void userIdsAreCaseSensitive() {
    assertTrue(repository.createSession("alice"));
    assertTrue(repository.createSession("Alice"));
  }

  @Test
  void schemaCreationIsIdempotent() {
    String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    try (Database first = Database.open(url)) {
      new JdbcUserRepository(first, Clock.systemUTC()).createSession("alice");
      Database second = Database.open(url);

      assertFalse(new JdbcUserRepository(second, Clock.systemUTC()).createSession("alice"));
    }
  }

  @Test
  void closeIsIdempotent() {
    database.close();
    database.close();
  }

  @Test
  void storageFailureIsWrappedInRepositoryException() {
    database.close();

    assertThrows(RepositoryException.class, () -> repository.addModificationIfLoggedIn("alice"));
  }

  /** Clock that only moves when told to, so session boundaries are deterministic. */
  private static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }
  }
}
