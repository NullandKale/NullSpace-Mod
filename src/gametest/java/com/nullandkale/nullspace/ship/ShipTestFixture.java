package com.nullandkale.nullspace.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Tests use the same reference build supplied by the development lab. */
public final class ShipTestFixture {
  public static BlockPos build(ServerLevel level, BlockPos base) {
    return com.nullandkale.nullspace.dev.ReferenceShip.build(level, base);
  }

  private ShipTestFixture() {}
}
