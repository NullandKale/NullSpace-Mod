package com.nullandkale.nullspace.content.synth;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.sound.ShipSounds;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.model.sound.SoundCues;
import com.nullandkale.nullspace.model.sound.SynthPatch;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.Vec3;

/**
 * The Synth block's behaviour, shared by its screen, the {@code ship synth} terminal command and redstone: change its patch, load a cue as a preset, play,
 * stop. A one-shot patch plays once; a loop plays until it is stopped (or, from redstone, while the signal is on). Aboard a ship it needs the ship's controls
 * to have power, a battery's included, and to be switched on in the ship's systems; elsewhere it plays like a note block.
 */
public final class Synths
{
    public static final BlockEntityType<SynthBlockEntity> ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.parse("nullspace:synth"), new BlockEntityType<>(SynthBlockEntity::new, Set.of(ModBlocks.SYNTH)));

    /** The Synth blocks playing a loop, by level and position. */
    private static final ServerContext.Key<Set<Placed>> PLAYING = new ServerContext.Key<>();

    private record Placed(ServerLevel level, BlockPos pos)
    {}

    public static void initialize()
    {
        ShipSounds.producer(Synths::loops);
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) ->
        {
            if (entity instanceof SynthBlockEntity synth && synth.playing())
                playing(level.getServer()).add(new Placed(level, entity.getBlockPos().immutable()));
        });
    }

    private static Set<Placed> playing(MinecraftServer server)
    {
        return ServerContext.get(server).service(PLAYING, HashSet::new);
    }

    static SynthBlockEntity at(ServerLevel level, BlockPos pos)
    {
        if (!(level.getBlockEntity(pos) instanceof SynthBlockEntity synth))
            throw new IllegalArgumentException("That Synth is gone");
        return synth;
    }

    /** Why the Synth at {@code pos} cannot sound now, or empty when it can. */
    public static String silence(ServerLevel level, BlockPos pos)
    {
        var ship = WorldStore.get(level.getServer()).at(level, pos);
        if (ship == null || !ShipSystems.enabled(ship))
            return "";
        if (!ShipSystems.active(ship, pos))
            return "This Synth is switched off in the ship's systems";
        if (ShipSystems.power(level, ship, PowerBus.System.CONTROLS) <= 0)
            return "No power for the ship's controls";
        return "";
    }

    /** Applies settings in the patch's text form ({@code tone square 880 440 0.4}, {@code noise 0.3 0.8}...). */
    public static String apply(ServerLevel level, BlockPos pos, String text)
    {
        var synth = at(level, pos);
        synth.patch(synth.patch().with(text));
        return "Set: " + synth.patch().text();
    }

    /** Loads a cue's patch as the block's own. */
    public static String preset(ServerLevel level, BlockPos pos, String cue)
    {
        var patch = SoundCues.preset(cue);
        if (patch == null)
            throw new IllegalArgumentException("No cue called " + cue + ". Cues: " + String.join(", ", SoundCues.names()));
        at(level, pos).patch(patch);
        return "Loaded " + cue;
    }

    /** Plays the block's patch: once, or as a loop until stopped. */
    public static String play(ServerLevel level, BlockPos pos)
    {
        var synth = at(level, pos);
        String silent = silence(level, pos);
        if (!silent.isEmpty())
            throw new IllegalArgumentException(silent);
        if (synth.patch().loop())
        {
            synth.playing(true);
            playing(level.getServer()).add(new Placed(level, pos.immutable()));
            return "Playing (loop)";
        }
        ShipSounds.play(level, Vec3.atCenterOf(pos), "", synth.patch(), 1, 1);
        return "Played";
    }

    /** Plays a named cue once from the block, leaving its patch as it is. */
    public static String playCue(ServerLevel level, BlockPos pos, String cue)
    {
        at(level, pos);
        if (SoundCues.get(cue).isEmpty())
            throw new IllegalArgumentException("No cue called " + cue + ". Cues: " + String.join(", ", SoundCues.names()));
        String silent = silence(level, pos);
        if (!silent.isEmpty())
            throw new IllegalArgumentException(silent);
        ShipSounds.play(level, Vec3.atCenterOf(pos), cue);
        return "Played " + cue;
    }

    public static String stop(ServerLevel level, BlockPos pos)
    {
        var synth = at(level, pos);
        synth.playing(false);
        playing(level.getServer()).remove(new Placed(level, pos.immutable()));
        return "Stopped";
    }

    /** A redstone signal changed: a one-shot plays as it turns on, a loop plays while it is on. */
    static void redstone(ServerLevel level, BlockPos pos, boolean on)
    {
        var synth = at(level, pos);
        if (synth.powered() == on)
            return;
        synth.powered(on);
        try
        {
            if (on)
                play(level, pos);
            else if (synth.patch().loop())
                stop(level, pos);
        }
        catch (IllegalArgumentException silent)
        {
            // Without power the pulse does nothing, as a note block does nothing without a signal.
        }
    }

    private static void loops(MinecraftServer server, java.util.function.Consumer<ShipSounds.Loop> out)
    {
        for (var placed : List.copyOf(playing(server)))
        {
            if (!placed.level().hasChunkAt(placed.pos()) || !(placed.level().getBlockEntity(placed.pos()) instanceof SynthBlockEntity synth) || !synth.playing())
            {
                playing(server).remove(placed);
                continue;
            }
            if (!silence(placed.level(), placed.pos()).isEmpty())
                continue;
            out.accept(new ShipSounds.Loop("synth:" + placed.level().dimension().identifier() + ":" + placed.pos().asLong(), "", synth.patch(), placed.level(), Vec3.atCenterOf(placed.pos()), null, 1, 1, 0, 1));
        }
    }

    /** Opens the block's screen. */
    public static void open(ServerPlayer player, BlockPos pos, String message)
    {
        ComputerTerminal.send(player, pos, message, "synth", "");
    }

    /** The screen's actions. */
    public static void action(ServerPlayer player, BlockPos pos, ComputerTerminal.Action action, String argument)
    {
        var level = player.level();
        String notice;
        try
        {
            notice = switch (action)
            {
                case SYNTH_SET -> apply(level, pos, argument);
                case SYNTH_PRESET -> preset(level, pos, argument);
                case SYNTH_PLAY -> play(level, pos);
                case SYNTH_STOP -> stop(level, pos);
                default -> throw new IllegalArgumentException("Not a Synth action");
            };
        }
        catch (IllegalArgumentException refused)
        {
            notice = refused.getMessage();
        }
        open(player, pos, notice);
    }

    /**
     * {@code ship synth ...} at a ship terminal: with no words, lists the ship's Synth blocks; a first word that is a number picks one of them (else the one
     * nearest the terminal); then {@code play [cue]}, {@code stop}, {@code preset <cue>}, {@code dump}, {@code cues}, or settings ({@code tone ...},
     * {@code noise ...}, {@code env ...}, {@code pulse ...}, {@code loop on|off}).
     */
    public static String command(ServerLevel level, CompoundTag ship, BlockPos terminal, List<String> words)
    {
        var synths = new ArrayList<>(ShipSystems.connected(level, ship).stream().filter(p -> level.getBlockState(p).is(ModBlocks.SYNTH)).toList());
        synths.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(terminal)).thenComparingLong(BlockPos::asLong));
        var base = WorldStore.pos(ship, "base");
        if (words.isEmpty())
        {
            if (synths.isEmpty())
                return "No Synth blocks on this ship";
            var lines = new ArrayList<String>();
            for (int i = 0; i < synths.size(); i++)
            {
                var p = synths.get(i);
                var synth = at(level, p);
                var local = p.subtract(base);
                lines.add((i + 1) + ": at " + local.getX() + " " + local.getY() + " " + local.getZ() + (synth.playing() ? " playing" : "") + " | " + synth.patch().text());
            }
            return String.join("\n", lines);
        }
        var rest = new ArrayList<>(words);
        if (rest.getFirst().equals("cues"))
            return String.join(", ", SoundCues.names());
        int index = 0;
        if (rest.getFirst().matches("\\d+"))
        {
            index = Integer.parseInt(rest.removeFirst()) - 1;
            if (rest.isEmpty())
                throw new IllegalArgumentException("ship synth " + (index + 1) + " play | stop | preset <cue> | dump | tone ... | noise ... | env ... | pulse ... | loop on|off");
        }
        if (synths.isEmpty())
            throw new IllegalArgumentException("No Synth blocks on this ship");
        if (index < 0 || index >= synths.size())
            throw new IllegalArgumentException("This ship has " + synths.size() + " Synth block(s)");
        var pos = synths.get(index);
        String verb = rest.getFirst().toLowerCase(Locale.ROOT);
        return switch (verb)
        {
            case "play" -> rest.size() > 1 ? playCue(level, pos, rest.get(1)) : play(level, pos);
            case "stop" -> stop(level, pos);
            case "preset" ->
            {
                if (rest.size() < 2)
                    throw new IllegalArgumentException("ship synth preset <cue>");
                yield preset(level, pos, rest.get(1));
            }
            case "dump" -> at(level, pos).patch().text();
            case "tone", "noise", "env", "pulse", "loop", "wave", "from", "to", "sweep", "tonelevel", "noiselevel", "bright", "attack", "hold", "release", "rate", "duty" -> apply(level, pos, String.join(" ", rest));
            default -> throw new IllegalArgumentException("ship synth play | stop | preset <cue> | dump | cues | tone ... | noise ... | env ... | pulse ... | loop on|off");
        };
    }

    /** What the screen shows: the patch and whether it plays, as catalog entries. */
    public static List<com.nullandkale.nullspace.ui.terminal.TerminalPackets.Destination> view(ServerLevel level, BlockPos pos)
    {
        if (!(level.getBlockEntity(pos) instanceof SynthBlockEntity synth))
            return List.of();
        return List.of(new com.nullandkale.nullspace.ui.terminal.TerminalPackets.Destination("patch", synth.patch().text(), synth.playing() ? "PLAYING" : "IDLE", silence(level, pos)));
    }

    private Synths()
    {
    }
}
