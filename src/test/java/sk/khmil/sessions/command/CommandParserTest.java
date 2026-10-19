package sk.khmil.sessions.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandParserTest {

  private final CommandParser parser = new CommandParser();

  static Stream<Arguments> validLines() {
    return Stream.of(
        Arguments.of("LOGIN(alice)", new Command.Login("alice")),
        Arguments.of("LOGOUT(alice)", new Command.Logout("alice")),
        Arguments.of("DATA_MODIFY(alice)", new Command.DataModify("alice")),
        Arguments.of("STATS()", new Command.Stats()),
        Arguments.of("EXIT()", new Command.Exit()),
        Arguments.of("  LOGIN(bob)  ", new Command.Login("bob")),
        Arguments.of("LOGIN( bob )", new Command.Login("bob")),
        Arguments.of("login(alice)", new Command.Login("alice")),
        Arguments.of("Data_Modify(Alice)", new Command.DataModify("Alice")),
        Arguments.of("STATS( )", new Command.Stats()),
        Arguments.of("LOGIN(user-42@corp.sk)", new Command.Login("user-42@corp.sk")),
        Arguments.of("LOGIN(John.Doe_7)", new Command.Login("John.Doe_7")),
        Arguments.of("LOGIN(7)", new Command.Login("7")),
        Arguments.of(
            "LOGIN(3f2b9c1e-8a4d-4f6b-9e2a-1c7d5b8e0f42)",
            new Command.Login("3f2b9c1e-8a4d-4f6b-9e2a-1c7d5b8e0f42")),
        Arguments.of("LOGIN(" + "a".repeat(64) + ")", new Command.Login("a".repeat(64))));
  }

  @ParameterizedTest
  @MethodSource("validLines")
  void parsesValidLine(String line, Command expected) {
    ParseResult result = parser.parse(line);

    assertEquals(new ParseResult.Success(expected), result);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "\t",
        "FOO(1)",
        "LOGIN",
        "LOGIN alice",
        "LOGIN(alice",
        "LOGIN(alice)x",
        "LOGIN(alice) EXIT()",
        "(alice)",
        "LOGIN()",
        "LOGIN(   )",
        "LOGOUT()",
        "DATA_MODIFY()",
        "LOGIN(al ice)",
        "LOGIN(ali#ce)",
        "LOGIN(ali\u0000ce)",
        "LOGIN(\u001b[31mred)",
        "LOGIN(ali\u00e7e)",
        "LOGIN(user/../admin)",
        "LOGIN(ali231@@@)",
        "LOGIN(a@b@c)",
        "LOGIN(alice@)",
        "LOGIN(@alice)",
        "LOGIN(-alice)",
        "LOGIN(.hidden)",
        "LOGIN(a)(b)",
        "STATS(5)",
        "EXIT(now)"
      })
  void rejectsInvalidLineWithoutThrowing(String line) {
    ParseResult result = parser.parse(line);

    assertInstanceOf(ParseResult.Failure.class, result);
  }

  @Test
  void failureReasonNamesTheUnknownCommand() {
    ParseResult result = parser.parse("foo(1)");

    assertEquals(new ParseResult.Failure("unknown command: FOO"), result);
  }

  @Test
  void userIdLongerThanLimitIsRejected() {
    String tooLong = "a".repeat(CommandParser.MAX_USER_ID_LENGTH + 1);

    assertInstanceOf(ParseResult.Failure.class, parser.parse("LOGIN(" + tooLong + ")"));
  }

  @Test
  void errorMessagesNeverEchoControlCharactersOrLongInput() {
    ParseResult result = parser.parse("\u001b[2J" + "x".repeat(1_000));

    String reason = ((ParseResult.Failure) result).reason();
    assertFalse(reason.contains("\u001b"), reason);
    assertTrue(reason.length() < 100, reason);
  }

  @Test
  void userCommandRejectsBlankUserIdOnConstruction() {
    assertThrows(IllegalArgumentException.class, () -> new Command.Login(" "));
  }
}
