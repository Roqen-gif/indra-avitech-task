package sk.khmil.sessions.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import sk.khmil.sessions.user.RepositoryException;

/**
 * Hands out JDBC connections and owns the schema.
 *
 * <p>Every caller gets its own {@link Connection} and must close it, so connections are never
 * shared between threads.
 */
public final class Database implements AutoCloseable {

  private static final String CREATE_SESSIONS =
      """
      CREATE TABLE IF NOT EXISTS sessions (
          user_id      VARCHAR(255) PRIMARY KEY,
          logged_in_at TIMESTAMP NOT NULL
      )""";

  private static final String CREATE_MODIFICATIONS =
      """
      CREATE TABLE IF NOT EXISTS data_modifications (
          id          BIGINT AUTO_INCREMENT PRIMARY KEY,
          user_id     VARCHAR(255) NOT NULL,
          modified_at TIMESTAMP NOT NULL
      )""";

  private static final String CREATE_MODIFICATIONS_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_modifications_user ON data_modifications(user_id)";

  private final String url;

  private Database(String url) {
    this.url = Objects.requireNonNull(url, "url");
  }

  /** Opens the database at the given JDBC URL and creates the schema if it does not exist. */
  public static Database open(String url) {
    Database database = new Database(url);
    database.createSchema();
    return database;
  }

  /** Returns a new connection; the caller owns it and must close it. */
  public Connection connection() throws SQLException {
    return DriverManager.getConnection(url);
  }

  private void createSchema() {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute(CREATE_SESSIONS);
      statement.execute(CREATE_MODIFICATIONS);
      statement.execute(CREATE_MODIFICATIONS_INDEX);
    } catch (SQLException e) {
      throw new RepositoryException("Failed to create schema", e);
    }
  }

  /** Shuts the embedded database down so all data is flushed before the process exits. */
  @Override
  public void close() {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    } catch (SQLException e) {
      throw new RepositoryException("Failed to shut down database", e);
    }
  }
}
