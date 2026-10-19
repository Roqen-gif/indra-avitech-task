package sk.khmil.sessions.user;

import java.util.Objects;

/**
 * Result of a business operation, shown to the user.
 *
 * @param accepted whether the command took effect
 * @param message human-readable description
 */
public record Outcome(boolean accepted, String message) {

  public Outcome {
    Objects.requireNonNull(message, "message");
  }

  public static Outcome accepted(String message) {
    return new Outcome(true, message);
  }

  public static Outcome rejected(String message) {
    return new Outcome(false, message);
  }
}
