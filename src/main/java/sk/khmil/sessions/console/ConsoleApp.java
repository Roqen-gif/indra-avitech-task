package sk.khmil.sessions.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import sk.khmil.sessions.command.Command;
import sk.khmil.sessions.command.CommandParser;
import sk.khmil.sessions.command.ParseResult;
import sk.khmil.sessions.dispatch.CommandDispatcher;
import sk.khmil.sessions.user.Outcome;
import sk.khmil.sessions.user.UserService;
import sk.khmil.sessions.user.UserStats;

/**
 * Reads commands line by line and hands them to the dispatcher without waiting for their result.
 *
 * <p>Ends on {@code EXIT()} or end of input, then shuts the dispatcher down so every queued command
 * is still processed.
 */
public final class ConsoleApp {

  private static final Logger LOG = Logger.getLogger(ConsoleApp.class.getName());

  /** Short help shown when the application starts. */
  public static final String USAGE =
      "Commands: LOGIN(user_id), LOGOUT(user_id), DATA_MODIFY(user_id), STATS(), EXIT()."
          + " Ctrl+D also exits.";

  private final BufferedReader input;
  private final PrintStream output;
  private final CommandParser parser;
  private final UserService userService;
  private final CommandDispatcher dispatcher;
  private final Duration shutdownTimeout;
  private final AtomicBoolean stopped = new AtomicBoolean();

  public ConsoleApp(
      BufferedReader input,
      PrintStream output,
      CommandParser parser,
      UserService userService,
      CommandDispatcher dispatcher,
      Duration shutdownTimeout) {
    this.input = Objects.requireNonNull(input, "input");
    this.output = Objects.requireNonNull(output, "output");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.userService = Objects.requireNonNull(userService, "userService");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
  }

  /**
   * Processes input until {@code EXIT()} or end of input, then waits for queued commands. The
   * dispatcher is shut down even if reading fails unexpectedly, so its threads never keep the JVM
   * alive.
   */
  public void run() {
    try {
      readUntilExit();
    } finally {
      stop(false);
    }
  }

  /**
   * Graceful stop requested from outside, e.g. by a shutdown hook on Ctrl+C: commands already
   * queued are still processed. Safe to call from any thread and more than once.
   */
  public void stopFromSignal() {
    stop(true);
  }

  /** Shuts the dispatcher down exactly once, whichever thread gets here first. */
  private void stop(boolean fromSignal) {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    if (fromSignal) {
      output.println();
    }
    output.println("Shutting down...");
    if (!dispatcher.shutdown(shutdownTimeout)) {
      LOG.warning("Some commands were not processed before the shutdown timeout");
    }
  }

  private void readUntilExit() {
    String line;
    while (!stopped.get() && (line = readLine()) != null && processUnlessStopped(line)) {
      // process() does all the work; the loop only decides when to stop.
    }
  }

  /** A stop signal can arrive between reading a line and dispatching it; then just stop reading. */
  private boolean processUnlessStopped(String line) {
    try {
      return process(line);
    } catch (RejectedExecutionException e) {
      LOG.fine("Ignoring input received during shutdown: " + line);
      return false;
    }
  }

  /** Handles one line without waiting for its result; returns {@code false} on {@code EXIT()}. */
  private boolean process(String line) {
    return switch (parser.parse(line)) {
      case ParseResult.Failure failure -> {
        output.println("WARN: " + failure.reason());
        yield true;
      }
      case ParseResult.Success(Command.UserCommand command) -> {
        dispatcher.dispatch(command.userId(), () -> report(execute(command)));
        yield true;
      }
      case ParseResult.Success(Command.Stats stats) -> {
        dispatcher.runExclusively(() -> output.print(format(userService.stats())));
        yield true;
      }
      case ParseResult.Success(Command.Exit exit) -> false;
    };
  }

  private Outcome execute(Command.UserCommand command) {
    return switch (command) {
      case Command.Login login -> userService.login(login.userId());
      case Command.Logout logout -> userService.logout(logout.userId());
      case Command.DataModify modify -> userService.modifyData(modify.userId());
    };
  }

  private void report(Outcome outcome) {
    if (outcome.accepted()) {
      LOG.fine(outcome.message());
    } else {
      output.println("WARN: " + outcome.message());
    }
  }

  /** Builds the whole block first so that concurrent output cannot interleave with it. */
  static String format(UserStats stats) {
    StringBuilder text = new StringBuilder();
    text.append("Logged-in users: ").append(stats.loggedInUsers()).append(System.lineSeparator());
    text.append("Users currently modifying data: ")
        .append(stats.usersModifyingData())
        .append(System.lineSeparator());
    text.append("Modifications per user:").append(System.lineSeparator());
    if (stats.modificationsPerUser().isEmpty()) {
      text.append("  (none)").append(System.lineSeparator());
    }
    for (Map.Entry<String, Long> entry : stats.modificationsPerUser().entrySet()) {
      text.append("  ")
          .append(entry.getKey())
          .append(": ")
          .append(entry.getValue())
          .append(System.lineSeparator());
    }
    return text.toString();
  }

  private String readLine() {
    try {
      return input.readLine();
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Failed to read input, shutting down", e);
      return null;
    }
  }
}
