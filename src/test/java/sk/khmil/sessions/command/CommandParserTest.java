package sk.khmil.sessions.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        Arguments.of("LOGIN(user-42@corp.sk)", new Command.Login("user-42@corp.sk")));
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
  void userCommandRejectsBlankUserIdOnConstruction() {
    assertThrows(IllegalArgumentException.class, () -> new Command.Login(" "));
  }
}
