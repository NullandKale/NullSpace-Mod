package com.nullandkale.nullspace.gameplay.pads;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.gameplay.ship.ShipDiagnostics;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;

import com.google.gson.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

public final class PadComputers
{
    public static final Identifier ID = Identifier.parse("nullspace:pad_computer");
    public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, ID, new PadComputerBlock(BlockBehaviour.Properties.of().strength(3).setId(ResourceKey.create(Registries.BLOCK, ID))));
    private static final ServerContext.Key<Map<UUID, Long>> KEY = new ServerContext.Key<>();

    private static Map<UUID, Long> requests(net.minecraft.server.MinecraftServer server)
    {
        return ServerContext.get(server).service(KEY, HashMap::new);
    }

    public static void initialize()
    {
        Registry.register(BuiltInRegistries.ITEM, ID, new BlockItem(BLOCK, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix()));
        PadStatus.initialize();
        PadRefresh.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
        {
            dispatcher.register(Commands.literal("nullspace").then(Commands.literal("reload-pads").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).executes(ctx ->
            {
                PadRepository.get(ctx.getSource().getServer()).reload();
                return 1;
            })));
            var coordinates = Commands.argument("x", IntegerArgumentType.integer());
            var y = Commands.argument("y", IntegerArgumentType.integer());
            var z = Commands.argument("z", IntegerArgumentType.integer());
            z.then(Commands.literal("inspect").executes(ctx -> action(ctx.getSource().getPlayerOrException(), position(ctx), null)));
            z.then(Commands.literal("register").then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> action(ctx.getSource().getPlayerOrException(), position(ctx), StringArgumentType.getString(ctx, "name")))).executes(ctx -> action(ctx.getSource().getPlayerOrException(), position(ctx), "")));
            dispatcher.register(Commands.literal("nullspace").then(Commands.literal("pad").then(coordinates.then(y.then(z)))));
        });
    }

    private static BlockPos position(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx)
    {
        return new BlockPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"));
    }

    public static PadScanner.Result inspect(ServerPlayer player, BlockPos pos)
    {
        return inspect(player.level(), pos);
    }

    public static PadScanner.Result inspect(net.minecraft.server.level.ServerLevel level, BlockPos pos)
    {
        var facing = level.getBlockState(pos).getValue(PadComputerBlock.FACING);
        var result = PadScanner.scan((x, y, z) ->
        {
            BlockPos point = new BlockPos(x, y, z);
            if (!level.hasChunkAt(point))
                return PadScanner.Cell.UNLOADED;
            var state = level.getBlockState(point);
            if (state.is(Blocks.GRASS_BLOCK))
                return PadScanner.Cell.GRASS;
            if (state.is(Blocks.DIRT))
                return PadScanner.Cell.DIRT;
            if (state.is(Blocks.COBBLESTONE))
                return PadScanner.Cell.BORDER;
            return PadScanner.Cell.OTHER;
        }, pos.getX(), pos.getY(), pos.getZ(), facing.getStepX(), facing.getStepZ());
        if (result.valid())
        {
            int height = PadRegistry.buildHeight(level, result.pad());
            return new PadScanner.Result(result.pad(), "Valid pad: " + result.pad().width() + " x " + result.pad().depth() + ", ship clearance " + height + " blocks" + (height < 32 ? ". The registered pad above limits this build area; launch still needs clear" + " sky." : "."));
        }
        return result;
    }

    public static int action(ServerPlayer player, BlockPos pos, String name)
    {
        if (TransferLocks.busy(player))
            return 0;
        if (!player.level().hasChunkAt(pos) || !player.level().getBlockState(pos).is(BLOCK) || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64 || !player.mayInteract(player.level(), pos))
        {
            open(player, pos, "Cannot use this pad computer. Move within 8 blocks and check the computer still exists" + " and is accessible.");
            return 0;
        }
        long now = System.nanoTime();
        Long previous = requests(player.level().getServer()).put(player.getUUID(), now);
        if (previous != null && now - previous < 500_000_000L)
        {
            open(player, pos, "Please wait a moment before the next action");
            return 0;
        }
        var result = inspect(player, pos);
        String message = result.message();

        if (name != null && result.valid())
        {
            try
            {
                message = register(player.level(), pos, result.pad(), name);
            }
            catch (Exception error)
            {
                NullSpace.LOGGER.error("Could not save pad", error);
                message = "Could not save registration. No pad was registered.";
            }
        }
        open(player, pos, message);
        return result.valid() && (name == null || message.startsWith("Registered ")) ? 1 : 0;
    }

    public static void details(ServerPlayer player, BlockPos pos, boolean highlight)
    {
        var result = inspect(player, pos);
        if (highlight && result.problem() != null)
        {
            var point = result.problem();
            ShipDiagnostics.highlight(player, java.util.List.of(new BlockPos(point.x(), point.y(), point.z())));
        }
        String message = result.message();
        if (!highlight)
        {
            message += "\nComputer: " + pos.toShortString() + "\nDimension: " + player.level().dimension().identifier();
            var pad = PadRegistry.read(player.level().getServer()).stream().filter(p -> p.computer().equals(pos)).findFirst();
            if (pad.isPresent())
            {
                var state = PadRegistry.state(player.level().getServer(), pad.get());
                message += "\nRegistration: " + pad.get().name() + "\nState: " + state.state() + " " + state.detail();
            }
        }
        open(player, pos, message);
    }

    public static String register(net.minecraft.server.level.ServerLevel level, BlockPos pos, PadScanner.Pad pad, String name) throws java.io.IOException
    {
        return register(level, pos, pad, name, true);
    }

    /**
     * Additive fixtures keep unavailable registrations so existing user test cases survive
     * generation.
     */
    public static String register(net.minecraft.server.level.ServerLevel level, BlockPos pos, PadScanner.Pad pad, String name, boolean pruneMissing) throws java.io.IOException
    {
        name = name.strip();
        if (!name.matches("[A-Za-z0-9 _-]{1,32}"))
            return "Use 1-32 letters, numbers, spaces, underscores or hyphens";
        var repository = PadRepository.get(level.getServer());
        var pads = new ArrayList<>(repository.all());
        PadRegistry.Pad existing = null;
        String dimension = level.dimension().identifier().toString();
        for (var iterator = pads.iterator(); iterator.hasNext();)
        {
            var entry = iterator.next();
            if (pruneMissing && entry.dimension().equals(dimension) && level.hasChunkAt(entry.computer()) && !level.getBlockState(entry.computer()).is(BLOCK))
            {
                iterator.remove();
                continue;
            }
            if (entry.dimension().equals(dimension) && entry.computer().equals(pos))
            {
                existing = entry;
                continue;
            }
            if (entry.name().equalsIgnoreCase(name))
                return "A pad already has that name. Choose another name, or rename that pad first.";
            if (entry.dimension().equals(dimension) && pad.overlaps(entry.bounds()))
                return "This ring overlaps another registered pad at the same height. Leave a gap between" + " their cobblestone rings.";
        }
        if (existing != null)
            pads.remove(existing);
        pads.add(new PadRegistry.Pad(existing == null ? UUID.randomUUID().toString() : existing.id(), name, dimension, pos.immutable(), pad));
        repository.replace(pads);
        PadRegistry.reconcileHeights(level);
        BuildPreviews.registrationsChanged(level);
        return "Registered " + name + " (" + pad.width() + " x " + pad.depth() + ")";
    }

    public static void open(ServerPlayer player, BlockPos pos, String message)
    {
        String name = PadRegistry.read(player.level().getServer()).stream().filter(p -> p.dimension().equals(player.level().dimension().identifier().toString()) && p.computer().equals(pos)).map(PadRegistry.Pad::name).findFirst().orElse("");
        if (name.isBlank())
            name = suggestedName(player);
        ComputerTerminal.send(player, pos, message + "\nPreviews: pad " + (BuildPreviews.enabled(player, pos, false) ? "ON" : "OFF") + " | ship " + (BuildPreviews.enabled(player, pos, true) ? "ON" : "OFF"), "pad", name);
    }

    public static void preview(ServerPlayer player, BlockPos pos, boolean ship)
    {
        try
        {
            open(player, pos, BuildPreviews.toggle(player, pos, ship));
        }
        catch (IllegalArgumentException invalid)
        {
            open(player, pos, invalid.getMessage());
        }
    }

    public static String suggestedName(ServerPlayer player)
    {
        var names = PadRegistry.read(player.level().getServer()).stream().map(p -> p.name().toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        if (!names.contains("home"))
            return "Home";
        int i = 2;
        while (names.contains("landing pad " + i))
            i++;
        return "Landing pad " + i;
    }

    private PadComputers()
    {
    }
}
