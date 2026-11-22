package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.synth.SynthBlockEntity.Cue;
import com.nullandkale.nullspace.content.synth.Synths;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.sound.Hearing;
import com.nullandkale.nullspace.gameplay.sound.ShipAmbience;
import com.nullandkale.nullspace.gameplay.systems.AirSounds;
import com.nullandkale.nullspace.gameplay.systems.EngineSounds;
import com.nullandkale.nullspace.gameplay.systems.RoomAcoustics;
import com.nullandkale.nullspace.model.sound.SoundCues;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Every sound cue on a stand, in a lit hall for listening. {@code /nullspace shipyard sounds} builds it. Each stand is a Synth block that plays its cue exactly
 * as the game does (every layer, and the live values the game would give it), with a button on top for a sound that plays once or a lever for one that loops,
 * and a sign saying what it is. The north row has every cue in groups, with the live values that matter side by side (a small leak and a rushing one, light
 * and full thrust, the alarm at the threshold and with almost no air), then the Minecraft sounds the game borrows for its own things (ship doors, suits, a
 * burning wreck). The south row plays sounds clear, through a closed door, through two and through a wall, as the hearing rules muffle them, and an explosion
 * clear and through a hull. A stand's screen shows the cue's patch; changing it there makes the stand an ordinary Synth to tune from.
 * The hall is rebuilt in the same place each visit.
 */
public final class SoundGallery
{
    public static final int PITCH = 3, GAP = 3, MARGIN = 4, DEPTH = 15, HEIGHT = 7;
    /** Where the rows stand, against the north and south walls. */
    public static final int NORTH_Z = 1, SOUTH_Z = DEPTH - 2;

    /** One stand: its group, its sign's two middle lines, and what it plays. */
    public record Stand(String group, DyeColor colour, String name, String detail, Cue cue)
    {
        boolean loop()
        {
            var first = SoundCues.preset(cue.name());
            return first != null && first.loop();
        }
    }

    private static Cue cue(String name, float volume, float pitch, float brightness, float rate, float muffle)
    {
        return new Cue(name, volume, pitch, brightness, rate, muffle);
    }

    private static Cue engine(double thrust, double speed, float muffle)
    {
        var hum = EngineSounds.hum(Vec3.ZERO, thrust, speed);
        return cue("engine.hum", hum.volume(), hum.pitch(), hum.brightness(), 1, muffle);
    }

    private static Cue breach(int amount)
    {
        return cue(AirSounds.breachCue(amount), AirSounds.breachVolume(amount), 1, AirSounds.breachBrightness(amount), 1, 0);
    }

    /** The north row: every cue, grouped, with the live values the game gives it. */
    public static List<Stand> cues()
    {
        var out = new ArrayList<Stand>();
        out.add(new Stand("TRAVEL", DyeColor.LIGHT_BLUE, "ftl.jolt", "each jump step", new Cue("ftl.jolt")));
        out.add(new Stand("TRAVEL", DyeColor.LIGHT_BLUE, "ftl.drone", "while cruising", new Cue("ftl.drone")));
        out.add(new Stand("AIR", DyeColor.CYAN, "air.hiss", "small leak", breach(10)));
        out.add(new Stand("AIR", DyeColor.CYAN, "air.hiss", "strong leak", breach(50)));
        out.add(new Stand("AIR", DyeColor.CYAN, "air.leak", "rushing breach", breach(150)));
        out.add(new Stand("AIR", DyeColor.CYAN, "air.refill", "room filling", cue("air.refill", AirSounds.refillVolume(50), 1, AirSounds.refillBrightness(50), 1, 0)));
        out.add(new Stand("AIR", DyeColor.CYAN, "air.refill", "nearly full", cue("air.refill", AirSounds.refillVolume(90), 1, AirSounds.refillBrightness(90), 1, 0)));
        out.add(new Stand("ALARMS", DyeColor.RED, "air.suit", "on suit air", cue("air.suit", AirSounds.SUIT_VOLUME, 1, 0, 1, 0)));
        out.add(new Stand("ALARMS", DyeColor.RED, "alarm.lowair", "air " + (AirSounds.DYING - 1) + "%", cue("alarm.lowair", AirSounds.ALARM_VOLUME, 1, 0, AirSounds.alarmRate(AirSounds.DYING - 1), 0)));
        out.add(new Stand("ALARMS", DyeColor.RED, "alarm.lowair", "air 2%", cue("alarm.lowair", AirSounds.ALARM_VOLUME, 1, 0, AirSounds.alarmRate(2), 0)));
        out.add(new Stand("ENGINES", DyeColor.ORANGE, "engine.hum", "light thrust", engine(.1, 0, 0)));
        out.add(new Stand("ENGINES", DyeColor.ORANGE, "engine.hum", "half thrust", engine(.5, .5, 0)));
        out.add(new Stand("ENGINES", DyeColor.ORANGE, "engine.hum", "full, at speed", engine(1, 1, 0)));
        out.add(new Stand("DOCKING", DyeColor.YELLOW, "dock.clamp", "locking on", new Cue("dock.clamp")));
        out.add(new Stand("DOCKING", DyeColor.YELLOW, "dock.release", "undocking", new Cue("dock.release")));
        out.add(new Stand("DOCKING", DyeColor.YELLOW, "rcs.puff", "maneuvering jet", new Cue("rcs.puff")));
        out.add(new Stand("COMBAT", DyeColor.MAGENTA, "weapon.fire", "ship's weapon", new Cue("weapon.fire")));
        out.add(new Stand("COMBAT", DyeColor.MAGENTA, "turret.fire", "turret shot", new Cue("turret.fire")));
        out.add(new Stand("COMBAT", DyeColor.MAGENTA, "hull.impact", "hit on the hull", new Cue("hull.impact")));
        out.add(new Stand("AMBIENCE", DyeColor.LIGHT_GRAY, "ship.hum", "aboard in space", cue("ship.hum", ShipAmbience.VOLUME, 1, 0, 1, 0)));
        // Minecraft's sounds that the game chooses for its own things, played as the game plays them.
        out.add(new Stand("BORROWED", DyeColor.GREEN, "ship door", "iron, opening", recording("minecraft:block.iron_trapdoor.open", 1, 1, 0)));
        out.add(new Stand("BORROWED", DyeColor.GREEN, "ship door", "iron, closing", recording("minecraft:block.iron_trapdoor.close", 1, 1, 0)));
        out.add(new Stand("BORROWED", DyeColor.GREEN, "ship door", "wood, opening", recording("minecraft:block.wooden_trapdoor.open", 1, 1, 0)));
        out.add(new Stand("BORROWED", DyeColor.GREEN, "ship door", "wood, closing", recording("minecraft:block.wooden_trapdoor.close", 1, 1, 0)));
        out.add(new Stand("BORROWED", DyeColor.GREEN, "space suit", "putting it on", recording("minecraft:item.armor.equip_iron", 1, 1, 0)));
        out.add(new Stand("BORROWED", DyeColor.GREEN, "armored suit", "putting it on", recording("minecraft:item.armor.equip_netherite", 1, 1, 0)));
        out.add(new Stand("BORROWED", DyeColor.GREEN, "wreck", "burning", recording("minecraft:block.fire.ambient", com.nullandkale.nullspace.content.WreckBlockEntity.FIRE_VOLUME, 1, 0)));
        out.add(new Stand("BORROWED", DyeColor.GREEN, "golden tree", "picking fruit", recording("minecraft:block.sweet_berry_bush.pick_berries", 1, 1, 0)));
        out.add(new Stand("BORROWED", DyeColor.GREEN, "green fluid", "filling a bucket", recording("minecraft:item.bucket.fill", 1, 1, 0)));
        return out;
    }

    /** How the client plays an explosion: four times as loud as a block's sound, lower. */
    static final float BLAST_VOLUME = 4, BLAST_PITCH = .7f;

    private static Cue recording(String sound, float volume, float pitch, float muffle)
    {
        return new Cue(sound, volume, pitch, 0, 1, muffle);
    }

    /**
     * The south row: a loop and a one-shot clear, through a closed door, through two and through a wall; a ship door (one of Minecraft's sounds, which can only
     * be made quieter) the same way; and an explosion, clear and as the muffled recording heard through a hull or a door.
     */
    public static List<Stand> hearing()
    {
        String[] where = {"clear", "closed door", "two doors", "a wall"};
        float[] muffle = {Hearing.rooms(0), Hearing.rooms(RoomAcoustics.DOOR), Hearing.rooms(2 * RoomAcoustics.DOOR), Hearing.rooms(RoomAcoustics.WALL)};
        var out = new ArrayList<Stand>();
        for (int i = 0; i < where.length; i++)
            out.add(new Stand("HEARING ENGINE", DyeColor.WHITE, "engine.hum full", where[i], engine(1, 1, muffle[i])));
        for (int i = 0; i < where.length; i++)
            out.add(new Stand("HEARING HIT", DyeColor.WHITE, "hull.impact", where[i], cue("hull.impact", 1, 1, 0, 1, muffle[i])));
        for (int i = 0; i < where.length; i++)
            out.add(new Stand("HEARING DOOR", DyeColor.WHITE, "ship door shut", where[i], recording("minecraft:block.iron_trapdoor.close", 1, 1, muffle[i])));
        out.add(new Stand("HEARING BLAST", DyeColor.WHITE, "explosion", "clear", recording("minecraft:entity.generic.explode", BLAST_VOLUME, BLAST_PITCH, 0)));
        out.add(new Stand("HEARING BLAST", DyeColor.WHITE, "explosion", "through a hull", recording("nullspace:muffled_explosion", BLAST_VOLUME, BLAST_PITCH, 0)));
        return out;
    }

    public static void initialize()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.literal("sounds").executes(ctx ->
        {
            var player = ctx.getSource().getPlayerOrException();
            try
            {
                var server = player.level().getServer();
                var origin = build(server);
                player.teleportTo(server.overworld(), origin.getX() + 2.5, origin.getY() + 1, origin.getZ() + DEPTH / 2.0, Set.of(), -90, 10, true);
                player.sendSystemMessage(Component.literal("Sound gallery, going east. North wall: every cue as the game plays it, in groups (travel, air, alarms, engines, docking, combat, ambience), then the Minecraft sounds the game borrows. South wall: the full engine, a hull hit and a ship door, clear, through a closed door, through two and through a wall, then an explosion clear and through a hull. Buttons play once; levers start and stop a loop. A synth stand's screen shows its patch; change it there to tune from it."));
                return 1;
            }
            catch (Exception error)
            {
                com.nullandkale.nullspace.NullSpace.LOGGER.error("The sound gallery failed", error);
                player.sendSystemMessage(Component.literal("Could not build the sound gallery: " + ShipMessages.failure(error)));
                return 0;
            }
        })))));
    }

    /** The x of each stand along a row, from the hall's west wall: a gap between groups. */
    static int[] columns(List<Stand> row)
    {
        var x = new int[row.size()];
        int at = MARGIN;
        for (int i = 0; i < row.size(); i++)
        {
            if (i > 0 && !row.get(i).group().equals(row.get(i - 1).group()))
                at += GAP;
            x[i] = at;
            at += PITCH;
        }
        return x;
    }

    /** The hall's length, east to west. */
    public static int width()
    {
        var northX = columns(cues());
        return northX[northX.length - 1] + MARGIN + 1;
    }

    /**
     * The x of each hearing stand: the row is centred, across from the engines, and laid out east to west so that it reads from left to right for someone
     * facing it from the aisle.
     */
    public static int[] hearingColumns()
    {
        var x = columns(hearing());
        int span = x[x.length - 1] - x[0], start = (width() - 1 - span) / 2;
        var out = new int[x.length];
        for (int i = 0; i < x.length; i++)
            out[i] = start + x[x.length - 1] - x[i];
        return out;
    }

    /** Builds the hall and its stands, replacing any earlier one; returns its south-west corner. */
    public static BlockPos build(MinecraftServer server)
    {
        var level = server.overworld();
        var north = cues();
        var south = hearing();
        var northX = columns(north);
        int width = width();
        int x = 1024 + 4400, z = 2048;
        for (int cx = x >> 4; cx <= (x + width) >> 4; cx++)
            for (int cz = z >> 4; cz <= (z + DEPTH) >> 4; cz++)
                level.getChunk(cx, cz);
        // The first visit finds the ground; later ones build in the same place (the hall's own roof would otherwise count as ground).
        var saved = com.nullandkale.nullspace.core.save.WorldStore.get(server).section("soundGallery");
        int y = saved.getIntOr("y", Integer.MIN_VALUE);
        if (y == Integer.MIN_VALUE)
        {
            y = level.getMinY() + 40;
            for (int dx = 0; dx < width; dx += 2)
                for (int dz = 0; dz < DEPTH; dz += 2)
                    y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz) + 4);
            if (y + HEIGHT + 4 >= level.getMaxY())
                throw new IllegalArgumentException("No room above the terrain for a gallery.");
            saved.putInt("y", y);
            com.nullandkale.nullspace.core.save.WorldStore.get(server).requestSave();
        }
        var origin = new BlockPos(x, y, z);
        // Stop what an earlier visit left playing, before its blocks go.
        for (int dx = 0; dx < width; dx++)
            for (int dz : new int[]{NORTH_Z, SOUTH_Z, 2, DEPTH - 3})
                if (level.getBlockEntity(origin.offset(dx, 1, dz)) instanceof com.nullandkale.nullspace.content.synth.SynthBlockEntity)
                    Synths.stop(level, origin.offset(dx, 1, dz));
        // A closed hall: floor, walls and a roof with lamps, so nothing outside is heard over the stands.
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < DEPTH; dz++)
                for (int dy = 0; dy <= HEIGHT; dy++)
                {
                    boolean edge = dx == 0 || dz == 0 || dx == width - 1 || dz == DEPTH - 1;
                    boolean lamp = dx % 4 == 2 && dz % 4 == 3;
                    var block = dy == 0 ? Blocks.CONCRETE.lightGray() : dy == HEIGHT ? (lamp ? Blocks.SEA_LANTERN : Blocks.STONE_BRICKS) : edge ? Blocks.STONE_BRICKS : Blocks.AIR;
                    level.setBlock(origin.offset(dx, dy, dz), block.defaultBlockState(), 18);
                }
        // The roof lamps alone leave the floor dim: invisible light blocks over the aisle.
        for (int dx = 2; dx < width - 1; dx += 4)
            for (int dz : new int[]{3, DEPTH / 2, DEPTH - 4})
                level.setBlock(origin.offset(dx, 4, dz), Blocks.LIGHT.defaultBlockState(), 18);
        for (int i = 0; i < north.size(); i++)
            stand(level, origin.offset(northX[i], 1, NORTH_Z), Direction.SOUTH, north.get(i));
        var southX = hearingColumns();
        for (int i = 0; i < south.size(); i++)
            stand(level, origin.offset(southX[i], 1, SOUTH_Z), Direction.NORTH, south.get(i));
        return origin;
    }

    /** A Synth against the wall facing the aisle, playing its cue, a button (once) or lever (loop) on top, and its sign on the wall above. */
    private static void stand(ServerLevel level, BlockPos at, Direction facing, Stand stand)
    {
        level.setBlock(at, ModBlocks.SYNTH.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing), 18);
        Synths.cue(level, at, stand.cue());
        var control = stand.loop() ? Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, facing) : Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR).setValue(ButtonBlock.FACING, facing);
        level.setBlock(at.above(), control, 18);
        var sign = at.above(2);
        level.setBlock(sign, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.WallSignBlock.FACING, facing), 18);
        var text = net.minecraft.world.level.block.entity.SignText.EMPTY.asMutable().setColor(stand.colour()).setTextGlowing(true);
        String[] lines = {stand.group().startsWith("HEARING") ? "HEARING" : stand.group(), stand.name(), stand.detail(), stand.loop() ? "lever: on/off" : "button: once"};
        for (int i = 0; i < lines.length; i++)
            text.setLine(i, Component.literal(lines[i]));
        var entity = (net.minecraft.world.level.block.entity.SignBlockEntity) level.getBlockEntity(sign);
        entity.setText(text.asImmutable(), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
        entity.setWaxed(true);
    }

    private SoundGallery()
    {
    }
}
