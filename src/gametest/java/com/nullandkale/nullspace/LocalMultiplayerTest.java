package com.nullandkale.nullspace;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.content.guide.GuideContent;
import com.nullandkale.nullspace.content.guide.GuideRecipes;
import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.dev.ShipLab;
import com.nullandkale.nullspace.dev.SphereLab;
import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.navigation.Piloting;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.BlockSupport;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYard;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYardClientGameTest;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYardService;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsConsole;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.computer.ComputerImageCodec;
import com.nullandkale.nullspace.model.computer.VirtualComputer;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.compute.SpaceComputeRenderer;
import com.nullandkale.nullspace.render.effects.PreviewRenderer;
import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.ShipPilotScreen;
import com.nullandkale.nullspace.ui.screen.ShipyardScreen;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;
import com.nullandkale.nullspace.ui.screen.ViewportScreen;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/** Invoked only by multiplayer-test.ps1. Separate JVM clients and a loopback dedicated server. */
public final class LocalMultiplayerTest
{
    private static void verifyPilotViewport(ClientGameTestContext ctx)
    {
        ctx.runOnClient(c ->
        {
            var s = (ShipPilotScreen) c.gui.screen();
            var t = s.viewport().target;
            check(t.width <= SpaceSettings.width() && t.height <= SpaceSettings.height(), "Fullscreen pilot targets must stay within the selected quality budget on the 4:3" + " client");
            check(Math.abs((long) t.width * s.height - (long) t.height * s.width) <= Math.max(s.width, s.height), "Fullscreen pilot targets must preserve the screen aspect ratio");
        });
    }

    private static void verifyGuide(ClientGameTestContext ctx)
    {
        ctx.waitFor(c -> c.player.getMainHandItem().is(FieldGuide.ITEM), 6000);
        ctx.runOnClient(c -> c.gameMode.useItem(c.player, net.minecraft.world.InteractionHand.MAIN_HAND));
        ctx.waitFor(c -> c.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.BookViewScreen, 6000);
        ctx.runOnClient(c ->
        {
            var stack = c.player.getMainHandItem();
            var data = stack.get(GuideRecipes.COMPONENT);
            check(data != null && data.recipes().size() == GuideContent.RECIPES.size(), "Dedicated server must synchronize all graphical recipe snapshots");
            var access = net.minecraft.client.gui.screens.inventory.BookViewScreen.BookAccess.fromItem(stack);
            int first = access.getPageCount() - data.recipes().size();
            int terminal = first + GuideContent.RECIPES.indexOf("computer_terminal");
            ((net.minecraft.client.gui.screens.inventory.BookViewScreen) c.gui.screen()).setPage(terminal);
            int left = (c.gui.screen().width - 192) / 2 + 36;
            c.gui.screen().mouseClicked(new net.minecraft.client.input.MouseButtonEvent(left + 27, 82, new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            check(c.gui.screen().getNarrationMessage().getString().contains("Computer base"), "Both real clients must navigate graphical ingredient recipes");
        });
        ctx.takeScreenshot("local-multiplayer-guide");
        ctx.runOnClient(c -> c.gui.screen().onClose());
    }

    private static Path directory;
    private static String homePadName;

    private static void signal(String name)
    {
        NullSpace.LOGGER.info("MULTIPLAYER_CHECKPOINT {}", name);
        try
        {
            Files.writeString(directory.resolve(name), "ok");
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void await(ClientGameTestContext ctx, String name)
    {
        ctx.waitFor(c -> Files.exists(directory.resolve(name)), 6000);
    }

    private static boolean hasFlightLog(net.minecraft.nbt.CompoundTag ship)
    {
        if (ship == null)
            return false;
        var computers = ship.getCompoundOrEmpty("computers");
        for (String key : computers.keySet())
        {
            var disk = new VirtualComputer((n, a) ->
            {
                throw new AssertionError("A persistence assertion must not invoke a device");
            });
            disk.restore(ComputerImageCodec.decode(computers.getCompoundOrEmpty(key).get("image")));
            if (disk.files.files().containsKey("/home/crew/flightlog") && disk.files.read("/home/crew/flightlog").equals("multiplayer_flight_log\n"))
                return true;
        }
        return false;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
            throw new AssertionError(message);
    }

    private static void checkTracking(net.minecraft.server.MinecraftServer server)
    {
        try
        {
            var field = net.minecraft.server.level.DistanceManager.class.getDeclaredField("playersPerChunk");
            field.setAccessible(true);
            for (var player : server.getPlayerList().getPlayers())
            {
                int copies = 0;
                for (var world : server.getAllLevels())
                    if (world.getEntity(player.getUUID()) == player)
                        copies++;
                check(copies == 1, "A connected player must be registered in exactly one native world after arrival");
                var manager = player.level().getChunkSource().chunkMap.getDistanceManager();
                var map = (java.util.Map<?, ?>) field.get(manager);
                var last = player.getLastSectionPos();
                var entry = (java.util.Set<?>) map.get(last.chunk().pack());
                if (entry == null || !entry.contains(player))
                    throw new AssertionError("Tracking invariant: tick=" + server.getTickCount() + " player=" + player.getPlainTextName() + " current=" + player.position() + " last=" + last + " dimension=" + player.level().dimension() + " tracked=" + map + " phase=" + (Files.exists(directory.resolve("remote-camera-ready")) ? "remote" : Files.exists(directory.resolve("systems-ready")) ? "systems" : Files.exists(directory.resolve("crew-returned")) ? "returned" : "baseline"));
            }
        }
        catch (ReflectiveOperationException error)
        {
            throw new AssertionError(error);
        }
    }

    private static void verifyComputeScene(ClientGameTestContext ctx, String phase)
    {
        ctx.waitFor(c -> SpaceSceneRenderer.computeReady() && NullSpaceClient.definitions().scene() != null, 6000);
        ctx.runOnClient(c ->
        {
            var definition = NullSpaceClient.definitions().scene();
            check(SpaceSceneRenderer.viewport().compute().stats().frames() > 0, "Each observer must render its own compute view");
            try
            {
                Files.writeString(directory.resolve(System.getProperty("nullspace.local.role") + "-" + phase + ".scene"), SpaceDefinitionCodec.encode(definition).toString());
                Files.writeString(directory.resolve(System.getProperty("nullspace.local.role") + "-" + phase + ".pose"), c.player.position().toString());
            }
            catch (java.io.IOException e)
            {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private static boolean space(net.minecraft.client.Minecraft c)
    {
        return c.level != null && c.level.dimension().equals(TravelService.SPACE) && c.gui.screen() == null;
    }

    private static void action(ClientGameTestContext ctx, ComputerTerminal.Action action)
    {
        if (ctx.computeOnClient(c -> c.gui.screen() instanceof TerminalScreen t && t.shipController()))
        {
            var control = switch (action)
            {
                case INSPECT -> ShipControl.Action.INSPECT;
                case LAUNCH -> ShipControl.Action.LAUNCH;
                case LAND -> ShipControl.Action.LAND;
                default -> throw new AssertionError("Unsupported unified controller test action: " + action);
            };
            if (action == ComputerTerminal.Action.LAND)
                ShipControlTestUi.destination(ctx, homePadName, "Pads");
            ShipControlTestUi.action(ctx, control);
            return;
        }
        ctx.runOnClient(c ->
        {
            var terminal = (TerminalScreen) c.gui.screen();
            String argument = "", id = "";
            if (action == ComputerTerminal.Action.LAND)
            {
                argument = terminal.fieldValue();
                final String name = argument;
                id = terminal.destinations().stream().filter(d -> d.name().equalsIgnoreCase(name)).findFirst().orElseThrow().id();
            }
            ClientPlayNetworking.send(new TerminalPackets.Request(terminal.sessionId(), action, argument, id));
        });
    }

    private static void connect(ClientGameTestContext ctx, int port)
    {
        ctx.runOnClient(c -> ConnectScreen.startConnecting(new TitleScreen(), c, new ServerAddress("127.0.0.1", port), new ServerData("Local NullSpace verification", "127.0.0.1:" + port, ServerData.Type.OTHER), false, null));
        ctx.waitFor(c -> c.level != null && c.player != null, 6000);
    }

    private static void buyShipTogether(ClientGameTestContext ctx)
    {
        ctx.waitFor(c -> c.gui.screen() instanceof ShipyardScreen yard && yard.status().contains("Two-player delivery test"), 6000);
        ctx.waitTicks(5);
        int design = java.util.stream.IntStream.range(0, ShipBlueprints.PRESETS.size()).filter(i -> ShipBlueprints.PRESETS.get(i).id().equals("mule")).findFirst().orElseThrow();
        ShipYardClientGameTest.choose(ctx, design);
        ctx.waitFor(c -> c.gui.screen() instanceof ShipyardScreen yard && yard.selected().equals("mule") && yard.ready(), 6000);
        String role = System.getProperty("nullspace.local.role");
        signal(role + "-shipyard-ready");
        await(ctx, "pilot-shipyard-ready");
        await(ctx, "crew-shipyard-ready");
        ShipYardClientGameTest.choose(ctx, ShipBlueprints.PRESETS.size());
        ctx.waitFor(c -> c.gui.screen() instanceof ShipyardScreen yard && (yard.status().contains("DELIVERED:") || yard.status().contains("Clear the pad")), 6000);
        ctx.runOnClient(c ->
        {
            var terminal = (ShipyardScreen) c.gui.screen();
            try
            {
                Files.writeString(directory.resolve(role + "-shipyard-result"), terminal.status().contains("DELIVERED:") ? "delivered" : "occupied");
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
        });
        signal(role + "-shipyard-done");
    }

    public static void run(ClientGameTestContext ctx)
    {
        directory = Path.of(System.getProperty("nullspace.local.directory")).toAbsolutePath();
        int port = Integer.parseInt(System.getProperty("nullspace.local.port"));
        if (System.getProperty("nullspace.local.role").equals("crew"))
        {
            crew(ctx, port);
            return;
        }
        var properties = new Properties();
        properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("server-port", Integer.toString(port));
        properties.setProperty("white-list", "false");
        properties.setProperty("enforce-whitelist", "false");
        properties.setProperty("online-mode", "false");
        properties.setProperty("enable-query", "false");
        properties.setProperty("enable-rcon", "false");
        properties.setProperty("max-players", "4");
        properties.setProperty("spawn-protection", "0");
        properties.setProperty("view-distance", "3");
        properties.setProperty("simulation-distance", "3");
        try (var dedicated = ctx.worldBuilder().createServer(properties))
        {
            signal("server-ready");
            try (var connection = dedicated.connect())
            {
                dedicated.waitFor(s -> s.getPlayerList().getPlayers().size() == 2, 6000);
                dedicated.runOnServer(server -> net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(ticking ->
                {
                    if (ticking == server)
                        checkTracking(server);
                }));
                var base = dedicated.computeOnServer(s ->
                {
                    try
                    {
                        return ShipLab.ensure(s);
                    }
                    catch (java.io.IOException error)
                    {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
                var controller = base.offset(6, 3, 3);
                homePadName = "Reference " + base.getX();
                signal("reference-ready");
                var mobId = new UUID[1];
                var itemId = new UUID[1];
                dedicated.runOnServer(s ->
                {
                    int i = 0;
                    for (var p : s.getPlayerList().getPlayers())
                    {
                        p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                        p.teleportTo(s.overworld(), base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5 + i++, Set.of(), 37, 11, true);
                        p.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 11));
                        ShipService.open(p, controller, "Local multiplayer inspection");
                    }
                    var dropperPos = base.offset(1, 3, 6);
                    s.overworld().setBlock(dropperPos, net.minecraft.world.level.block.Blocks.DROPPER.defaultBlockState(), BlockSupport.TRANSFER_FLAGS);
                    var dropper = (net.minecraft.world.level.block.entity.DropperBlockEntity) s.overworld().getBlockEntity(dropperPos);
                    dropper.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
                    var brewerPos = base.offset(5, 3, 5);
                    s.overworld().setBlock(brewerPos, net.minecraft.world.level.block.Blocks.BREWING_STAND.defaultBlockState(), BlockSupport.TRANSFER_FLAGS);
                    var brewer = (net.minecraft.world.level.block.entity.BrewingStandBlockEntity) s.overworld().getBlockEntity(brewerPos);
                    brewer.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE));
                    brewer.setItem(3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHER_WART, 3));
                    var mob = net.minecraft.world.entity.EntityTypes.CREEPER.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    mob.setNoAi(true);
                    mob.setHealth(13);
                    mob.setPos(base.getX() + 1.5, base.getY() + 3, base.getZ() + 3.5);
                    s.overworld().addFreshEntity(mob);
                    mobId[0] = mob.getUUID();
                    var item = new net.minecraft.world.entity.item.ItemEntity(s.overworld(), base.getX() + 3.5, base.getY() + 3, base.getZ() + 4.5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 3));
                    s.overworld().addFreshEntity(item);
                    itemId[0] = item.getUUID();
                });
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 6000);
                await(ctx, "crew-validated");
                ctx.waitTicks(15);
                dedicated.runOnServer(server ->
                {
                    for (var p : server.getPlayerList().getPlayers())
                    {
                        var previous = p.getMainHandItem().copy();
                        var guide = new net.minecraft.world.item.ItemStack(FieldGuide.ITEM);
                        FieldGuide.refresh(guide, p.level());
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, guide);
                        if (!previous.isEmpty())
                            p.getInventory().add(previous);
                        p.inventoryMenu.broadcastChanges();
                    }
                });
                signal("guide-ready");
                verifyGuide(ctx);
                await(ctx, "crew-guide");
                dedicated.runOnServer(server ->
                {
                    var p = server.getPlayerList().getPlayers().stream().filter(player -> player.getGameProfile().name().equals("NullSpace_crew")).findFirst().orElseThrow();
                    ShipService.open(p, controller, "Ship ready after guide test");
                });

                dedicated.runOnServer(s ->
                {
                    var p = s.getPlayerList().getPlayers().stream().filter(player -> player.getGameProfile().name().equals("NullSpace_pilot")).findFirst().orElseThrow();
                    PadComputers.open(p, base.offset(-1, 1, 3), "Preview isolation test");
                });
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Preview isolation"), 6000);
                action(ctx, ComputerTerminal.Action.PREVIEW_PAD);
                ctx.waitFor(c -> PreviewRenderer.count(false) == 100, 6000);
                ctx.waitTicks(3);
                action(ctx, ComputerTerminal.Action.PREVIEW_SHIP);
                ctx.waitFor(c -> PreviewRenderer.count(true) > 100, 6000);
                signal("pilot-previews");
                await(ctx, "crew-no-previews");
                action(ctx, ComputerTerminal.Action.PREVIEW_PAD);
                ctx.waitFor(c -> PreviewRenderer.count(false) == 0 && PreviewRenderer.count(true) > 0, 6000);
                ctx.waitTicks(3);
                action(ctx, ComputerTerminal.Action.PREVIEW_SHIP);
                ctx.waitFor(c -> PreviewRenderer.count(true) == 0, 6000);
                dedicated.runOnServer(s ->
                {
                    var p = s.getPlayerList().getPlayers().stream().filter(player -> player.getGameProfile().name().equals("NullSpace_pilot")).findFirst().orElseThrow();
                    ShipService.open(p, controller, "Preview isolation complete");
                });
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Preview isolation complete"), 6000);
                ctx.waitTicks(15);
                dedicated.runOnServer(server ->
                {
                    var pilot = server.getPlayerList().getPlayers().stream().filter(p -> p.getGameProfile().name().equals("NullSpace_pilot")).findFirst().orElseThrow();
                    var pc = base.offset(4, 4, 6);
                    server.overworld().setBlock(pc, ModBlocks.TERMINAL.defaultBlockState(), BlockSupport.TRANSFER_FLAGS);
                    ComputerService.open(pilot, pc);
                });
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && java.util.stream.IntStream.range(0, 25).anyMatch(row -> t.buffer().row(row).contains("NullOS")), 6000);
                ctx.runOnClient(c ->
                {
                    var screen = (TerminalScreen) c.gui.screen();
                    for (char ch : "echo multiplayer_flight_log > flightlog".toCharArray())
                        screen.charTyped(new net.minecraft.client.input.CharacterEvent(ch));
                    screen.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
                });
                ctx.waitTicks(10);
                dedicated.runOnServer(server ->
                {
                    ComputerService.flush(server);
                    var ship = WorldStore.get(server).at(server.overworld(), base.offset(4, 4, 6));
                    check(hasFlightLog(ship), "Real keyboard input must persist a guest file on the shared ship");
                    var pilot = server.getPlayerList().getPlayers().stream().filter(p -> p.getGameProfile().name().equals("NullSpace_pilot")).findFirst().orElseThrow();
                    ShipService.open(pilot, controller, "Computer cargo ready");
                });
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Computer cargo ready"), 6000);
                ctx.waitTicks(15);
                action(ctx, ComputerTerminal.Action.INSPECT);
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Ship ready"), 6000);
                dedicated.runOnServer(s ->
                {
                    var crew = s.getPlayerList().getPlayers().stream().filter(p -> p.getGameProfile().name().equals("NullSpace_crew")).findFirst().orElseThrow();
                    crew.openMenu((net.minecraft.world.level.block.entity.DropperBlockEntity) s.overworld().getBlockEntity(base.offset(1, 3, 6)));
                });
                ctx.waitTicks(15);
                action(ctx, ComputerTerminal.Action.LAUNCH);
                ctx.waitFor(LocalMultiplayerTest::space, 6000);
                await(ctx, "crew-space");
                dedicated.runOnServer(s ->
                {
                    check(s.getPlayerList().getPlayers().stream().allMatch(p -> p.level().dimension().equals(TravelService.SPACE)), "Both real clients must arrive");
                    var level = s.getLevel(TravelService.SPACE);
                    check(level.getEntity(mobId[0]) instanceof net.minecraft.world.entity.monster.Creeper mob && mob.getHealth() == 13, "Creeper identity/health must survive multiplayer launch");
                });
                verifyComputeScene(ctx, "initial");
                await(ctx, "crew-initial.scene");
                dedicated.runOnServer(s ->
                {
                    var scene = SceneCatalog.get(s);
                    scene.putLong("revision", scene.getLongOr("revision", 0) + 1);
                    ((net.minecraft.nbt.CompoundTag) scene.getListOrEmpty("bodies").getFirst()).putInt("color", 0xffaa66ff);
                    WorldStore.get(s).save();
                });
                signal("scene-edited");
                ctx.waitFor(c -> NullSpaceClient.definitions().scene() != null && NullSpaceClient.definitions().scene().revision() >= 2, 6000);
                verifyComputeScene(ctx, "edited");
                await(ctx, "crew-edited.scene");
                try
                {
                    check(Files.readString(directory.resolve("pilot-initial.scene")).equals(Files.readString(directory.resolve("crew-initial.scene"))), "Observers must receive identical initial definitions");
                    check(Files.readString(directory.resolve("pilot-edited.scene")).equals(Files.readString(directory.resolve("crew-edited.scene"))), "Scene revision must converge on both real clients");
                    check(!Files.readString(directory.resolve("pilot-initial.pose")).equals(Files.readString(directory.resolve("crew-initial.pose"))), "Two compute observers must have distinct poses");
                }
                catch (java.io.IOException e)
                {
                    throw new java.io.UncheckedIOException(e);
                }
                var flightStart = dedicated.computeOnServer(s ->
                {
                    var pilot = s.getPlayerList().getPlayers().stream().filter(p -> p.getGameProfile().name().equals("NullSpace_pilot")).findFirst().orElseThrow();
                    var vessel = SceneService.observer(pilot);
                    var helm = WorldStore.pos(vessel, "controller");
                    vessel.putDouble("simVX", 99);
                    NavigationService.open(pilot, helm, true);
                    check(vessel.getDoubleOr("simVX", -1) == 0, "Reacquiring a helm after restart must discard stale saved velocity");
                    var crew = s.getPlayerList().getPlayers().stream().filter(p -> p != pilot).findFirst().orElseThrow();
                    boolean rejected = false;
                    try
                    {
                        NavigationService.open(crew, helm, true);
                    }
                    catch (IllegalArgumentException expected)
                    {
                        rejected = true;
                    }
                    check(rejected, "Only one real client can own pilot input");
                    NavigationService.open(crew, helm, false);
                    return SceneService.pose(vessel).position();
                });
                signal("flight-ready");
                ctx.waitFor(c -> c.gui.screen() instanceof ShipPilotScreen s && s.canPilot(), 6000);
                await(ctx, "crew-observing");
                verifyPilotViewport(ctx);
                ctx.runOnClient(c -> ((ShipPilotScreen) c.gui.screen()).keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_W, 0, 0)));
                ctx.waitTicks(25);
                ctx.runOnClient(c -> ((ShipPilotScreen) c.gui.screen()).keyReleased(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_W, 0, 0)));
                dedicated.runOnServer(s ->
                {
                    var vessel = SceneService.observer(s.getPlayerList().getPlayers().getFirst());
                    check(SceneService.pose(vessel).position().distanceTo(flightStart) > 1, "Actual network controls must move the shared ship pose");
                });
                ctx.takeScreenshot("local-multiplayer-flight");
                ctx.runOnClient(c -> c.gui.screen().onClose());
                signal("flight-stop");
                await(ctx, "crew-flight-closed");
                for (String route : new String[]{"sol_luna", "sol_terra"})
                {
                    dedicated.runOnServer(s ->
                    {
                        var p = s.getPlayerList().getPlayers().stream().filter(player -> player.getGameProfile().name().equals("NullSpace_pilot")).findFirst().orElseThrow();
                        Piloting.navigation(p, WorldStore.pos(SceneService.observer(p), "controller"));
                    });
                    ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 6000);
                    ctx.waitTicks(15);
                    signal(route.equals("sol_luna") ? "jump-luna" : "jump-home");
                    ShipControlTestUi.destination(ctx, route.equals("sol_luna") ? "Sol / Luna" : "Sol / Terra", "Regions");
                    ShipControlTestUi.action(ctx, ShipControl.Action.JUMP);
                    String sceneId = route.equals("sol_luna") ? "nullspace:sol_luna" : "nullspace:local-space";
                    ctx.waitFor(c -> space(c) && NullSpaceClient.definitions().scene() != null && NullSpaceClient.definitions().scene().id().equals(sceneId), 6000);
                    String phase = route.equals("sol_luna") ? "luna" : "home";
                    verifyComputeScene(ctx, phase);
                    await(ctx, "crew-" + phase);
                    try
                    {
                        check(Files.readString(directory.resolve("pilot-" + phase + ".scene")).equals(Files.readString(directory.resolve("crew-" + phase + ".scene"))), "Both real clients must converge after the helm region jump");
                    }
                    catch (java.io.IOException e)
                    {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                signal("disconnect-crew");
                dedicated.waitFor(s -> s.getPlayerList().getPlayers().size() == 1, 6000);
                dedicated.runOnServer(s ->
                {
                    var p = s.getPlayerList().getPlayers().getFirst();
                    var vessel = WorldStore.get(s).at(p.level(), p.blockPosition());
                    ShipService.open(p, WorldStore.pos(vessel, "controller"), "Return while crew is offline");
                });
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 6000);
                ctx.waitTicks(15);
                action(ctx, ComputerTerminal.Action.LAND);
                ctx.waitFor(c -> c.level != null && c.level.dimension().equals(Level.OVERWORLD) && c.gui.screen() == null, 6000);
                signal("reconnect-crew");
                await(ctx, "crew-returned");
                dedicated.waitFor(s -> s.getPlayerList().getPlayers().size() == 2, 6000);
                dedicated.runOnServer(server ->
                {
                    var ship = WorldStore.get(server).at(server.overworld(), base.offset(4, 4, 6));
                    check(hasFlightLog(ship), "Computer files must survive launch, jumps, offline crew and landing");
                });
                dedicated.runOnServer(s ->
                {
                    check(s.getPlayerList().getPlayers().stream().allMatch(p -> p.level().dimension().equals(Level.OVERWORLD)), "Offline crew must reconnect aboard landed ship");
                    for (var p : s.getPlayerList().getPlayers())
                        check(p.getInventory().countItem(net.minecraft.world.item.Items.EMERALD) == 11, "Both inventories must survive reconnect and travel");
                    for (var p : s.getPlayerList().getPlayers())
                        check(p.getInventory().countItem(FieldGuide.ITEM) == 1, "Graphical guide items must survive launch, travel and reconnect exactly once");
                    check(s.overworld().getEntity(mobId[0]) != null && s.getLevel(TravelService.SPACE).getEntity(mobId[0]) == null, "Mob must exist exactly once after return");
                    // Items may be picked up by a passenger; either identity or inventory accounts for
                    // the cargo.
                    int gold = s.getPlayerList().getPlayers().stream().mapToInt(p -> p.getInventory().countItem(net.minecraft.world.item.Items.GOLD_INGOT)).sum();
                    check(s.overworld().getEntity(itemId[0]) != null || gold == 3, "Dropped cargo must survive");
                    var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) s.overworld().getBlockEntity(base.offset(2, 3, 2));
                    check(chest.getItem(0).getCount() == 7, "Chest cargo must survive");
                    var dropper = (net.minecraft.world.level.block.entity.DropperBlockEntity) s.overworld().getBlockEntity(base.offset(1, 3, 6));
                    check(dropper != null && dropper.getItem(0).is(net.minecraft.world.item.Items.DIAMOND) && dropper.getItem(0).getCount() == 5, "Dropper cargo must survive real multiplayer launch, offline return and" + " reconnect");
                    var brewer = (net.minecraft.world.level.block.entity.BrewingStandBlockEntity) s.overworld().getBlockEntity(base.offset(5, 3, 5));
                    check(brewer != null && brewer.getItem(0).is(net.minecraft.world.item.Items.GLASS_BOTTLE) && brewer.getItem(3).getCount() == 3, "Brewing stand cargo must survive multiplayer travel");
                    for (var p : s.getPlayerList().getPlayers())
                        check(p.containerMenu == p.inventoryMenu, "Travel must close old source container menus before its block entities are" + " removed");
                });
                ctx.takeScreenshot("local-multiplayer-return");
                var engineering = dedicated.computeOnServer(s ->
                {
                    try
                    {
                        var origin = SphereLab.ensure(s, ShipGallery.ensure(s)).base();
                        for (int x = 14; x <= 19; x++)
                            s.overworld().setBlock(origin.offset(x, 6, 21), new net.minecraft.world.level.block.Block[]{ModBlocks.GENERATOR, ModBlocks.BATTERY, ModBlocks.AIR_SUPPLY, ModBlocks.LOCAL_SCANNER, ModBlocks.LOCAL_SCANNER, ModBlocks.MONITOR}[x - 14].defaultBlockState(), 18);
                        s.overworld().setBlock(origin.offset(16, 7, 21), ModBlocks.CAMERA.defaultBlockState(), 18);
                        var vessel = ShipTopology.resolve(s.overworld(), SphereLab.controller(origin));
                        SystemsUpgrade.upgrade(s.overworld(), vessel);
                        for (var p : s.getPlayerList().getPlayers())
                        {
                            p.teleportTo(s.overworld(), origin.getX() + 16.5, origin.getY() + 16, origin.getZ() + 23.5, Set.of(), 0, 0, true);
                            s.overworld().getChunkSource().move(p);
                        }
                        return SphereLab.controller(origin);
                    }
                    catch (java.io.IOException e)
                    {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                ctx.waitTicks(20);
                dedicated.runOnServer(s ->
                {
                    for (var p : s.getPlayerList().getPlayers())
                        SystemsConsole.action(p, engineering, ComputerTerminal.Action.SYSTEMS);
                });
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.revision() >= 0, 6000);
                signal("systems-ready");
                await(ctx, "crew-system-snapshot");
                ctx.runOnClient(c ->
                {
                    var terminal = (TerminalScreen) c.gui.screen();
                    ClientPlayNetworking.send(new TerminalPackets.Request(terminal.sessionId(), ComputerTerminal.Action.CONFIGURE, "power cameras 2", "", terminal.revision()));
                });
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().contains("CAMERAS requested 2"), 6000);
                signal("systems-edited");
                await(ctx, "crew-stale-rejected");
                dedicated.runOnServer(s ->
                {
                    for (var p : s.getPlayerList().getPlayers())
                        SystemsConsole.action(p, engineering, ComputerTerminal.Action.CONFIGURE, p.getGameProfile().name().equals("NullSpace_pilot") ? "rooms" : "profiles");
                });
                signal("independent-pages");
                ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("ROOMS"), 6000);
                ctx.waitTicks(25);
                ctx.runOnClient(c -> check(c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("ROOMS"), "Pilot must keep a live room page independently of crew"));
                await(ctx, "crew-page-preserved");
                dedicated.runOnServer(s ->
                {
                    var vessel = ShipTopology.resolve(s.overworld(), engineering);
                    check(vessel.getCompoundOrEmpty("systems").getIntArray("requests").orElseThrow()[4] == 2, "Two real sessions must share one authoritative allocation");
                    for (var p : s.getPlayerList().getPlayers())
                    {
                        var oldSession = ComputerTerminal.issue(p, engineering, "", "systems", Integer.toString(ShipSystems.revision(s.overworld(), vessel)));
                        SceneService.open(p, engineering.east());
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new TerminalPackets.Update(oldSession.session(), "Delayed reading from the closed engineering screen", 9999));
                    }
                });
                ctx.waitFor(c -> c.gui.screen() instanceof ViewportScreen, 6000);
                await(ctx, "crew-camera");
                ctx.takeScreenshot("local-multiplayer-camera");
                var remote = engineering.offset(4096, 0, 0);
                var remoteTnt = new UUID[1];
                dedicated.runOnServer(s ->
                {
                    for (int x = -8; x <= 8; x++)
                        for (int z = 1; z <= 16; z++)
                        {
                            s.overworld().setBlock(remote.offset(x, -2, z), net.minecraft.world.level.block.Blocks.SEA_LANTERN.defaultBlockState(), 18);
                            if (z == 14)
                                s.overworld().setBlock(remote.offset(x, 0, z), net.minecraft.world.level.block.Blocks.CONCRETE.blue().defaultBlockState(), 18);
                        }
                    var tnt = net.minecraft.world.entity.EntityTypes.TNT.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    tnt.setPos(remote.getX() + .5, remote.getY() + .5, remote.getZ() + 8.5);
                    tnt.setFuse(100);
                    s.overworld().addFreshEntity(tnt);
                    remoteTnt[0] = tnt.getUUID();
                    var furnacePos = remote.offset(-2, -1, 8);
                    s.overworld().setBlock(furnacePos, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(), 18);
                    var furnace = (net.minecraft.world.level.block.entity.FurnaceBlockEntity) s.overworld().getBlockEntity(furnacePos);
                    furnace.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_ORE));
                    furnace.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL));
                    for (var p : s.getPlayerList().getPlayers())
                    {
                        s.getPlayerList().op(new net.minecraft.server.players.NameAndId(p.getGameProfile()));
                        p.forceSetRotation(0, false, 0, false);
                        p.level().getChunkSource().move(p);
                        s.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "nullspace viewport " + remote.getX() + " " + remote.getY() + " " + remote.getZ());
                    }
                });
                signal("remote-camera-ready");
                ctx.waitFor(c -> c.gui.screen() instanceof ViewportScreen v && v.sourcePosition().equals(remote), 6000);
                ctx.waitTicks(30);
                var content = new java.util.concurrent.atomic.AtomicInteger(-1);
                ctx.runOnClient(c ->
                {
                    check(!c.level.getChunkSource().hasChunk(remote.getX() >> 4, remote.getZ() >> 4), "Remote camera must not populate either player's ordinary chunk cache");
                    net.minecraft.client.Screenshot.takeScreenshot(((ViewportScreen) c.gui.screen()).viewport().target, image ->
                    {
                        try
                        {
                            int first = image.getPixel(0, 0), count = 0;
                            for (int y = 0; y < image.getHeight(); y += 3)
                                for (int x = 0; x < image.getWidth(); x += 3)
                                    if (image.getPixel(x, y) != first)
                                        count++;
                            content.set(count);
                        }
                        finally
                        {
                            image.close();
                        }
                    });
                });
                ctx.waitFor(c -> content.get() >= 0, 6000);
                check(content.get() > 100, "Distant source must render real geometry outside normal chunk range");
                await(ctx, "crew-remote-camera");
                dedicated.runOnServer(s ->
                {
                    var tnt = s.overworld().getEntity(remoteTnt[0]);
                    check(tnt instanceof net.minecraft.world.entity.item.PrimedTnt primed && primed.getFuse() == 100, "Camera-only chunks must not advance a TNT fuse");
                    var furnace = (net.minecraft.world.level.block.entity.FurnaceBlockEntity) s.overworld().getBlockEntity(remote.offset(-2, -1, 8));
                    check(furnace != null && furnace.getItem(1).getCount() == 1 && furnace.getItem(2).isEmpty(), "Camera-only chunks must not consume furnace fuel or run block entities");
                });
                ctx.takeScreenshot("local-multiplayer-remote-camera");
                // Two actual network clients share a fresh pad and race separate authenticated sessions.
                var deliveryBase = new net.minecraft.core.BlockPos(4096, 96, -4096);
                var deliveryBeacon = deliveryBase.offset(-1, 1, 1);
                dedicated.runOnServer(s ->
                {
                    var level = s.overworld();
                    for (int x = (deliveryBase.getX() - 4) >> 4; x <= (deliveryBase.getX() + 16) >> 4; x++)
                        for (int z = (deliveryBase.getZ() - 1) >> 4; z <= (deliveryBase.getZ() + 20) >> 4; z++)
                            level.setChunkForced(x, z, true);
                    try
                    {
                        ShipYard.buildPad(level, deliveryBase, 16, 20, "Shared Delivery");
                    }
                    catch (java.io.IOException error)
                    {
                        throw new java.io.UncheckedIOException(error);
                    }
                    level.setBlock(deliveryBeacon, ModBlocks.SHIP_BEACON.defaultBlockState(), 18);
                    for (int z = 1; z <= 3; z++)
                        level.setBlock(deliveryBase.offset(-3, 0, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 18);
                    int i = 0;
                    for (var p : s.getPlayerList().getPlayers())
                    {
                        p.teleportTo(level, deliveryBase.getX() - 2.5, deliveryBase.getY() + 1, deliveryBase.getZ() + 1.5 + i++, Set.of(), 0, 0, true);
                    }
                });
                ctx.waitTicks(40);
                dedicated.runOnServer(s ->
                {
                    for (var p : s.getPlayerList().getPlayers())
                        ShipYardService.open(p, deliveryBeacon, "mule", "Two-player delivery test");
                });
                buyShipTogether(ctx);
                await(ctx, "crew-shipyard-done");
                try
                {
                    check(!Files.readString(directory.resolve("pilot-shipyard-result")).equals(Files.readString(directory.resolve("crew-shipyard-result"))), "Exactly one real client wins the shared-pad purchase race");
                }
                catch (java.io.IOException error)
                {
                    throw new java.io.UncheckedIOException(error);
                }
                dedicated.runOnServer(s ->
                {
                    var deliveries = WorldStore.get(s).ships().stream().map(t -> (net.minecraft.nbt.CompoundTag) t).filter(t -> t.getStringOr("blueprint", "").equals("mule")).toList();
                    check(deliveries.size() == 1, "Concurrent purchases create exactly one ship identity");
                    check(WorldStore.get(s).list("shipyardOrders").isEmpty(), "The completed purchase has no leftover order");
                    for (var p : s.getPlayerList().getPlayers())
                        check(p.getInventory().countItem(net.minecraft.world.item.Items.EMERALD) == 11, "Neither buyer is charged for a free ship");
                    var ship = deliveries.getFirst();
                    var pad = PadRepository.get(s).dimension(s.overworld().dimension().identifier().toString()).stream().filter(p -> p.name().equals("Shared Delivery")).findFirst().orElseThrow();
                    HullScan.inspect(s.overworld(), WorldStore.pos(ship, "controller"), pad.bounds(), true);
                    ShipYardService.recover(s);
                    check(WorldStore.get(s).ships().stream().filter(t -> ((net.minecraft.nbt.CompoundTag) t).getStringOr("blueprint", "").equals("mule")).count() == 1, "Recovery after a buyer race never duplicates the ship");
                });
                ctx.takeScreenshot("local-multiplayer-shipyard");
                signal("shipyard-race-passed");
                signal("passed");
                // Keep live refresh running while the other client disconnects from its open shipyard.
                ctx.waitTicks(40);
                await(ctx, "crew-finished");
                dedicated.waitFor(s -> s.getPlayerList().getPlayers().size() == 1, 6000);
            }
        }
    }

    private static void crew(ClientGameTestContext ctx, int port)
    {
        await(ctx, "server-ready");
        connect(ctx, port);
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 6000);
        ctx.runOnClient(c -> ClientPlayNetworking.send(new ControllerPackets.Input(UUID.randomUUID(), ShipControl.Action.INSPECT, "")));
        ctx.waitTicks(30);
        ctx.runOnClient(c -> check(c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Local multiplayer inspection"), "A forged controller session must not execute an inspection or replace the crew's" + " real session"));
        action(ctx, ComputerTerminal.Action.INSPECT);
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Ship ready"), 6000);
        signal("crew-validated");
        await(ctx, "guide-ready");
        verifyGuide(ctx);
        signal("crew-guide");
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Ship ready after guide"), 6000);
        await(ctx, "pilot-previews");
        ctx.runOnClient(c ->
        {
            check(PreviewRenderer.count(false) == 0 && PreviewRenderer.count(true) == 0, "Another player's pad/ship previews must never appear on the crew client");
            check(c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Ship ready"), "Another player's preview actions must not replace the crew terminal");
        });
        signal("crew-no-previews");
        ctx.waitFor(LocalMultiplayerTest::space, 6000);
        signal("crew-space");
        verifyComputeScene(ctx, "initial");
        await(ctx, "scene-edited");
        ctx.waitFor(c -> NullSpaceClient.definitions().scene() != null && NullSpaceClient.definitions().scene().revision() >= 2, 6000);
        verifyComputeScene(ctx, "edited");
        await(ctx, "flight-ready");
        ctx.waitFor(c -> c.gui.screen() instanceof ShipPilotScreen s && !s.canPilot(), 6000);
        verifyPilotViewport(ctx);
        signal("crew-observing");
        await(ctx, "flight-stop");
        ctx.runOnClient(c -> c.gui.screen().onClose());
        signal("crew-flight-closed");
        for (String phase : new String[]{"luna", "home"})
        {
            await(ctx, "jump-" + phase);
            String sceneId = phase.equals("luna") ? "nullspace:sol_luna" : "nullspace:local-space";
            ctx.waitFor(c -> space(c) && NullSpaceClient.definitions().scene() != null && NullSpaceClient.definitions().scene().id().equals(sceneId), 6000);
            verifyComputeScene(ctx, phase);
            signal("crew-" + phase);
        }
        await(ctx, "disconnect-crew");
        ctx.runOnClient(c -> c.getConnection().getConnection().disconnect(Component.literal("Local offline-arrival test")));
        try
        {
            ctx.waitFor(c -> c.level == null && NullSpaceClient.definitions().scene() == null && SpaceComputeRenderer.liveViews() == 0, 200);
        }
        catch (AssertionError timeout)
        {
            ctx.runOnClient(c ->
            {
                throw new AssertionError("Disconnect ownership: world=" + (c.level != null) + " definition=" + (NullSpaceClient.definitions().scene() != null) + " GPU views=" + SpaceComputeRenderer.liveViews(), timeout);
            });
            throw timeout;
        }
        await(ctx, "reconnect-crew");
        connect(ctx, port);
        ctx.waitFor(c -> c.level != null && c.level.dimension().equals(Level.OVERWORLD), 6000);
        signal("crew-returned");
        await(ctx, "systems-ready");
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.revision() >= 0, 6000);
        var stale = new TerminalPackets.Request[1];
        ctx.runOnClient(c ->
        {
            var terminal = (TerminalScreen) c.gui.screen();
            stale[0] = new TerminalPackets.Request(terminal.sessionId(), ComputerTerminal.Action.CONFIGURE, "power air 0", "", terminal.revision());
        });
        signal("crew-system-snapshot");
        await(ctx, "systems-edited");
        ctx.runOnClient(c -> ClientPlayNetworking.send(stale[0]));
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().contains("Systems changed"), 6000);
        signal("crew-stale-rejected");
        await(ctx, "independent-pages");
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("CRUISE"), 6000);
        ctx.waitTicks(25);
        ctx.runOnClient(c -> check(c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("CRUISE"), "Crew must keep a separate profile page across automatic updates"));
        signal("crew-page-preserved");
        ctx.waitFor(c -> c.gui.screen() instanceof ViewportScreen, 6000);
        signal("crew-camera");
        await(ctx, "remote-camera-ready");
        ctx.waitTicks(40);
        ctx.runOnClient(c ->
        {
            if (!(c.gui.screen() instanceof ViewportScreen))
                throw new AssertionError("Crew must retain the remote viewport");
        });
        signal("crew-remote-camera");
        buyShipTogether(ctx);
        await(ctx, "passed");
        ctx.runOnClient(c -> c.getConnection().getConnection().disconnect(Component.literal("Local verification complete")));
        ctx.waitFor(c -> c.level == null, 6000);
        ctx.runOnClient(c -> c.gui.setScreen(new TitleScreen()));
        signal("crew-finished");
    }

    private LocalMultiplayerTest()
    {
    }
}
