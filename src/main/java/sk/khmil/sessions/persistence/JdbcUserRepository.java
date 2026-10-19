package sk.khmil.sessions.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import sk.khmil.sessions.user.RepositoryException;
import sk.khmil.sessions.user.UserRepository;
import sk.khmil.sessions.user.UserStats;

/** {@link UserRepository} on plain JDBC; one connection per operation keeps it thread-safe. */
public final class JdbcUserRepository implements UserRepository {

  /** SQLState for a unique or primary key violation. */
  private static final String UNIQUE_VIOLATION = "23505";

  private static final String INSERT_SESSION =
      "INSERT INTO sessions (user_id, logged_in_at) VALUES (?, ?)";
  private static final String DELETE_SESSION = "DELETE FROM sessions WHERE user_id = ?";
  private static final String INSERT_MODIFICATION =
      "INSERT INTO data_modifications (user_id, modified_at)"
          + " SELECT user_id, ? FROM sessions WHERE user_id = ?";
  private static final String COUNT_SESSIONS = "SELECT COUNT(*) FROM sessions";
  private static final String COUNT_MODIFICATIONS =
      "SELECT user_id, COUNT(*) FROM data_modifications GROUP BY user_id";

  private final Database database;
  private final Clock clock;

  public JdbcUserRepository(Database database, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public boolean createSession(String userId) {
    try (Connection connection = database.connection();
        PreparedStatement statement = connection.prepareStatement(INSERT_SESSION)) {
      statement.setString(1, userId);
      statement.setTimestamp(2, now());
      statement.executeUpdate();
      return true;
    } catch (SQLException e) {
      if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
        return false;
      }
      throw new RepositoryException("Failed to create session for " + userId, e);
    }
  }

  @Override
  public boolean deleteSession(String userId) {
    try (Connection connection = database.connection();
        PreparedStatement statement = connection.prepareStatement(DELETE_SESSION)) {
      statement.setString(1, userId);
      return statement.executeUpdate() > 0;
    } catch (SQLException e) {
      throw new RepositoryException("Failed to delete session for " + userId, e);
    }
  }

  @Override
  public boolean addModificationIfLoggedIn(String userId) {
    try (Connection connection = database.connection();
        PreparedStatement statement = connection.prepareStatement(INSERT_MODIFICATION)) {
      statement.setTimestamp(1, now());
      statement.setString(2, userId);
      return statement.executeUpdate() > 0;
    } catch (SQLException e) {
      throw new RepositoryException("Failed to record modification for " + userId, e);
    }
  }

  @Override
  public UserStats stats() {
    try (Connection connection = database.connection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try {
        UserStats stats = UserStats.of(countSessions(connection), countModifications(connection));
        connection.commit();
        return stats;
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new RepositoryException("Failed to read statistics", e);
    }
  }

  private static long countSessions(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(COUNT_SESSIONS);
        ResultSet resultSet = statement.executeQuery()) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private static Map<String, Long> countModifications(Connection connection) throws SQLException {
    Map<String, Long> counts = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(COUNT_MODIFICATIONS);
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        counts.put(resultSet.getString(1), resultSet.getLong(2));
      }
    }
    return counts;
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }
}
