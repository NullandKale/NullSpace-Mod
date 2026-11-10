package com.nullandkale.nullspace.worldgen.piece;

import com.nullandkale.nullspace.content.ModBlocks;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StructureMode;

/** G3: kits assemble deterministically, within bounds, without overlap, closed and complete. */
public final class AssemblerGameTest
{
    /** Builds test pieces in code: a box of stone bricks plus markers. */
    private static final class Draft
    {
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final Map<BlockPos, CompoundTag> entities = new LinkedHashMap<>();
        final Vec3i size;

        Draft(int x, int y, int z)
        {
            size = new Vec3i(x, y, z);
            for (int i = 0; i < x; i++)
                for (int j = 0; j < y; j++)
                    for (int k = 0; k < z; k++)
                        blocks.put(new BlockPos(i, j, k), Blocks.STONE_BRICKS.defaultBlockState());
        }

        Draft put(int x, int y, int z, Block block)
        {
            blocks.put(new BlockPos(x, y, z), block.defaultBlockState());
            return this;
        }

        Draft connector(int x, int y, int z, Direction front, String type, String fill)
        {
            return connector(x, y, z, front, type, "", fill);
        }

        Draft connector(int x, int y, int z, Direction front, String type, String target, String fill)
        {
            var at = new BlockPos(x, y, z);
            blocks.put(at, Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION, FrontAndTop.fromFrontAndTop(front, front.getAxis().isVertical() ? Direction.NORTH : Direction.UP)));
            var nbt = new CompoundTag();
            nbt.putString("name", "minecraft:" + type);
            if (!target.isEmpty())
                nbt.putString("target", "minecraft:" + target);
            nbt.putString("final_state", fill);
            entities.put(at, nbt);
            return this;
        }

        Draft point(int x, int y, int z, String metadata)
        {
            var at = new BlockPos(x, y, z);
            blocks.put(at, Blocks.STRUCTURE_BLOCK.defaultBlockState());
            var nbt = new CompoundTag();
            nbt.putString("mode", StructureMode.DATA.name());
            nbt.putString("metadata", metadata);
            entities.put(at, nbt);
            return this;
        }

        Piece piece(String id)
        {
            return Piece.of(id, new TemplateCodec.Template(size, blocks, entities));
        }
    }

    public static Kit kit(boolean engines)
    {
        var entries = new ArrayList<Kit.Entry>();
        entries.add(new Kit.Entry(new Draft(5, 4, 4).put(2, 1, 1, ModBlocks.COMPUTER).connector(2, 1, 3, Direction.SOUTH, "spine", "minecraft:air").piece("test/nose"), "nose", 1));
        entries.add(new Kit.Entry(new Draft(5, 4, 5).put(1, 1, 2, ModBlocks.GENERATOR).put(3, 1, 2, ModBlocks.AIR_SUPPLY).put(2, 2, 1, Blocks.SEA_LANTERN).put(2, 2, 3, Blocks.SEA_LANTERN).point(2, 2, 2, "loot:cargo").connector(2, 1, 0, Direction.NORTH, "spine", "minecraft:air").connector(2, 1, 4, Direction.SOUTH, "spine", "minecraft:air").connector(4, 1, 2, Direction.EAST, "hull_side", "wing_root", "minecraft:air").connector(0, 1, 2, Direction.WEST, "hull_side", "wing_root", "minecraft:air").piece("test/hull"), "hull", 2));
        entries.add(new Kit.Entry(new Draft(5, 4, 6).put(2, 1, 3, ModBlocks.GENERATOR).connector(2, 1, 0, Direction.NORTH, "spine", "minecraft:air").connector(2, 1, 5, Direction.SOUTH, "spine", "minecraft:air").piece("test/long_hull"), "hull", 1));
        if (engines)
            entries.add(new Kit.Entry(new Draft(5, 4, 3).put(2, 1, 1, ModBlocks.ENGINE).connector(2, 1, 0, Direction.NORTH, "spine", "minecraft:air").piece("test/engine"), "engine", 1));
        entries.add(new Kit.Entry(new Draft(4, 1, 3).connector(0, 0, 1, Direction.WEST, "wing_root", "hull_side", "minecraft:air").piece("test/wing"), "wing", 1));
        entries.add(new Kit.Entry(new Draft(1, 1, 1).connector(0, 0, 0, Direction.WEST, "wing_root", "hull_side", "minecraft:glass").piece("test/side_cap"), "cap", 1));
        entries.add(new Kit.Entry(new Draft(1, 1, 1).connector(0, 0, 0, Direction.NORTH, "spine", "minecraft:glass").piece("test/spine_cap"), "cap", 1));
        return new Kit("test", "Test Works", entries, Map.of(Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE));
    }

    public static Archetype scout(Vec3i max)
    {
        return new Archetype("test_scout", Archetype.Kind.SHIP, "nose", List.of(new Archetype.Count("hull", 2, 3), new Archetype.Count("engine", 1, 1), new Archetype.Count("wing", 0, 2)), "cap", max);
    }

    @GameTest
    public void kitsAssembleCompleteClosedShipsDeterministically(GameTestHelper ctx)
    {
        var kit = kit(true);
        var archetype = scout(new Vec3i(32, 32, 32));
        var shapes = new HashSet<Map<BlockPos, BlockState>>();
        for (long seed = 0; seed < 12; seed++)
        {
            var build = Assembler.assemble(kit, archetype, seed);
            ctx.assertTrue(build.problems().isEmpty(), "Seed " + seed + " builds a complete ship: " + build.problems());
            ctx.assertTrue(build.open().isEmpty(), "Every connector is joined or capped");
            var roles = new HashMap<String, Integer>();
            build.placed().forEach(p -> roles.merge(p.role(), 1, Integer::sum));
            ctx.assertTrue(roles.get("nose") == 1 && roles.get("engine") == 1 && roles.get("hull") >= 2 && roles.get("hull") <= 3 && roles.getOrDefault("wing", 0) <= 2, "Role counts stay inside the archetype: " + roles);
            int cells = 0;
            for (var placed : build.placed())
                cells += kit.pieces().stream().filter(e -> e.piece().id().equals(placed.piece())).findFirst().orElseThrow().piece().blocks().size();
            ctx.assertTrue(cells == build.blocks().size(), "No two pieces share a block: " + cells + " piece blocks, " + build.blocks().size() + " placed");
            ctx.assertTrue(build.blocks().keySet().stream().allMatch(p -> p.getX() >= 0 && p.getY() >= 0 && p.getZ() >= 0), "The build starts at the origin");
            ctx.assertTrue(build.blocks().values().stream().noneMatch(s -> s.is(Blocks.STONE_BRICKS) || s.is(Blocks.JIGSAW) || s.is(Blocks.STRUCTURE_BLOCK)), "Kit materials are swapped and markers removed");
            ctx.assertTrue(build.points("loot").size() == roles.get("hull") - (int) build.placed().stream().filter(p -> p.piece().equals("test/long_hull")).count(), "Each standard hull brings its loot point");
            ctx.assertTrue(Assembler.assemble(kit, archetype, seed).blocks().equals(build.blocks()), "The same seed gives the same build");
            shapes.add(build.blocks());
        }
        ctx.assertTrue(shapes.size() >= 3, "Different seeds give different builds: " + shapes.size());
        ctx.succeed();
    }

    @GameTest
    public void assemblyReportsWhatItCannotBuild(GameTestHelper ctx)
    {
        var cramped = Assembler.assemble(kit(true), scout(new Vec3i(32, 32, 12)), 1);
        ctx.assertTrue(cramped.problems().stream().anyMatch(p -> p.contains("hull")), "A size limit too small for the minimum hulls is reported: " + cramped.problems() + " placed " + cramped.placed() + " size " + cramped.size());
        var size = cramped.size();
        ctx.assertTrue(size.getZ() <= 12, "Nothing is placed past the limit: " + size);
        var engineless = Assembler.assemble(kit(false), scout(new Vec3i(32, 32, 32)), 1);
        ctx.assertTrue(engineless.problems().stream().anyMatch(p -> p.contains("engine")), "A missing role is reported: " + engineless.problems());
        ctx.assertTrue(engineless.problems().stream().anyMatch(p -> p.contains(ModBlocks.ENGINE.getName().getString())), "A ship without an engine block is reported: " + engineless.problems());
        ctx.succeed();
    }
}
