package sk.khmil.sessions.command;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses lines of the form {@code NAME(arg)}.
 *
 * <p>Command names are case-insensitive. User ids are case-sensitive, at most {@value
 * #MAX_USER_ID_LENGTH} characters long, use the POSIX portable filename set (letters, digits,
 * {@code . _ -}) plus a single optional {@code @}, and start with a letter or digit. Plain names,
 * emails and UUIDs are accepted, while control characters, terminal escape sequences and over-long
 * values are rejected before reaching the database. Surrounding whitespace on the line and around
 * the argument is ignored. The parser is stateless and thread-safe.
 */
public final class CommandParser {

  private static final Pattern COMMAND = Pattern.compile("([A-Za-z_]+)\\((.*)\\)");
  static final int MAX_USER_ID_LENGTH = 64;
  private static final Pattern VALID_USER_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*(@[A-Za-z0-9._-]+)?");

  /** Longest piece of user input repeated back in an error message. */
  private static final int MAX_ECHO_LENGTH = 40;

  public ParseResult parse(String line) {
    if (line == null || line.isBlank()) {
      return new ParseResult.Failure("empty input");
    }
    Matcher matcher = COMMAND.matcher(line.strip());
    if (!matcher.matches()) {
      return new ParseResult.Failure("expected NAME(arg) but got: " + printable(line.strip()));
    }
    String name = matcher.group(1).toUpperCase(Locale.ROOT);
    String argument = matcher.group(2).strip();
    return switch (name) {
      case "LOGIN" -> withUserId(name, argument, Command.Login::new);
      case "LOGOUT" -> withUserId(name, argument, Command.Logout::new);
      case "DATA_MODIFY" -> withUserId(name, argument, Command.DataModify::new);
      case "STATS" -> withoutArgument(name, argument, new Command.Stats());
      case "EXIT" -> withoutArgument(name, argument, new Command.Exit());
      default -> new ParseResult.Failure("unknown command: " + printable(name));
    };
  }

  private static ParseResult withUserId(
      String name, String argument, Function<String, Command> factory) {
    if (argument.isEmpty()) {
      return new ParseResult.Failure(name + " requires a user_id");
    }
    if (argument.length() > MAX_USER_ID_LENGTH || !VALID_USER_ID.matcher(argument).matches()) {
      return new ParseResult.Failure(
          "invalid user_id for "
              + name
              + ": use 1-"
              + MAX_USER_ID_LENGTH
              + " characters from A-Z a-z 0-9 . _ -, one optional @,"
              + " starting with a letter or digit");
    }
    return new ParseResult.Success(factory.apply(argument));
  }

  private static ParseResult withoutArgument(String name, String argument, Command command) {
    if (!argument.isEmpty()) {
      return new ParseResult.Failure(name + " takes no arguments");
    }
    return new ParseResult.Success(command);
  }

  /**
   * Makes user input safe to show: non-printable characters (including terminal escape sequences)
   * become {@code ?} and long input is shortened.
   */
  static String printable(String text) {
    String visible =
        text.codePoints()
            .map(c -> c >= 0x20 && c < 0x7F ? c : '?')
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    return visible.length() <= MAX_ECHO_LENGTH
        ? visible
        : visible.substring(0, MAX_ECHO_LENGTH) + "...";
  }
}
