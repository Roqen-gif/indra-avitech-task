package sk.khmil.sessions.dispatch;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * Runs tasks asynchronously on a fixed set of single-threaded workers.
 *
 * <p>Tasks with the same key always go to the same worker, so they run in submission order; tasks
 * with different keys may run in parallel. A failing task is logged and never stops its worker.
 *
 * <p>{@link #dispatch} and {@link #runExclusively} must be called from a single submitting thread;
 * {@link #shutdown} must be called after the last submission.
 */
public final class CommandDispatcher {

  private static final Logger LOG = Logger.getLogger(CommandDispatcher.class.getName());

  private final List<ExecutorService> workers;

  public CommandDispatcher(int workerCount) {
    if (workerCount < 1) {
      throw new IllegalArgumentException("workerCount must be at least 1");
    }
    this.workers =
        IntStream.range(0, workerCount)
            .mapToObj(i -> Executors.newSingleThreadExecutor(namedThread("worker-" + i)))
            .toList();
  }

  /** Queues the task on the worker that owns {@code key}. */
  public void dispatch(String key, Runnable task) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(task, "task");
    workerFor(key).execute(() -> runSafely(task));
  }

  /**
   * Runs {@code action} once every worker has finished all tasks submitted before this call, while
   * all workers are paused. Tasks submitted afterwards run only once the action is done, so the
   * action sees an exact point-in-time state. The calling thread does not wait.
   *
   * @return a future that completes when the action has run
   */
  public CompletableFuture<Void> runExclusively(Runnable action) {
    Objects.requireNonNull(action, "action");
    CompletableFuture<Void> done = new CompletableFuture<>();
    CyclicBarrier barrier =
        new CyclicBarrier(
            workers.size(),
            () -> {
              runSafely(action);
              done.complete(null);
            });
    workers.forEach(worker -> worker.execute(() -> awaitBarrier(barrier)));
    return done;
  }

  /**
   * Stops accepting tasks, lets already queued tasks finish and waits up to {@code timeout} in
   * total. Workers still running after the timeout are interrupted.
   *
   * @return {@code true} if all queued tasks completed in time
   */
  public boolean shutdown(Duration timeout) {
    workers.forEach(ExecutorService::shutdown);
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      for (ExecutorService worker : workers) {
        long remaining = deadline - System.nanoTime();
        if (!worker.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
          LOG.warning("Workers did not finish within " + timeout + ", interrupting them");
          workers.forEach(ExecutorService::shutdownNow);
          return false;
        }
      }
      return true;
    } catch (InterruptedException e) {
      workers.forEach(ExecutorService::shutdownNow);
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private ExecutorService workerFor(String key) {
    return workers.get(Math.floorMod(key.hashCode(), workers.size()));
  }

  private static void runSafely(Runnable task) {
    try {
      task.run();
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "Task failed", e);
    }
  }

  private static void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (BrokenBarrierException e) {
      LOG.log(Level.WARNING, "Exclusive action was aborted", e);
    }
  }

  private static ThreadFactory namedThread(String name) {
    return runnable -> new Thread(runnable, name);
  }
}
