package com.nullandkale.nullspace.gameplay.scene;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingManeuver;
import com.nullandkale.nullspace.gameplay.scene.ScenePackets.Frame;
import com.nullandkale.nullspace.gameplay.systems.MonitorWall;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.flight.ShipHeading;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.model.space.PlanetSky;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/**
 * Camera and monitor views: which feed a panel shows, ship and machine views, and cycling monitors.
 */
public final class ScenePanels
{

    public static void machineView(ServerPlayer player, BlockPos console)
    {
        SceneService.open(player, console, false, true, false);
    }

    public static void equipmentView(ServerPlayer player, BlockPos console)
    {
        SceneService.open(player, console, false, false, true);
    }

    public static void shipView(ServerPlayer p, CompoundTag ship, UUID lease, int revision, boolean pilot, int power)
    {
        var data = new CompoundTag();
        data.putBoolean("pilotView", true);
        data.putBoolean("universe", true);
        data.putBoolean("canPilot", pilot);
        data.putString("dimension", p.level().dimension().identifier().toString());
        data.putString("observer", ship.getStringOr("id", ""));
        data.putString("location", LocalSpaces.label(p.level().getServer(), ship));
        data.putString("status", "LIVE | " + ship.getStringOr("flightStatus", pilot ? "PILOT" : "OBSERVER"));
        data.putInt("propulsion", power);
        data.putDouble("speed", new Vec3(ship.getDoubleOr("simVX", 0), ship.getDoubleOr("simVY", 0), ship.getDoubleOr("simVZ", 0)).length());
        var connected = ShipSystems.connected(p.level(), ship);
        var base = WorldStore.pos(ship, "base");
        int extent = 1;
        for (int axis = 0; axis < 3; axis++)
        {
            final int a = axis;
            var stats = connected.stream().mapToInt(pos -> a == 0 ? pos.getX() - base.getX() : a == 1 ? pos.getY() - base.getY() : pos.getZ() - base.getZ()).summaryStatistics();
            data.putDouble("target" + "XYZ".charAt(axis), stats.getCount() == 0 ? 16 : (stats.getMin() + stats.getMax() + 1) * .5);
            if (stats.getCount() > 0)
                extent = Math.max(extent, stats.getMax() - stats.getMin() + 1);
        }
        data.putDouble("viewDistance", Math.clamp(extent * 2.2, 28, 300));
        data.putFloat("helmYaw", ShipHeading.forward(p.level(), ship).toYRot());
        var subjects = new ListTag();
        WorldStore.get(p.level().getServer()).ships().stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("dimension", "").equals(data.getStringOr("dimension", "")) && LocalSpaces.together(ship, t)).sorted(Comparator.comparingDouble(t -> DockingManeuver.sceneDistance(ship, t))).limit(SceneService.MAX_SUBJECTS).forEach(t -> SceneService.addSubject(subjects, p.level(), t));
        data.put("subjects", subjects);
        SceneService.deliver(p, new Frame(lease, revision, data));
    }

    static void panels(ServerPlayer player)
    {
        if (!ServerPlayNetworking.canSend(player, Frame.TYPE) || TransferLocks.busy(player))
            return;
        var level = player.level();
        var ship = SceneService.observer(player);
        var data = new CompoundTag();
        data.putBoolean("panels", true);
        data.putString("dimension", level.dimension().identifier().toString());
        var displays = new ListTag();
        if (ship != null && !TransferLocks.locked(level, WorldStore.pos(ship, "base")))
        {
            var blocks = ShipSystems.connected(level, ship);
            var cameras = blocks.stream().filter(p -> level.getBlockState(p).is(ModBlocks.CAMERA) && (!ShipSystems.enabled(ship) || ShipSystems.active(ship, p))).sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
            blocks.stream().filter(p -> level.getBlockState(p).is(ModBlocks.MONITOR) && player.distanceToSqr(Vec3.atCenterOf(p)) <= 144).filter(p -> MonitorWall.tiles(level, p).getFirst().equals(p)).sorted(Comparator.comparingDouble(p -> player.distanceToSqr(Vec3.atCenterOf(p)))).limit(2).forEach(p ->
            {
                var display = new CompoundTag();
                display.putLong("position", p.asLong());
                var tiles = MonitorWall.tiles(level, p);
                var normal = level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING);
                display.putDouble("centerX", tiles.stream().mapToInt(BlockPos::getX).average().orElse(p.getX()) + .5);
                display.putDouble("centerY", tiles.stream().mapToInt(BlockPos::getY).average().orElse(p.getY()) + .5);
                display.putDouble("centerZ", tiles.stream().mapToInt(BlockPos::getZ).average().orElse(p.getZ()) + .5);
                display.putInt("wallWidth", (int) tiles.stream().mapToInt(t -> normal.getAxis() == Direction.Axis.X ? t.getZ() : t.getX()).distinct().count());
                display.putInt("wallHeight", (int) tiles.stream().mapToInt(BlockPos::getY).distinct().count());
                display.putBoolean("mounted", true);
                display.putString("facing", level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING).getName());
                int channel = cameras.isEmpty() ? 0 : Math.floorMod(ship.getCompoundOrEmpty("monitorChannels").getIntOr(Long.toString(p.subtract(WorldStore.pos(ship, "base")).asLong()), 0), cameras.size());
                String selectedFeed = tiles.stream().map(level::getBlockEntity).filter(MachineBlockEntity.class::isInstance).map(MachineBlockEntity.class::cast).map(e -> e.camera).filter(s -> !s.isEmpty()).findFirst().orElse("");
                if (!selectedFeed.isEmpty())
                {
                    if (selectedFeed.startsWith("id:"))
                    {
                        channel = -1;
                        for (int index = 0; index < cameras.size(); index++)
                            if (ShipSystems.deviceId(ship, cameras.get(index)).equals(selectedFeed.substring(3)))
                                channel = index;
                    }
                    else
                        try
                        {
                            channel = cameras.indexOf(BlockPos.of(Long.parseLong(selectedFeed)));
                        }
                        catch (NumberFormatException invalid)
                        {
                            channel = -1;
                        }
                }
                boolean power = !ShipSystems.enabled(ship) || channel < ShipSystems.power(level, ship, PowerBus.System.CAMERAS) && ShipSystems.active(ship, p);
                if (power && !cameras.isEmpty() && channel >= 0)
                {
                    var camera = cameras.get(channel);
                    var facing = level.getBlockState(camera).getValue(HorizontalDirectionalBlock.FACING);
                    var eye = Vec3.atCenterOf(camera).add(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(.65)).subtract(Vec3.atLowerCornerOf(WorldStore.pos(ship, "base")));
                    display.putLong("source", camera.asLong());
                    display.putInt("selection", channel);
                    display.putDouble("cameraX", eye.x);
                    display.putDouble("cameraY", eye.y);
                    display.putDouble("cameraZ", eye.z);
                    display.putFloat("cameraYaw", facing.toYRot());
                    display.putString("status", "LIVE");
                }
                else
                    display.putString("status", power ? "NO CAMERA" : "NO POWER");
                displays.add(display);
            });
            if (!displays.isEmpty())
            {
                boolean space = level.dimension().equals(TravelService.SPACE);
                data.putBoolean("universe", space);
                data.putLong("seed", SceneCatalog.get(level.getServer()).getLongOr("seed", 79419843));
                data.putString("observer", ship.getStringOr("id", ""));
                var subjects = new ListTag();
                if (space)
                    WorldStore.get(level.getServer()).ships().stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) && LocalSpaces.together(ship, t)).sorted(Comparator.comparingDouble(t -> DockingManeuver.sceneDistance(ship, t))).limit(SceneService.MAX_SUBJECTS).forEach(t -> SceneService.addSubject(subjects, level, t));
                else
                {
                    var source = SceneService.subject(level, ship);
                    source.putDouble("x", 0);
                    source.putDouble("y", 0);
                    source.putDouble("z", 0);
                    source.putDouble("yaw", 0);
                    source.putDouble("pitch", 0);
                    source.putDouble("roll", 0);
                    source.put("players", SceneFrames.players(level, WorldStore.pos(ship, "base"), new net.minecraft.world.phys.AABB(WorldStore.pos(ship, "controller")).inflate(48)));
                    subjects.add(source);
                }
                data.put("subjects", subjects);
                if (!space)
                {
                    PlanetSky.decorate(data, level, WorldStore.pos(ship, "base"));
                    data.put("terrain", TerrainService.collect(player, WorldStore.pos(ship, "controller"), WorldStore.pos(ship, "base")));
                    data.put("entities", TerrainService.entities(level, WorldStore.pos(ship, "base"), WorldStore.pos(ship, "controller")));
                }
                if (subjects.stream().anyMatch(t -> !((CompoundTag) t).getBooleanOr("ready", false)))
                    for (Tag t : displays)
                        ((CompoundTag) t).putString("status", "LOADING");
            }
        }
        data.put("displays", displays);
        SceneService.deliver(player, new Frame(UUID.nameUUIDFromBytes((System.identityHashCode(level.getServer()) + ":" + player.getUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8)), level.getServer().getTickCount(), data));
    }

    public static void cycleMonitor(ServerPlayer player, BlockPos console)
    {
        var ship = WorldStore.get(player.level().getServer()).at(player.level(), console);
        if (ship == null || TransferLocks.busy(player))
            return;
        if (!ship.contains("monitorChannels"))
            ship.put("monitorChannels", new CompoundTag());
        var channels = ship.getCompoundOrEmpty("monitorChannels");
        var key = Long.toString(console.subtract(WorldStore.pos(ship, "base")).asLong());
        channels.putInt(key, Math.floorMod(channels.getIntOr(key, 0) + 1, 10000));
        WorldStore.get(player.level().getServer()).requestSave();
        panels(player);
    }

    private ScenePanels()
    {
    }
}
