package com.nullandkale.nullspace.gameplay.sound;

import com.nullandkale.nullspace.model.sound.SoundCues;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import com.mojang.brigadier.arguments.StringArgumentType;

/** {@code /nullspace sound <cue>}: hear a cue yourself, as it plays at the listener. Only you hear it, so anyone may use it. */
public final class SoundCommand
{
    public static void initialize()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("sound").executes(c ->
        {
            c.getSource().sendSuccess(() -> Component.literal("Cues: " + String.join(", ", SoundCues.names().stream().sorted().toList())), false);
            return 1;
        }).then(Commands.argument("cue", StringArgumentType.string()).suggests((c, b) -> SharedSuggestionProvider.suggest(SoundCues.names(), b)).executes(c ->
        {
            String cue = StringArgumentType.getString(c, "cue");
            if (SoundCues.get(cue).isEmpty())
            {
                c.getSource().sendFailure(Component.literal("No cue called " + cue + ". Cues: " + String.join(", ", SoundCues.names().stream().sorted().toList())));
                return 0;
            }
            ShipSounds.player(c.getSource().getPlayerOrException(), cue);
            c.getSource().sendSuccess(() -> Component.literal("Playing " + cue), false);
            return 1;
        })))));
    }

    private SoundCommand()
    {
    }
}
