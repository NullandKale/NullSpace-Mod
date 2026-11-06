package com.nullandkale.nullspace.ship;

import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.equipment.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;

public final class ShipBlocks {
  public static final Block SHIP_BEACON = machine("ship_beacon");
  public static final Block STATION_CORE = machine("station_core");
  public static final Block DOOR_CONNECTOR = machine("door_connector");
  public static final Block DOCKING_PORT = machine("docking_port");
  public static final Block DOCKING_BASE = machine("docking_base");
  public static final Item SYSTEM_UPGRADE = component("system_upgrade");
  public static final Item STATION_MODULE = component("station_module");
  public static final Block TERMINAL = machine("computer_terminal");
  public static final Block LOCAL_SCANNER = machine("local_scanner");
  public static final Block LONG_RANGE_SCANNER = machine("long_range_scanner");
  public static final Block COMPUTER_BASE = machine("computer_base");
  public static final Item MACHINE_CASING = component("machine_casing");
  public static final Item CIRCUIT_BOARD = component("circuit_board");
  public static final Item SHIP_TITLE = component("ship_title");
  public static final Block COMPUTER = machine("ship_computer");
  public static final Block HELM = machine("helm");
  public static final Block SYSTEMS_CONTROLLER = machine("systems_controller");
  public static final Block ENGINE = machine("engine");
  public static final Block GENERATOR = machine("generator");
  public static final Block BATTERY = machine("battery");
  public static final Block AIR_SUPPLY = machine("air_supply");
  public static final Block DOOR_CONTROL = machine("door_control");
  public static final Block CAMERA = machine("camera");
  public static final Block MONITOR = machine("monitor");
  public static final Block WEAPON = machine("weapon");
  public static final Item HELMET = suit("space_helmet", ArmorType.HELMET);
  public static final Item CHEST = suit("space_chestplate", ArmorType.CHESTPLATE);
  public static final Item LEGS = suit("space_leggings", ArmorType.LEGGINGS);
  public static final Item BOOTS = suit("space_boots", ArmorType.BOOTS);

  private static Block machine(String name) {
    if (!com.nullandkale.nullspace.content.ContentCatalog.equipment(name)
        .orElseThrow()
        .kind()
        .equals("block")) throw new IllegalStateException("Equipment must define a block: " + name);
    var id = Identifier.parse("nullspace:" + name);
    var block =
        Registry.register(
            BuiltInRegistries.BLOCK,
            id,
            createMachine(
                name,
                BlockBehaviour.Properties.of()
                    .strength(4)
                    .lightLevel(state -> name.equals("ship_beacon") ? 10 : 0)
                    .setId(ResourceKey.create(Registries.BLOCK, id))));
    var itemProperties =
        new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, id))
            .useBlockDescriptionPrefix();
    if (name.equals("station_core")) itemProperties.stacksTo(1);
    if (name.equals("computer_base"))
      itemProperties.component(
          DataComponents.LORE,
          new ItemLore(
              java.util.List.of(Component.translatable("tooltip.nullspace.computer_base"))));
    Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, itemProperties));
    return block;
  }

  private static Machine createMachine(String name, BlockBehaviour.Properties props) {
    return java.util.Set.of(
                "generator",
                "battery",
                "engine",
                "local_scanner",
                "long_range_scanner",
                "camera",
                "monitor",
                "air_supply",
                "weapon",
                "docking_base")
            .contains(name)
        ? new InventoryMachine(props, name)
        : new Machine(props);
  }

  private static Item component(String name) {
    var id = Identifier.parse("nullspace:" + name);
    var properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
    if (name.equals("ship_title")) properties.stacksTo(1);
    if (name.equals("system_upgrade"))
      properties.component(
          DataComponents.LORE,
          new ItemLore(
              java.util.List.of(
                  Component.literal("Install in Engine, Generator, Battery or Scanner"),
                  Component.literal("1 / 2 / 3 modules: Tier I / II / III"))));
    return Registry.register(BuiltInRegistries.ITEM, id, new Item(properties));
  }

  private static Item suit(String name, ArmorType type) {
    var id = Identifier.parse("nullspace:" + name);
    return Registry.register(
        BuiltInRegistries.ITEM,
        id,
        new Item(
            new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, id))
                .humanoidArmor(ArmorMaterials.IRON, type)));
  }

  public static void initialize() {}

  public static boolean navigation(net.minecraft.world.level.block.state.BlockState state) {
    return state.is(COMPUTER) || state.is(HELM);
  }

  public static String equipmentMode(BlockState state) {
    if (!(state.getBlock() instanceof Machine)) return "";
    var name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    return com.nullandkale.nullspace.terminal.EquipmentMenus.supports(name) ? name : "";
  }

  public static class Machine extends HorizontalDirectionalBlock {
    public Machine(Properties props) {
      super(props);
      registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
      return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
      if (state.is(LOCAL_SCANNER) || state.is(LONG_RANGE_SCANNER)) {
        if (player instanceof ServerPlayer sp) {
          try {
            com.nullandkale.nullspace.systems.ShipSystems.use(sp, pos);
          } catch (IllegalArgumentException blocked) {
            sp.sendSystemMessage(Component.literal(blocked.getMessage()));
          }
        }
      } else if (level.getBlockEntity(pos)
          instanceof com.nullandkale.nullspace.systems.MachineEntity) {
        if (player instanceof ServerPlayer sp)
          com.nullandkale.nullspace.systems.MachineMenus.open(sp, pos);
      } else if (state.is(STATION_CORE) || state.is(DOOR_CONNECTOR) || state.is(DOCKING_PORT)) {
        if (player instanceof ServerPlayer serverPlayer) {
          try {
            com.nullandkale.nullspace.station.StationService.open(serverPlayer, pos, "");
          } catch (IllegalArgumentException error) {
            serverPlayer.sendSystemMessage(Component.literal(error.getMessage()));
          }
        }
      } else if (state.is(SHIP_BEACON)) {
        if (player instanceof ServerPlayer serverPlayer)
          com.nullandkale.nullspace.shipyard.ShipYardService.open(serverPlayer, pos, "", "");
      } else if (state.is(TERMINAL)) {
        if (player instanceof ServerPlayer serverPlayer)
          com.nullandkale.nullspace.computer.ComputerService.open(serverPlayer, pos);
      } else if (state.is(COMPUTER_BASE)) {
        if (player instanceof ServerPlayer serverPlayer)
          serverPlayer.sendOverlayMessage(
              Component.translatable("tooltip.nullspace.computer_base"));
      } else if (navigation(state) && player instanceof ServerPlayer serverPlayer)
        ShipService.open(serverPlayer, pos, "Ready for inspection");
      else if (state.is(SYSTEMS_CONTROLLER) && player instanceof ServerPlayer serverPlayer) {
        try {
          com.nullandkale.nullspace.systems.ShipSystems.action(
              serverPlayer,
              pos,
              com.nullandkale.nullspace.terminal.ComputerTerminal.Action.SYSTEMS);
        } catch (IllegalArgumentException error) {
          com.nullandkale.nullspace.terminal.ComputerTerminal.send(
              serverPlayer, pos, error.getMessage(), "systems", "0");
        }
      } else if (player instanceof ServerPlayer serverPlayer) {
        try {
          com.nullandkale.nullspace.systems.ShipSystems.use(serverPlayer, pos);
        } catch (IllegalArgumentException error) {
          serverPlayer.sendSystemMessage(
              net.minecraft.network.chat.Component.literal(error.getMessage()));
        }
      }
      return InteractionResult.SUCCESS;
    }
  }

  public static final class InventoryMachine extends Machine implements EntityBlock {
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty
        LEGACY_HULL =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("legacy_hull");

    private final String kind;

    public InventoryMachine(Properties props, String kind) {
      super(props);
      this.kind = kind;
      registerDefaultState(defaultBlockState().setValue(LEGACY_HULL, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      super.createBlockStateDefinition(builder);
      builder.add(LEGACY_HULL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
      var state = super.getStateForPlacement(context);
      if ("camera".equals(kind) || "monitor".equals(kind)) {
        if (context.getClickedFace().getAxis().isVertical()) return null;
        return state.setValue(FACING, context.getClickedFace()).setValue(LEGACY_HULL, false);
      }
      return state;
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(
        BlockState state,
        net.minecraft.world.level.BlockGetter level,
        BlockPos at,
        net.minecraft.world.phys.shapes.CollisionContext context) {
      if (state.getValue(LEGACY_HULL) || !("camera".equals(kind) || "monitor".equals(kind)))
        return net.minecraft.world.phys.shapes.Shapes.block();
      double min = "camera".equals(kind) ? 5 : 0,
          max = "camera".equals(kind) ? 11 : 16,
          depth = "camera".equals(kind) ? 9 : 14;
      return switch (state.getValue(FACING)) {
        case NORTH -> Block.box(min, min, depth, max, max, 16);
        case SOUTH -> Block.box(min, min, 0, max, max, 16 - depth);
        case EAST -> Block.box(0, min, min, 16 - depth, max, max);
        default -> Block.box(depth, min, min, 16, max, max);
      };
    }

    @Override
    protected boolean canSurvive(
        BlockState state, net.minecraft.world.level.LevelReader level, BlockPos at) {
      if (("camera".equals(kind) || "monitor".equals(kind)) && !state.getValue(LEGACY_HULL)) {
        var back = at.relative(state.getValue(FACING).getOpposite());
        return level.getBlockState(back).isFaceSturdy(level, back, state.getValue(FACING));
      }
      return true;
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(
        BlockPos at, BlockState state) {
      return new com.nullandkale.nullspace.systems.MachineEntity(at, state);
    }
  }

  private ShipBlocks() {}
}
