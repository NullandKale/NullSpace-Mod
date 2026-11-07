package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.generation.VillagePorts;
import com.nullandkale.nullspace.pad.PadComputers;
import com.nullandkale.nullspace.pad.PadRepository;
import com.nullandkale.nullspace.ship.PadRegistry;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** P8: some villages get a registered landing pad with a Pad Computer and a service hut. */
public final class VillagePortGameTest {
  @GameTest(maxTicks = 40)
  public void someVillagesGetARegisteredPortWithAServiceHut(GameTestHelper ctx) throws Exception {
    var level = ctx.getLevel();
    var server = level.getServer();
    int chosen = 0;
    for (int i = 0; i < 3000; i++) if (VillagePorts.chosen(level.getSeed(), new ChunkPos(i, i * 7))) chosen++;
    ctx.assertTrue(chosen > 850 && chosen < 1150, "About one village in three gets a port: " + chosen);

    var village = new BoundingBox(2_100_000, 0, 2_100_000, 2_100_060, 40, 2_100_060);
    var before = PadRegistry.read(server);
    BlockPos computer = null;
    try {
      computer = VillagePorts.build(level, village, 42);
      ctx.assertTrue(computer != null, "A flat site beside the village takes a port");
      ctx.assertTrue(level.getBlockState(computer).is(PadComputers.BLOCK), "The port has a Pad Computer");
      var at = computer;
      var pad =
          PadRegistry.read(server).stream()
              .filter(p -> p.computer().equals(at))
              .findFirst()
              .orElse(null);
      ctx.assertTrue(pad != null && pad.name().contains(" Port"), "Its pad is registered as a port");
      ctx.assertTrue(
          pad.bounds().minX() > village.maxX() || pad.bounds().maxX() < village.minX()
              || pad.bounds().minZ() > village.maxZ() || pad.bounds().maxZ() < village.minZ(),
          "The pad stands outside the village");
      boolean craft = false, door = false;
      for (var p : BlockPos.betweenClosed(computer.offset(-6, -1, -6), computer.offset(1, 3, 6))) {
        craft |= level.getBlockState(p).is(Blocks.CRAFTING_TABLE);
        door |= level.getBlockState(p).getBlock() instanceof DoorBlock;
      }
      ctx.assertTrue(craft && door, "The service hut has a door and a crafting table");
      for (int y = 1; y <= 24; y++)
        ctx.assertTrue(
            level.getBlockState(new BlockPos(pad.bounds().minX() + 8, pad.bounds().y() + y, pad.bounds().minZ() + 8)).isAir(),
            "The sky over the pad is clear");
    } finally {
      PadRepository.get(server).replace(before);
      if (computer != null)
        for (var p : BlockPos.betweenClosed(computer.offset(-8, -2, -12), computer.offset(22, 26, 12)))
          level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
    }
    ctx.succeed();
  }
}
