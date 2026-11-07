package com.nullandkale.nullspace.testing;

import com.nullandkale.nullspace.dev.ReferenceShip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Tests use the same reference build supplied by the development lab. */
public final class ShipTestFixture {
  public static BlockPos build(ServerLevel level, BlockPos base) {
    return ReferenceShip.build(level, base);
  }

  private ShipTestFixture() {}
}
