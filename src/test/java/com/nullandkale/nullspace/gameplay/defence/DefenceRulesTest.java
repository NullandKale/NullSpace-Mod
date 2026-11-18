package com.nullandkale.nullspace.gameplay.defence;

import com.nullandkale.nullspace.gameplay.systems.ShipAir;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefenceRulesTest
{
    @Test
    void aWornDiamondPickaxeCostsAStackOfCoalAndNothingCostsLessThanEight()
    {
        assertEquals(64.0, RepairCost.fuelFor(RepairCost.FULL_DURABILITY), 1e-9);
        assertEquals(8.0, RepairCost.fuelFor(10), "the cheapest job");
        assertEquals(8.0, RepairCost.fuelFor(1));
        assertEquals(32.0, RepairCost.fuelFor(RepairCost.FULL_DURABILITY / 2), 0.05, "scales with the durability restored");
        assertTrue(RepairCost.fuelFor(2031) > 64.0, "a netherite pickaxe costs more than a stack");
    }

    @Test
    void theCostIsPaidInTheUnitsOfTheGenerators()
    {
        // One coal is 12,800 units and is shown as 1.00, so a stack of coal is 819,200.
        assertEquals(819200, (int) Math.ceil(RepairCost.fuelFor(RepairCost.FULL_DURABILITY) * RepairCost.UNITS_PER_FUEL));
    }

    @Test
    void anExplosionTakesAShareOfTheAirThatGrowsWithItsPowerAndStopsShortOfAll()
    {
        assertEquals(0.6, ShipAir.blastShare(3.0F), 1e-6, "a creeper takes a dent");
        assertEquals(0.8, ShipAir.blastShare(4.0F), 1e-6, "TNT takes most of it");
        assertEquals(0.95, ShipAir.blastShare(8.0F), 1e-6, "never all of it");
        assertEquals(0.0, ShipAir.blastShare(0.0F), 1e-6);
        assertTrue(ShipAir.blastShare(4.0F) > ShipAir.blastShare(3.0F));
    }
}
