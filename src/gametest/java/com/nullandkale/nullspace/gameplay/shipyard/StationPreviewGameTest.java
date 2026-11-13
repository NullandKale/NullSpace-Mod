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
    @GameTest
    public void existingStationBriefBuildsReproduciblyWithBoundedSupplyMarkers(GameTestHelper ctx)
    {
        for (var maker : Maker.values())
        {
            var spec = new StationSpec(maker, StationSpec.Purpose.HARBOR, StationSpec.Size.COMPACT, StationSpec.Condition.DERELICT, 419);
            var a = ExistingStationPlans.build(spec);
            var b = ExistingStationPlans.build(StationSpec.read(spec.save()));
            ctx.assertTrue(a.equals(b), "The saved brief reproduces its blocks, core, docks and supply positions");
            ctx.assertTrue(a.blocks().get(a.core()).is(ModBlocks.STATION_CORE) && a.docks().size() >= 2, "Existing rooms retain their core and usable docks");
            ctx.assertTrue(a.points().size() >= 1 && a.points().size() <= 4 && a.points().stream().map(point -> point.pos()).distinct().count() == a.points().size(), "Supply points are unique and bounded by room count");
            for (var point : a.points())
                ctx.assertTrue(a.blocks().get(point.pos()).is(Blocks.BARREL) || a.blocks().get(point.pos()).is(Blocks.CHEST), "Every supply marker refers to an actual container");
        }
        ctx.succeed();
    }

    @GameTest(maxTicks = 240)
    public void previewCommandParsesAllSelectorsAndDoesNotPlaceUnsupportedBriefs(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var player = ctx.makeMockServerPlayerInLevel();
        var source = server.createCommandSourceStack().withEntity(player);
        var dispatcher = server.getCommands().getDispatcher();
        ctx.assertTrue(dispatcher.getRoot().getChild("nullspace").getChild("shipyard").getChild("stations").getCommand() != null, "Adding preview selectors preserves the original station-gallery command");
        String command = "nullspace shipyard stations preview bastion harbor compact derelict -419";
        int count = WorldStore.get(server).ships().size();
        ctx.assertTrue(dispatcher.execute(command, source) == 1, "The registered preview command accepts the complete reproducible brief");
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-station-preview.nbt");
        var receipt = WorldStore.read(marker);
        var spec = StationSpec.read(receipt.getCompoundOrEmpty("stationSpec"));
        ctx.assertTrue(spec.equals(StationSpec.parse("bastion", "harbor", "compact", "derelict", -419)), "Preview records its exact selectors and seed");
        var origin = player.blockPosition().offset(-5, -1, -20);
        var plan = ExistingStationPlans.build(spec);
        ctx.assertTrue(server.overworld().getBlockState(origin.offset(8, -1, 8).offset(plan.core())).is(ModBlocks.STATION_CORE), "The preview actually places the selected station core");
        ctx.assertTrue(WorldStore.get(server).ships().size() == count, "A gallery preview does not add an ambient site or ownership record");
        ctx.assertTrue(dispatcher.execute("nullspace shipyard stations preview rivet research large operational 77", source) == 0, "Unsupported new layouts are refused rather than faked by the old builder");
        ctx.assertTrue(WorldStore.read(marker).equals(receipt), "A refused brief leaves the preview receipt unchanged");
        server.getPlayerList().remove(player);
        ctx.succeed();
    }
}
