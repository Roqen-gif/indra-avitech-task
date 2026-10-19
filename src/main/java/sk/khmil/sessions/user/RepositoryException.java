package sk.khmil.sessions.user;

/** Unchecked wrapper for storage failures, so callers are not tied to JDBC. */
public final class RepositoryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RepositoryException(String message, Throwable cause) {
    super(message, cause);
  }
}
