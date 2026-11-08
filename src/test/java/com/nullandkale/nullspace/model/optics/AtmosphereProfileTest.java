package com.nullandkale.nullspace.model.optics;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AtmosphereProfileTest
{
    private final AtmosphereProfile earth = AtmosphereProfile.temperate(6360, 100. / 6360);

    @Test
    void profileRoundTripsWithoutLosingOpticalUnits()
    {
        assertEquals(earth, AtmosphereProfile.decode(earth.encode()));
        assertEquals(100, earth.height(), 1e-10);
        assertEquals(8, earth.rayleighHeight(), 1e-10);
    }

    @Test
    void densityFallsExponentiallyAndEndsAtTheTop()
    {
        var low = earth.extinction(0);
        var high = earth.extinction(50);
        assertTrue(low.x > high.x && low.y > high.y && low.z > high.z);
        assertEquals(Vec3.ZERO, earth.extinction(100));
        assertEquals(earth.extinction(0), earth.extinction(-10));
    }

    @Test
    void noonTransmitsMoreThanHorizonAndHorizonReddeningIsStronger()
    {
        var noon = earth.transmittance(.05, 1, 4096);
        var horizon = earth.transmittance(.05, 0, 4096);
        assertTrue(noon.x > horizon.x && noon.z > horizon.z);
        assertTrue(noon.x > noon.z);
        assertTrue(horizon.x / horizon.z > noon.x / noon.z * 10);
    }

    @Test
    void PlanetCastsAShadowAndHigherObserversSeeTheSunAfterGroundSunset()
    {
        assertEquals(Vec3.ZERO, earth.transmittance(.05, -.05, 512));
        assertTrue(earth.transmittance(50, -.05, 4096).x > .1);
    }

    @Test
    void ScaledPlanetPreservesOpticalDepth()
    {
        var compressed = AtmosphereProfile.temperate(636, 100. / 6360);
        var a = earth.transmittance(.5, .3, 4096);
        var b = compressed.transmittance(.05, .3, 4096);
        assertEquals(a.x, b.x, 1e-9);
        assertEquals(a.y, b.y, 1e-9);
        assertEquals(a.z, b.z, 1e-9);
    }

    @Test
    void MidpointReferenceConverges()
    {
        var low = earth.transmittance(.1, .015, 2048);
        var high = earth.transmittance(.1, .015, 8192);
        assertTrue(low.distanceTo(high) < .00001);
    }

    @Test
    void RaysLeavingAtmosphereHaveUnitTransmission()
    {
        var t = earth.transmittance(100, 1, 1024);
        assertTrue(t.distanceTo(new Vec3(1, 1, 1)) < 1e-9);
    }

    @Test
    void MalformedProfilesAndRaysAreRejectedBeforeGpuWork()
    {
        for (String field : new String[]{"radius", "height", "rayleighR", "mie", "absorptionB", "rayleighHeight", "mieHeight", "albedo", "clouds"})
        {
            var tag = earth.encode();
            tag.putDouble(field, Double.NaN);
            assertThrows(IllegalArgumentException.class, () -> AtmosphereProfile.decode(tag), field);
        }
        assertThrows(IllegalArgumentException.class, () -> earth.transmittance(-1, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> earth.transmittance(0, 2, 64));
        assertThrows(IllegalArgumentException.class, () -> earth.transmittance(0, 0, 0));
        var bad = earth.encode();
        bad.putDouble("clouds", 2);
        assertThrows(IllegalArgumentException.class, () -> AtmosphereProfile.decode(bad));
    }
}
