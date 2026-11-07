package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** End pads are landing destinations from Terra's orbit only. */
public final class EndRouteGameTest {
  @GameTest
  public void endPadsBelongToTerrasOrbitOnly(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    String end = net.minecraft.world.level.Level.END.identifier().toString();
    ctx.assertTrue(
        PlanetSurfaces.surfaceOf(server, LocalSpaces.HOME, end), "Terra's orbit reaches End pads");
    for (var zone : StarSystems.galaxy(server).zones())
      if (!zone.equals(LocalSpaces.HOME))
        ctx.assertTrue(
            !PlanetSurfaces.surfaceOf(server, zone, end),
            "Only Terra reaches the End, not " + zone);
    ctx.assertTrue(
        PlanetSurfaces.surfaceOf(server, LocalSpaces.HOME, "minecraft:overworld"),
        "Terra's own pads still belong to Terra");
    ctx.succeed();
  }
}
