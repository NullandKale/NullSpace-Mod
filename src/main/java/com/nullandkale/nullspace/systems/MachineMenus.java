package com.nullandkale.nullspace.systems;

import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.station.*;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class MachineMenus {
  public static final BlockEntityType<MachineEntity> ENTITY =
      Registry.register(
          BuiltInRegistries.BLOCK_ENTITY_TYPE,
          Identifier.parse("nullspace:machine"),
          new BlockEntityType<>(
              MachineEntity::new,
              Set.of(
                  ShipBlocks.GENERATOR,
                  ShipBlocks.BATTERY,
                  ShipBlocks.ENGINE,
                  ShipBlocks.LOCAL_SCANNER,
                  ShipBlocks.CAMERA,
                  ShipBlocks.MONITOR,
                  ShipBlocks.AIR_SUPPLY,
                  ShipBlocks.WEAPON,
                  ShipBlocks.DOCKING_BASE)));
  public static final net.minecraft.world.inventory.MenuType<MachineMenu> MENU =
      Registry.register(
          BuiltInRegistries.MENU,
          Identifier.parse("nullspace:machine"),
          new net.minecraft.world.inventory.MenuType<>(
              MachineMenu::new, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));

  public record View(int container, CompoundTag data) implements CustomPacketPayload {
    public static final Type<View> TYPE = new Type<>(Identifier.parse("nullspace:machine_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeInt(p.container);
              b.writeNbt(p.data);
            },
            b -> new View(b.readInt(), b.readNbt()));

    @Override
    public Type<View> type() {
      return TYPE;
    }
  }

  public record Edit(int container, int action, String value) implements CustomPacketPayload {
    public static final Type<Edit> TYPE = new Type<>(Identifier.parse("nullspace:machine_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeInt(p.container);
              b.writeInt(p.action);
              b.writeUtf(p.value, 64);
            },
            b -> new Edit(b.readInt(), b.readInt(), b.readUtf(64)));

    @Override
    public Type<Edit> type() {
      return TYPE;
    }
  }

  public static boolean open(ServerPlayer p, BlockPos at) {
    if (p.level().getBlockEntity(at) instanceof MachineEntity machine
        && machine.stillValid(p)
        && StationService.mayUse(p, at)
        && !TravelService.busy(p)
        && !TravelService.locked(p.level(), at)) {
      p.openMenu(machine);
      return true;
    }
    return false;
  }

  public static void initialize() {
    PayloadTypeRegistry.clientboundPlay().register(View.TYPE, View.CODEC);
    PayloadTypeRegistry.serverboundPlay().register(Edit.TYPE, Edit.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        Edit.TYPE,
        (packet, context) ->
            context
                .server()
                .execute(
                    () -> {
                      if (context.player().containerMenu instanceof MachineMenu menu
                          && menu.containerId == packet.container
                          && menu.stillValid(context.player()))
                        menu.edit(context.player(), packet.action, packet.value);
                    }));
  }

  private MachineMenus() {}
}
