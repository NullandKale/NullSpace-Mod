package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.mixin.ShipBlockEventsAccessor;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.BuildPreviews;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.piston.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.*;

public final class BlockSupportGameTest {
  private static final int FLAGS = BlockSupport.TRANSFER_FLAGS;

  private static CompoundTag data(ServerLevel level, BlockPos p) {
    return level.getBlockEntity(p).saveWithoutMetadata(level.registryAccess());
  }

  private static void load(ServerLevel level, BlockPos p, CompoundTag data) {
    level
        .getBlockEntity(p)
        .loadWithComponents(
            TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
  }

  private static ItemStack book(String name) {
    var book = new ItemStack(Items.WRITTEN_BOOK);
    book.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    book.set(
        DataComponents.WRITTEN_BOOK_CONTENT,
        new net.minecraft.world.item.component.WrittenBookContent(
            net.minecraft.server.network.Filterable.passThrough("Ship log"),
            "Crew",
            0,
            List.of(
                net.minecraft.server.network.Filterable.passThrough(
                    Component.literal("Engineering manifest")),
                net.minecraft.server.network.Filterable.passThrough(
                    Component.literal("Cargo and navigation"))),
            true));
    return book;
  }

  private static void seed(BlockEntity be) {
    be.setComponents(
        DataComponentMap.builder()
            .set(
                DataComponents.CUSTOM_NAME,
                Component.literal("Transit fixture " + be.typeHolder().getRegisteredName()))
            .build());
    if (be instanceof Container container)
      for (int slot = 0; slot < container.getContainerSize(); slot++) {
        var item = book("Manifest " + slot);
        container.setItem(slot, item);
      }
    if (be instanceof LecternBlockEntity lectern) {
      lectern.setBook(book("Ship log"));
    }
    if (be instanceof JukeboxBlockEntity jukebox)
      jukebox.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
    if (be instanceof BeehiveBlockEntity hive)
      hive.storeBee(BeehiveBlockEntity.Occupant.create(23));
  }

  @GameTest
  public void everyPortableBlockEntityVariantPreservesNbtWithoutDrops(GameTestHelper ctx)
      throws java.io.IOException {
    var level = ctx.getLevel();
    var source = ctx.absolutePos(new BlockPos(41000, 70, 41000));
    var target = source.offset(32, 0, 0);
    var types = new HashSet<String>();
    int variants = 0;
    for (var center : List.of(source, target))
      for (var d : Direction.values())
        level.setBlock(center.relative(d), Blocks.STONE.defaultBlockState(), FLAGS);
    for (var block : BuiltInRegistries.BLOCK) {
      var state = block.defaultBlockState();
      if (state.hasProperty(BlockStateProperties.WATERLOGGED))
        state = state.setValue(BlockStateProperties.WATERLOGGED, false);
      if (!(block instanceof EntityBlock factory) || !state.hasBlockEntity()) continue;
      var probe = factory.newBlockEntity(source, state);
      if (probe == null
          || !BlockSupport.portable(probe.getType())
          || !BlockSupport.stateRestriction(state).isEmpty()) continue;
      level.setBlock(source, state, FLAGS);
      var be = level.getBlockEntity(source);
      seed(be);
      BlockSupport.validate(level, source);
      var expected = data(level, source);
      var expectedState = level.getBlockState(source);
      var template = new StructureTemplate();
      template.fillFromWorld(level, source, new Vec3i(1, 1, 1), false, List.of());
      TravelService.place(level, template, target);
      ctx.assertTrue(
          level.getBlockState(target).equals(expectedState),
          "Block state changed: " + BuiltInRegistries.BLOCK.getKey(block));
      ctx.assertTrue(
          expected.equals(data(level, target)),
          "Block entity NBT changed: "
              + BuiltInRegistries.BLOCK.getKey(block)
              + " expected="
              + expected
              + " actual="
              + data(level, target));
      TravelService.clear(level, source, source);
      ctx.assertTrue(level.getBlockState(source).isAir(), "Source machine must be removed");
      ctx.assertTrue(
          expected.equals(data(level, target)),
          "Clearing source cannot mutate destination inventory");
      TravelService.clear(level, target, target);
      types.add(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).getPath());
      variants++;
    }
    ctx.assertTrue(
        types.equals(
            BuiltInRegistries.BLOCK_ENTITY_TYPE.stream()
                .filter(BlockSupport::portable)
                .map(t -> BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(t).getPath())
                .collect(java.util.stream.Collectors.toSet())),
        "Every portable BE type must have a tested representative: " + types);
    ctx.assertTrue(
        level
            .getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                net.minecraft.world.phys.AABB.encapsulatingFullBlocks(
                    source.offset(-2, -2, -2), target.offset(3, 3, 3)))
            .isEmpty(),
        "Snapshot placement/removal cannot spill inventories, books, pots or music discs");
    var rows = BlockSupport.catalog();
    ctx.assertTrue(
        rows.size()
            == BuiltInRegistries.BLOCK.stream()
                .filter(b -> BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("minecraft"))
                .count(),
        "Catalog must cover every vanilla block");
    var report =
        level.getServer().getWorldPath(LevelResource.ROOT).resolve("ship-block-support.json");
    java.nio.file.Files.writeString(
        report, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(rows));
    NullSpace.LOGGER.info(
        "Ship block catalog: {}; {} portable variants, {} block entity types",
        report,
        variants,
        types.size());
    ctx.succeed();
  }

  @GameTest
  public void unopenedLootSeedAndContentsAreNotRerolled(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var from = ctx.absolutePos(new BlockPos(42000, 70, 42000));
    var to = from.east(32);
    level.setBlock(from, Blocks.CHEST.defaultBlockState(), FLAGS);
    var nbt = data(level, from);
    nbt.putString("LootTable", "minecraft:chests/simple_dungeon");
    nbt.putLong("LootTableSeed", 987654321L);
    load(level, from, nbt);
    var expected = data(level, from);
    var template = new StructureTemplate();
    template.fillFromWorld(level, from, new Vec3i(1, 1, 1), false, List.of());
    TravelService.place(level, template, to);
    ctx.assertTrue(
        expected.equals(data(level, to)),
        "Vanilla template placement must not randomize an unopened ship container's loot seed");
    TravelService.clear(level, from, from);
    TravelService.clear(level, to, to);
    ctx.succeed();
  }

  @GameTest
  public void transferDoesNotIgniteTntOrTriggerPoweredPistons(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var from = ctx.absolutePos(new BlockPos(42500, 70, 42500));
    var to = from.east(32);
    var states =
        List.of(
            Blocks.REDSTONE_BLOCK.defaultBlockState(),
            Blocks.TNT.defaultBlockState(),
            Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.SOUTH));
    for (int i = 0; i < states.size(); i++) level.setBlock(from.east(i), states.get(i), FLAGS);
    var template = new StructureTemplate();
    template.fillFromWorld(level, from, new Vec3i(3, 1, 1), false, List.of());
    TravelService.place(level, template, to);
    for (int i = 0; i < states.size(); i++)
      ctx.assertTrue(
          level.getBlockState(to.east(i)).equals(states.get(i)),
          "Snapshot placement must preserve stable powered block state");
    ctx.assertTrue(
        level
            .getEntitiesOfClass(
                net.minecraft.world.entity.item.PrimedTnt.class,
                net.minecraft.world.phys.AABB.encapsulatingFullBlocks(
                    to.offset(-1, -1, -1), to.offset(4, 2, 2)))
            .isEmpty(),
        "Transport is not a fresh TNT placement/ignition");
    ctx.assertTrue(
        ((ShipBlockEventsAccessor) level)
            .nullspace$blockEvents().stream().noneMatch(e -> e.pos().equals(to.east(2))),
        "Transport cannot queue a new piston action");
    ctx.succeed();
  }

  @GameTest
  public void settledRedstonePistonsSoilAndFallingBlocksPassButActiveStatesFail(
      GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(43000, 70, 43000));
    var helm = ShipTestFixture.build(level, base);
    var pad =
        new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
    var at = base.offset(2, 3, 3);
    for (var block :
        List.of(
            Blocks.REDSTONE_WIRE,
            Blocks.REPEATER,
            Blocks.COMPARATOR,
            Blocks.OBSERVER,
            Blocks.PISTON,
            Blocks.STICKY_PISTON,
            Blocks.SAND,
            Blocks.GRAVEL,
            Blocks.ANVIL,
            Blocks.TNT,
            Blocks.DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.FARMLAND,
            Blocks.ENCHANTING_TABLE,
            Blocks.BREWING_STAND)) {
      level.setBlock(at, block.defaultBlockState(), FLAGS);
      ctx.assertTrue(
          HullScan.inspect(level, helm, pad, true).blocks().contains(at),
          "Stable connected block must travel: " + block);
      ctx.assertTrue(
          BuildPreviews.shipPoints(level, pad).contains(at),
          "Preview must include soil and new hardware");
    }
    level.setBlock(
        at,
        Blocks.PISTON
            .defaultBlockState()
            .setValue(PistonBaseBlock.FACING, Direction.NORTH)
            .setValue(PistonBaseBlock.EXTENDED, true),
        FLAGS);
    level.setBlock(
        at.north(),
        Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.NORTH),
        FLAGS);
    HullScan.inspect(level, helm, pad, true);
    level.setBlock(at.north(), Blocks.AIR.defaultBlockState(), FLAGS);
    expect(ctx, () -> HullScan.inspect(level, helm, pad, true), "matching stationary head");
    level.setBlock(at, Blocks.REPEATER.defaultBlockState(), FLAGS);
    level.scheduleTick(at, Blocks.REPEATER, 20);
    BlockSupport.validate(level, at); // Pending block actions travel with the ship.
    level
        .getBlockTicks()
        .clearArea(net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(at, at));
    level.setBlock(at, Blocks.PISTON.defaultBlockState(), FLAGS);
    level.blockEvent(at, Blocks.PISTON, 0, Direction.NORTH.get3DDataValue());
    expect(ctx, () -> HullScan.inspect(level, helm, pad, true), "queued action");
    level.clearBlockEvents(
        net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(at, at));
    level.setBlock(
        at, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true), FLAGS);
    BlockSupport.validate(level, at); // Waterlogged blocks travel.
    level.setBlock(at, Blocks.FIRE.defaultBlockState(), FLAGS);
    BlockSupport.validate(level, at); // Fire travels.
    level.setBlock(at, Blocks.SAND.defaultBlockState(), FLAGS);
    level.setBlock(at.below(), Blocks.AIR.defaultBlockState(), FLAGS);
    expect(ctx, () -> BlockSupport.validate(level, at), "Support this falling block");
    ctx.succeed();
  }

  @GameTest
  public void hiveFlowerReferencesMoveOnlyWithCapturedFlowers(GameTestHelper ctx) {
    var hive = new CompoundTag();
    hive.putString("id", "minecraft:beehive");
    var flower = new BlockPos(10, 20, 30);
    hive.store("flower_pos", BlockPos.CODEC, flower);
    var occupant = new CompoundTag();
    var bee = new CompoundTag();
    bee.putString("id", "minecraft:bee");
    bee.store("flower_pos", BlockPos.CODEC, flower);
    occupant.put("entity_data", bee);
    occupant.putInt("ticks_in_hive", 23);
    var bees = new ListTag();
    bees.add(occupant);
    hive.put("bees", bees);
    var block = new CompoundTag();
    block.put("nbt", hive);
    var blocks = new ListTag();
    blocks.add(block);
    var template = new CompoundTag();
    template.put("blocks", blocks);
    var delta = new BlockPos(4000, 70, 0);
    var moved =
        BlockSupport.destinationTemplate(template, Set.of(flower), delta)
            .getListOrEmpty("blocks")
            .getCompoundOrEmpty(0)
            .getCompoundOrEmpty("nbt");
    ctx.assertTrue(
        moved.read("flower_pos", BlockPos.CODEC).orElseThrow().equals(flower.offset(delta)),
        "A captured flower memory must translate");
    ctx.assertTrue(
        moved
            .getListOrEmpty("bees")
            .getCompoundOrEmpty(0)
            .getCompoundOrEmpty("entity_data")
            .read("flower_pos", BlockPos.CODEC)
            .orElseThrow()
            .equals(flower.offset(delta)),
        "Stored bee flower memory must translate");
    var external =
        BlockSupport.destinationTemplate(template, Set.of(), delta)
            .getListOrEmpty("blocks")
            .getCompoundOrEmpty(0)
            .getCompoundOrEmpty("nbt");
    ctx.assertTrue(
        !external.contains("flower_pos")
            && !external
                .getListOrEmpty("bees")
                .getCompoundOrEmpty(0)
                .getCompoundOrEmpty("entity_data")
                .contains("flower_pos"),
        "Planetary flowers must not remain remote targets");
    ctx.assertTrue(
        hive.read("flower_pos", BlockPos.CODEC).orElseThrow().equals(flower)
            && hive.getListOrEmpty("bees").getCompoundOrEmpty(0).getIntOr("ticks_in_hive", 0) == 23,
        "Journal source and stored bees must remain unchanged");
    ctx.succeed();
  }

  @GameTest(maxTicks = 200)
  public void workshopCargoAndStoredBeesSurviveJournalRecoveryAndTravel(GameTestHelper ctx)
      throws java.io.IOException {
    var level = ctx.getLevel();
    var server = level.getServer();
    var base = ctx.absolutePos(new BlockPos(44000, 70, 44000));
    var helm = ShipTestFixture.build(level, base);
    var pad =
        new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
    String name = "Workshop " + UUID.randomUUID().toString().substring(0, 8);
    PadComputers.register(level, base.offset(-1, 1, 3), pad, name);
    var machines =
        List.of(
            Blocks.HOPPER,
            Blocks.DISPENSER,
            Blocks.DROPPER,
            Blocks.BREWING_STAND,
            Blocks.CRAFTER,
            Blocks.LECTERN,
            Blocks.DECORATED_POT,
            Blocks.CHISELED_BOOKSHELF,
            Blocks.JUKEBOX,
            Blocks.ENCHANTING_TABLE,
            Blocks.BEEHIVE);
    int[][] offsets = {
      {1, 3}, {1, 4}, {1, 5}, {2, 5}, {3, 5}, {4, 5}, {5, 5}, {1, 1}, {2, 1}, {4, 1}, {5, 1}
    };
    var positions = new ArrayList<BlockPos>();
    for (int i = 0; i < machines.size(); i++) {
      var at = base.offset(offsets[i][0], 3, offsets[i][1]);
      positions.add(at);
      level.setBlock(at, machines.get(i).defaultBlockState(), FLAGS);
      seed(level.getBlockEntity(at));
    }
    var hive = positions.getLast();
    var flower = base.offset(2, 4, 1);
    level.setBlock(flower, Blocks.DANDELION.defaultBlockState(), FLAGS);
    var hiveData = data(level, hive);
    hiveData.store("flower_pos", BlockPos.CODEC, flower);
    hiveData
        .getListOrEmpty("bees")
        .getCompoundOrEmpty(0)
        .getCompoundOrEmpty("entity_data")
        .store("flower_pos", BlockPos.CODEC, flower);
    load(level, hive, hiveData);
    for (int x = base.getX() >> 4; x <= (base.getX() + 7) >> 4; x++)
      for (int z = base.getZ() >> 4; z <= (base.getZ() + 7) >> 4; z++)
        level.setChunkForced(x, z, true);
    int slot = 0;
    while (!TravelService.spaceSlotFree(server, slot)) slot++;
    server
        .getLevel(TravelService.SPACE)
        .setChunkForced((slot * TravelService.SPACE_SPACING) >> 4, 0, true);
    var player = ctx.makeMockServerPlayerInLevel();
    player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
    ctx.runAfterDelay(
        10,
        () -> {
          var previous = TravelService.failureInjector;
          try {
            var expected = positions.stream().map(at -> data(level, at)).toList();
            for (String stage :
                List.of("PREPARED", "DESTINATION_STAGED", "SOURCE_CLEARED", "PASSENGERS_MOVED")) {
              TravelService.failureInjector =
                  point -> {
                    if (point.equals(stage)) throw new TravelService.SimulatedCrash();
                  };
              boolean stopped = false;
              try {
                TravelService.transfer(player, helm, "", ShipService.preflight(level, helm));
              } catch (TravelService.SimulatedCrash crash) {
                stopped = true;
              } finally {
                TravelService.failureInjector = previous;
              }
              ctx.assertTrue(stopped, "Workshop must reach injection stage " + stage);
              TravelService.recover(server);
              TravelService.recover(server);
              for (int i = 0; i < positions.size(); i++)
                ctx.assertTrue(
                    expected.get(i).equals(data(level, positions.get(i))),
                    "Workshop state changed after recovery " + stage + ": " + machines.get(i));
            }
            TravelService.transfer(player, helm, "", ShipService.preflight(level, helm));
            var space = player.level();
            var vessel = WorldStore.get(server).at(space, player.blockPosition());
            var delta = WorldStore.pos(vessel, "base").subtract(base.above());
            for (int i = 0; i < positions.size(); i++) {
              var expectedMoved = expected.get(i).copy();
              if (i == positions.size() - 1) {
                expectedMoved.store("flower_pos", BlockPos.CODEC, flower.offset(delta));
                expectedMoved
                    .getListOrEmpty("bees")
                    .getCompoundOrEmpty(0)
                    .getCompoundOrEmpty("entity_data")
                    .store("flower_pos", BlockPos.CODEC, flower.offset(delta));
              }
              ctx.assertTrue(
                  expectedMoved.equals(data(space, positions.get(i).offset(delta))),
                  "Workshop state changed on launch: " + machines.get(i));
            }
            ctx.assertTrue(
                ((BeehiveBlockEntity) space.getBlockEntity(hive.offset(delta))).getOccupantCount()
                    == 1,
                "Stored bee cannot be released or duplicated by transfer");
            TravelService.transfer(
                player, helm.offset(delta), name, ShipService.preflight(space, helm.offset(delta)));
            for (int i = 0; i < positions.size(); i++)
              ctx.assertTrue(
                  expected.get(i).equals(data(level, positions.get(i))),
                  "Workshop state changed after landing: " + machines.get(i));
            ctx.succeed();
          } finally {
            TravelService.failureInjector = previous;
            server.getPlayerList().remove(player);
          }
        });
  }

  private static void expect(GameTestHelper ctx, Runnable action, String message) {
    boolean failed = false;
    try {
      action.run();
    } catch (HullScan.Failure error) {
      failed = error.getMessage().contains(message) && error.getMessage().contains("Location:");
    }
    ctx.assertTrue(failed, "Expected actionable rejection: " + message);
  }
}
