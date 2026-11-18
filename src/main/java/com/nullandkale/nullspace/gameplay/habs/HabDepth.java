package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.worldgen.encounters.DerelictLoot;
import com.nullandkale.nullspace.worldgen.encounters.SiteLoot;
import com.nullandkale.nullspace.worldgen.encounters.SiteThreats;
import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Room;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;

/**
 * The depth ramp of a hab (see {@code docs/design/habs-and-rewards.md}): the surface lobby is safe, and each floor down is more dangerous and
 * pays better. The danger of a floor sets how many lost crew and repair bots walk it, how good the stock in its chests is, and how well the
 * controller room is guarded. Danger is the same 0 to 3 scale every vessel uses ({@link SiteThreats}, {@link DerelictLoot}), so a hab's rooms
 * are stocked from the same tables as a derelict's, with the hab's own rooms added.
 */
public final class HabDepth
{
    /** What a room of a hab asks of the world: a creature of a kind (0 zombie, 1 skeleton, 2 repair bot, 3 pest) at a cell. */
    public record Spawn(String room, int kind, boolean hard, int danger, BlockPos at)
    {}

    /** The floor a room is on, counting the surface lobby as floor 1. */
    public static int floorOf(HabPlanner.Hab hab, Room room)
    {
        return hab.floors() - room.level();
    }

    /** The danger of a floor: nothing in the lobby, then 1, 2 and 3 going down (a small hab has two floors below the lobby, so reaches 2). */
    public static int dangerOf(HabPlanner.Hab hab, Room room)
    {
        return Math.clamp(floorOf(hab, room) - 1, 0, 3);
    }

    /** The chance that a room of a floor of this danger holds one of the crew. */
    static double chance(int danger)
    {
        return switch (danger)
        {
            case 1 -> 0.14;
            case 2 -> 0.26;
            case 3 -> 0.40;
            default -> 0;
        };
    }

    /**
     * Who is where, decided from the plan alone so that it can be checked without a world: each room off the lobby has its chance of one crew
     * member, a dormitory has an occupant now and then (a little more often deeper, and never a row of them), the controller room has a guard for
     * each danger level (the hard rank among them from danger 2), and the deeper floors add repair bots and pests.
     */
    public static List<Spawn> plan(HabPlanner.Hab hab, BlockPos origin, long seed)
    {
        var random = new SplittableRandom(seed ^ 0x4841425FL);
        var out = new ArrayList<Spawn>();
        for (var room : hab.plan().rooms())
        {
            int danger = dangerOf(hab, room);
            if (danger < 1 || room.use() == Use.CORRIDOR || room.use() == Use.ARRIVAL || room.id().equals(hab.grove()))
                continue;
            var i = room.interior();
            var at = origin.offset((i.x0() + i.x1()) / 2, room.standY(), (i.z0() + i.z1()) / 2);
            if (room.use() == Use.CONTROLLER_ROOM)
            {
                for (int guard = 0; guard < danger; guard++)
                    out.add(new Spawn(room.id(), guard % 2, danger >= 2 && guard == 0, danger, at.offset(guard - 1, 0, 0)));
                continue;
            }
            if (room.use() == Use.BUNK_ROOM && random.nextDouble() < 0.25 * danger)
                out.add(new Spawn(room.id(), 0, false, danger, at));
            if (random.nextDouble() >= chance(danger))
                continue;
            double roll = random.nextDouble();
            int kind = roll < 0.5 ? 0 : roll < 0.8 || danger < 2 ? 1 : 2;
            out.add(new Spawn(room.id(), kind, danger == 3 && random.nextInt(4) == 0, danger, at));
            if (danger >= 2 && random.nextDouble() < 0.06 * danger)
                out.add(new Spawn(room.id(), 3, false, danger, at.offset(1, 0, 1)));
        }
        return out;
    }

    /** Spawns {@link #plan} into the world. Returns the creatures placed. */
    public static List<Mob> populate(ServerLevel level, BlockPos origin, HabPlanner.Hab hab, long seed)
    {
        var placed = new ArrayList<Mob>();
        var random = RandomSource.create(seed);
        for (var spawn : plan(hab, origin, seed))
        {
            var difficulty = SiteThreats.difficulty(spawn.danger(), 0);
            Mob mob;
            if (spawn.kind() == 2)
                mob = SiteThreats.REPAIR_BOT.create(level, EntitySpawnReason.STRUCTURE);
            else if (spawn.kind() == 3)
                mob = SiteThreats.special(level, SiteThreats.pest(spawn.danger(), random), difficulty);
            else
            {
                EntityType<? extends Mob> type = spawn.kind() == 0 ? (spawn.hard() ? EntityTypes.ZOMBIE_VILLAGER : EntityTypes.ZOMBIE) : EntityTypes.SKELETON;
                mob = SiteThreats.crew(level, type, difficulty);
            }
            if (mob == null)
                continue;
            mob.snapTo(spawn.at().getX() + .5, spawn.at().getY(), spawn.at().getZ() + .5, random.nextFloat() * 360, 0);
            mob.setPersistenceRequired();
            if (level.addFreshEntity(mob))
                placed.add(mob);
        }
        return placed;
    }

    /**
     * Which table each marked chest takes: a room's tier is the danger of its floor, so the deeper rooms give better stock, with the controller
     * room's chest the hab's treasure at its floor's danger (at least 1). A hab starts without power, so the rooms of its electronics hold the
     * power-failure stock.
     */
    public static List<SiteLoot.Assignment> assignments(HabPlanner.Hab hab, BlockPos origin, List<Piece.Point> points, long seed, String name)
    {
        var out = new ArrayList<SiteLoot.Assignment>();
        int n = 0;
        for (var spot : DerelictLoot.spots(points))
        {
            var room = hab.plan().room(spot.room());
            int danger = dangerOf(hab, room);
            String table;
            if (spot.use().equals("controller_room"))
                table = "treasure_" + Math.clamp(danger, 1, 3);
            else
                // (A grove's garden is variant 2 of the station rooms; it has the second garden's stock.)
                table = "derelict/" + (spot.use().equals("garden") ? "garden_" + spot.variant() % 2 : spot.key()) + (dead(spot.use()) ? "_dead" : "") + "_" + Math.clamp(danger, 0, 3);
            out.add(new SiteLoot.Assignment(origin.offset(spot.pos()), table, seed + n++, name));
        }
        return out;
    }

    private static boolean dead(String use)
    {
        return Set.of("engineering", "machine_hall", "controller_room", "operations", "bridge", "maintenance").contains(use);
    }

    /** Gives the golden apple tree of a placed hab one to three apples. Returns how many. */
    public static int seedTree(ServerLevel level, BlockPos origin, com.nullandkale.nullspace.worldgen.stations.HabStructure.Build build, long seed)
    {
        for (var point : build.plan().points("tree"))
            if (level.getBlockEntity(origin.offset(point.pos())) instanceof com.nullandkale.nullspace.gameplay.trees.TreeBlockEntity tree)
            {
                int apples = 1 + Math.floorMod(new SplittableRandom(seed ^ 0x4150504CL).nextInt(), 3);
                tree.stock(level, apples);
                return apples;
            }
        return 0;
    }

    /** Stocks every marked chest of a placed hab. Returns how many were stocked. */
    public static int stock(ServerLevel level, BlockPos origin, HabPlanner.Hab hab, List<Piece.Point> points, long seed, String name)
    {
        return SiteLoot.apply(level, assignments(hab, origin, points, seed, name));
    }

    private HabDepth()
    {
    }
}
