package com.nullandkale.nullspace.worldgen.piece;

import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.io.*;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StructureMode;

/** G1: templates round-trip, markers become connectors and points, and pieces rotate. */
public final class PieceGameTest
{
    private static BlockState jigsaw(Direction front)
    {
        var top = front.getAxis().isVertical() ? Direction.NORTH : Direction.UP;
        return Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION, FrontAndTop.fromFrontAndTop(front, top));
    }

    /** A 5 x 3 x 3 stone corridor with a spine connector at each end and two data points. */
    private static TemplateCodec.Template corridor()
    {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        var entities = new LinkedHashMap<BlockPos, CompoundTag>();
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 3; y++)
                for (int z = 0; z < 3; z++)
                    blocks.put(new BlockPos(x, y, z), y == 1 && z == 1 ? Blocks.AIR.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
        blocks.put(new BlockPos(2, 1, 0), jigsaw(Direction.NORTH));
        var north = new CompoundTag();
        north.putString("name", "minecraft:spine");
        north.putString("final_state", "minecraft:air");
        entities.put(new BlockPos(2, 1, 0), north);
        blocks.put(new BlockPos(2, 1, 2), jigsaw(Direction.SOUTH));
        var south = new CompoundTag();
        south.putString("name", "minecraft:spine");
        south.putString("final_state", "minecraft:glass");
        entities.put(new BlockPos(2, 1, 2), south);
        blocks.put(new BlockPos(1, 1, 1), Blocks.STRUCTURE_BLOCK.defaultBlockState());
        var loot = new CompoundTag();
        loot.putString("mode", StructureMode.DATA.name());
        loot.putString("metadata", "loot:cargo");
        entities.put(new BlockPos(1, 1, 1), loot);
        blocks.put(new BlockPos(3, 1, 1), Blocks.STRUCTURE_BLOCK.defaultBlockState());
        var core = new CompoundTag();
        core.putString("mode", StructureMode.DATA.name());
        core.putString("metadata", "core");
        entities.put(new BlockPos(3, 1, 1), core);
        return new TemplateCodec.Template(new Vec3i(5, 3, 3), blocks, entities);
    }

    @GameTest
    public void templatesRoundTripThroughVanillaStructureNbt(GameTestHelper ctx) throws IOException
    {
        var ship = TemplateCodec.Template.of(ShipBlueprints.blocks(ShipBlueprints.get("halcyon_horizon")));
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(TemplateCodec.write(ship), bytes);
        var back = TemplateCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
        ctx.assertTrue(back.size().equals(ship.size()), "Size survives: " + back.size() + " vs " + ship.size());
        ctx.assertTrue(back.blocks().equals(ship.blocks()), "Every block state survives the round trip");
        var marked = corridor();
        var again = TemplateCodec.read(TemplateCodec.write(marked));
        ctx.assertTrue(again.blocks().equals(marked.blocks()), "Air entries and markers survive");
        ctx.assertTrue(again.blockEntities().equals(marked.blockEntities()), "Block entity data survives");
        ctx.succeed();
    }

    @GameTest
    public void markersBecomeConnectorsAndPointsAndLeaveTheBlocks(GameTestHelper ctx)
    {
        var piece = Piece.of("test/corridor", corridor());
        ctx.assertTrue(piece.connectors().size() == 2, "Two connectors: " + piece.connectors());
        var north = piece.connectors().stream().filter(c -> c.facing() == Direction.NORTH).findFirst().orElseThrow();
        ctx.assertTrue(north.type().equals("spine"), "The namespace is dropped from the connector type: " + north.type());
        ctx.assertTrue(north.fill().isAir(), "A connector with no final state becomes air, as in vanilla: " + north.fill());
        var south = piece.connectors().stream().filter(c -> c.facing() == Direction.SOUTH).findFirst().orElseThrow();
        ctx.assertTrue(south.fill().is(Blocks.GLASS), "An explicit final state is kept");
        ctx.assertTrue(piece.blocks().get(new BlockPos(2, 1, 0)).isAir() && piece.blocks().get(new BlockPos(2, 1, 2)).is(Blocks.GLASS), "Connectors are replaced by their fill");
        ctx.assertTrue(piece.points("loot").size() == 1 && piece.points("loot").getFirst().argument().equals("cargo"), "loot:cargo is a loot point");
        ctx.assertTrue(piece.points("core").size() == 1 && piece.points("core").getFirst().pos().equals(new BlockPos(3, 1, 1)), "core is a point");
        ctx.assertTrue(piece.blocks().values().stream().noneMatch(s -> s.is(Blocks.JIGSAW) || s.is(Blocks.STRUCTURE_BLOCK)), "No marker block is left");
        ctx.succeed();
    }

    @GameTest
    public void piecesRotateWithTheirStatesAndMarkers(GameTestHelper ctx)
    {
        var piece = Piece.of("test/corridor", corridor());
        var turned = piece.rotate(Rotation.CLOCKWISE_90);
        ctx.assertTrue(turned.size().equals(new Vec3i(3, 3, 5)), "Width and depth swap: " + turned.size());
        var connector = turned.connectors().stream().filter(c -> c.fill().isAir()).findFirst().orElseThrow();
        ctx.assertTrue(connector.facing() == Direction.EAST, "North turns east: " + connector.facing());
        ctx.assertTrue(connector.pos().equals(new BlockPos(2, 1, 2)), "The north connector moves to the east side: " + connector.pos());
        for (var at : turned.blocks().keySet())
            ctx.assertTrue(at.getX() >= 0 && at.getX() < 3 && at.getZ() >= 0 && at.getZ() < 5, "Turned blocks stay inside the box: " + at);
        var stairs = new TemplateCodec.Template(new Vec3i(1, 1, 1), Map.of(BlockPos.ZERO, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH)), Map.of());
        var stair = Piece.of("test/stair", stairs).rotate(Rotation.CLOCKWISE_90).blocks().get(BlockPos.ZERO);
        ctx.assertTrue(stair.getValue(StairBlock.FACING) == Direction.EAST, "Block states turn with the piece");
        var full = piece.rotate(Rotation.CLOCKWISE_90).rotate(Rotation.CLOCKWISE_90).rotate(Rotation.CLOCKWISE_180);
        ctx.assertTrue(full.blocks().equals(piece.blocks()) && full.connectors().equals(piece.connectors()) && full.points().equals(piece.points()), "A full turn gives the original piece");
        ctx.assertTrue(Piece.turning(Direction.NORTH, Direction.WEST) == Rotation.COUNTERCLOCKWISE_90, "turning() finds the rotation between facings");
        ctx.succeed();
    }
}
