package sk.khmil.sessions.command;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses lines of the form {@code NAME(arg)}.
 *
 * <p>Command names are case-insensitive; user ids are case-sensitive and may not contain whitespace
 * or parentheses. Surrounding whitespace on the line and around the argument is ignored. The parser
 * is stateless and thread-safe.
 */
public final class CommandParser {

  private static final Pattern COMMAND = Pattern.compile("([A-Za-z_]+)\\((.*)\\)");
  private static final Pattern VALID_USER_ID = Pattern.compile("[^\\s()]+");

  public ParseResult parse(String line) {
    if (line == null || line.isBlank()) {
      return new ParseResult.Failure("empty input");
    }
    Matcher matcher = COMMAND.matcher(line.strip());
    if (!matcher.matches()) {
      return new ParseResult.Failure("expected NAME(arg) but got: " + line.strip());
    }
    String name = matcher.group(1).toUpperCase(Locale.ROOT);
    String argument = matcher.group(2).strip();
    return switch (name) {
      case "LOGIN" -> withUserId(name, argument, Command.Login::new);
      case "LOGOUT" -> withUserId(name, argument, Command.Logout::new);
      case "DATA_MODIFY" -> withUserId(name, argument, Command.DataModify::new);
      case "STATS" -> withoutArgument(name, argument, new Command.Stats());
      case "EXIT" -> withoutArgument(name, argument, new Command.Exit());
      default -> new ParseResult.Failure("unknown command: " + name);
    };
  }

  private static ParseResult withUserId(
      String name, String argument, Function<String, Command> factory) {
    if (argument.isEmpty()) {
      return new ParseResult.Failure(name + " requires a user_id");
    }
    if (!VALID_USER_ID.matcher(argument).matches()) {
      return new ParseResult.Failure("invalid user_id for " + name + ": " + argument);
    }
    return new ParseResult.Success(factory.apply(argument));
  }

  private static ParseResult withoutArgument(String name, String argument, Command command) {
    if (!argument.isEmpty()) {
      return new ParseResult.Failure(name + " takes no arguments");
    }
    return new ParseResult.Success(command);
  }
}
