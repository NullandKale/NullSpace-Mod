package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.gameplay.ship.ShipMessages;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.piston.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;

/** One transfer policy shared by preflight and the generated vanilla compatibility catalog. */
public final class BlockSupport
{
    public static final int TRANSFER_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
    public static final Set<String> PORTABLE_ENTITIES = Set.of("furnace", "chest", "trapped_chest", "ender_chest", "jukebox", "dispenser", "dropper", "sign", "hanging_sign", "brewing_stand", "enchanting_table", "beacon", "skull", "daylight_detector", "hopper", "comparator", "banner", "shulker_box", "conduit", "barrel", "smoker", "blast_furnace", "lectern", "bell", "campfire", "beehive", "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst", "sculk_shrieker", "chiseled_bookshelf", "shelf", "brushable_block", "decorated_pot", "crafter", "copper_golem_statue");

    /** Every block entity travels with its saved data, except a piston that is mid-move. */
    public static boolean portable(BlockEntityType<?> type)
    {
        var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        return id == null || !(id.getNamespace().equals("minecraft") && id.getPath().equals("piston"));
    }

    public static String entityRestriction(BlockEntityType<?> type)
    {
        if (portable(type))
            return "";
        return switch (BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).getPath())
        {
            case "piston" -> "Wait for this piston to finish moving before launching.";
            case "mob_spawner", "trial_spawner", "vault" -> "Spawner and vault encounter data cannot travel yet. Leave this block on the planet.";
            case "creaking_heart" -> "Creaking hearts retain a world-bound protector. Leave this heart on the planet.";
            case "potent_sulfur" -> "Sulfur eruption timing cannot be preserved yet. Leave this block on the planet.";
            case "end_gateway", "end_portal" -> "Active portals cannot travel. Remove the portal before launching.";
            case "command_block", "structure_block", "jigsaw", "test_block", "test_instance_block" -> "World editing, command, and test blocks cannot travel. Remove this block before" + " launching.";
            default -> "This block entity has no reviewed transfer support yet. Move it off the ship before" + " launching.";
        };
    }

    /**
     * World-independent state restrictions. Empty means eligible, subject to the live checks below.
     */
    public static String stateRestriction(BlockState state)
    {
        // Liquids, waterlogged blocks, fire, portals and shriekers all travel. Only a piston that is
        // mid-move must settle first.
        if (state.is(Blocks.MOVING_PISTON))
            return "Wait for this piston to finish moving before launching.";
        if (state.getBlock() instanceof PistonHeadBlock && state.getValue(PistonHeadBlock.SHORT))
            return "Wait for this piston head to finish moving before launching.";
        return "";
    }

    public static void validate(ServerLevel level, BlockPos pos)
    {
        var state = level.getBlockState(pos);
        String reason = stateRestriction(state);
        if (!reason.isEmpty())
            throw ShipMessages.blockProblem(level, pos, reason);
        var be = level.getBlockEntity(pos);
        if (be != null)
        {
            reason = entityRestriction(be.getType());
            if (!reason.isEmpty())
                throw new HullScan.Failure(ShipMessages.block(level, pos) + ": " + reason + "\nBlock entity: " + be.typeHolder().getRegisteredName(), pos, List.of(pos));
        }
        else if (state.hasBlockEntity())
            throw ShipMessages.blockProblem(level, pos, "This block's saved data is still loading or missing. Wait a moment; if it persists," + " replace the block before launching.");
        // Pending block actions (clocks, buttons, repeaters) travel with the ship.
        if (state.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below())))
            throw ShipMessages.blockProblem(level, pos, "Support this falling block from below and let it settle before launching.");
        if (be instanceof VibrationSystem vibration)
        {
            var data = vibration.getVibrationData();
            if (data.getCurrentVibration() != null || data.getSelectionStrategy().chosenCandidate(Long.MAX_VALUE).isPresent())
                throw ShipMessages.blockProblem(level, pos, "This sculk device is processing a vibration. Stand still and let it finish before" + " launching.");
        }
        if (be instanceof SculkCatalystBlockEntity catalyst && !catalyst.getListener().getSculkSpreader().getCursors().isEmpty())
            throw ShipMessages.blockProblem(level, pos, "This catalyst is spreading sculk. Let the spreading finish before launching.");
    }

    public static void validatePistons(ServerLevel level, Set<BlockPos> captured)
    {
        for (var pos : captured)
        {
            var s = level.getBlockState(pos);
            if (s.getBlock() instanceof PistonBaseBlock && s.getValue(PistonBaseBlock.EXTENDED))
            {
                var head = pos.relative(s.getValue(PistonBaseBlock.FACING));
                var h = level.getBlockState(head);
                if (!captured.contains(head) || !(h.getBlock() instanceof PistonHeadBlock) || h.getValue(PistonHeadBlock.FACING) != s.getValue(PistonBaseBlock.FACING) || (h.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY) != s.is(Blocks.STICKY_PISTON))
                    throw ShipMessages.blockProblem(level, pos, "This extended piston needs its matching stationary head inside the ship. Retract or" + " repair it before launching.");
            }
            else if (s.getBlock() instanceof PistonHeadBlock)
            {
                var base = pos.relative(s.getValue(PistonHeadBlock.FACING).getOpposite());
                var b = level.getBlockState(base);
                if (!captured.contains(base) || !(b.getBlock() instanceof PistonBaseBlock) || !b.getValue(PistonBaseBlock.EXTENDED) || b.getValue(PistonBaseBlock.FACING) != s.getValue(PistonHeadBlock.FACING))
                    throw ShipMessages.blockProblem(level, pos, "This piston head is detached from its base. Repair or remove it before launching.");
            }
        }
    }

    /**
     * Translate hive flower memories only when the flower itself travels; forget planetary flowers.
     */
    public static CompoundTag destinationTemplate(CompoundTag original, Set<BlockPos> captured, BlockPos delta)
    {
        var copy = original.copy();
        for (var entry : copy.getListOrEmpty("blocks"))
        {
            var tag = ((CompoundTag) entry).getCompoundOrEmpty("nbt");
            if (!tag.getStringOr("id", "").equals("minecraft:beehive"))
                continue;
            flower(tag, captured, delta);
            for (var bee : tag.getListOrEmpty("bees"))
                flower(((CompoundTag) bee).getCompoundOrEmpty("entity_data"), captured, delta);
        }
        return copy;
    }

    /** Keep the complete original in the recovery journal, but move only the connected hull. */
    public static CompoundTag connectedTemplate(CompoundTag original, BlockPos min, Set<BlockPos> captured)
    {
        var copy = original.copy();
        copy.getListOrEmpty("blocks").removeIf(entry ->
        {
            var coords = ((CompoundTag) entry).getListOrEmpty("pos");
            return !captured.contains(min.offset(coords.getIntOr(0, 0), coords.getIntOr(1, 0), coords.getIntOr(2, 0)));
        });
        return copy;
    }

    private static void flower(CompoundTag tag, Set<BlockPos> captured, BlockPos delta)
    {
        tag.read("flower_pos", BlockPos.CODEC).ifPresent(pos ->
        {
            if (captured.contains(pos))
                tag.store("flower_pos", BlockPos.CODEC, pos.offset(delta));
            else
                tag.remove("flower_pos");
        });
    }

    public record CatalogEntry(String id, String name, String status, String blockEntity, int eligibleStates, int totalStates, List<String> notes)
    {}

    public static List<CatalogEntry> catalog()
    {
        var rows = new ArrayList<CatalogEntry>();
        for (var block : BuiltInRegistries.BLOCK)
        {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals("minecraft"))
                continue;
            var state = block.defaultBlockState();
            var notes = new LinkedHashSet<String>();
            String entity = "";
            boolean portable = true;
            int eligible = 0;
            if (state.hasBlockEntity() && block instanceof EntityBlock factory)
            {
                var be = factory.newBlockEntity(BlockPos.ZERO, state);
                if (be == null)
                {
                    portable = false;
                    notes.add("No portable block-entity factory.");
                }
                else
                {
                    entity = be.typeHolder().getRegisteredName();
                    portable = portable(be.getType());
                    if (!portable)
                        notes.add(entityRestriction(be.getType()));
                }
            }
            for (var variant : block.getStateDefinition().getPossibleStates())
            {
                String why = stateRestriction(variant);
                if (why.isEmpty())
                    eligible++;
                else
                    notes.add(why);
            }
            if (state.isAir())
            {
                portable = false;
                notes.add("Empty space, not captured ship material.");
            }
            if (block instanceof FallingBlock)
                notes.add("Must be supported from below and settled.");
            if (block instanceof PistonBaseBlock || block instanceof PistonHeadBlock)
                notes.add("Stationary only; extended pistons need a matching head and base within the ship.");
            if (block instanceof DoorBlock)
                notes.add("Closed, matching halves with a full sealing frame are required for travel.");
            if (block instanceof TrapDoorBlock)
                notes.add("Decorative panels may travel; airtight hatches need a closed four-sided horizontal" + " frame.");
            if (Set.of("minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor", "minecraft:sculk_shrieker").contains(entity))
                notes.add("No pending vibrations or block actions; summoning shriekers are excluded.");
            if (entity.equals("minecraft:sculk_catalyst"))
                notes.add("No active sculk-spreading cursors.");
            if (entity.equals("minecraft:beehive"))
                notes.add("Stored bees travel. Flower memories follow captured flowers; planetary flower memories" + " are cleared.");
            if (state.is(Blocks.LODESTONE))
                notes.add("Existing lodestone compasses retain their original dimension/coordinates.");
            if (block instanceof BedBlock || state.is(Blocks.RESPAWN_ANCHOR))
                notes.add("Existing player respawn bindings are not retargeted; use obeys vanilla dimension" + " rules.");
            if (entity.equals("minecraft:ender_chest"))
                notes.add("Personal ender inventory remains player-owned.");
            if (entity.equals("minecraft:beacon") || entity.equals("minecraft:conduit"))
                notes.add("Effects recalculate from the destination environment and physical structure.");
            if (block instanceof DiodeBlock || block instanceof ObserverBlock || block instanceof RedstoneWireBlock || block instanceof DispenserBlock || block instanceof CrafterBlock)
                notes.add("Stop clocks and wait for scheduled ticks and queued actions before travel.");
            if (block instanceof BrushableBlock)
                notes.add("Must be supported and not being brushed.");
            String status = !portable || eligible == 0 ? "UNSUPPORTED" : eligible < block.getStateDefinition().getPossibleStates().size() || !notes.isEmpty() ? "CONDITIONAL" : "SUPPORTED";
            rows.add(new CatalogEntry(id.toString(), ShipMessages.block(block), status, entity, eligible, block.getStateDefinition().getPossibleStates().size(), List.copyOf(notes)));
        }
        rows.sort(Comparator.comparing(CatalogEntry::id));
        return List.copyOf(rows);
    }

    private BlockSupport()
    {
    }
}
