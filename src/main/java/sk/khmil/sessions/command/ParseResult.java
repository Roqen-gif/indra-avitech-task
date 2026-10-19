package sk.khmil.sessions.command;

import java.util.Objects;

/** Outcome of parsing one input line; invalid input is a value, not an exception. */
public sealed interface ParseResult {

  record Success(Command command) implements ParseResult {
    public Success {
      Objects.requireNonNull(command, "command");
    }
  }

  record Failure(String reason) implements ParseResult {
    public Failure {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
