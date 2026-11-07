package com.nullandkale.nullspace.worldgen.ships;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.ui.terminal.ControllerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ShipBlueprintsTest {
  @Test
  void lastCatalogPageKeepsPurchaseVisibleAndBlankRowsCannotBuy() {
    var t = new ControllerTerminal("shipyard", "Ready", "sparrow");
    for (int i = 0; i < 14; i++) t.input(TerminalInput.Key.DOWN);
    assertTrue(t.display().row(14).contains("Bastion Pioneer"));
    t.input(new TerminalInput.Pointer(10, 15));
    assertNull(t.pollAction(), "Blank catalog rows cannot trigger a purchase");
    t.input(TerminalInput.Key.ENTER);
    assertEquals(new ControllerTerminal.Action("ship_quote", "bastion_pioneer"), t.pollAction());
    t.update("Pioneer details");
    t.input(new TerminalInput.Pointer(10, 18));
    assertEquals(new ControllerTerminal.Action("ship_buy", "bastion_pioneer"), t.pollAction());
  }

  @Test
  void presetsHaveStableUniqueIdentitiesAndFitTheShipEnvelope() {
    var ids = new HashSet<String>();
    for (var p : ShipBlueprints.PRESETS) {
      assertTrue(ids.add(p.id()));
      assertTrue(p.id().matches("[a-z_]{1,32}"));
      assertTrue(p.name().length() <= 17);
      assertTrue(p.width() <= 32 && p.height() <= 32 && p.depth() <= 32);
      assertTrue(p.height() >= p.decks() * 5, "Declared height includes deck headroom and hull");
      assertSame(p, ShipBlueprints.get(p.id()));
    }
    assertEquals(15, ids.size());
    assertEquals(
        List.of("sparrow", "skylark", "mule", "wayfarer", "aegis", "atlas"),
        ShipBlueprints.PRESETS.subList(0, 6).stream().map(ShipBlueprints.Preset::id).toList());
    assertThrows(IllegalArgumentException.class, () -> ShipBlueprints.get("forged"));
    assertThrows(IllegalArgumentException.class, () -> ShipBlueprints.get(null));
  }

  @Test
  void placementUsesTheChosenFloorAndCentersWithoutRotation() {
    var random = new Random(8871541);
    for (var p : ShipBlueprints.PRESETS)
      for (int sample = 0; sample < 80; sample++) {
        int x = random.nextInt(60000000) - 30000000, z = random.nextInt(60000000) - 30000000;
        int width = p.width() + random.nextInt(33 - p.width());
        int depth = p.depth() + random.nextInt(33 - p.depth());
        var pad = new PadScanner.Pad(x, x + width - 1, random.nextInt(300) - 60, z, z + depth - 1);
        var at = ShipBlueprints.origin(p, pad, p.height());
        assertEquals(pad.y() + 1, at.getY());
        assertTrue(at.getX() >= pad.minX() && at.getX() + p.width() - 1 <= pad.maxX());
        assertTrue(at.getZ() >= pad.minZ() && at.getZ() + p.depth() - 1 <= pad.maxZ());
        assertTrue(Math.abs((at.getX() - x) - (pad.maxX() - at.getX() - p.width() + 1)) <= 1);
        assertTrue(Math.abs((at.getZ() - z) - (pad.maxZ() - at.getZ() - p.depth() + 1)) <= 1);
      }
  }

  @Test
  void widthDepthAndHeightFailuresNeverSilentlyRotateOrUseAnotherFloor() {
    for (var p : ShipBlueprints.PRESETS) {
      var exact = new PadScanner.Pad(0, p.width() - 1, 70, 0, p.depth() - 1);
      assertEquals(new BlockPos(0, 71, 0), ShipBlueprints.origin(p, exact, p.height()));
      assertThrows(
          IllegalArgumentException.class, () -> ShipBlueprints.origin(p, exact, p.height() - 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> ShipBlueprints.origin(p, new PadScanner.Pad(0, p.width() - 2, 70, 0, 31), 32));
      assertThrows(
          IllegalArgumentException.class,
          () -> ShipBlueprints.origin(p, new PadScanner.Pad(0, 31, 70, 0, p.depth() - 2), 32));
    }
  }

  @Test
  void quotesAndPurchasesAreSeparateKeyboardActionsWithNoNameEditor() {
    var t = new ControllerTerminal("shipyard", "Pad ready", "sparrow");
    assertEquals(-1, t.field());
    t.input(new TerminalInput.Text("forged"));
    assertEquals("sparrow", t.fieldValue());
    t.input(TerminalInput.Key.DOWN);
    t.input(TerminalInput.Key.ENTER);
    assertEquals(new ControllerTerminal.Action("ship_quote", "skylark"), t.pollAction());
    t.input(TerminalInput.Key.ENTER);
    assertNull(t.pollAction(), "A waiting terminal cannot enqueue duplicate purchases");
    t.update("Skylark details");
    int buyRow = 18;
    t.input(new TerminalInput.Pointer(10, buyRow));
    assertEquals(new ControllerTerminal.Action("ship_buy", "skylark"), t.pollAction());
    assertTrue(t.display().row(buyRow).contains("Skylark"));
    assertTrue(t.display().row(23).contains("ESC Close"));
    assertTrue(t.display().row(1).contains("SHIPYARD"));
  }
}
