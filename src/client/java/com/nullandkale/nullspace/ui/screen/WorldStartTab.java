package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.gameplay.start.WorldStartSettings;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WorldStartTab extends GridLayoutTab
{
    public WorldStartTab(Screen parent, WorldStartAccess selection)
    {
        super(Component.literal("Start"));
        var font = Minecraft.getInstance().font;
        var rows = layout.rowSpacing(8).createRowHelper(1);
        rows.addChild(new StringWidget(Component.literal("Where will your adventure begin?"), font));
        var ship = Button.builder(Component.literal("Starting ship: " + ShipBlueprints.get(selection.nullspace$start().ship()).name()), b -> Minecraft.getInstance().gui.setScreen(new StartingShipScreen(parent, selection, () -> b.setMessage(Component.literal("Starting ship: " + ShipBlueprints.get(selection.nullspace$start().ship()).name()))))).width(310).build();
        ship.active = selection.nullspace$start().space();
        rows.addChild(CycleButton.builder((Boolean space) -> Component.literal(space ? "Aboard a ship" : "On a planet"), selection.nullspace$start().space()).withValues(false, true).create(0, 0, 310, 20, Component.literal("Start"), (button, space) ->
        {
            selection.nullspace$start(new WorldStartSettings(space, selection.nullspace$start().ship()));
            ship.active = space;
        }));
        rows.addChild(ship);
        rows.addChild(new StringWidget(Component.literal("Ships include air, fuel, food and a spacesuit."), font));
        rows.addChild(new StringWidget(Component.literal("Ship starts orbit a randomly chosen planet."), font));
    }
}
