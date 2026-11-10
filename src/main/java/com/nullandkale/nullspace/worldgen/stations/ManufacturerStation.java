package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.worldgen.shipgen.Spec;

import java.util.*;

import net.minecraft.core.*;

/**
 * The Manufacturer Exchange: a ring of four generated tiles inside one 32-block section. An
 * entrance hub with the core and berth sits beside Rivet's workshop (the second berth), Halcyon's
 * observation dome and Bastion's power room, each in its own maker's materials. Visitors can walk
 * every room from either berth.
 */
public final class ManufacturerStation
{
    /** The tiles, by id: the hub is at 0,0, Rivet beside it, Halcyon below it and Bastion opposite. */
    public static StationLayout.Layout layout()
    {
        var rooms = List.of(new StationLayout.Room("hub", "arrival_bright", 0, 0, 0), new StationLayout.Room("rivet", "workshop", 0, 1, 0), new StationLayout.Room("halcyon", "dome_observation_bright", 0, 0, 1), new StationLayout.Room("bastion", "engineering", 0, 1, 1));
        var joints = List.of(new StationLayout.Joint("hub", "rivet"), new StationLayout.Joint("hub", "halcyon"), new StationLayout.Joint("rivet", "bastion"), new StationLayout.Joint("halcyon", "bastion"));
        return new StationLayout.Layout(rooms, joints);
    }

    private static Spec.Maker maker(StationLayout.Room room)
    {
        return switch (room.id())
        {
            case "rivet" -> Spec.Maker.RIVET;
            case "bastion" -> Spec.Maker.BASTION;
            default -> Spec.Maker.HALCYON;
        };
    }

    public static StationBlueprint.Plan build()
    {
        return StationBlueprint.build(layout(), ManufacturerStation::maker);
    }

    /** A standing cell inside each manufacturer's wing, by the doorway you come in through. */
    public static final Map<String, BlockPos> ROOMS = Map.of("Rivet workshop", new BlockPos(17, 2, 3), "Halcyon observation", new BlockPos(3, 2, 17), "Bastion operations", new BlockPos(17, 2, 17));

    public static final BlockPos CORE = build().core();

    public static void label(net.minecraft.server.level.ServerLevel level, BlockPos base)
    {
        ShipGallery.label(level, base.offset(17, 2, 3), net.minecraft.world.item.DyeColor.ORANGE, "RIVET WORKS", "Workshop wing", "Benches + smelting", "Bolt scout");
        ShipGallery.label(level, base.offset(3, 2, 17), net.minecraft.world.item.DyeColor.CYAN, "HALCYON AEROSPACE", "Observation dome", "Glass + sensors", "Swift scout");
        ShipGallery.label(level, base.offset(17, 2, 17), net.minecraft.world.item.DyeColor.RED, "BASTION", "Power room", "Generators + air", "Ward scout");
    }

    private ManufacturerStation()
    {
    }
}
