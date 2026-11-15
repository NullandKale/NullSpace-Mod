package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.entity.Stowaways;
import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.worldgen.encounters.SiteThreats;

import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Every creature a derelict can hold, at every danger (the crew, the repair bot and the stowaways), standing still in a lit hall for review.
 * {@code /nullspace shipyard enemies} builds it: one row per creature, one column per danger from 0 to 3, 44 in all, built
 * exactly as a site would build them (suits, ranks and vanilla's spawn upgrades for that danger). They have no AI and cannot
 * be hurt, so you can walk up and look at gear and colors. The hall is rebuilt in the same place each visit.
 */
public final class EnemyGallery
{
    public static final int COLUMN_PITCH = 6, ROW_PITCH = 5, MARGIN = 5, HEIGHT = 6;

    /** A row: its label, and how a creature of it is made at a danger. */
    private record Kind(String label, java.util.function.BiFunction<ServerLevel, Integer, Mob> make)
    {}

    private static Mob crew(ServerLevel level, EntityType<? extends Mob> type, int danger)
    {
        return SiteThreats.crew(level, type, SiteThreats.difficulty(danger, 0));
    }

    private static Mob special(ServerLevel level, EntityType<? extends Mob> type, int danger)
    {
        return SiteThreats.special(level, type, SiteThreats.difficulty(danger, 0));
    }

    private static final List<Kind> KINDS = List.of(new Kind("Zombie", (l, d) -> crew(l, EntityTypes.ZOMBIE, d)), new Kind("Zombie villager", (l, d) -> crew(l, EntityTypes.ZOMBIE_VILLAGER, d)), new Kind("Skeleton", (l, d) -> crew(l, EntityTypes.SKELETON, d)), new Kind("Stray", (l, d) -> crew(l, EntityTypes.STRAY, d)), new Kind("Wither skeleton", (l, d) -> crew(l, EntityTypes.WITHER_SKELETON, d)), new Kind("Repair bot", (l, d) -> SiteThreats.REPAIR_BOT.create(l, EntitySpawnReason.STRUCTURE)), new Kind("Ghost creeper", (l, d) -> special(l, Stowaways.GHOST_CREEPER, d)), new Kind("Pale spider", (l, d) -> special(l, Stowaways.PALE_SPIDER, d)), new Kind("Pale cave spider", (l, d) -> special(l, Stowaways.PALE_CAVE_SPIDER, d)), new Kind("Pale silverfish", (l, d) -> special(l, Stowaways.PALE_SILVERFISH, d)), new Kind("Violet slime", (l, d) -> special(l, Stowaways.VIOLET_SLIME, d)), new Kind("Violet blaze", (l, d) -> special(l, Stowaways.VIOLET_BLAZE, d)));

    public static int count()
    {
        return KINDS.size() * 4;
    }

    public static void initialize()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.literal("enemies").executes(ctx ->
        {
            var player = ctx.getSource().getPlayerOrException();
            try
            {
                var server = player.level().getServer();
                var origin = build(server);
                player.teleportTo(server.overworld(), origin.getX() + 2.5, origin.getY() + 1, origin.getZ() + (MARGIN * 2 + KINDS.size() * ROW_PITCH) / 2.0, Set.of(), -90, 10, true);
                player.sendSystemMessage(Component.literal("Enemy gallery: %d creatures, one row each (zombie, zombie villager, skeleton, stray, wither skeleton, repair bot, ghost creeper, pale spider, pale cave spider, pale silverfish, violet slime, violet blaze) and one column per danger 0 to 3, going east. They stand still and cannot be hurt.".formatted(count())));
                return 1;
            }
            catch (Exception error)
            {
                player.sendSystemMessage(Component.literal("Could not build the enemy gallery: " + ShipMessages.failure(error)));
                return 0;
            }
        })))));
    }

    /** Builds the hall and its creatures, replacing any earlier one; returns its south-west corner. */
    public static BlockPos build(MinecraftServer server)
    {
        var level = server.overworld();
        int width = MARGIN * 2 + 4 * COLUMN_PITCH, depth = MARGIN * 2 + KINDS.size() * ROW_PITCH;
        int x = 1024 + 2400, z = 2048;
        for (int cx = x >> 4; cx <= (x + width) >> 4; cx++)
            for (int cz = z >> 4; cz <= (z + depth) >> 4; cz++)
                level.getChunk(cx, cz);
        int y = level.getMinY();
        for (int dx = 0; dx < width; dx += 2)
            for (int dz = 0; dz < depth; dz += 2)
                y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
        y += 4;
        if (y + HEIGHT + 4 >= level.getMaxY())
            throw new IllegalArgumentException("No room above the terrain for a gallery.");
        var origin = new BlockPos(x, y, z);
        level.getEntitiesOfClass(Mob.class, new AABB(x - 2, y - 1, z - 2, x + width + 2, y + HEIGHT + 2, z + depth + 2)).forEach(Mob::discard);
        // A closed hall: floor, walls and a roof with lamps. The roof also keeps zombies and skeletons out of the sun.
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                for (int dy = 0; dy <= HEIGHT; dy++)
                {
                    boolean edge = dx == 0 || dz == 0 || dx == width - 1 || dz == depth - 1;
                    boolean lamp = dx % 4 == 2 && dz % 5 == 2;
                    var block = dy == 0 ? (lamp ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray()) : dy == HEIGHT ? (lamp ? Blocks.SEA_LANTERN : Blocks.STONE_BRICKS) : edge ? Blocks.STONE_BRICKS : Blocks.AIR;
                    level.setBlock(origin.offset(dx, dy, dz), block.defaultBlockState(), 18);
                }
        for (int column = 0; column < 4; column++)
            ShipGallery.label(level, origin.offset(MARGIN + column * COLUMN_PITCH, 1, 2), DyeColor.YELLOW, "Danger " + column, column == 0 ? "no threats" : column == 1 ? "near" : column == 2 ? "middle" : "deepest");
        for (int row = 0; row < KINDS.size(); row++)
        {
            ShipGallery.label(level, origin.offset(2, 1, MARGIN + row * ROW_PITCH), DyeColor.GREEN, KINDS.get(row).label);
            for (int column = 0; column < 4; column++)
            {
                var mob = KINDS.get(row).make.apply(level, column);
                if (mob == null)
                    continue;
                mob.snapTo(origin.getX() + MARGIN + column * COLUMN_PITCH + .5, origin.getY() + 1, origin.getZ() + MARGIN + row * ROW_PITCH + .5, 90, 0);
                mob.setNoAi(true);
                mob.setPermanentlyInvulnerable(true);
                mob.setSilent(true);
                mob.setPersistenceRequired();
                level.addFreshEntity(mob);
            }
        }
        return origin;
    }

    private EnemyGallery()
    {
    }
}
