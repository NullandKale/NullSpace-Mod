package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.piece.Piece;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.item.ItemStack;

/**
 * Places the entities a room's marked points describe: item frames holding one handy item, armor
 * stands wearing space suits, paintings and animals. The generator writes only points, since
 * entities are not blocks; this is the one place that turns them into creatures and frames.
 */
public final class RoomEntities
{
    /** Spawns every point's entity relative to {@code origin}; returns how many were placed. */
    public static int spawn(ServerLevel level, BlockPos origin, List<Piece.Point> points)
    {
        return spawn(level, origin, points, entity ->
        {});
    }

    /** Assigns recovery identities before the entities become visible to the world. */
    public static int spawn(ServerLevel level, BlockPos origin, List<Piece.Point> points, java.util.function.Consumer<Entity> prepare)
    {
        int placed = 0;
        for (var point : points)
        {
            var at = origin.offset(point.pos());
            var arguments = point.argument().split(" ");
            boolean ok = switch (point.name())
            {
                case "frame" -> frame(level, at, arguments, prepare);
                case "stand" -> stand(level, at, arguments, prepare);
                case "painting" -> painting(level, at, arguments, prepare);
                case "animal" -> animal(level, at, arguments[0], prepare);
                case "log" -> com.nullandkale.nullspace.gameplay.habs.HabLogs.place(level, at, point.argument());
                case "stock" -> TestbedStock.fill(level, at, point.argument());
                default -> false;
            };
            if (ok)
                placed++;
        }
        return placed;
    }

    private static boolean frame(ServerLevel level, BlockPos at, String[] arguments, java.util.function.Consumer<Entity> prepare)
    {
        var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(arguments[0]));
        var facing = Direction.byName(arguments[1]);
        if (item.isEmpty() || facing == null)
            return false;
        var frame = new ItemFrame(level, at, facing);
        frame.setItem(new ItemStack(item.get()), false);
        prepare.accept(frame);
        return level.addFreshEntity(frame);
    }

    private static boolean stand(ServerLevel level, BlockPos at, String[] arguments, java.util.function.Consumer<Entity> prepare)
    {
        var facing = Direction.byName(arguments[0]);
        var stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.STRUCTURE);
        if (stand == null || facing == null)
            return false;
        boolean armored = arguments.length > 1 && arguments[1].equals("armored");
        boolean empty = arguments.length > 1 && arguments[1].equals("empty");
        if (!empty)
        {
            stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(armored ? ModBlocks.ARMORED_HELMET : ModBlocks.HELMET));
            stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(armored ? ModBlocks.ARMORED_CHEST : ModBlocks.CHEST));
            stand.setItemSlot(EquipmentSlot.LEGS, new ItemStack(armored ? ModBlocks.ARMORED_LEGS : ModBlocks.LEGS));
            stand.setItemSlot(EquipmentSlot.FEET, new ItemStack(armored ? ModBlocks.ARMORED_BOOTS : ModBlocks.BOOTS));
        }
        stand.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, facing.toYRot(), 0);
        prepare.accept(stand);
        return level.addFreshEntity(stand);
    }

    /** A painting of the requested size on the wall behind {@code at}; tries the neighbouring cells if the first anchor does not hold. */
    private static boolean painting(ServerLevel level, BlockPos at, String[] arguments, java.util.function.Consumer<Entity> prepare)
    {
        var facing = Direction.byName(arguments[0]);
        if (facing == null)
            return false;
        int width = Integer.parseInt(arguments[1]), height = Integer.parseInt(arguments[2]);
        var variants = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT).listElements().filter(h -> h.value().width() == width && h.value().height() == height).sorted(Comparator.comparing(h -> h.key().identifier().toString())).toList();
        if (variants.isEmpty())
            return false;
        var variant = variants.get(Math.floorMod(at.hashCode(), variants.size()));
        for (var anchor : new BlockPos[]{at, at.relative(facing.getClockWise()), at.relative(facing.getCounterClockWise())})
        {
            var painting = new Painting(level, anchor, facing, variant);
            if (painting.survives())
            {
                prepare.accept(painting);
                return level.addFreshEntity(painting);
            }
        }
        return false;
    }

    private static boolean animal(ServerLevel level, BlockPos at, String kind, java.util.function.Consumer<Entity> prepare)
    {
        EntityType<? extends Mob> type = switch (kind)
        {
            case "sheep" -> EntityTypes.SHEEP;
            case "cow" -> EntityTypes.COW;
            case "pig" -> EntityTypes.PIG;
            case "minecraft:bee" -> EntityTypes.BEE;
            default -> EntityTypes.CHICKEN;
        };
        var mob = type.create(level, EntitySpawnReason.STRUCTURE);
        if (mob == null)
            return false;
        mob.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, (at.hashCode() & 3) * 90f, 0);
        mob.setPersistenceRequired();
        prepare.accept(mob);
        return level.addFreshEntity(mob);
    }

    private RoomEntities()
    {
    }
}
