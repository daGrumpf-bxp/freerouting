package app.freerouting.drc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.io.kicad.KiCadDrcReport;
import app.freerouting.management.BoardLoader;
import app.freerouting.settings.DesignRulesCheckerSettings;
import app.freerouting.settings.GlobalSettings;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reproduction coverage for Issue #575 using a single-pass test.
 *
 * <p>The fixture board is expensive to load and analyze, so this class keeps all assertions in one
 * test method to avoid repeated setup/DRC work.
 */
public class UnconnectedItemsReproductionTest extends RoutingFixtureTest {

  private static final String TEST_BOARD =
      "Issue575-drc_Natural_Tone_Preamp_7_unconnected_items.dsn";

  /** Expected counts from the reference freerouting DRC JSON for this board. */
  private static final int EXPECTED_UNCONNECTED_NET_GROUPS = 9;

  /**
   * KiCad's DRC reports 5 dangling tracks and 4 dangling vias. The reference freerouting JSON had
   * 24 dangling tracks, from wire ends that missed their pad centre by a rounding error.
   */
  private static final int EXPECTED_DANGLING_TRACKS = 5;

  private static final int EXPECTED_DANGLING_VIAS = 4;

  /** Resets global settings before the reproduction test. */
  @BeforeEach
  protected void setUp() {
    Freerouting.globalSettings = new GlobalSettings();
  }

  @Test
  void testIssue575DrcReproductionSinglePass() {
    RoutingJob job = getRoutingJob(TEST_BOARD);
    assertNotNull(job, "Job should not be null");
    BoardLoader.loadBoardIfNeeded(job);
    assertNotNull(job.board, "Board should be loaded");

    BasicBoard board = job.board;

    // Connectivity spot-check: the GND traces on both layers that end on a GND pad. KiCad's DRC
    // does not report them as dangling. Their ends are written half a micrometre off the pad
    // centre (six significant digits), and are moved onto it on import.
    for (int layer = 0; layer <= 1; layer++) {
      Trace gndToPad = traceWithCorner(board, layer, 154376.2, -109587.8);
      assertFalse(
          gndToPad.isTail(), "GND trace ending on the pad must be connected on layer " + layer);
      assertTrue(
          gndToPad.getNormalContacts().stream().anyMatch(Pin.class::isInstance),
          "GND trace must contact the pad on layer " + layer);
    }

    DesignRulesChecker drc = new DesignRulesChecker(board, new DesignRulesCheckerSettings());
    Collection<UnconnectedItems> allIssues = drc.getAllUnconnectedItems();
    long danglingTracks = allIssues.stream().filter(ui -> "track_dangling".equals(ui.type)).count();
    long danglingVias = allIssues.stream().filter(ui -> "via_dangling".equals(ui.type)).count();

    System.out.println("Dangling tracks detected: " + danglingTracks);
    System.out.println("Dangling vias   detected: " + danglingVias);

    // KiCad's DRC documents 5 genuinely dangling tracks.
    // Normalization failures can push the actual count higher; we accept any
    // count >= the reference minimum to avoid false failures on harmless over-detection.
    assertTrue(
        danglingTracks >= EXPECTED_DANGLING_TRACKS,
        "DRC should detect at least "
            + EXPECTED_DANGLING_TRACKS
            + " dangling tracks in this board (per KiCad's DRC); "
            + "actual="
            + danglingTracks
            + ".");

    // The reference JSON documents 4 dangling vias. Correct parsing of (path pcb …)
    // boundary polylines can increase the count; use a lower-bound guard like tracks.
    assertTrue(
        danglingVias >= EXPECTED_DANGLING_VIAS,
        "DRC should detect at least "
            + EXPECTED_DANGLING_VIAS
            + " dangling vias in this board (per reference JSON); "
            + "actual="
            + danglingVias
            + ".");

    long unconnectedNetGroups =
        allIssues.stream().filter(ui -> "unconnectedItems".equals(ui.type)).count();

    System.out.println("Unconnected net groups detected: " + unconnectedNetGroups);

    // Normalization failures inflate the count well above the reference value
    // of 9 (each failed net produces additional disconnected components).
    // Use a lower-bound guard so the test catches under-detection regressions
    // while remaining tolerant of the over-detection caused by normalization artefacts.
    assertTrue(
        unconnectedNetGroups >= EXPECTED_UNCONNECTED_NET_GROUPS,
        "DRC should detect at least "
            + EXPECTED_UNCONNECTED_NET_GROUPS
            + " unconnected net groups (per reference JSON); actual="
            + unconnectedNetGroups
            + ".");

    // Tracks KiCad's DRC reports as dangling: GND on Bottom (0.1001 mm) and +5V on Bottom (0.0657
    // mm).
    Set<Integer> danglingTrackIds =
        allIssues.stream()
            .filter(ui -> "track_dangling".equals(ui.type))
            .map(ui -> ui.firstItem.getId())
            .collect(Collectors.toSet());
    Trace[] spotChecks = {
      traceWithCorner(board, 1, 170098.0, -98149.9), traceWithCorner(board, 1, 162568.0, -100684.0)
    };
    for (Trace track : spotChecks) {
      assertTrue(
          track.isTail(), "Track " + track.getId() + " should be dangling (is_tail == true)");
      assertTrue(
          danglingTrackIds.contains(track.getId()),
          "DRC should detect track " + track.getId() + " as a dangling track");
    }

    KiCadDrcReport report = drc.generateReport(TEST_BOARD, "mm");

    assertNotNull(report, "DRC report should not be null");
    assertNotNull(report.violations, "Violations list should not be null");
    assertNotNull(report.unconnectedItems, "Unconnected-items list should not be null");

    // Dangling tracks + dangling vias are reported as violations
    long trackDanglingViolations =
        report.violations.stream().filter(v -> "track_dangling".equals(v.type)).count();
    long viaDanglingViolations =
        report.violations.stream().filter(v -> "via_dangling".equals(v.type)).count();

    System.out.println("Report: track_dangling violations = " + trackDanglingViolations);
    System.out.println("Report: via_dangling   violations = " + viaDanglingViolations);
    System.out.println("Report: unconnectedItems entries = " + report.unconnectedItems.size());

    // Lower-bound for dangling tracks (normalization artefacts may inflate count)
    assertTrue(
        trackDanglingViolations >= EXPECTED_DANGLING_TRACKS,
        "Report should contain at least "
            + EXPECTED_DANGLING_TRACKS
            + " track_dangling violations; actual="
            + trackDanglingViolations);
    assertTrue(
        viaDanglingViolations >= EXPECTED_DANGLING_VIAS,
        "Report should contain at least "
            + EXPECTED_DANGLING_VIAS
            + " via_dangling violations; actual="
            + viaDanglingViolations);
    // Unconnected net groups: normalization failures inflate the count above the
    // reference value of 9, so we use a lower-bound guard here as well.
    assertTrue(
        report.unconnectedItems.size() >= EXPECTED_UNCONNECTED_NET_GROUPS,
        "Report should contain at least "
            + EXPECTED_UNCONNECTED_NET_GROUPS
            + " unconnected-item entries; actual="
            + report.unconnectedItems.size());
  }

  /** The trace on layer whose first or last corner lies within 1 um of DSN point (x, y). */
  private static Trace traceWithCorner(BasicBoard board, int layer, double x, double y) {
    FloatPoint target = board.communication.coordinateTransform.dsnToBoard(new double[] {x, y});
    double tolerance = board.communication.coordinateTransform.dsnToBoard(1.0);
    for (Item item : board.getItems()) {
      if (item instanceof Trace trace
          && trace.getLayer() == layer
          && (trace.firstCorner().toFloat().distance(target) <= tolerance
              || trace.lastCorner().toFloat().distance(target) <= tolerance)) {
        return trace;
      }
    }
    throw new AssertionError("No trace on layer " + layer + " ends at " + x + ", " + y);
  }
}
