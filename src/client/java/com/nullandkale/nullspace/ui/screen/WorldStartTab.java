package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.gameplay.start.StartDifficulty;
import com.nullandkale.nullspace.gameplay.start.StartPlanets;
import com.nullandkale.nullspace.gameplay.start.WorldStartSettings;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldOptions;

/**
 * The Start tab of Create New World: where the adventure begins, in which ship and on which planet, with a warning and a
 * difficulty for what the choice costs (no air, no trees, far from Terra).
 */
public final class WorldStartTab extends GridLayoutTab
{
    private static final int LINES = 5;

    private final Screen parent;
    private final WorldStartAccess selection;
    private final StringWidget difficulty;
    private final List<StringWidget> warnings = new ArrayList<>();
    private final Button ship;
    private final CycleButton<String> planet;
    private final CycleButton<Boolean> mode;

    public WorldStartTab(Screen parent, WorldStartAccess selection)
    {
        super(Component.literal("Start"));
        this.parent = parent;
        this.selection = selection;
        var font = Minecraft.getInstance().font;
        var rows = layout.rowSpacing(5).createRowHelper(1);
        rows.addChild(new StringWidget(Component.literal("Where will your adventure begin?"), font));
        ship = Button.builder(shipLabel(), b -> Minecraft.getInstance().gui.setScreen(new StartingShipScreen(parent, selection, () ->
        {
            b.setMessage(shipLabel());
            refresh();
        }))).width(310).build();
        int start = selection.nullspace$start().crash() ? 2 : selection.nullspace$start().space() ? 1 : 0;
        rows.addChild(CycleButton.builder((Integer choice) -> Component.literal(choice == 2 ? "Crash-landed" : choice == 1 ? "Aboard a ship" : "On a planet"), start).withValues(0, 1, 2).create(0, 0, 310, 20, Component.literal("Start"), (button, choice) ->
        {
            var now = selection.nullspace$start();
            selection.nullspace$start(new WorldStartSettings(choice >= 1, now.ship(), choice == 2, now.multi(), now.planet()));
            refresh();
        }));
        rows.addChild(ship);
        planet = CycleButton.builder((String choice) -> Component.literal(StartPlanets.label(choice)), selection.nullspace$start().planet()).withValues(StartPlanets.CHOICES).create(0, 0, 310, 20, Component.literal("Planet"), (button, choice) ->
        {
            var now = selection.nullspace$start();
            selection.nullspace$start(new WorldStartSettings(now.space(), now.ship(), now.crash(), now.multi(), choice));
            ensureSeedHas(choice);
            refresh();
        });
        rows.addChild(planet);
        mode = CycleButton.booleanBuilder(Component.literal("Multi-crash: everyone crashes separately"), Component.literal("Single crash: one wreck for everyone"), selection.nullspace$start().multi()).create(0, 0, 310, 20, Component.literal("Crash mode"), (button, multi) ->
        {
            var now = selection.nullspace$start();
            selection.nullspace$start(new WorldStartSettings(now.space(), now.ship(), now.crash(), multi, now.planet()));
            refresh();
        });
        rows.addChild(mode);
        difficulty = new StringWidget(Component.empty(), font);
        difficulty.setWidth(310);
        rows.addChild(difficulty);
        for (int i = 0; i < LINES; i++)
        {
            var line = new StringWidget(Component.empty(), font);
            line.setWidth(310);
            warnings.add(line);
            rows.addChild(line);
        }
        refresh();
    }

    private Component shipLabel()
    {
        return Component.literal("Starting ship: " + ShipBlueprints.get(selection.nullspace$start().ship()).name());
    }

    /** The chart the world will have, when the seed is typed; null when it is blank (the seed is chosen at creation). */
    private GalaxyPlan.Galaxy chart()
    {
        if (!(parent instanceof CreateWorldScreen create))
            return null;
        var seed = WorldOptions.parseSeed(create.getUiState().getSeed());
        return seed.isPresent() ? GalaxyPlan.generate(seed.getAsLong()) : null;
    }

    /**
     * A planet type has to exist in the world's chart. If the seed is blank or the typed seed has none, a random seed that does is
     * chosen and shown in the seed field, so the type is guaranteed and can be reused.
     */
    private void ensureSeedHas(String choice)
    {
        if (choice.equals("random") || !(parent instanceof CreateWorldScreen create))
            return;
        var chart = chart();
        if (chart != null && StartPlanets.available(chart, choice))
            return;
        var random = new Random();
        for (int tries = 0; tries < 5000; tries++)
        {
            long seed = random.nextLong();
            if (StartPlanets.available(GalaxyPlan.generate(seed), choice))
            {
                create.getUiState().setSeed(Long.toString(seed));
                return;
            }
        }
    }

    private void refresh()
    {
        var now = selection.nullspace$start();
        ship.active = now.space();
        planet.active = now.space();
        mode.active = now.crash();
        var result = StartDifficulty.describe(now.planet(), now.crash(), chart(), StartDifficulty.shipClass(ShipBlueprints.get(now.ship()).decks()));
        if (!now.space())
        {
            difficulty.setMessage(Component.literal("An ordinary Minecraft start."));
            warnings.forEach(w -> w.setMessage(Component.empty()));
            return;
        }
        difficulty.setMessage(Component.literal("Difficulty: " + result.level().label).withStyle(style -> style.withColor(result.level().color)));
        for (int i = 0; i < LINES; i++)
            warnings.get(i).setMessage(i < result.warnings().size() ? Component.literal(result.warnings().get(i)).withStyle(style -> style.withColor(0xdddddd)) : Component.empty());
    }
}
