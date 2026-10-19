package sk.khmil.sessions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import sk.khmil.sessions.command.CommandParser;
import sk.khmil.sessions.console.ConsoleApp;
import sk.khmil.sessions.dispatch.CommandDispatcher;
import sk.khmil.sessions.persistence.Database;
import sk.khmil.sessions.persistence.JdbcUserRepository;
import sk.khmil.sessions.user.UserService;

/** Entry point: wires all components by hand and runs the console loop. */
public final class Main {

  private static final String DEFAULT_DATABASE_URL = "jdbc:h2:./data/app";
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

  private Main() {}

  /**
   * Starts the application.
   *
   * @param args optional JDBC URL of the database, defaults to {@value #DEFAULT_DATABASE_URL}
   */
  public static void main(String[] args) {
    System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s [%3$s] %5$s%6$s%n");
    String databaseUrl = args.length > 0 ? args[0] : DEFAULT_DATABASE_URL;
    int workerCount = Runtime.getRuntime().availableProcessors();

    try (Database database = Database.open(databaseUrl)) {
      UserService userService =
          new UserService(new JdbcUserRepository(database, Clock.systemUTC()));
      userService.resetSessions();
      BufferedReader input =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      ConsoleApp app =
          new ConsoleApp(
              input,
              System.out,
              new CommandParser(),
              userService,
              new CommandDispatcher(workerCount),
              SHUTDOWN_TIMEOUT);
      System.err.println(ConsoleApp.USAGE);
      app.run();
    }
  }
}
