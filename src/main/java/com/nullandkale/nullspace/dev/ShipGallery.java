package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;

import java.nio.file.*;
import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Additive, opt-in walking gallery. Existing reference ships and player plots are never rebuilt.
 */
public final class ShipGallery
{
    public static final int WIDTH = 86, DEPTH = 108, PAD_SIZE = 12;

    public record Plot(int number, String title, String hint, String expected)
    {
        public boolean blank()
        {
            return number >= 13;
        }

        public boolean ready()
        {
            return number <= 8;
        }
    }

    public static final List<Plot> PLOTS = List.of(new Plot(1, "Scout", "Basic round trip", ""), new Plot(2, "Glass cabin", "See the cabin", ""), new Plot(3, "Cargo", "Check containers", ""), new Plot(4, "Airlock", "One door at a time", ""), new Plot(5, "Upper deck", "Walk upstairs", ""), new Plot(6, "Half steps", "Slabs are fittings", ""), new Plot(7, "Twin engines", "Two clear exhausts", ""), new Plot(8, "Iron door", "Use the buttons", ""), new Plot(9, "Roof leak", "Patch missing roof", "sealed"), new Plot(10, "No engine", "Install an engine", "engine"), new Plot(11, "Blocked engine", "Clear the exhaust", "exhaust"), new Plot(12, "Sky obstruction", "Remove high glass", "obstruction"), new Plot(13, "Blank 01", "Your test build", ""), new Plot(14, "Blank 02", "Your test build", ""), new Plot(15, "Blank 03", "Your test build", ""), new Plot(16, "Blank 04", "Your test build", ""), new Plot(17, "Blank 05", "Your test build", ""), new Plot(18, "Blank 06", "Your test build", ""), new Plot(19, "Blank 07", "Your test build", ""), new Plot(20, "Blank 08", "Your test build", ""));

    public record Gallery(BlockPos origin, String prefix)
    {
        public BlockPos base(int number)
        {
            if (number < 1 || number > PLOTS.size())
                throw new IllegalArgumentException("Choose plot 1-20");
            return origin.offset(6 + ((number - 1) % 4) * 20, 0, 10 + ((number - 1) / 4) * 20);
        }

        public String name(Plot p)
        {
            return prefix + " " + String.format(Locale.ROOT, "%02d", p.number) + " " + p.title;
        }

        public BlockPos spawn()
        {
            return origin.offset(5, 1, 4);
        }

        public BlockPos controller(int number)
        {
            return base(number).offset(8, number == 5 ? 4 : 2, 7);
        }
    }

    public static Gallery ensure(MinecraftServer server) throws java.io.IOException
    {
        Path marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-ship-gallery-v1.nbt");
        if (Files.exists(marker))
        {
            var data = WorldStore.read(marker);
            if (data.getIntOr("version", 0) != 1)
                throw new IllegalStateException("Unknown ship gallery version");
            if (!data.getBooleanOr("complete", false))
                throw new IllegalStateException("Gallery generation was interrupted; partial builds are preserved at " + BlockPos.of(data.getLongOr("origin", 0)));
            return new Gallery(BlockPos.of(data.getLongOr("origin", 0)), data.getStringOr("prefix", "Lab"));
        }
        var level = server.overworld();
        var pads = PadRegistry.read(server);
        Gallery gallery = null;
        for (int attempt = 0; attempt < 16 && gallery == null; attempt++)
        {
            int x = 256 + (attempt % 4) * 128, z = 128 + (attempt / 4) * 128, y = level.getMinY();
            var proposed = new PadScanner.Pad(x, x + WIDTH - 1, 0, z, z + DEPTH - 1);
            if (pads.stream().anyMatch(p -> p.dimension().equals(level.dimension().identifier().toString()) && proposed.overlaps(p.bounds())))
                continue;
            // WORLD_SURFACE includes glass, leaves, fluids, and existing builds. Nothing is cleared.
            // getHeight returns the minimum Y for an unloaded chunk; load the bounded site first.
            for (int cx = x >> 4; cx <= (x + WIDTH - 1) >> 4; cx++)
                for (int cz = z >> 4; cz <= (z + DEPTH - 1) >> 4; cz++)
                    level.getChunk(cx, cz);
            for (int dx = 0; dx < WIDTH; dx++)
                for (int dz = 0; dz < DEPTH; dz++)
                    y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
            y += 4;
            if (y + 40 > level.getMaxY())
                continue;
            String prefix = "Lab";
            for (int suffix = 2; conflicts(pads, prefix); suffix++)
                prefix = "Lab" + suffix;
            gallery = new Gallery(new BlockPos(x, y, z), prefix);
        }
        if (gallery == null)
            throw new IllegalStateException("No empty gallery site within the bounded search area");
        var data = new CompoundTag();
        data.putInt("version", 1);
        data.putLong("origin", gallery.origin.asLong());
        data.putString("prefix", gallery.prefix);
        data.putBoolean("complete", false);
        // Reserve the site first. An interrupted build must never trigger destructive regeneration.
        WorldStore.write(marker, data);
        buildWalkways(level, gallery);
        for (var plot : PLOTS)
        {
            var base = gallery.base(plot.number);
            buildPad(level, base, plot.number);
            if (!plot.blank())
                buildShip(level, base, plot.number);
            label(level, base.offset(1, 1, -3), plot.ready() ? DyeColor.GREEN : plot.blank() ? DyeColor.BLUE : DyeColor.ORANGE, String.format(Locale.ROOT, "%02d %s", plot.number, plot.title), plot.ready() ? "READY TO LAUNCH" : plot.blank() ? "EMPTY / REGISTERED" : "REPAIR EXAMPLE", plot.hint, plot.blank() ? "12 x 12 x 32" : "Close doors first");
            if (plot.blank())
                ((SignBlockEntity) level.getBlockEntity(base.offset(1, 1, -3))).setWaxed(false);
            var bounds = new PadScanner.Pad(base.getX(), base.getX() + 11, base.getY(), base.getZ(), base.getZ() + 11);
            String registered = PadComputers.register(level, base.offset(-1, 1, 5), bounds, gallery.name(plot), false);
            if (!registered.startsWith("Registered "))
                throw new IllegalStateException(registered);
        }
        buildSupplies(level, gallery);
        server.saveEverything(true, true, true);
        data.putBoolean("complete", true);
        WorldStore.write(marker, data);
        return gallery;
    }

    private static boolean conflicts(List<PadRegistry.Pad> pads, String prefix)
    {
        return PLOTS.stream().anyMatch(plot -> pads.stream().anyMatch(p -> p.name().equalsIgnoreCase(prefix + " " + String.format(Locale.ROOT, "%02d", plot.number) + " " + plot.title)));
    }

    private static void buildWalkways(ServerLevel level, Gallery g)
    {
        for (int x = 0; x < WIDTH; x++)
            for (int z = 0; z < DEPTH; z++)
            {
                var p = g.origin.offset(x, 0, z);
                level.setBlock(p, ((x % 5 == 0 && z % 5 == 0) ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray()).defaultBlockState(), 18);
                if (x == 0 || x == WIDTH - 1 || z == 0 || z == DEPTH - 1)
                    level.setBlock(p.above(), Blocks.GLASS.defaultBlockState(), 18);
            }
        label(level, g.origin.offset(2, 1, 4), DyeColor.BLUE, "SHIP TEST LAB", "01-08 READY", "09-12 REPAIR", "13-20 BUILD");
        label(level, g.origin.offset(9, 1, 4), DyeColor.BLACK, "WALK INTO A SHIP", "Open / close door", "Inspect > Launch", "Land: own pad");
        label(level, g.origin.offset(14, 1, 4), DyeColor.BLACK, "SUPPLY BARRELS", "Suit for EVA", "Creative is safe", "Survival: hazards");
    }

    private static void buildPad(ServerLevel level, BlockPos base, int number)
    {
        for (int x = -1; x <= 12; x++)
            for (int z = -1; z <= 12; z++)
            {
                boolean edge = x == -1 || x == 12 || z == -1 || z == 12;
                var block = edge ? Blocks.COBBLESTONE : number == 2 ? ((x + z) % 2 == 0 ? Blocks.DIRT : Blocks.GRASS_BLOCK) : number >= 17 ? Blocks.DIRT : Blocks.GRASS_BLOCK;
                level.setBlock(base.offset(x, 0, z), block.defaultBlockState(), 18);
            }
        level.setBlock(base.offset(-1, 1, 5), PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.EAST), 18);
    }

    private static void buildShip(ServerLevel level, BlockPos b, int number)
    {
        int roof = number == 5 ? 7 : 5;
        for (int x = 2; x <= 8; x++)
            for (int z = 3; z <= 9; z++)
                for (int y = 1; y <= roof; y++)
                {
                    if (x != 2 && x != 8 && z != 3 && z != 9 && y != 1 && y != roof)
                        continue;
                    boolean window = number == 2 && y >= 2 && y <= roof - 1 && (x == 2 || x == 8 || z == 9);
                    level.setBlock(b.offset(x, y, z), (window ? Blocks.GLASS : Blocks.STONE).defaultBlockState(), 18);
                }
        level.setBlock(b.offset(5, roof, 6), Blocks.SEA_LANTERN.defaultBlockState(), 18);
        var controller = b.offset(8, number == 5 ? 4 : 2, 7);
        level.setBlock(controller, ModBlocks.COMPUTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
        level.setBlock(b.offset(2, 2, 6), ModBlocks.ENGINE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
        door(level, b.offset(5, 2, 3), number == 8 ? Blocks.IRON_DOOR : Blocks.OAK_DOOR);
        // An attached, captured stair inside the footprint makes boarding a normal walk.
        level.setBlock(b.offset(5, 1, 2), Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH), 18);
        chest(level, b.offset(4, number == 5 ? 4 : 2, 7), Blocks.CHEST, new ItemStack(Items.DIAMOND, 7));
        if (number == 3)
        {
            chest(level, b.offset(3, 2, 5), Blocks.BARREL, new ItemStack(Items.IRON_INGOT, 16));
            chest(level, b.offset(7, 2, 5), Blocks.DYED_SHULKER_BOX.blue(), new ItemStack(Items.REDSTONE, 32));
            label(level, b.offset(4, 2, 6), DyeColor.BLACK, "CARGO ROUND TRIP", "7 diamonds", "16 iron ingots", "32 redstone");
        }
        if (number == 4)
        {
            for (int x = 3; x <= 7; x++)
                for (int y = 2; y < roof; y++)
                    level.setBlock(b.offset(x, y, 5), Blocks.STONE.defaultBlockState(), 18);
            door(level, b.offset(5, 2, 5), Blocks.OAK_DOOR);
        }
        if (number == 5)
        {
            for (int x = 3; x <= 7; x++)
                for (int z = 7; z <= 8; z++)
                    level.setBlock(b.offset(x, 3, z), Blocks.STONE.defaultBlockState(), 18);
            for (int step = 0; step < 2; step++)
                level.setBlock(b.offset(5, 2 + step, 5 + step), Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH), 18);
        }
        if (number == 6)
            for (int x = 3; x <= 7; x++)
                level.setBlock(b.offset(x, 2, 6), Blocks.STONE_SLAB.defaultBlockState(), 18);
        if (number == 7)
        {
            level.setBlock(b.offset(2, 2, 6), Blocks.STONE.defaultBlockState(), 18);
            for (int z : new int[]{5, 7})
                level.setBlock(b.offset(2, 2, z), ModBlocks.ENGINE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
        }
        if (number == 8)
        {
            level.setBlock(b.offset(4, 3, 2), Blocks.STONE_BUTTON.defaultBlockState().setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL).setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), 18);
            level.setBlock(b.offset(4, 3, 4), Blocks.STONE_BUTTON.defaultBlockState().setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL).setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH), 18);
        }
        if (number == 9)
            level.setBlock(b.offset(5, roof, 7), Blocks.AIR.defaultBlockState(), 18);
        if (number == 10)
            level.setBlock(b.offset(2, 2, 6), Blocks.STONE.defaultBlockState(), 18);
        if (number == 11)
            level.setBlock(b.offset(1, 2, 6), Blocks.STONE.defaultBlockState(), 18);
        if (number == 12)
            level.setBlock(b.offset(5, 36, 6), Blocks.GLASS.defaultBlockState(), 18);
        level.setBlock(b.offset(2, 2, 7), ModBlocks.GENERATOR.defaultBlockState(), 18);
        FuelSystem.provision(level, b.offset(2, 2, 7), 64);
        level.setBlock(b.offset(8, 2, 6), ModBlocks.AIR_SUPPLY.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
    }

    private static void door(ServerLevel level, BlockPos bottom, Block block)
    {
        var state = block.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.OPEN, false);
        level.setBlock(bottom, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 18);
        level.setBlock(bottom.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 18);
    }

    private static void chest(ServerLevel level, BlockPos at, Block block, ItemStack stack)
    {
        level.setBlock(at, block.defaultBlockState(), 18);
        ((net.minecraft.world.Container) level.getBlockEntity(at)).setItem(0, stack);
    }

    private static void buildSupplies(ServerLevel level, Gallery g)
    {
        Item[][] supplies = {{ModBlocks.HELMET, ModBlocks.CHEST, ModBlocks.LEGS, ModBlocks.BOOTS, FieldGuide.ITEM, Items.COOKED_BEEF}, {ModBlocks.COMPUTER.asItem(), ModBlocks.ENGINE.asItem(), PadComputers.BLOCK.asItem(), Items.OAK_DOOR, Items.IRON_DOOR, Items.STONE_BUTTON}, {Items.STONE, Items.GLASS, Items.STONE_STAIRS, Items.STONE_SLAB, Items.SEA_LANTERN, Items.COBBLESTONE, Items.DIRT, Items.GRASS_BLOCK, Items.CHEST, Items.BARREL, Items.LEVER}};
        for (int column = 0; column < supplies.length; column++)
        {
            var at = g.origin.offset(18 + column * 2, 1, 4);
            level.setBlock(at, Blocks.BARREL.defaultBlockState(), 18);
            var container = (net.minecraft.world.Container) level.getBlockEntity(at);
            for (int slot = 0; slot < supplies[column].length; slot++)
            {
                var item = supplies[column][slot];
                container.setItem(slot, new ItemStack(item, column == 0 && item != Items.COOKED_BEEF ? 1 : 64));
            }
        }
    }

    public static void label(ServerLevel level, BlockPos at, DyeColor color, String... lines)
    {
        level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState(), 18);
        var sign = (SignBlockEntity) level.getBlockEntity(at);
        var text = SignText.EMPTY.asMutable().setColor(color).setTextGlowing(true);
        for (int i = 0; i < Math.min(4, lines.length); i++)
            text.setLine(i, Component.literal(lines[i]));
        sign.setText(text.asImmutable(), SignTextSlot.FRONT);
        sign.setText(text.asImmutable(), SignTextSlot.BACK);
        sign.setWaxed(true);
    }

    private ShipGallery()
    {
    }
}
