package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingManeuver;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.docking.StationConstruction;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.stations.ManufacturerStation;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;

import java.nio.charset.StandardCharsets;
import java.util.*;

import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/**
 * Starting a station: deploying a core from a ship and the generated waypoint and showcase
 * stations.
 */
public final class StationDeployment
{
    /** Blocks that do something when used. Placing a station core on one of them would be an accident. */
    private static final List<Class<?>> USABLE = new ArrayList<>();

    static
    {
        USABLE.add(EntityBlock.class);
        USABLE.add(ModBlocks.Machine.class);
        USABLE.add(DoorBlock.class);
        USABLE.add(TrapDoorBlock.class);
        USABLE.add(FenceGateBlock.class);
        USABLE.add(ButtonBlock.class);
        USABLE.add(LeverBlock.class);
        USABLE.add(BedBlock.class);
        USABLE.add(NoteBlock.class);
        USABLE.add(ComposterBlock.class);
        USABLE.add(CakeBlock.class);
        USABLE.add(RespawnAnchorBlock.class);
        USABLE.add(DaylightDetectorBlock.class);
        USABLE.add(FlowerPotBlock.class);
    }

    public static CompoundTag waypoint(ServerPlayer p, boolean random)
    {
        var own = SceneService.observer(p);
        if (own == null)
            throw new IllegalArgumentException("Use your helm in space");
        var server = p.level().getServer();
        String address = LocalSpaces.address(own);
        String id = UUID.nameUUIDFromBytes(("nullspace:waypoint:" + address + (random ? ":generated" : "")).getBytes(StandardCharsets.UTF_8)).toString();
        var old = WorldStore.get(server).repository().find(id);
        if (old != null)
            return old;
        var probe = new CompoundTag();
        probe.putString("id", id);
        probe.putString("localSpace", address);
        probe.putLong("base", StationBuilds.reserve(server).asLong());
        Vec3 position = StationConstruction.constructionSite(server, own);
        var layout = random ? StationLayout.generate(server.overworld().getSeed() ^ address.hashCode()) : StationLayout.waypoint();
        var result = StationBuilds.queue(server, id, random ? "Survey Station" : "Waypoint Station", address, position, "", StationBlueprint.build(layout), false);
        var pieces = new CompoundTag();
        for (var room : layout.rooms())
            pieces.putString(room.x() + "_" + room.z(), room.encode());
        result.put("roomPieces", pieces);
        WorldStore.get(server).save();
        return result;
    }

    public static CompoundTag showcase(ServerPlayer p)
    {
        var own = SceneService.observer(p);
        if (own == null || !p.level().dimension().equals(TravelService.SPACE))
            throw new IllegalArgumentException("Use your Ship Controller in space to find the showcase");
        var server = p.level().getServer();
        String address = LocalSpaces.address(own);
        String id = UUID.nameUUIDFromBytes(("nullspace:manufacturer-showcase:v1:" + address).getBytes(StandardCharsets.UTF_8)).toString();
        var old = WorldStore.get(server).repository().find(id);
        if (old != null)
            return old;
        var result = StationBuilds.queue(server, id, "Manufacturer Showcase", address, StationConstruction.constructionSite(server, own), "", ManufacturerStation.build(), false);
        result.putString("template", "manufacturer_showcase_v1");
        WorldStore.get(server).save();
        return result;
    }

    public static CompoundTag start(ServerPlayer p, CompoundTag own)
    {
        return start(p, own, StationBuilds.kit(p, ModBlocks.STATION_CORE.asItem()));
    }

    static CompoundTag start(ServerPlayer p, CompoundTag own, ItemStack stack)
    {
        DockingManeuver.requireFree(p, own);
        var source = DockingPorts.firstPort(p.level(), own);
        String id = StationBuilds.receipt(p, stack);
        var existing = WorldStore.get(p.level().getServer()).repository().find(id);
        if (existing != null)
            throw new IllegalArgumentException("This core already owns a station; finish its construction");
        var probe = new CompoundTag();
        probe.putString("id", id);
        probe.putString("localSpace", LocalSpaces.address(own));
        probe.putLong("base", StationBuilds.reserve(p.level().getServer()).asLong());
        var s = StationBuilds.queue(p.level().getServer(), id, p.getPlainTextName() + " Station", LocalSpaces.address(own), StationConstruction.constructionSite(p.level().getServer(), own), p.getUUID().toString(), StationBlueprint.foundation(), true);
        s.putString("constructionShip", own.getStringOr("id", ""));
        s.putLong("constructionSourcePort", source.controller().subtract(WorldStore.pos(own, "base")).asLong());
        com.nullandkale.nullspace.gameplay.travel.BuildJournal.resume(p.level(), s.getStringOr("buildJournal", "")).step(() ->
        {
            StationBuilds.redeem(p, stack, id, 1);
            return null;
        });
        return s;
    }

    public static boolean stationStartTarget(net.minecraft.world.level.Level level, BlockPos pos)
    {
        var state = level.getBlockState(pos);
        var block = state.getBlock();
        if (state.isAir() || state.getMenuProvider(level, pos) != null)
            return false;
        for (var usable : USABLE)
            if (usable.isInstance(block))
                return false;
        return true;
    }

    static boolean stationStartGesture(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level, InteractionHand hand)
    {
        return player.isShiftKeyDown() && level.dimension().equals(TravelService.SPACE) && player.getItemInHand(hand).is(ModBlocks.STATION_CORE.asItem());
    }

    static InteractionResult startFromGesture(net.minecraft.world.entity.player.Player player, InteractionHand hand)
    {
        if (!(player instanceof ServerPlayer p))
            return InteractionResult.SUCCESS;
        try
        {
            var own = SceneService.observer(p);
            if (own == null)
                throw new IllegalArgumentException("Use your helm in space");
            start(p, own, p.getItemInHand(hand));
            p.sendSystemMessage(Component.literal("Station construction started"));
        }
        catch (IllegalArgumentException blocked)
        {
            p.sendSystemMessage(Component.literal(blocked.getMessage()));
        }
        return InteractionResult.SUCCESS;
    }

    private StationDeployment()
    {
    }
}
