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
 * shared between threads. One extra connection stays open for the lifetime of this object: an
 * embedded H2 database closes when its last connection closes, and reopening the file for every
 * operation would be slow.
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

  /** Never used for queries; it only keeps the embedded database open. */
  private final Connection keepAlive;

  private Database(String url, Connection keepAlive) {
    this.url = url;
    this.keepAlive = keepAlive;
  }

  /**
   * H2 closes databases in its own JVM shutdown hook by default. Shutdown hooks run concurrently,
   * so that would race with our graceful shutdown on Ctrl+C; this class owns the lifecycle instead.
   */
  private static final String NO_AUTO_CLOSE = ";DB_CLOSE_ON_EXIT=FALSE";

  /** Opens the database at the given JDBC URL and creates the schema if it does not exist. */
  public static Database open(String jdbcUrl) {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    String url = jdbcUrl.contains("DB_CLOSE_ON_EXIT") ? jdbcUrl : jdbcUrl + NO_AUTO_CLOSE;
    Database database;
    try {
      database = new Database(url, DriverManager.getConnection(url));
    } catch (SQLException e) {
      throw new RepositoryException("Failed to open database " + url, e);
    }
    try {
      database.createSchema();
    } catch (RepositoryException e) {
      database.close();
      throw e;
    }
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

  /**
   * Shuts the embedded database down so all data is flushed before the process exits. Calling it
   * again has no effect. Synchronized because both the main thread and a shutdown hook may call it.
   */
  @Override
  public synchronized void close() {
    try {
      if (keepAlive.isClosed()) {
        return;
      }
      try (Connection connection = keepAlive;
          Statement statement = connection.createStatement()) {
        statement.execute("SHUTDOWN");
      }
    } catch (SQLException e) {
      throw new RepositoryException("Failed to shut down database", e);
    }
  }
}
