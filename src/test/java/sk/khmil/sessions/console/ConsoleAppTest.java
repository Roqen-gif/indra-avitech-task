package sk.khmil.sessions.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sk.khmil.sessions.command.CommandParser;
import sk.khmil.sessions.dispatch.CommandDispatcher;
import sk.khmil.sessions.persistence.Database;
import sk.khmil.sessions.persistence.JdbcUserRepository;
import sk.khmil.sessions.user.UserService;
import sk.khmil.sessions.user.UserStats;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ConsoleAppTest {

  private static final String NL = System.lineSeparator();

  private Database database;
  private UserService userService;

  @BeforeEach
  void setUp() {
    database = Database.open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    userService = new UserService(new JdbcUserRepository(database, Clock.systemUTC()));
  }

  @AfterEach
  void tearDown() {
    database.close();
  }

  @Test
  void exampleSessionFromSpecPrintsExpectedOutput() {
    String output =
        run(
            """
            LOGIN(alice)
            DATA_MODIFY(alice)
            DATA_MODIFY(alice)
            DATA_MODIFY(bob)
            LOGIN(alice)
            FOO(1)
            STATS()
            EXIT()
            """);

    assertTrue(output.contains("OK: alice logged in"), output);
    assertEquals(
        2, output.lines().filter("OK: modification recorded for alice"::equals).count(), output);
    assertTrue(output.contains("WARN: modification ignored, bob is not logged in"), output);
    assertTrue(output.contains("WARN: alice is already logged in"), output);
    assertTrue(output.contains("WARN: unknown command: FOO"), output);
    assertTrue(
        output.contains(
            "Logged-in users: 1"
                + NL
                + "Users currently modifying data: 1"
                + NL
                + "Modifications per user:"
                + NL
                + "  alice: 2"
                + NL),
        output);
    assertTrue(output.contains("Shutting down..." + NL), output);
  }

  @Test
  void endOfInputBehavesLikeExitAndProcessesQueuedCommands() {
    String output = run("LOGIN(alice)\nDATA_MODIFY(alice)\n");

    assertTrue(output.contains("Shutting down..." + NL), output);
    assertEquals(UserStats.of(1, 1, Map.of("alice", 1L)), userService.stats());
  }

  @Test
  void linesAfterExitAreNotProcessed() {
    run("LOGIN(alice)\nEXIT()\nLOGIN(bob)\n");

    assertEquals(1, userService.stats().loggedInUsers());
  }

  @Test
  void invalidLinesAreReportedAndDoNotStopProcessing() {
    String output = run("\n   \nLOGIN()\nSTATS(5)\nLOGIN(alice)x\nLOGIN(alice)\n");

    assertEquals(5, output.lines().filter(line -> line.startsWith("WARN: ")).count(), output);
    assertEquals(1, userService.stats().loggedInUsers());
  }

  @Test
  void statsSeesAllCommandsEnteredBeforeIt() {
    StringBuilder script = new StringBuilder();
    for (int user = 0; user < 20; user++) {
      script.append("LOGIN(u").append(user).append(")\n");
      script.append("DATA_MODIFY(u").append(user).append(")\n");
    }
    script.append("STATS()\n");
    for (int user = 0; user < 20; user++) {
      script.append("LOGOUT(u").append(user).append(")\n");
    }

    String output = run(script.toString());

    assertTrue(output.contains("Logged-in users: 20" + NL), output);
    assertFalse(output.contains("WARN"), output);
  }

  @Test
  void readFailureShutsDownGracefully() {
    BufferedReader failing =
        new BufferedReader(new StringReader("")) {
          @Override
          public String readLine() throws IOException {
            throw new IOException("stream closed");
          }
        };
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    new ConsoleApp(
            failing,
            new PrintStream(bytes, true, StandardCharsets.UTF_8),
            new CommandParser(),
            userService,
            new CommandDispatcher(2),
            Duration.ofSeconds(5))
        .run();

    assertEquals("Shutting down..." + NL, bytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void stopFromSignalShutsDownOnceAndIgnoresLaterInput() throws Exception {
    PipedWriter keyboard = new PipedWriter();
    BufferedReader input = new BufferedReader(new PipedReader(keyboard));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ConsoleApp app =
        new ConsoleApp(
            input,
            new PrintStream(bytes, true, StandardCharsets.UTF_8),
            new CommandParser(),
            userService,
            new CommandDispatcher(2),
            Duration.ofSeconds(5));
    Thread reader = new Thread(app::run, "test-reader");
    reader.start();

    app.stopFromSignal();
    app.stopFromSignal();
    keyboard.write("LOGIN(alice)\n");
    keyboard.close();
    reader.join();

    assertEquals(NL + "Shutting down..." + NL, bytes.toString(StandardCharsets.UTF_8));
    assertEquals(0, userService.stats().loggedInUsers());
  }

  @Test
  void formatShowsPlaceholderWhenThereAreNoModifications() {
    assertEquals(
        "Logged-in users: 0"
            + NL
            + "Users currently modifying data: 0"
            + NL
            + "Modifications per user:"
            + NL
            + "  (none)"
            + NL,
        ConsoleApp.format(UserStats.of(0, 0, Map.of())));
  }

  private String run(String script) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    new ConsoleApp(
            new BufferedReader(new StringReader(script)),
            output,
            new CommandParser(),
            userService,
            new CommandDispatcher(4),
            Duration.ofSeconds(5))
        .run();
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
