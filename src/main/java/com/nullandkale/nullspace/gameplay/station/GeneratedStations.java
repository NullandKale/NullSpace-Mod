package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.StationConstruction;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationPlanner;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;
import com.nullandkale.nullspace.worldgen.stations.StationStructure;

import java.nio.charset.StandardCharsets;
import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;

/**
 * Builds a planned, furnished station in space beside the player's ship, so it can be seen and
 * flown to in the game itself. It goes through the same construction queue as every other station;
 * the rooms' frames, stands, paintings and animals appear when the build finishes.
 *
 * <pre>
 * /nullspace station new &lt;maker&gt; &lt;purpose&gt; &lt;size&gt; [seed]
 * </pre>
 */
public final class GeneratedStations
{
    public static void initialize()
    {
        var seeded = Commands.argument("seed", LongArgumentType.longArg()).executes(ctx -> run(ctx, true));
        var size = Commands.argument("size", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(StationSpec.Size.values()), builder)).executes(ctx -> run(ctx, false)).then(seeded);
        var purpose = Commands.argument("purpose", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(StationSpec.Purpose.values()), builder)).then(size);
        var maker = Commands.argument("maker", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(Maker.values()), builder)).then(purpose);
        var literal = Commands.literal("new").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(maker);
        var orbital = Commands.literal("orbital").executes(GeneratedStations::listOrbital);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("station").then(literal).then(orbital))));
    }

    /** Lists the zones of this world that have an orbital station, so one can be flown to. */
    private static int listOrbital(CommandContext<CommandSourceStack> ctx)
    {
        var server = ctx.getSource().getServer();
        var galaxy = com.nullandkale.nullspace.gameplay.navigation.StarSystems.galaxy(server);
        long seed = server.overworld().getSeed();
        int count = 0;
        for (var zone : galaxy.zones())
            if (com.nullandkale.nullspace.worldgen.encounters.OrbitalStations.placed(seed, galaxy, zone))
            {
                var spec = com.nullandkale.nullspace.worldgen.encounters.OrbitalStations.spec(seed, zone);
                var place = LocalSpaces.get(server, zone);
                String where = place == null ? zone : place.systemName() + " / " + place.name();
                ctx.getSource().sendSuccess(() -> Component.literal(where + ": " + spec.description()), false);
                count++;
            }
        int total = count;
        ctx.getSource().sendSuccess(() -> Component.literal(total + " zones have an orbital station; it is built when you arrive there."), false);
        return count;
    }

    private static List<String> ids(Enum<?>[] values)
    {
        return Arrays.stream(values).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
    }

    private static int run(CommandContext<CommandSourceStack> ctx, boolean seeded) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var player = ctx.getSource().getPlayerOrException();
        try
        {
            long seed = seeded ? LongArgumentType.getLong(ctx, "seed") : player.level().getServer().overworld().getRandom().nextLong();
            var spec = StationSpec.parse(StringArgumentType.getString(ctx, "maker"), StringArgumentType.getString(ctx, "purpose"), StringArgumentType.getString(ctx, "size"), "operational", seed);
            generated(player, spec);
            player.sendSystemMessage(Component.literal("Building " + spec.description() + " (seed " + seed + ") beside your ship. It appears over the next few seconds; use Docking at the helm to fly to a port."));
            return 1;
        }
        catch (RuntimeException failure)
        {
            player.sendSystemMessage(Component.literal("Could not build the station: " + ShipMessages.failure(failure)));
            return 0;
        }
    }

    /** Plans, furnishes and queues the station; the same brief at the same place is the same station. */
    public static CompoundTag generated(ServerPlayer p, StationSpec spec)
    {
        var own = SceneService.observer(p);
        if (own == null)
            throw new IllegalArgumentException("Use your helm in space");
        var server = p.level().getServer();
        String address = LocalSpaces.address(own);
        String id = UUID.nameUUIDFromBytes(("nullspace:generated:" + spec.description() + ":" + spec.seed() + ":" + address).getBytes(StandardCharsets.UTF_8)).toString();
        var existing = WorldStore.get(server).repository().find(id);
        if (existing != null)
            return existing;
        var blueprint = StationStructure.build(StationPlanner.generate(spec));
        var metadata = new CompoundTag();
        metadata.putBoolean("generatedRooms", true);
        metadata.put("stationSpec", spec.save());
        String name = spec.maker().label + " " + spec.purpose().name().charAt(0) + spec.purpose().name().substring(1).toLowerCase(Locale.ROOT) + " Station";
        var result = StationBuilds.queue(server, id, name, address, StationConstruction.constructionSite(server, own), "", blueprint, false, metadata);
        if (result == null)
            throw new IllegalArgumentException("No clear space for a station of this size here");
        WorldStore.get(server).requestSave();
        return result;
    }

    private GeneratedStations()
    {
    }
}
