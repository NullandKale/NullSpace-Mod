package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.stations.RoomKit.Side;
import com.nullandkale.nullspace.worldgen.stations.RoomKit.Spec;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Rooms where the station's work is done. Each is zones along the walls and a clear route down the
 * middle; large rooms repeat their bays instead of stretching one unit. Containers stand on the floor
 * in rows, as a player would place them, never in stacks.
 */
final class RoomsWork
{
    private static Spec cycle(int i, Spec... all)
    {
        return all[Math.floorMod(i, all.length)];
    }

    private static BlockState bars()
    {
        return Blocks.IRON_BARS.defaultBlockState();
    }

    /** Power and air: generators with their batteries, a control console and fuel, down a hazard-striped aisle. */
    static void engineering(RoomKit k)
    {
        if (k.variant >= 1)
        {
            shipEngineering(k);
            return;
        }
        k.air();
        int c = k.doorU;
        for (int u = 0; u < k.w; u++)
            for (int v = k.d - 3; v < k.d; v++)
                k.floor(u, v, Blocks.BLACKSTONE);
        k.floorStripe(c, k.d - 4, k.style.accent);
        k.bank(Side.BACK, 1, k.w - 2, 2, Fx.GENERATOR, Fx.BATTERY);
        if (k.big())
        {
            // A machine hall: banks down the left wall, two consoles down the right, fuel, and pipes overhead.
            k.bank(Side.LEFT, 3, k.d - 4, 2, Fx.GENERATOR, Fx.BATTERY);
            for (int v : new int[]{3, 5})
                k.column(k.w - 1, v, k.left, Fx.TERMINAL, Fx.MONITOR);
            k.run(Side.RIGHT, k.d - 4, k.d - 2, Fx.BARREL);
            for (int v : new int[]{k.d / 3 + 1, 2 * k.d / 3})
                for (int u = 0; u < k.w; u++)
                    k.put(u, v, k.h - 1, bars());
        }
        else
        {
            k.run(Side.LEFT, 2, 3, Fx.BARREL);
            k.column(k.w - 1, 2, k.left, Fx.TERMINAL, Fx.MONITOR);
        }
        k.frame(Side.DOOR, c - 2, 1, "nullspace:air_canister");
        k.frame(Side.BACK, k.w / 2, 1, "nullspace:circuit_board");
    }

    /**
     * A ship's engineering: engines in the stern wall behind two clear rows, and generators with their batteries
     * down both side walls. The engines face outward through the wall and nothing may stand in their exhaust.
     */
    private static void shipEngineering(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        for (int u = 0; u < k.w; u++)
            for (int v = k.d - 2; v < k.d; v++)
                k.floor(u, v, Blocks.BLACKSTONE);
        k.floorStripe(c, k.d - 3, k.style.accent);
        for (int u : new int[]{c - 3, c + 3, c - 5, c + 5})
            if (u >= 0 && u < k.w)
                k.out.put(k.at(u, k.d, 0), Fx.ENGINE.get(k.fwd));
        // A bank of generators and batteries per side, more on a bigger ship: variant 1 is two a side, up to four.
        int banks = Math.min(k.d - 1, k.variant + 1);
        k.bank(Side.LEFT, 0, banks - 1, 1, Fx.GENERATOR, Fx.BATTERY);
        k.bank(Side.RIGHT, 0, banks - 1, 1, Fx.GENERATOR, Fx.BATTERY);
        if (banks < k.d - 2)
        {
            k.run(Side.LEFT, banks, banks, Fx.BARREL);
            k.run(Side.RIGHT, banks, banks, Fx.BARREL);
        }
        k.frame(Side.DOOR, c - 2, 1, "nullspace:air_canister");
        k.frame(Side.DOOR, c + 2, 1, "nullspace:circuit_board");
    }

    /** Benches along the walls and an assembly table with room to walk round it; a large shop adds island benches under a gantry. */
    static void workshop(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorBorder(k.planks);
        Spec[] left = {Fx.CRAFT, Fx.SMITH, Fx.CUTTER, Fx.ANVIL}, right = {Fx.LOOM, Fx.GRIND, Fx.FLETCH, Fx.CHARTS};
        int last = k.big() ? k.d - 3 : 4;
        for (int v = 2; v <= last; v++)
            k.column(0, v, k.right, cycle(v, left));
        for (int v = 2; v <= (k.big() ? k.d - 3 : 3); v++)
            k.column(k.w - 1, v, k.left, cycle(v, right));
        for (int u = 1; u <= k.w - 2; u++)
            k.put(u, k.d - 1, 0, (u % 2 == 0 ? Fx.CHEST : Fx.BARREL).get(k.back));
        k.frame(Side.LEFT, 3, 1, "minecraft:iron_pickaxe");
        k.frame(Side.RIGHT, 2, 1, "minecraft:shears");
        if (k.big())
        {
            Spec[] island = {Fx.CRAFT, Fx.SMITH, Fx.CUTTER, Fx.ANVIL, Fx.LOOM, Fx.GRIND};
            for (int side : new int[]{-3, 3})
                for (int v = 3; v <= k.d - 4; v++)
                    k.put(c + side, v, 0, cycle(v, island).get(k.fwd));
            // A gantry over the work floor: log posts standing on the benches, and a rail of bars between
            // them (bars meet solid blocks, never fences).
            for (int side : new int[]{-3, 3})
                for (int v : new int[]{3, k.d - 4})
                    for (int r = 1; r <= 3; r++)
                        k.put(c + side, v, r, k.log.defaultBlockState());
            for (int u = c - 2; u <= c + 2; u++)
                for (int v : new int[]{3, k.d - 4})
                    k.put(u, v, 3, bars());
        }
        k.tableSet(3, k.big() ? 2 : 1, false, k.lantern());
    }

    /** Furnace banks against the back wall under a scorched floor, ore bins, a quench trough and anvils. */
    static void foundry(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        for (int u = 0; u < k.w; u++)
            for (int v = k.d - 3; v < k.d; v++)
                k.floor(u, v, Blocks.BLACKSTONE);
        k.floorStripe(c, k.d - 4, k.style.accent);
        k.run(Side.BACK, 1, k.w - 2, Fx.FURNACE, Fx.BLAST);
        int bins = k.big() ? 4 : k.d - 3;
        k.run(Side.LEFT, 2, bins, Fx.BARREL, Fx.HOPPER);
        for (int v = 2; v <= k.d - 3; v++)
            k.column(k.w - 1, v, k.left, v % 2 == 0 ? Fx.ANVIL : Fx.waterCauldron());
        k.frame(Side.RIGHT, 3, 1, "minecraft:flint_and_steel");
        k.frame(Side.RIGHT, 5, 1, "minecraft:bucket");
        if (k.big())
        {
            // Furnace banks face each other down the long walls, with a casting floor between.
            k.run(Side.LEFT, 5, k.d - 4, Fx.FURNACE, Fx.BLAST);
            for (int v = 4; v <= k.d - 5; v += 3)
            {
                k.put(c, v, 0, Blocks.POLISHED_ANDESITE.defaultBlockState());
                k.put(c, v, 1, Fx.ANVIL.get(k.fwd));
            }
            k.run(Side.RIGHT, 6, 7, Fx.GRIND);
        }
    }

    /** Rows of chests and barrels on the floor in bays divided by lanes, with a manifest desk by the door. */
    static void cargoHold(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        var accent = k.style.accent;
        for (int u = 0; u < k.w; u++)
            if ((u - c) % 3 == 0)
                k.floorStripe(u, k.d - 1, accent);
        if (k.w >= 7)
        {
            k.put(c - 3, 0, 0, k.slabTop());
            k.column(c + 3, 0, k.left, Fx.LECTERN);
        }
        for (int u = 1; u < k.w - 1; u++)
        {
            if ((u - c) % 3 == 0)
                continue;
            boolean chests = Math.abs(u - c) % 3 == 1;
            var face = u < c ? k.right : k.left;
            int v = 2;
            while (v <= k.d - 3)
            {
                if ((v - 2) % 4 == 3)
                {
                    v++;
                    continue;
                }
                boolean nextOk = v + 1 <= k.d - 3 && (v - 1) % 4 != 3;
                if (chests && nextOk && k.doubleChest(u, v, k.fwd, face))
                    v += 2;
                else
                {
                    k.put(u, v, 0, (chests ? Fx.CHEST : Fx.BARREL).get(face));
                    v++;
                }
            }
        }
        if (k.big())
            for (int u = 0; u < k.w; u++)
                if ((u - c) % 3 == 0)
                    for (int v = 2; v <= k.d - 3; v++)
                        k.put(u, v, k.h - 1, bars());
    }

    /** A counter across the room with a gate, intake chutes at the back, sorting tables and a customs desk. */
    static void receivingBay(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorBorder(k.style.accent);
        int chutes = k.big() ? 4 : 2;
        for (int i = 0; i < chutes; i++)
        {
            int u = k.w / 2 - chutes + 1 + i * 2;
            k.column(u, k.d - 1, k.back, Fx.BARREL, Fx.HOPPER);
        }
        // The counter, with a three-wide gate in front of the door.
        for (int u = 0; u < k.w; u++)
            if (Math.abs(u - c) > 1)
                k.put(u, 2, 0, k.slabTop());
        k.column(c - 3, 3, k.fwd, Fx.LECTERN);
        k.column(c + 3, 3, k.fwd, Fx.TERMINAL);
        if (k.big())
        {
            for (int v : new int[]{5, 8})
            {
                k.table(1, v, 2, 1, null);
                k.table(k.w - 3, v, 2, 1, null);
                k.put(1, v + 1, 0, Fx.BARREL, k.fwd);
                k.put(k.w - 2, v + 1, 0, Fx.BARREL, k.fwd);
            }
            // The goods cage: chests behind bars at the back corner.
            for (int u = 1; u <= 3; u++)
                for (int v = k.d - 4; v <= k.d - 2; v++)
                    if (u == 3 || v == k.d - 4)
                        for (int r = 0; r < 2; r++)
                            k.put(u, v, r, bars());
            k.put(1, k.d - 2, 0, Fx.CHEST, k.right);
            k.put(2, k.d - 2, 0, Fx.CHEST, k.right);
        }
        k.frame(Side.BACK, k.w / 2 + 1, 1, "minecraft:clock");
    }

    /** Containers on the floor along the walls with a lane between: general stores, a seed store or a spares store. */
    static void stores(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(k.planks);
        switch (k.variant)
        {
            case 1 ->
            {
                // Seed store: composters and hay along one wall, seed barrels along the other and the back.
                for (int v = 2; v <= k.d - 3; v++)
                    k.put(0, v, 0, (v % 2 == 0 ? Fx.COMPOSTER : Fx.HAY).get(k.right));
                k.run(Side.RIGHT, 2, k.d - 3, Fx.BARREL);
                k.run(Side.BACK, 1, k.w - 2, Fx.BARREL);
                k.frame(Side.BACK, k.w / 2 - 1, 1, "minecraft:iron_hoe");
                k.frame(Side.BACK, k.w / 2 + 1, 1, "minecraft:shears");
            }
            case 2 ->
            {
                // Spares: chests along both walls and a repair bench at the back.
                k.chestRun(Side.LEFT, 2, k.d - 3);
                k.chestRun(Side.RIGHT, 2, k.d - 3);
                for (int u = 1; u <= k.w - 2; u++)
                    k.put(u, k.d - 1, 0, (u % 2 == 0 ? Fx.GRIND : Fx.ANVIL).get(k.back));
                k.frame(Side.BACK, k.w / 2 - 1, 1, "nullspace:circuit_board");
                k.frame(Side.BACK, k.w / 2 + 1, 1, "nullspace:machine_casing");
            }
            default ->
            {
                // General stores: chests along the left wall, barrels along the right, chests at the back.
                k.chestRun(Side.LEFT, 2, k.d - 3);
                k.run(Side.RIGHT, 2, k.d - 3, Fx.BARREL);
                k.chestRun(Side.BACK, 1, k.w - 2);
            }
        }
        if (k.big())
            for (int side : new int[]{-3, 3})
                for (int v = 3; v <= k.d - 4; v++)
                    if ((v - 3) % 4 != 3)
                        k.put(c + side, v, 0, (k.variant == 1 ? Fx.BARREL : v % 2 == 0 ? Fx.CHEST : Fx.BARREL).get(k.fwd));
    }

    /** Tool bench, spares along the wall, a pipe junction and charging cradles for the repair bots that live here. */
    static void maintenance(RoomKit k)
    {
        k.air();
        k.floorBorder(Blocks.POLISHED_ANDESITE);
        Spec[] bench = {Fx.GRIND, Fx.SMITH, Fx.ANVIL};
        for (int v = 2; v <= k.d - 3; v++)
            k.column(0, v, k.right, cycle(v, bench));
        k.frame(Side.LEFT, 3, 1, "minecraft:iron_pickaxe");
        k.frame(Side.LEFT, 5, 1, "minecraft:shears");
        // The junction: cauldrons under hoppers, with barrels between.
        for (int v = 2; v <= k.d - 3; v += 2)
            k.column(k.w - 1, v, k.left, Fx.waterCauldron(), Fx.HOPPER);
        for (int v = 3; v <= k.d - 3; v += 2)
            k.put(k.w - 1, v, 0, Fx.BARREL.get(k.left));
        // Charging cradles along the back wall: a dark pad between bars.
        int cradles = k.big() ? 3 : 1;
        for (int i = 0; i < cradles; i++)
            for (int side : new int[]{-1, 1})
            {
                int u = k.w / 2 + side * (2 + i * 3);
                for (int du = -1; du <= 0; du++)
                    for (int v = k.d - 3; v < k.d; v++)
                        k.floor(u + du, v, Blocks.BLACKSTONE);
                int post = side > 0 ? u + 1 : u - 2;
                k.put(post, k.d - 2, 0, Blocks.POLISHED_ANDESITE.defaultBlockState());
                k.put(post, k.d - 2, 1, Blocks.POLISHED_ANDESITE.defaultBlockState());
            }
        k.frame(Side.BACK, k.w / 2, 1, "nullspace:machine_casing");
    }

    private RoomsWork()
    {
    }
}
