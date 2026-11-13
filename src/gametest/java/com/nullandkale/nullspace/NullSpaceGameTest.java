package com.nullandkale.nullspace;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class NullSpaceGameTest
{
    @GameTest
    public void versionCommandWorksForOrdinaryPlayersAndConsole(GameTestHelper context) throws Exception
    {
        var server = context.getLevel().getServer();
        var messages = new java.util.ArrayList<String>();
        var output = new net.minecraft.commands.CommandSource()
        {
            public void sendSystemMessage(net.minecraft.network.chat.Component message)
            {
                messages.add(message.getString());
            }

            public boolean acceptsSuccess()
            {
                return true;
            }

            public boolean acceptsFailure()
            {
                return true;
            }

            public boolean shouldInformAdmins()
            {
                return false;
            }
        };
        var player = context.makeMockServerPlayerInLevel();
        context.assertTrue(!player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER), "Version test uses an ordinary player");
        for (var source : java.util.List.of(player.createCommandSourceStack(), server.createCommandSourceStack()))
        {
            messages.clear();
            int result = server.getCommands().getDispatcher().execute("nullspace version", source.withSource(output));
            var metadata = FabricLoader.getInstance().getModContainer(NullSpace.MOD_ID).orElseThrow().getMetadata();
            context.assertTrue(result == 1 && messages.size() == 1, "Command succeeds and prints one response");
            context.assertTrue(messages.getFirst().contains("NullSpace " + metadata.getVersion().getFriendlyString()), "Command reports the loaded mod version");
            context.assertTrue(messages.getFirst().contains(metadata.getCustomValue("nullspace:build").getAsString()), "Command reports the packaged build label");
        }
        context.succeed();
    }

    @GameTest
    public void modLoadsAndWorldTicks(GameTestHelper context)
    {
        if (!FabricLoader.getInstance().isModLoaded(NullSpace.MOD_ID))
        {
            throw new IllegalStateException("NullSpace was not loaded into the test server");
        }
        context.setBlock(1, 1, 1, Blocks.STONE);
        context.runAfterDelay(1, () ->
        {
            context.assertBlockPresent(Blocks.STONE, 1, 1, 1);
            context.succeed();
        });
    }
}
