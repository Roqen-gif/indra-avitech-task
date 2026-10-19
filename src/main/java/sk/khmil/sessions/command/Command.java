package sk.khmil.sessions.command;

import java.util.Objects;

/** A parsed console command. Commands bound to a user expose it via {@link UserCommand}. */
public sealed interface Command {

  /** A command that acts on behalf of a single user and must be ordered per user. */
  sealed interface UserCommand extends Command {
    String userId();
  }

  record Login(String userId) implements UserCommand {
    public Login {
      requireValidUserId(userId);
    }
  }

  record Logout(String userId) implements UserCommand {
    public Logout {
      requireValidUserId(userId);
    }
  }

  record DataModify(String userId) implements UserCommand {
    public DataModify {
      requireValidUserId(userId);
    }
  }

  record Stats() implements Command {}

  record Exit() implements Command {}

  private static void requireValidUserId(String userId) {
    Objects.requireNonNull(userId, "userId");
    if (userId.isBlank()) {
      throw new IllegalArgumentException("userId must not be blank");
    }
  }
}
