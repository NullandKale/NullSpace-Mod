package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.*;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

public final class StationPreviewGameTest
{
    @GameTest(maxTicks = 600)
    public void aJourneyStationBriefBuildsReproduciblyFromThePlanner(GameTestHelper ctx)
    {
        for (long seed = 1; seed <= 6; seed++)
        {
            var spec = EncounterStations.encounter(seed * 7919);
            ctx.assertTrue(spec.condition() == StationSpec.Condition.DERELICT, "Journey stations are derelict");
            var a = EncounterStations.build(spec);
            var b = EncounterStations.build(StationSpec.read(spec.save()));
            ctx.assertTrue(a.equals(b), "The saved brief reproduces its blocks, core, docks and furnishings");
            ctx.assertTrue(a.blocks().get(a.core()).is(ModBlocks.STATION_CORE) && a.docks().size() >= 1, "A journey station has its core and a usable dock");
            ctx.assertTrue(a.points().stream().anyMatch(point -> point.name().equals("frame") || point.name().equals("stand") || point.name().equals("painting") || point.name().equals("animal")), "Its rooms are furnished");
        }
        ctx.succeed();
    }

    @GameTest(maxTicks = 600)
    public void previewCommandsPlaceTheGeneratedStationsAndRefuseBadSelectors(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var player = ctx.makeMockServerPlayerInLevel();
        var source = server.createCommandSourceStack().withEntity(player);
        var dispatcher = server.getCommands().getDispatcher();
        var stations = dispatcher.getRoot().getChild("nullspace").getChild("shipyard").getChild("stations");
        ctx.assertTrue(stations.getCommand() != null, "Adding preview selectors preserves the original station-gallery command");
        for (var literal : List.of("preview", "compare", "variants"))
            ctx.assertTrue(stations.getChild(literal) != null, "The " + literal + " command is registered");
        int count = WorldStore.get(server).ships().size();
        ctx.assertTrue(dispatcher.execute("nullspace shipyard stations preview halcyon research standard derelict -419", source) == 1, "The registered preview command accepts the complete reproducible brief");
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-station-preview.nbt");
        var receipt = WorldStore.read(marker);
        var spec = StationSpec.read(receipt.getCompoundOrEmpty("stationSpec"));
        ctx.assertTrue(spec.equals(StationSpec.parse("halcyon", "research", "standard", "derelict", -419)), "Preview records its exact selectors and seed");
        var origin = BlockPos.of(receipt.getLongOr("origin", 0));
        var out = StationStructure.build(StationPlanner.generate(spec));
        ctx.assertTrue(server.overworld().getBlockState(origin.offset(out.core())).is(ModBlocks.STATION_CORE), "The preview actually places the generated station core");
        ctx.assertTrue(WorldStore.get(server).ships().size() == count, "A gallery preview does not add an ambient site or ownership record");
        ctx.assertTrue(dispatcher.execute("nullspace shipyard stations compare agriculture compact operational 77", source) == 1, "Compare places the three makers from one seed");
        ctx.assertTrue(WorldStore.read(marker).getListOrEmpty("stations").size() == 3, "The receipt records all three stations");
        ctx.assertTrue(dispatcher.execute("nullspace shipyard stations variants rivet cargo compact operational 5", source) == 1, "Variants places four seeds of one brief");
        var before = WorldStore.read(marker);
        ctx.assertTrue(dispatcher.execute("nullspace shipyard stations preview bogus harbor compact derelict 1", source) == 0, "An unknown maker is refused");
        ctx.assertTrue(WorldStore.read(marker).equals(before), "A refused brief leaves the preview receipt unchanged");
        server.getPlayerList().remove(player);
        ctx.succeed();
    }

    /** The first placed station whose real block light is short of the minimum, as a message; null when every station is lit. */
    private static String lightFailure(net.minecraft.server.MinecraftServer server, List<StationPreview.Placed> placed)
    {
        var level = server.overworld();
        for (var p : placed)
        {
            int cells = 0, dim = 0, black = 0;
            for (var room : p.planned().rooms())
            {
                var i = room.interior();
                for (int l = 0; l < room.levels(); l++)
                    for (int x = i.x0(); x <= i.x1(); x++)
                        for (int z = i.z0(); z <= i.z1(); z++)
                        {
                            var at = p.origin().offset(x, room.y() + l * StationPlan.PITCH + 2, z);
                            var here = level.getBlockState(at);
                            var below = level.getBlockState(at.below());
                            if (!here.isAir() || !level.getBlockState(at.above()).isAir() || below.isAir())
                                continue;
                            cells++;
                            int light = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at);
                            if (light < StationLighting.MINIMUM)
                                dim++;
                            if (light < 1)
                                black++;
                        }
            }
            if (!(cells > 100 && black == 0 && dim <= cells / 50))
                return p.spec().maker() + " station: " + cells + " standing cells, " + dim + " below light " + StationLighting.MINIMUM + ", " + black + " dark in the real light engine";
        }
        return null;
    }

    /** The light engine works on the server thread's spare time, so a busy server settles later: poll until it is lit or time runs out. */
    @GameTest(maxTicks = 1500)
    public void torchesLightPlacedStationsInTheRealLightEngine(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var specs = new ArrayList<StationSpec>();
        for (var maker : Maker.values())
            specs.add(new StationSpec(maker, StationSpec.Purpose.RESEARCH, StationSpec.Size.STANDARD, StationSpec.Condition.OPERATIONAL, 77));
        var placed = StationPreview.build(server, specs);
        var attempts = new int[1];
        var poll = new Runnable[1];
        poll[0] = () ->
        {
            var failure = lightFailure(server, placed);
            if (failure == null)
            {
                ctx.succeed();
                return;
            }
            if (++attempts[0] >= 18)
                ctx.assertTrue(false, failure + " after " + (300 + attempts[0] * 60) + " ticks");
            ctx.runAfterDelay(60, () -> poll[0].run());
        };
        ctx.runAfterDelay(300, () -> poll[0].run());
    }
}
