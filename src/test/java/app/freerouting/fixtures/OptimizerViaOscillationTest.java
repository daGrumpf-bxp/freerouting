package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import app.freerouting.autoroute.pipeline.BatchOptimizer;
import app.freerouting.board.actions.ItemIdGenerator;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.management.HeadlessBoardManager;
import app.freerouting.settings.sources.TestingSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Regression test for a via that never settles in the optimizer.
 *
 * <p>The garland holds one net that changes layer through a via placed the way KiCad exports it,
 * with a short stub on each layer. The Top stub is not exactly at 45 degrees (135 x 134.8 um), and
 * a trace of another net runs on Bottom next to the via. {@code ViaOptimizer.optViaLocation} moves
 * the via, the traces still pass through the old position, and the next call moves it back. Each
 * move is reported as a change, so {@code TraceTightener.optChangedArea} ran its rounds up to
 * {@code MAX_ROUNDS} (1000): about 30 s for this three-trace board, and minutes per item on a dense
 * board. With the move limit per via, the pass takes well under a second and ends with the same
 * result.
 */
class OptimizerViaOscillationTest extends RoutingFixtureTest {

  private static final String DSN = "Optimizer-via-oscillation-garland.dsn";

  @Test
  void viaThatOscillatesDoesNotStallTheOptimizerPass() {
    RoutingJob job = getRoutingJob(DSN, new TestingSettings());
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }
    job.board = boardManager.getRoutingBoard();
    job.thread = new NoOpStoppableThread();
    job.routerSettings.optimizer.maxThreads = 1;
    job.routerSettings.optimizer.maxPasses = 1;
    // The board is already near its minimum length: the guards would skip the pass.
    job.routerSettings.optimizer.enablePreflightGuards = false;

    BoardStatistics before = job.board.getStatistics();

    // Without the move limit this pass takes about 30 s.
    assertTimeoutPreemptively(
        Duration.ofSeconds(10), () -> BatchOptimizer.create(job).runBatchLoop());

    BoardStatistics after = job.board.getStatistics();
    assertEquals(0, after.connections.incompleteCount, "incomplete connections");
    assertEquals(
        before.clearanceViolations.totalCount,
        after.clearanceViolations.totalCount,
        "clearance violations");
    assertEquals(before.items.viaCount, after.items.viaCount, "via count");
  }

  private static final class NoOpStoppableThread extends StoppableThread {
    @Override
    protected void threadAction() {}
  }
}
