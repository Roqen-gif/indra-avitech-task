package sk.khmil.sessions.user;

import java.util.Objects;

/**
 * Business rules for sessions and data modifications.
 *
 * <p>Synchronous and stateless: all state lives in the {@link UserRepository}, whose operations are
 * atomic, so this class is thread-safe without any locking of its own.
 */
public final class UserService {

  private final UserRepository repository;

  public UserService(UserRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /** Starts a session; rejected if the user is already logged in. */
  public Outcome login(String userId) {
    return repository.createSession(userId)
        ? Outcome.accepted(userId + " logged in")
        : Outcome.rejected(userId + " is already logged in");
  }

  /** Ends a session; rejected if the user is not logged in. */
  public Outcome logout(String userId) {
    return repository.deleteSession(userId)
        ? Outcome.accepted(userId + " logged out")
        : Outcome.rejected(userId + " is not logged in");
  }

  /** Records one modification; ignored if the user is not logged in. */
  public Outcome modifyData(String userId) {
    return repository.addModificationIfLoggedIn(userId)
        ? Outcome.accepted("modification recorded for " + userId)
        : Outcome.rejected("modification ignored, " + userId + " is not logged in");
  }

  /** Returns the current statistics. */
  public UserStats stats() {
    return repository.stats();
  }
}
