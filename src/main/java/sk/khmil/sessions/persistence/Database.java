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

  /** Opens the database at the given JDBC URL and creates the schema if it does not exist. */
  public static Database open(String url) {
    Objects.requireNonNull(url, "url");
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
   * again has no effect.
   */
  @Override
  public void close() {
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
