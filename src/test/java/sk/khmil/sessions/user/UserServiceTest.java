package sk.khmil.sessions.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sk.khmil.sessions.persistence.Database;
import sk.khmil.sessions.persistence.JdbcUserRepository;

class UserServiceTest {

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
  void loginIsAccepted() {
    assertEquals(Outcome.accepted("alice logged in"), service.login("alice"));
  }

  @Test
  void secondLoginOfSameUserIsRejected() {
    service.login("alice");

    assertEquals(Outcome.rejected("alice is already logged in"), service.login("alice"));
  }

  @Test
  void logoutWithoutLoginIsRejected() {
    assertEquals(Outcome.rejected("alice is not logged in"), service.logout("alice"));
  }

  @Test
  void logoutAfterLoginIsAcceptedAndEndsSession() {
    service.login("alice");

    assertTrue(service.logout("alice").accepted());
    assertFalse(service.logout("alice").accepted());
    assertEquals(0, service.stats().loggedInUsers());
  }

  @Test
  void dataModifyWithoutLoginIsIgnored() {
    Outcome outcome = service.modifyData("bob");

    assertFalse(outcome.accepted());
    assertEquals(UserStats.of(0, Map.of()), service.stats());
  }

  @Test
  void threeDataModifiesCreateThreeRecords() {
    service.login("alice");

    for (int i = 0; i < 3; i++) {
      assertTrue(service.modifyData("alice").accepted());
    }

    assertEquals(UserStats.of(1, Map.of("alice", 3L)), service.stats());
  }

  @Test
  void dataModifyAfterLogoutIsIgnoredButEarlierRecordsRemain() {
    service.login("alice");
    service.modifyData("alice");
    service.logout("alice");

    assertFalse(service.modifyData("alice").accepted());
    assertEquals(UserStats.of(0, Map.of("alice", 1L)), service.stats());
  }

  @Test
  void exampleSessionFromSpecProducesExpectedStats() {
    service.login("alice");
    service.modifyData("alice");
    service.modifyData("alice");
    service.modifyData("bob");
    service.login("alice");

    assertEquals(UserStats.of(1, Map.of("alice", 2L)), service.stats());
  }

  @Test
  void resetSessionsLogsEveryoneOutButKeepsModifications() {
    service.login("alice");
    service.modifyData("alice");

    service.resetSessions();

    assertEquals(UserStats.of(0, Map.of("alice", 1L)), service.stats());
    assertTrue(service.login("alice").accepted());
  }
}
