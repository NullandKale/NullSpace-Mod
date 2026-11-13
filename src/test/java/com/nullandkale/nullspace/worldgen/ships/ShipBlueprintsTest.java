package com.nullandkale.nullspace.worldgen.ships;

import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.ui.terminal.ControllerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;

import java.util.*;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShipBlueprintsTest
{
    @Test
    void tinyDesignsAreExplicitCatalogChoicesAndNeverRandomSalvage()
    {
        assertEquals(Set.of("rivet_spark", "halcyon_wisp", "bastion_dart"), ShipBlueprints.PRESETS.stream().filter(ShipBlueprints.Preset::tiny).map(ShipBlueprints.Preset::id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(ShipBlueprints.get(ShipBlueprints.DEFAULT_STARTER).tiny());
        for (int kind : List.of(1, 2))
            for (long seed = 0; seed < 300; seed++)
                assertFalse(ShipBlueprints.get(com.nullandkale.nullspace.worldgen.encounters.EncounterSites.design(kind, seed)).tiny());
    }

    @Test
    void lastCatalogPageKeepsPurchaseVisibleAndBlankRowsCannotBuy()
    {
        var t = new ControllerTerminal("shipyard", "Ready", "rivet_bolt");
        for (int i = 1; i < ShipBlueprints.PRESETS.size(); i++)
            t.input(TerminalInput.Key.DOWN);
        int last = -1;
        for (int row = 0; row < 24; row++)
            if (t.display().row(row).contains("Bastion Dart"))
                last = row;
        assertTrue(last > 0, "The last design is on screen once it is selected");
        t.input(new TerminalInput.Pointer(10, 19));
        assertNull(t.pollAction(), "Blank catalog rows cannot trigger a purchase");
        t.input(TerminalInput.Key.ENTER);
        assertEquals(new ControllerTerminal.Action("ship_quote", "bastion_dart"), t.pollAction());
        t.update("Dart details");
        t.input(new TerminalInput.Pointer(10, 18));
        assertEquals(new ControllerTerminal.Action("ship_buy", "bastion_dart"), t.pollAction());
    }

    @Test
    void presetsHaveStableUniqueIdentitiesAndFitTheShipEnvelope()
    {
        var ids = new HashSet<String>();
        for (var p : ShipBlueprints.PRESETS)
        {
            assertTrue(ids.add(p.id()));
            assertTrue(p.id().matches("[a-z_]{1,32}"));
            assertTrue(p.name().length() <= 17);
            assertTrue(p.width() <= 32 && p.height() <= 32 && p.depth() <= 32);
            assertTrue(p.height() >= p.decks() * 5, "Declared height includes deck headroom and hull");
            assertSame(p, ShipBlueprints.get(p.id()));
        }
        assertEquals(12, ids.size());
        assertEquals(List.of("rivet_bolt", "rivet_hauler", "rivet_ranger", "halcyon_swift", "halcyon_courier", "halcyon_horizon", "bastion_ward", "bastion_tender", "bastion_pioneer", "rivet_spark", "halcyon_wisp", "bastion_dart"), ShipBlueprints.PRESETS.stream().map(ShipBlueprints.Preset::id).toList());
        // Designs retired when ships became generated still resolve, so old saves and settings keep working.
        assertEquals("rivet_bolt", ShipBlueprints.get("sparrow").id());
        assertEquals("rivet_hauler", ShipBlueprints.get("mule").id());
        assertThrows(IllegalArgumentException.class, () -> ShipBlueprints.get("forged"));
        assertThrows(IllegalArgumentException.class, () -> ShipBlueprints.get(null));
    }

    @Test
    void placementUsesTheChosenFloorAndCentersWithoutRotation()
    {
        var random = new Random(8871541);
        for (var p : ShipBlueprints.PRESETS)
            for (int sample = 0; sample < 80; sample++)
            {
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
    void widthDepthAndHeightFailuresNeverSilentlyRotateOrUseAnotherFloor()
    {
        for (var p : ShipBlueprints.PRESETS)
        {
            var exact = new PadScanner.Pad(0, p.width() - 1, 70, 0, p.depth() - 1);
            assertEquals(new BlockPos(0, 71, 0), ShipBlueprints.origin(p, exact, p.height()));
            assertThrows(IllegalArgumentException.class, () -> ShipBlueprints.origin(p, exact, p.height() - 1));
            assertThrows(IllegalArgumentException.class, () -> ShipBlueprints.origin(p, new PadScanner.Pad(0, p.width() - 2, 70, 0, 31), 32));
            assertThrows(IllegalArgumentException.class, () -> ShipBlueprints.origin(p, new PadScanner.Pad(0, 31, 70, 0, p.depth() - 2), 32));
        }
    }

    @Test
    void quotesAndPurchasesAreSeparateKeyboardActionsWithNoNameEditor()
    {
        var t = new ControllerTerminal("shipyard", "Pad ready", "rivet_bolt");
        assertEquals(-1, t.field());
        t.input(new TerminalInput.Text("forged"));
        assertEquals("rivet_bolt", t.fieldValue());
        t.input(TerminalInput.Key.DOWN);
        t.input(TerminalInput.Key.ENTER);
        assertEquals(new ControllerTerminal.Action("ship_quote", "rivet_hauler"), t.pollAction());
        t.input(TerminalInput.Key.ENTER);
        assertNull(t.pollAction(), "A waiting terminal cannot enqueue duplicate purchases");
        t.update("Rivet Hauler details");
        int buyRow = 18;
        t.input(new TerminalInput.Pointer(10, buyRow));
        assertEquals(new ControllerTerminal.Action("ship_buy", "rivet_hauler"), t.pollAction());
        assertTrue(t.display().row(buyRow).contains("Rivet Hauler"));
        assertTrue(t.display().row(23).contains("ESC Close"));
        assertTrue(t.display().row(1).contains("SHIPYARD"));
    }
}
