package com.nullandkale.nullspace.ui.terminal;

import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Blocks;

/** Real codecs and server authorization; rejected requests must leave valid sessions usable. */
public final class ProtocolBoundaryGameTest {
  private static RegistryFriendlyByteBuf buffer(GameTestHelper ctx) {
    return new RegistryFriendlyByteBuf(
        io.netty.buffer.Unpooled.buffer(), ctx.getLevel().registryAccess());
  }

  private static <T> void roundTrip(
      GameTestHelper ctx, StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
    var b = buffer(ctx);
    try {
      codec.encode(b, value);
      ctx.assertTrue(
          value.equals(codec.decode(b)) && !b.isReadable(),
          "Exact packet round-trip: " + value.getClass().getSimpleName());
    } finally {
      b.release();
    }
  }

  private static void rejected(GameTestHelper ctx, Runnable operation, String reason) {
    boolean rejected = false;
    try {
      operation.run();
    } catch (RuntimeException expected) {
      rejected = true;
    }
    ctx.assertTrue(rejected, reason);
  }

  @GameTest
  public void everyTerminalActionAndMaximumLegalPayloadRoundTrips(GameTestHelper ctx) {
    var id = UUID.randomUUID();
    for (var action : ComputerTerminal.Action.values())
      roundTrip(
          ctx,
          ComputerTerminal.Request.CODEC,
          new ComputerTerminal.Request(
              id, action, "x".repeat(32), "d".repeat(36), Integer.MAX_VALUE));
    var destinations =
        java.util.stream.IntStream.range(0, 128)
            .mapToObj(
                i ->
                    new ComputerTerminal.Destination(
                        UUID.randomUUID().toString(), "N".repeat(160), "S".repeat(16)))
            .toList();
    roundTrip(
        ctx,
        ComputerTerminal.Open.CODEC,
        new ComputerTerminal.Open(
            new BlockPos(-30000000, -64, 30000000),
            "m".repeat(2048),
            "long_range_scanner",
            "v".repeat(32),
            id,
            destinations));
    roundTrip(
        ctx,
        ComputerTerminal.Open.CODEC,
        new ComputerTerminal.Open(
            BlockPos.ZERO,
            "Station services",
            "station_core",
            "0",
            id,
            List.of(
                new ComputerTerminal.Destination(
                    UUID.randomUUID().toString(),
                    "Halcyon Courier - abandoned freighter",
                    "Available"))));
    roundTrip(
        ctx, ComputerTerminal.Update.CODEC, new ComputerTerminal.Update(id, "u".repeat(3000), 123));
    roundTrip(
        ctx, ComputerTerminal.Rejected.CODEC, new ComputerTerminal.Rejected(id, "r".repeat(256)));
    roundTrip(ctx, ComputerTerminal.Close.CODEC, new ComputerTerminal.Close(id));
    roundTrip(
        ctx, ComputerService.Input.CODEC, new ComputerService.Input(id, "i".repeat(2048), false));
    ctx.succeed();
  }

  @GameTest
  public void oversizedAndForgedPacketsRejectBeforeDispatch(GameTestHelper ctx) {
    var id = UUID.randomUUID();
    for (int count : new int[] {-1, 129, Integer.MAX_VALUE}) {
      var b = buffer(ctx);
      try {
        b.writeBlockPos(BlockPos.ZERO);
        b.writeUtf("");
        b.writeUtf("ship");
        b.writeUtf("");
        b.writeUUID(id);
        b.writeVarInt(count);
        rejected(
            ctx,
            () -> ComputerTerminal.Open.CODEC.decode(b),
            "Reject invalid destination count " + count);
      } finally {
        b.release();
      }
    }
    var b = buffer(ctx);
    try {
      b.writeUUID(id);
      b.writeVarInt(ComputerTerminal.Action.values().length);
      b.writeUtf("");
      b.writeUtf("");
      b.writeInt(0);
      rejected(
          ctx, () -> ComputerTerminal.Request.CODEC.decode(b), "Reject unknown action ordinal");
    } finally {
      b.release();
    }
    final var oversized = buffer(ctx);
    try {
      rejected(
          ctx,
          () ->
              ComputerService.Input.CODEC.encode(
                  oversized, new ComputerService.Input(id, "x".repeat(2049), false)),
          "Reject oversized keyboard input");
    } finally {
      oversized.release();
    }
    ctx.succeed();
  }

  @GameTest
  public void fullyColoredTerminalFitsItsActualNetworkPacket(GameTestHelper ctx) {
    var terminal = new TerminalBuffer();
    for (int y = 0; y < 25; y++)
      for (int x = 0; x < 80; x++)
        terminal.put(
            x,
            y,
            "X",
            0xff000000 | (y * 80 + x) * 7919 & 0xffffff,
            0xff000000 | (y * 80 + x) * 1543 & 0xffffff);
    String snapshot = terminal.snapshot();
    ctx.assertTrue(
        snapshot.length() <= ComputerService.MAX_SCREEN_LENGTH,
        "The worst styled screen must fit the protocol");
    roundTrip(
        ctx,
        ComputerService.Screen.CODEC,
        new ComputerService.Screen(UUID.randomUUID(), snapshot, false));
    var decoded = new TerminalBuffer();
    decoded.write(snapshot);
    for (int y = 0; y < 25; y++)
      for (int x = 0; x < 80; x++)
        ctx.assertTrue(
            terminal.cell(x, y).equals(decoded.cell(x, y)),
            "All 2000 RGB cells must survive a late viewer snapshot");
    ctx.succeed();
  }

  @GameTest
  public void anotherPlayerCannotCloseOrUseAPadSession(GameTestHelper ctx) {
    var owner = ctx.makeMockServerPlayerInLevel();
    var stranger = ctx.makeMockServerPlayerInLevel();
    var pos = ctx.absolutePos(new BlockPos(2, 3, 2));
    ctx.getLevel().setBlock(pos, PadComputers.BLOCK.defaultBlockState(), 18);
    owner.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
    stranger.teleportTo(owner.getX(), owner.getY(), owner.getZ());
    try {
      var session = ComputerTerminal.issue(owner, pos, "", "pad", "");
      ctx.assertTrue(
          !session.session().equals(ComputerTerminal.NONE),
          "A nearby pad must issue a valid session");
      ComputerTerminal.close(stranger, session.session());
      rejected(
          ctx,
          () ->
              ComputerTerminal.authorize(
                  stranger,
                  new ComputerTerminal.Request(
                      session.session(), ComputerTerminal.Action.REGISTER, "stolen", "")),
          "Reject another player's session");
      ComputerTerminal.authorize(
          owner,
          new ComputerTerminal.Request(session.session(), ComputerTerminal.Action.INSPECT, "", ""));
      ctx.succeed();
    } finally {
      ctx.getLevel().getServer().getPlayerList().remove(owner);
      ctx.getLevel().getServer().getPlayerList().remove(stranger);
    }
  }

  @GameTest
  public void invalidPadArgumentsAndActionsCannotInvalidateAValidOwner(GameTestHelper ctx) {
    var player = ctx.makeMockServerPlayerInLevel();
    var pos = ctx.absolutePos(new BlockPos(2, 3, 2));
    ctx.getLevel().setBlock(pos, PadComputers.BLOCK.defaultBlockState(), 18);
    player.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
    try {
      var session = ComputerTerminal.issue(player, pos, "", "pad", "");
      for (String name :
          List.of("x".repeat(33), "../world", "x\ny", "a;stop", "$(command)", "emoji 🚀"))
        rejected(
            ctx,
            () ->
                ComputerTerminal.authorize(
                    player,
                    new ComputerTerminal.Request(
                        session.session(), ComputerTerminal.Action.REGISTER, name, "")),
            "Reject invalid pad name " + name);
      for (var action :
          List.of(
              ComputerTerminal.Action.FIRE,
              ComputerTerminal.Action.LAUNCH,
              ComputerTerminal.Action.FAST_TRAVEL,
              ComputerTerminal.Action.CONFIGURE))
        rejected(
            ctx,
            () ->
                ComputerTerminal.authorize(
                    player, new ComputerTerminal.Request(session.session(), action, "", "")),
            "Pad must not grant " + action);
      ComputerTerminal.authorize(
          player,
          new ComputerTerminal.Request(
              session.session(), ComputerTerminal.Action.REGISTER, "Crew Pad-01", ""));
      ctx.succeed();
    } finally {
      ctx.getLevel().getServer().getPlayerList().remove(player);
    }
  }

  @GameTest
  public void movedReplacedAndClosedControllersRevokeSessions(GameTestHelper ctx) {
    var player = ctx.makeMockServerPlayerInLevel();
    var pos = ctx.absolutePos(new BlockPos(2, 3, 2));
    try {
      for (int scenario = 0; scenario < 4; scenario++) {
        ctx.getLevel().setBlock(pos, PadComputers.BLOCK.defaultBlockState(), 18);
        player.teleportTo(
            ctx.getLevel(), pos.getX() + .5, pos.getY(), pos.getZ() + .5, Set.of(), 0, 0, true);
        var session = ComputerTerminal.issue(player, pos, "", "pad", "");
        switch (scenario) {
          case 0 -> ctx.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
          case 1 -> player.teleportTo(pos.getX() + 9, pos.getY(), pos.getZ());
          case 2 -> ComputerTerminal.close(player, session.session());
          case 3 ->
              player.teleportTo(
                  ctx.getLevel().getServer().getLevel(TravelService.SPACE),
                  pos.getX(),
                  pos.getY(),
                  pos.getZ(),
                  Set.of(),
                  0,
                  0,
                  true);
        }
        rejected(
            ctx,
            () ->
                ComputerTerminal.authorize(
                    player,
                    new ComputerTerminal.Request(
                        session.session(), ComputerTerminal.Action.INSPECT, "", "")),
            "Revoked controller scenario " + scenario);
      }
      ctx.succeed();
    } finally {
      ctx.getLevel().getServer().getPlayerList().remove(player);
    }
  }
}
