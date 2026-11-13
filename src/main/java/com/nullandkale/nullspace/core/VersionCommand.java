package com.nullandkale.nullspace.core;

import com.nullandkale.nullspace.NullSpace;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Reports the installed mod's metadata, including CI provenance when available. */
public final class VersionCommand
{
    public static String description()
    {
        var metadata = FabricLoader.getInstance().getModContainer(NullSpace.MOD_ID).orElseThrow().getMetadata();
        var label = metadata.containsCustomValue("nullspace:build") ? metadata.getCustomValue("nullspace:build").getAsString() : "unknown build";
        var commit = metadata.containsCustomValue("nullspace:commit") ? metadata.getCustomValue("nullspace:commit").getAsString() : "unknown";
        return "NullSpace " + metadata.getVersion().getFriendlyString() + " (" + label + (commit.equals("unknown") ? "" : ", commit " + commit.substring(0, Math.min(12, commit.length()))) + ")";
    }

    public static void initialize()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("version").executes(context ->
        {
            context.getSource().sendSuccess(() -> Component.literal(description()), false);
            return 1;
        }))));
    }

    private VersionCommand()
    {
    }
}
