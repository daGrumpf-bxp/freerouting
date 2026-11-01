package app.freerouting.io.specctra.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * KiCad writes absolute wire coordinates with six significant digits, so past 100 mm (in um) a
 * locked track that starts on a pad centre is exported half a micrometre away from it, while the
 * pad keeps its exact position (placement plus pad offset). Trace-to-pin contacts need the exact
 * centre, so the imported track has to be moved onto it to stay connected to its pad.
 */
class WireEndOnPinCentreTest {

  private static String dsn(String wires) {
    return """
        (pcb rounded (resolution um 10) (unit um)
          (structure (layer F.Cu (type signal)) (layer B.Cu (type signal))
            (boundary (rect pcb 0 -200000 200000 0)) (rule (width 200) (clearance 200)))
          (placement
            (component R (place R1 165000 -100000 front 0)))
          (library
            (image R
              (pin P 1 647.5 0)
              (pin P 2 -647.5 0))
            (padstack P (shape (rect F.Cu -400 -450 400 450)) (attach off)))
          (network (net A (pins R1-1)) (net B (pins R1-2)))
          (wiring
        %s))
        """
        .formatted(wires);
  }

  private static RoutingBoard load(String wires) {
    byte[] bytes = dsn(wires).getBytes(StandardCharsets.UTF_8);
    return (RoutingBoard)
        ((BoardReadResult.Success) DsnReader.readBoard(new ByteArrayInputStream(bytes), null, null))
            .board();
  }

  private static Pin pin(RoutingBoard board, String number) {
    for (Item item : board.getItems()) {
      if (item instanceof Pin pin && pin.name().equals(number)) {
        return pin;
      }
    }
    throw new AssertionError("pin " + number + " not found");
  }

  private static PolylineTrace onlyTrace(RoutingBoard board) {
    for (Item item : board.getItems()) {
      if (item instanceof PolylineTrace trace) {
        return trace;
      }
    }
    throw new AssertionError("no trace");
  }

  @Test
  void lockedTrackRoundedNextToItsPadCentreIsConnectedToThePad() {
    RoutingBoard board =
        load("    (wire (path F.Cu 200 165648 -100000 167000 -100000) (net A) (type fix))");
    Pin pad = pin(board, "1");
    PolylineTrace track = onlyTrace(board);

    assertEquals(pad.getCenter(), track.firstCorner());
    assertTrue(track.getStartContacts().contains(pad));
  }

  @Test
  void trackOfAnotherNetIsLeftWhereItIs() {
    RoutingBoard board =
        load("    (wire (path F.Cu 200 165648 -100000 165648 -98000) (net B) (type fix))");
    PolylineTrace track = onlyTrace(board);

    assertFalse(track.firstCorner().equals(pin(board, "1").getCenter()));
  }

  @Test
  void trackEndingFartherThanTheToleranceIsLeftWhereItIs() {
    RoutingBoard board =
        load("    (wire (path F.Cu 200 165650 -100000 167000 -100000) (net A) (type fix))");
    PolylineTrace track = onlyTrace(board);
    IntPoint written =
        board.communication.coordinateTransform.dsnToBoard(new double[] {165650, -100000}).round();

    assertEquals(written, track.firstCorner());
    assertFalse(track.getStartContacts().contains(pin(board, "1")));
  }
}
