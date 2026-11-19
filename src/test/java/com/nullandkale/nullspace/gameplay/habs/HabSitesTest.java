package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HabSitesTest
{
    @Test
    void neighboringSitesLeaveRoomForTheLargestDungeonOnEitherSideOfZero()
    {
        for (int x = -12; x < 12; x++)
            for (int z = -12; z < 12; z++)
            {
                var site = HabSites.site(79419843L, x, z);
                assertEquals(x, Math.floorDiv(site.x(), HabSites.SPACING));
                assertEquals(z, Math.floorDiv(site.z(), HabSites.SPACING));
                assertTrue(HabSites.site(79419843L, x + 1, z).x() - site.x() > 512 + 32);
                assertTrue(HabSites.site(79419843L, x, z + 1).z() - site.z() > 512 + 32);
                for (int attempt = 0; attempt < HabSites.ATTEMPTS; attempt++)
                {
                    var alternative = HabSites.site(79419843L, x, z, attempt);
                    assertEquals(site.spec(), alternative.spec());
                    assertTrue(Math.abs(alternative.x() - site.x()) <= 64 && Math.abs(alternative.z() - site.z()) <= 64);
                    assertTrue(HabSites.site(79419843L, x + 1, z).x() - alternative.x() - 64 > 512 + 32);
                    assertTrue(HabSites.site(79419843L, x, z + 1).z() - alternative.z() - 64 > 512 + 32);
                }
            }
    }

    @Test
    void everyMakerSizeAndHabPurposeOccursAndExplorationOrderCannotChangeASelection()
    {
        var sites = new HashMap<Integer, HabSites.Site>();
        var makers = EnumSet.noneOf(Maker.class);
        var sizes = EnumSet.noneOf(StationSpec.Size.class);
        var purposes = EnumSet.noneOf(StationSpec.Purpose.class);
        for (int x = -100; x < 100; x++)
        {
            var site = HabSites.site(35917L, x, -7);
            sites.put(x, site);
            makers.add(site.spec().maker());
            sizes.add(site.spec().size());
            purposes.add(site.spec().purpose());
        }
        for (int x = 99; x >= -100; x--)
            assertEquals(sites.get(x), HabSites.site(35917L, x, -7));
        assertEquals(EnumSet.allOf(Maker.class), makers);
        assertEquals(EnumSet.allOf(StationSpec.Size.class), sizes);
        assertEquals(EnumSet.of(StationSpec.Purpose.HABITATION, StationSpec.Purpose.AGRICULTURE, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.RESEARCH, StationSpec.Purpose.SECURITY), purposes);
        assertNotEquals(sites.get(0), HabSites.site(35918L, 0, -7));
    }
}
