package sk.khmil.sessions.user;

/**
 * Persistent store for sessions and data modifications.
 *
 * <p>Implementations must be thread-safe. Failures are reported as {@link RepositoryException}.
 */
public interface UserRepository {

  /**
   * Creates a session for the user.
   *
   * @return {@code true} if created, {@code false} if the user already had a session
   */
  boolean createSession(String userId);

  /**
   * Deletes the user's session.
   *
   * @return {@code true} if a session existed and was deleted
   */
  boolean deleteSession(String userId);

  /** Records one data modification made by the user. */
  void addModification(String userId);

  /** Returns a consistent snapshot of the current statistics. */
  UserStats stats();
}
