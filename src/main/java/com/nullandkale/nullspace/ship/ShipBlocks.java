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
  public static final Block DOCKING_BASE = machine("docking_base");
  public static final Item SYSTEM_UPGRADE = component("system_upgrade");
  /** Needs amethyst: lets a machine reach upgrade tier 2. */
  public static final Item ADVANCED_UPGRADE = component("advanced_system_upgrade");
  /** Needs netherite scrap or an echo shard: lets a machine reach upgrade tier 3. */
  public static final Item EXOTIC_UPGRADE = component("exotic_system_upgrade");

  /** 0 for anything that is not an upgrade; 1 plain, 2 advanced, 3 exotic. */
  public static int upgradeRank(net.minecraft.world.item.ItemStack stack) {
    return stack.is(EXOTIC_UPGRADE)
        ? 3
        : stack.is(ADVANCED_UPGRADE) ? 2 : stack.is(SYSTEM_UPGRADE) ? 1 : 0;
  }
  public static final Item STATION_MODULE = component("station_module");
  public static final Block TERMINAL = machine("computer_terminal");
  public static final Block LOCAL_SCANNER = machine("local_scanner");
  public static final Block COMPUTER_BASE = machine("computer_base");
  public static final Item MACHINE_CASING = component("machine_casing");
  public static final Item CIRCUIT_BOARD = component("circuit_board");
  public static final Item SHIP_TITLE = component("ship_title");
  /** Breathable air for a suited player away from ship air; durability is the air left. */
  public static final Item AIR_CANISTER = component("air_canister");
  public static final Block COMPUTER = machine("ship_computer");
  public static final Block ENGINE = machine("engine");
  public static final Block GENERATOR = machine("generator");
  public static final Block BATTERY = machine("battery");
  public static final Block AIR_SUPPLY = machine("air_supply");
  public static final Block CAMERA = machine("camera");
  public static final Block MONITOR = machine("monitor");
  public static final Block WEAPON = machine("weapon");
  /**
   * The starting space suit: vacuum and air protection with almost no armor, so new players do
   * not begin in the equivalent of iron armor. White with orange trim and a tinted visor.
   */
  private static net.minecraft.resources.ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset>
      suitLook(String name) {
    return net.minecraft.resources.ResourceKey.create(
        net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID,
        Identifier.parse("nullspace:" + name));
  }

  public static final net.minecraft.world.item.equipment.ArmorMaterial PLAIN_SUIT =
      new net.minecraft.world.item.equipment.ArmorMaterial(
          15,
          java.util.Map.of(
              ArmorType.BOOTS, 1, ArmorType.LEGGINGS, 1, ArmorType.CHESTPLATE, 2,
              ArmorType.HELMET, 1, ArmorType.BODY, 1),
          9,
          net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_IRON,
          0,
          0,
          net.minecraft.tags.ItemTags.REPAIRS_IRON_ARMOR,
          suitLook("space_suit"));

  /** The crafted armored suit: iron-level protection with a little toughness, gunmetal look. */
  public static final net.minecraft.world.item.equipment.ArmorMaterial ARMORED_SUIT =
      new net.minecraft.world.item.equipment.ArmorMaterial(
          25,
          java.util.Map.of(
              ArmorType.BOOTS, 2, ArmorType.LEGGINGS, 5, ArmorType.CHESTPLATE, 6,
              ArmorType.HELMET, 2, ArmorType.BODY, 5),
          9,
          net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_NETHERITE,
          1,
          0,
          net.minecraft.tags.ItemTags.REPAIRS_IRON_ARMOR,
          suitLook("armored_space_suit"));

  public static final Item HELMET = suit("space_helmet", ArmorType.HELMET, PLAIN_SUIT);
  public static final Item CHEST = suit("space_chestplate", ArmorType.CHESTPLATE, PLAIN_SUIT);
  public static final Item LEGS = suit("space_leggings", ArmorType.LEGGINGS, PLAIN_SUIT);
  public static final Item BOOTS = suit("space_boots", ArmorType.BOOTS, PLAIN_SUIT);
  public static final Item ARMORED_HELMET =
      suit("armored_space_helmet", ArmorType.HELMET, ARMORED_SUIT);
  public static final Item ARMORED_CHEST =
      suit("armored_space_chestplate", ArmorType.CHESTPLATE, ARMORED_SUIT);
  public static final Item ARMORED_LEGS =
      suit("armored_space_leggings", ArmorType.LEGGINGS, ARMORED_SUIT);
  public static final Item ARMORED_BOOTS =
      suit("armored_space_boots", ArmorType.BOOTS, ARMORED_SUIT);

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
    if (name.equals("air_canister"))
      properties
          .durability(com.nullandkale.nullspace.ship.SpaceSurvival.CANISTER_TICKS)
          .component(
              DataComponents.LORE,
              new ItemLore(
                  java.util.List.of(
                      Component.literal("Suit air away from the ship: 2 minutes when full"),
                      Component.literal("Refill: use on a powered Air Supply"))));
    if (name.equals("system_upgrade"))
      properties.component(
          DataComponents.LORE,
          new ItemLore(
              java.util.List.of(
                  Component.literal("Install in Engine, Generator, Battery or Scanner"),
                  Component.literal("1 / 2 / 3 modules: Tier I / II / III"))));
    return Registry.register(BuiltInRegistries.ITEM, id, new Item(properties));
  }

  private static Item suit(
      String name, ArmorType type, net.minecraft.world.item.equipment.ArmorMaterial material) {
    var id = Identifier.parse("nullspace:" + name);
    return Registry.register(
        BuiltInRegistries.ITEM,
        id,
        new Item(
            new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, id))
                .humanoidArmor(material, type)));
  }

  public static void initialize() {}

  public static boolean navigation(net.minecraft.world.level.block.state.BlockState state) {
    return state.is(COMPUTER);
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
      if (state.is(LOCAL_SCANNER)) {
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
      } else if (state.is(STATION_CORE)) {
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
      else if (player instanceof ServerPlayer serverPlayer) {
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
    private final String kind;

    public InventoryMachine(Properties props, String kind) {
      super(props);
      this.kind = kind;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
      var state = super.getStateForPlacement(context);
      if ("camera".equals(kind) || "monitor".equals(kind)) {
        if (context.getClickedFace().getAxis().isVertical()) return null;
        return state.setValue(FACING, context.getClickedFace());
      }
      return state;
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(
        BlockState state,
        net.minecraft.world.level.BlockGetter level,
        BlockPos at,
        net.minecraft.world.phys.shapes.CollisionContext context) {
      if (!("camera".equals(kind) || "monitor".equals(kind)))
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
      if ("camera".equals(kind) || "monitor".equals(kind)) {
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
