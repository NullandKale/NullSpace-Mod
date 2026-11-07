package com.nullandkale.nullspace.gameplay.ship;

import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;

/** Player-facing names and repair advice; registry IDs remain available when a type matters. */
public final class ShipMessages {
  private static String words(String path) {
    var text = new StringBuilder();
    for (String word : path.replace('_', ' ').split(" ")) {
      if (!text.isEmpty()) text.append(' ');
      text.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
    }
    return text.toString();
  }

  public static String block(Block block) {
    String name = block.getName().getString();
    return name.equals(block.getDescriptionId())
        ? words(BuiltInRegistries.BLOCK.getKey(block).getPath())
        : name;
  }

  public static String block(ServerLevel level, BlockPos pos) {
    return block(level.getBlockState(pos).getBlock());
  }

  public static String entity(Entity entity) {
    String name = entity.getType().getDescription().getString();
    return name.equals(entity.getType().getDescriptionId())
        ? words(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath())
        : name;
  }

  public static HullScan.Failure entityProblem(Entity entity, String advice) {
    return new HullScan.Failure(
        entity(entity)
            + ": "
            + advice
            + "\nEntity type: "
            + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
        entity.blockPosition(),
        java.util.List.of(entity.blockPosition()));
  }

  public static HullScan.Failure blockProblem(ServerLevel level, BlockPos pos, String advice) {
    return new HullScan.Failure(
        advice + "\nBlock: " + block(level, pos), pos, java.util.List.of(pos));
  }

  public static String failure(Exception error) {
    return error instanceof IllegalArgumentException
            && error.getMessage() != null
            && !error.getMessage().isBlank()
        ? error.getMessage()
        : "We couldn't finish that action. Reopen the controller and try again. Details were saved"
            + " in the game log.";
  }

  private ShipMessages() {}
}
