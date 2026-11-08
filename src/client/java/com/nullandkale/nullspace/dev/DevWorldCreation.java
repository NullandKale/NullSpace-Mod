package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.gameplay.start.WorldStartSettings;
import com.nullandkale.nullspace.ui.screen.WorldStartAccess;

import java.io.*;
import java.nio.file.Files;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;

/** Development launcher only: use the real creation UI and remember its actual save folder. */
public final class DevWorldCreation
{
    private static String name, ship;
    private static boolean opening;
    private static CreateWorldScreen selected;

    public static void initialize()
    {
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
        {
            if (!opening || !(screen instanceof CreateWorldScreen create))
                return;
            opening = false;
            selected = create;
            create.getUiState().setName(name);
            ((WorldStartAccess) create).nullspace$start(new WorldStartSettings(true, ship));
        });
        String requested = System.getProperty("nullspace.dev.createWorld", "");
        if (requested.isBlank())
            return;
        ClientTickEvents.END_CLIENT_TICK.register(client ->
        {
            if (name == null && client.gui.screen() instanceof TitleScreen)
                open(client, requested, System.getProperty("nullspace.dev.startShip", "halcyon_horizon"));
        });
    }

    public static void open(Minecraft client, String worldName, String startingShip)
    {
        name = worldName;
        ship = startingShip;
        opening = true;
        selected = null;
        CreateWorldScreen.openFresh(client, () -> client.gui.setScreen(new TitleScreen()));
    }

    public static void remember(CreateWorldScreen screen, LevelStorageAccess access)
    {
        if (screen != selected)
            return;
        try
        {
            Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("nullspace-dev-world.txt"), access.getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
        }
        catch (IOException failure)
        {
            throw new UncheckedIOException("Cannot remember the new development world", failure);
        }
    }

    private DevWorldCreation()
    {
    }
}
