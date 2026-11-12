package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.save.Journals;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.StoreRecords;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.crew.ShipRespawns;
import com.nullandkale.nullspace.gameplay.navigation.Jumps;
import com.nullandkale.nullspace.gameplay.navigation.Piloting;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.ownership.CrewRelease;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.BlockSupport;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.LooseBlocks;
import com.nullandkale.nullspace.gameplay.ship.structure.Occupants;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService.Outcome;
import com.nullandkale.nullspace.gameplay.travel.TravelService.Pending;
import com.nullandkale.nullspace.gameplay.travel.TravelService.Result;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.nio.file.Path;
import java.util.*;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

import static com.nullandkale.nullspace.gameplay.travel.TravelService.SPACE;
import static com.nullandkale.nullspace.gameplay.travel.TravelService.SPACE_SPACING;

/**
 * One physical ship move (launch, landing, detaching loose blocks, recall or storage), run as a
 * sequence of phases. Checks come first and may stop the move. From the PREPARED checkpoint on, the
 * transfer journal records each stage so a crash can be replayed or rolled back (TransferRecovery).
 */
final class ShipTransfer
{
    // What was asked for
    private final ServerPlayer actor;
    private final ServerLevel source;
    private final BlockPos controller;
    private final String destination;
    private final ShipService.Inspection inspected;
    private final boolean recall;
    private final MinecraftServer server;

    // Source ship
    private Pending current;
    private boolean detaching, leavingPieces;
    private WorldStore store;
    private HullScan.Result scan;
    private BlockPos sourceBase, min, max;
    private CompoundTag vessel;
    private String id;

    // Destination
    private ServerLevel target;
    private BlockPos targetBase, delta, targetMin, targetMax;
    private PadRegistry.Pad landing;
    private ShipTransform transform;
    private String mainEntrance = "";
    private BlockPos mainExit;
    private final Map<BlockPos, String> entranceIds = new LinkedHashMap<>();

    // What travels
    private List<Entity> cargo;
    private List<ServerPlayer> crew;
    private final Set<UUID> cargoIds = new HashSet<>();
    private final List<Entity> roots = new ArrayList<>();

    // Journal
    private CompoundTag journal, after, beforeStore;
    private ListTag ships;
    private Path file;

    ShipTransfer(ServerPlayer actor, ServerLevel source, BlockPos controller, String destination, ShipService.Inspection inspected, boolean recall)
    {
        this.actor = actor;
        this.source = source;
        this.controller = controller;
        this.destination = destination;
        this.inspected = inspected;
        this.recall = recall;
        this.server = source.getServer();
    }

    Result run()
    {
        return RecordJournal.withinOperation(server, this::runJournaled);
    }

    private Result runJournaled()
    {
        Result stop;
        if ((stop = checkSource()) != null)
            return stop;
        if ((stop = chooseDestination()) != null)
            return stop;
        if ((stop = checkDestination()) != null)
            return stop;
        if ((stop = checkPassengersAndFuel()) != null)
            return stop;
        captureHull();
        if ((stop = saveCargo()) != null)
            return stop;
        buildAfterState();
        TransferRecovery.checkpoint(file, journal, "PREPARED");
        stageDestination();
        TransferRecovery.checkpoint(file, journal, "DESTINATION_STAGED");
        HullScan.clearCover(source, scan.blocks());
        TransferReplay.clear(source, scan.blocks());
        TransferRecovery.checkpoint(file, journal, "SOURCE_CLEARED");
        store.replace(after, ShipRepository.Mutation.TRANSFER);
        movePassengers();
        TransferRecovery.checkpoint(file, journal, "PASSENGERS_MOVED");
        store.replace(after, ShipRepository.Mutation.TRANSFER);
        server.saveEverything(true, true, true);
        // The world-save hook wrote the matching store copy after the chunks.
        journal.putLong("saveNumber", store.saveNumberCovering());
        TransferRecovery.checkpoint(file, journal, "COMMITTED");
        TransferRecovery.finish(file, journal, "COMMITTED");
        return new Result(Outcome.COMPLETED, "Arrived");
    }

    /** The request still matches, nothing else holds the area, and engines still have power. */
    private Result checkSource()
    {
        current = recall ? null : TravelService.pending(server).values().stream().filter(p -> p.actor.equals(actor.getUUID()) && p.computer.equals(controller) && p.destination.equals(destination) && p.sourceDimension.equals(source.dimension().identifier().toString())).findFirst().orElse(null);
        detaching = current != null && current.destination.equals(TravelService.DETACH);
        if (detaching && LooseBlocks.approved(source, controller).isEmpty())
            return Result.rejected("Loose blocks changed; review and confirm them again");
        if (TransferLocks.areaBusy(source, inspected.scan().bounds(), current, recall ? actor.getUUID() : null))
            return Result.rejected("This ship's area is reserved by another travel or recall");
        ComputerService.flush(source.getServer());
        if (inspected.vessel() != null)
        {
            if (!recall && !detaching && ShipSystems.enabled(inspected.vessel()) && ShipSystems.power(source, inspected.vessel(), PowerBus.System.ENGINES) == 0)
                return Result.rejected("Propulsion lost power before transfer");
            ShipSystems.cancelTransit(source, inspected.vessel());
        }
        store = WorldStore.get(server);
        scan = inspected.scan();
        sourceBase = inspected.base();
        leavingPieces = !recall && !LooseBlocks.approved(source, controller).isEmpty();
        vessel = inspected.vessel();
        id = vessel == null ? UUID.randomUUID().toString() : vessel.getStringOr("id", "");
        return null;
    }

    /** A clear, registered landing pad below, or a free storage slot in space. */
    private Result chooseDestination()
    {
        if (source.dimension().equals(SPACE) && !detaching)
        {
            landing = PadRegistry.read(server).stream().filter(p -> destination.startsWith("id:") ? p.id().equals(destination.substring(3)) : p.name().equalsIgnoreCase(destination.strip())).findFirst().orElse(null);
            if (landing == null)
                return Result.rejected("Unknown landing pad; use destinations to list names");
            if (!recall && !PlanetSurfaces.surfaceOf(server, LocalSpaces.address(vessel), landing.dimension()))
                return Result.rejected("Jump to the destination planet before landing at this pad");
            target = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(landing.dimension())));
            if (target == null)
                return Result.rejected("Destination dimension is unavailable");
            var validation = PadRegistry.check(target, landing);
            if (validation.state() != PadRegistry.State.READY)
                return Result.rejected(validation.detail());
            var b = landing.bounds();
            targetBase = new BlockPos(b.minX(), b.y() + 1, b.minZ());
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int z = b.minZ(); z <= b.maxZ(); z++)
                    for (int y = b.y() + 1; y <= target.getMaxY(); y++)
                        if (!arrivalClear(target.getBlockState(new BlockPos(x, y, z))))
                            return TravelService.blocked(target, new BlockPos(x, y, z), "The landing column is obstructed. Clear this block above the destination pad," + " then try landing again.");
        }
        else
        {
            target = server.getLevel(SPACE);
            if (target == null)
                return Result.rejected("Space dimension is not loaded; restart with NullSpace enabled");
            if (current != null)
                targetBase = current.targetBase;
            else
            {
                int slot = 0;
                while (slot <= 7000 && !TransferLocks.spaceSlotFree(server, slot))
                    slot++;
                if (slot > 7000)
                    return Result.rejected("No free physical ship regions remain");
                targetBase = new BlockPos(slot * SPACE_SPACING, 96, 0);
            }
        }
        return null;
    }

    /** The destination is inside the world, unreserved, loaded and empty of blocks and entities. */
    private Result checkDestination()
    {
        delta = targetBase.subtract(sourceBase);
        int turns = 0;
        if (landing != null)
        {
            var ports = new ArrayList<com.nullandkale.nullspace.gameplay.docking.DockingPorts.Port>();
            for (var at : scan.blocks().stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList())
                if (source.getBlockState(at).is(com.nullandkale.nullspace.content.ModBlocks.DOCKING_BASE))
                    try
                    {
                        var port = com.nullandkale.nullspace.gameplay.docking.DockingPorts.readPort(source, vessel.copy(), at, false);
                        ports.add(port);
                        entranceIds.put(at.subtract(sourceBase), port.id());
                    }
                    catch (IllegalArgumentException invalid)
                    {
                        /* Other framed exits may still be usable. */ }
            var pad = landing.bounds();
            var computer = landing.computer();
            Direction side = computer.getX() < pad.minX() ? Direction.WEST : computer.getX() > pad.maxX() ? Direction.EAST : computer.getZ() < pad.minZ() ? Direction.NORTH : Direction.SOUTH;
            if (!ports.isEmpty())
            {
                var port = ports.stream().filter(p -> p.id().equals(vessel.getStringOr("mainEntrance", ""))).findFirst().orElse(ports.getFirst());
                mainEntrance = port.id();
                turns = Math.floorMod(side.get2DDataValue() - port.facing().get2DDataValue(), 4);
            }
            else
            {
                // Hand-built hulls may have an exterior door without docking hardware.
                var exits = new LinkedHashMap<BlockPos, Direction>();
                for (var at : scan.blocks().stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList())
                {
                    var state = source.getBlockState(at);
                    if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) || !com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors.root(state))
                        continue;
                    var facing = state.getValue(net.minecraft.world.level.block.DoorBlock.FACING);
                    boolean front = scan.pressure().breathable(at.relative(facing));
                    boolean back = scan.pressure().breathable(at.relative(facing.getOpposite()));
                    if (front != back)
                        exits.put(at.subtract(sourceBase), back ? facing : facing.getOpposite());
                }
                if (!exits.isEmpty())
                {
                    var saved = vessel.contains("mainExit") ? WorldStore.pos(vessel, "mainExit") : null;
                    mainExit = exits.containsKey(saved) ? saved : exits.keySet().iterator().next();
                    turns = Math.floorMod(side.get2DDataValue() - exits.get(mainExit).get2DDataValue(), 4);
                }
            }
        }
        transform = new ShipTransform(sourceBase, targetBase, vessel == null ? inspected.pad().bounds().width() : vessel.getIntOr("width", 32), vessel == null ? inspected.pad().bounds().depth() : vessel.getIntOr("depth", 32), turns);
        if (landing != null && turns != 0)
        {
            var rotated = transform.bounds(scan.bounds());
            int w = rotated.max().getX() - rotated.min().getX() + 1;
            int d = rotated.max().getZ() - rotated.min().getZ() + 1;
            var pad = landing.bounds();
            var offset = new BlockPos(pad.minX() + Math.floorDiv(pad.width() - w, 2) - rotated.min().getX(), 0, pad.minZ() + Math.floorDiv(pad.depth() - d, 2) - rotated.min().getZ());
            transform = new ShipTransform(sourceBase, targetBase, transform.width(), transform.depth(), turns, offset);
        }
        var destinationBounds = transform.bounds(scan.bounds());
        targetMin = destinationBounds.min();
        targetMax = destinationBounds.max();
        if (landing != null && (targetMin.getX() < landing.bounds().minX() || targetMin.getZ() < landing.bounds().minZ() || targetMax.getX() > landing.bounds().maxX() || targetMax.getZ() > landing.bounds().maxZ()))
            return Result.rejected("Ship does not fit this pad with its entrance facing the Pad Computer");
        if (TransferLocks.areaBusy(target, new HullScan.Bounds(targetMin, targetMax), current, recall ? actor.getUUID() : null))
            return Result.rejected("This destination is reserved by another ship's travel or recall");
        if (targetMin.getY() < target.getMinY() || targetMax.getY() > target.getMaxY() || !target.getWorldBorder().isWithinBounds(targetMin) || !target.getWorldBorder().isWithinBounds(targetMax))
            return Result.rejected("Destination exceeds world limits");
        for (BlockPos pos : BlockPos.betweenClosed(targetMin, targetMax))
        {
            target.getChunk(pos);
            if (recall && !actor.mayInteract(target, pos))
                return Result.rejected("You cannot build on part of this landing pad");
            if (!arrivalClear(target.getBlockState(pos)))
                return TravelService.blocked(target, pos, "The destination area is occupied. Clear this block or choose another landing pad.");
        }
        for (int x = targetMin.getX() >> 4; x <= targetMax.getX() >> 4; x++)
            for (int z = targetMin.getZ() >> 4; z <= targetMax.getZ() >> 4; z++)
                target.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, new ChunkPos(x, z), 2);
        if (!HullEntities.loaded(target, new HullScan.Bounds(targetMin, targetMax)))
            return Result.waiting();
        var blockers = HullEntities.inBounds(target, new HullScan.Bounds(targetMin, targetMax)).stream().filter(e -> landing == null || !(e instanceof net.minecraft.world.entity.item.ItemEntity)).toList();
        if (!blockers.isEmpty())
        {
            var blocker = blockers.getFirst();
            NullSpace.LOGGER.info("Ship destination occupied: {} {} at {} in {}", blocker.getType(), blocker.getUUID(), blocker.position(), target.dimension().identifier());
            return Result.rejected("The destination is occupied. Move this occupant clear or choose another landing pad." + "\nOccupant: " + ShipMessages.entity(blocker) + "\nLocation: " + blocker.blockPosition().toShortString());
        }
        return null;
    }

    private boolean arrivalClear(net.minecraft.world.level.block.state.BlockState state)
    {
        return state.isAir() || HullScan.cover(state) || landing != null && (state.is(net.minecraft.world.level.block.Blocks.LAVA) || state.is(net.minecraft.world.level.block.Blocks.WATER));
    }

    /** Crew still match the loading screen, recall moves no one, and launch fuel is paid. */
    private Result checkPassengersAndFuel()
    {
        if (!HullEntities.loaded(source, scan.bounds()))
            return Result.waiting();
        var occupants = Occupants.inspect(source, scan, recall);
        cargo = occupants.cargo();
        crew = occupants.crew();
        if (recall && !crew.isEmpty())
            return Result.rejected("Recall cancelled: someone boarded the ship");
        if (current != null && !crew.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet()).equals(current.crew.keySet().stream().filter(uuid -> server.getPlayerList().getPlayer(uuid) != null).collect(java.util.stream.Collectors.toSet())))
            return Result.rejected("Passenger list changed; inspect and launch again");
        var fuelShip = vessel == null ? new CompoundTag() : vessel;
        if (vessel == null)
        {
            fuelShip.putLong("base", sourceBase.asLong());
            fuelShip.putLong("controller", controller.asLong());
        }
        int fuelCost = FuelSystem.travelCost(source, fuelShip, FuelSystem.transfer(scan.blocks().size()));
        if (!recall && !detaching && !FuelSystem.spend(source, fuelShip, fuelCost))
            return Result.rejected("Not enough fuel for travel: needs " + FuelSystem.display(fuelCost) + ". Load furnace fuel into a connected generator.");
        return null;
    }

    /** Copies the hull's blocks into the journal. Only the hull is saved, not the rest of its box. */
    private void captureHull()
    {
        StructureTemplate template = new StructureTemplate();
        min = scan.bounds().min();
        max = scan.bounds().max();
        template.fillFromWorld(source, min, new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1), false, List.of());
        if (target.dimension().equals(SPACE))
            SceneCatalog.forShip(server, vessel);
        try
        {
            journal = Journals.create(store.directory, Journals.Kind.MOVE, recall ? "recall" : target.dimension().equals(SPACE) ? "launch" : "landing", List.of(id));
        }
        catch (java.io.IOException error)
        {
            throw new java.io.UncheckedIOException(error);
        }
        journal.putString("source", source.dimension().identifier().toString());
        journal.putString("target", target.dimension().identifier().toString());
        journal.putLong("min", min.asLong());
        journal.putLong("max", max.asLong());
        journal.putLong("delta", delta.asLong());
        transform.save(journal, scan.bounds());
        // The hull and any snow resting on it are what the move clears at the source, so they are all rollback restores.
        var saved = template.save(new CompoundTag());
        var restored = new HashSet<BlockPos>(scan.blocks());
        for (var block : scan.blocks())
        {
            var above = block.above();
            if (!scan.blocks().contains(above) && source.getBlockState(above).is(net.minecraft.world.level.block.Blocks.SNOW))
                restored.add(above);
        }
        saved.getListOrEmpty("blocks").removeIf(entry ->
        {
            var coords = ((CompoundTag) entry).getListOrEmpty("pos");
            return !restored.contains(min.offset(coords.getIntOr(0, 0), coords.getIntOr(1, 0), coords.getIntOr(2, 0)));
        });
        journal.put("template", saved);
        journal.put("ticks", JournalTicks.capture(source, restored));
        if (landing != null)
        {
            // Fluids/ground cover displaced by arrival must return if this MOVE rolls back.
            var previous = new StructureTemplate();
            previous.fillFromWorld(target, targetMin, new Vec3i(targetMax.getX() - targetMin.getX() + 1, targetMax.getY() - targetMin.getY() + 1, targetMax.getZ() - targetMin.getZ() + 1), false, List.of());
            var region = new CompoundTag();
            region.putString("source", target.dimension().identifier().toString());
            region.putLong("min", targetMin.asLong());
            region.putLong("max", targetMax.asLong());
            var snapshot = previous.save(new CompoundTag());
            region.put("template", snapshot);
            region.put("ticks", JournalTicks.capture(target, TransferReplay.positions(snapshot, targetMin)));
            var regions = new ListTag();
            regions.add(region);
            journal.put("regions", regions);
        }
        ShipRespawns.captureSurface(source, id, sourceBase, scan.blocks());
        beforeStore = store.snapshot();
    }

    /**
     * Saves every non-player passenger into the journal. Roots are entities not riding other cargo;
     * riders move and are journaled with their mount.
     */
    private Result saveCargo()
    {
        ListTag entities = new ListTag();
        for (Entity entity : cargo)
            cargoIds.add(entity.getUUID());
        for (Entity entity : cargo)
        {
            var vehicle = entity.getVehicle();
            if (vehicle != null && cargoIds.contains(vehicle.getUUID()))
                continue;
            if (vehicle != null)
                entity.stopRiding(); // Riding something that stays behind.
            roots.add(entity);
        }
        for (Entity entity : roots)
        {
            // Leash knots, lightning and fishing bobbers are never saved by Minecraft: a knot is rebuilt from its
            // fence and the animals tied to it, and the others are over the moment the ship moves.
            if (!entity.getType().canSerialize())
                continue;
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.registryAccess());
            if (!entity.save(output))
                return Result.rejected("This passenger cannot be saved for travel. Move it off the ship and try again." + "\nOccupant: " + ShipMessages.entity(entity) + "\nLocation: " + entity.blockPosition().toShortString());
            var entry = new CompoundTag();
            entry.putString("id", entity.getUUID().toString());
            entry.put("data", output.buildResult());
            var riders = new ListTag();
            for (Entity rider : entity.getIndirectPassengers())
                if (!(rider instanceof net.minecraft.world.entity.player.Player))
                    riders.add(StringTag.valueOf(rider.getUUID().toString()));
            entry.put("riders", riders);
            entities.add(entry);
        }
        journal.put("entities", entities);
        return null;
    }

    /** The save state after the move: the moved ship's record, its crew and any left debris. */
    private void buildAfterState()
    {
        after = store.snapshot();
        ships = after.getListOrEmpty("ships");
        if (!after.contains("ships"))
        {
            ships = new ListTag();
            after.put("ships", ships);
        }
        ships.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id));
        if (detaching)
            ships.add(debris());
        var next = movedShip();
        var passengers = new ListTag();
        var members = crewRecords(passengers);
        next.put("crew", members);
        // Boarding another ship commits one authoritative crew assignment. An old ship
        // must not later recall or relocate this passenger through a stale crew entry.
        var boarded = members.stream().map(t -> ((CompoundTag) t).getStringOr("id", "")).collect(java.util.stream.Collectors.toSet());
        for (var tag : ships)
            ((CompoundTag) tag).getListOrEmpty("crew").removeIf(t -> boarded.contains(((CompoundTag) t).getStringOr("id", "")));
        RotatedShipState.ship(next, transform);
        RotatedShipState.beds(after, id, transform);
        ships.add(next);
        journal.put("players", passengers);
        for (Entity entity : cargo)
            TransferRecovery.authority(after, entity.getUUID(), target.dimension().identifier().toString(), source.dimension().identifier().toString(), min, max);
        // The journal holds only the records this move changes, as before and after values.
        var entries = StoreRecords.diff(beforeStore, after);
        journal.put("records", StoreRecords.encode(entries));
        file = Journals.file(store.directory, journal);
    }

    /**
     * Loose blocks left behind become their own record. Independent physical allocation and scene
     * actor: the debris cannot follow the ship through later local flight or an FTL region change. It
     * keeps its original inventories.
     */
    private CompoundTag debris()
    {
        var debris = new CompoundTag();
        debris.putString("id", UUID.randomUUID().toString());
        debris.putString("kind", "debris");
        debris.putString("home", "Loose blocks");
        debris.putString("dimension", source.dimension().identifier().toString());
        debris.putLong("base", sourceBase.asLong());
        for (String key : List.of("width", "depth", "height", "localSpace", "simX", "simY", "simZ", "simYaw", "simPitch", "simRoll", "owner", "access"))
            if (vessel.contains(key))
                debris.put(key, vessel.get(key).copy());
        return debris;
    }

    /** The ship's record at its destination: place, size, region, scene position and home pad. */
    private CompoundTag movedShip()
    {
        var next = vessel == null ? new CompoundTag() : vessel.copy();
        next.putString("id", id);
        next.putString("dimension", target.dimension().identifier().toString());
        if (!target.dimension().equals(SPACE))
        {
            next.remove("journey");
            next.remove("stored");
        }
        next.putInt("width", landing == null ? 32 : landing.bounds().width());
        next.putInt("depth", landing == null ? 32 : landing.bounds().depth());
        next.putInt("height", landing == null ? 32 : PadRegistry.buildHeight(target, landing.bounds()));
        next.putLong("base", targetBase.asLong());
        if (!source.dimension().equals(SPACE))
        {
            String address = PlanetSurfaces.address(source);
            if (address != null)
                next.putString("localSpace", address);
        }
        if (target.dimension().equals(SPACE) && !next.contains("simX"))
        {
            Jumps.randomizeArrival(server, next);
            var arrival = Jumps.arrivalOrigin(server, next);
            next.putDouble("simX", arrival.x);
            next.putDouble("simY", arrival.y);
            next.putDouble("simZ", arrival.z);
        }
        next.putLong("controller", transform.apply(controller).asLong());
        if (!mainEntrance.isEmpty())
        {
            next.putString("mainEntrance", mainEntrance);
            var nodes = next.getCompoundOrEmpty("doorNodes");
            entranceIds.forEach((position, token) -> nodes.putString(Long.toString(position.asLong()), token));
            next.put("doorNodes", nodes);
        }
        if (mainExit != null)
            next.putLong("mainExit", mainExit.asLong());
        var home = landing != null ? landing : inspected.pad();
        if (home != null)
        {
            next.putString("home", home.name());
            next.putString("homeId", home.id());
        }
        CrewRelease.authorize(next, actor);
        // Recall lands an empty ship on a planet pad; storing moves one into space instead.
        if (recall && !target.dimension().equals(SPACE))
        {
            String region = PlanetSurfaces.address(target);
            if (region != null)
                next.putString("localSpace", region);
            Piloting.stop(next);
            for (String field : List.of("simX", "simY", "simZ"))
                next.remove(field);
        }
        return next;
    }

    /**
     * Crew records for the moved ship, with where each player arrives. Online crew travel now;
     * offline crew (and passengers who disconnected after agreeing to the loading screen, including
     * on the first launch) arrive when they next log in. Fills {@code passengers} with where each
     * travelling player started, for rollback.
     */
    private ListTag crewRecords(ListTag passengers)
    {
        ListTag members = new ListTag();
        if (vessel != null)
            for (Tag t : vessel.getListOrEmpty("crew"))
            {
                var member = ((CompoundTag) t).copy();
                UUID uuid = UUID.fromString(member.getStringOr("id", ""));
                if (server.getPlayerList().getPlayer(uuid) == null)
                {
                    if (leavingPieces)
                        TransferRecovery.safeOfflineArrival(source, scan, sourceBase, member);
                    RotatedShipState.crew(member, transform);
                    members.add(member);
                    WorldStore.target(after, uuid, target.dimension().identifier().toString(), targetBase.getX() + member.getDoubleOr("x", 0), targetBase.getY() + member.getDoubleOr("y", 0), targetBase.getZ() + member.getDoubleOr("z", 0), member.getFloatOr("yaw", 0), member.getFloatOr("pitch", 0));
                }
            }
        for (ServerPlayer player : crew)
        {
            var member = new CompoundTag();
            member.putString("id", player.getUUID().toString());
            member.putDouble("x", player.getX() - sourceBase.getX());
            member.putDouble("y", player.getY() - sourceBase.getY());
            member.putDouble("z", player.getZ() - sourceBase.getZ());
            member.putFloat("yaw", player.getYRot());
            member.putFloat("pitch", player.getXRot());
            RotatedShipState.crew(member, transform);
            members.add(member);
            var original = new CompoundTag();
            original.putString("id", player.getUUID().toString());
            original.putDouble("x", player.getX());
            original.putDouble("y", player.getY());
            original.putDouble("z", player.getZ());
            original.putFloat("yaw", player.getYRot());
            original.putFloat("pitch", player.getXRot());
            passengers.add(original);
            WorldStore.target(after, player.getUUID(), target.dimension().identifier().toString(), targetBase.getX() + member.getDoubleOr("x", 0), targetBase.getY() + member.getDoubleOr("y", 0), targetBase.getZ() + member.getDoubleOr("z", 0), member.getFloatOr("yaw", 0), player.getXRot());
        }
        if (current != null)
            for (var entry : current.crew.entrySet())
            {
                UUID uuid = entry.getKey();
                if (server.getPlayerList().getPlayer(uuid) != null)
                    continue;
                if (members.stream().anyMatch(t -> ((CompoundTag) t).getStringOr("id", "").equals(uuid.toString())))
                    continue;
                Vec3 position = entry.getValue();
                var member = new CompoundTag();
                member.putString("id", uuid.toString());
                member.putDouble("x", position.x - sourceBase.getX());
                member.putDouble("y", position.y - sourceBase.getY());
                member.putDouble("z", position.z - sourceBase.getZ());
                if (leavingPieces)
                    TransferRecovery.safeOfflineArrival(source, scan, sourceBase, member);
                RotatedShipState.crew(member, transform);
                members.add(member);
                WorldStore.target(after, uuid, target.dimension().identifier().toString(), targetBase.getX() + member.getDoubleOr("x", 0), targetBase.getY() + member.getDoubleOr("y", 0), targetBase.getZ() + member.getDoubleOr("z", 0), member.getFloatOr("yaw", 0), 0);
                var original = new CompoundTag();
                original.putString("id", uuid.toString());
                original.putDouble("x", position.x);
                original.putDouble("y", position.y);
                original.putDouble("z", position.z);
                passengers.add(original);
            }
        return members;
    }

    /** Places the hull's blocks and scheduled ticks at the destination. */
    private void stageDestination()
    {
        // Force close any menus retaining references to source containers. Only real container
        // menus need closing: a terminal screen (for example My ships beside the pad) holds no
        // ship container, and closing it mid-session left players stuck (playtest 2).
        for (ServerPlayer player : source.players())
            if (scan.bounds().box().inflate(8).contains(player.position()) && player.containerMenu != player.inventoryMenu)
                player.closeContainer();
        var destinationSnapshot = BlockSupport.destinationTemplate(BlockSupport.connectedTemplate(journal.getCompoundOrEmpty("template"), min, scan.blocks()), scan.blocks(), transform::apply);
        destinationSnapshot = transform.template(target, destinationSnapshot, min);
        var destinationTemplate = new StructureTemplate();
        destinationTemplate.load(target.registryAccess().lookupOrThrow(Registries.BLOCK), destinationSnapshot);
        // Snow or grass on the pad gives way to the landing ship instead of ending up inside it.
        for (var at : BlockPos.betweenClosed(targetMin, targetMax))
            if (!target.getBlockState(at).isAir() && arrivalClear(target.getBlockState(at)))
                target.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
        if (landing != null)
            for (var entity : HullEntities.inBounds(target, new HullScan.Bounds(targetMin, targetMax)))
                if (entity instanceof net.minecraft.world.entity.item.ItemEntity item)
                {
                    // Keep dropped loot at the pad edge, outside the arriving hull and cabin.
                    item.setPos(landing.bounds().minX() - 1.5, landing.bounds().y() + 2, landing.bounds().minZ() - 1.5);
                    item.setDeltaMovement(Vec3.ZERO);
                }
        TransferRecovery.place(target, destinationTemplate, targetMin);
        var ticks = journal.getCompoundOrEmpty("ticks").copy();
        for (var kind : List.of("blocks", "fluids"))
            ticks.getListOrEmpty(kind).removeIf(t -> !scan.blocks().contains(WorldStore.pos((CompoundTag) t, "at")));
        JournalTicks.restore(target, ticks, transform::apply);
    }

    /**
     * Teleports cargo and crew with the hull. Leashes and mounts between travellers are detached
     * quietly first and restored after, so nothing is dropped or left behind.
     */
    private void movePassengers()
    {
        var leashes = new LinkedHashMap<UUID, UUID>();
        var posts = new LinkedHashMap<UUID, BlockPos>();
        var snapped = new ArrayList<UUID>();
        var crewIds = new HashSet<UUID>();
        for (ServerPlayer player : crew)
            crewIds.add(player.getUUID());
        // An animal tied to a fence post: the post is rebuilt at the destination, so remember where it stood
        // relative to the ship. That holds for a post outside the hull too, if a fence stands at the same spot on
        // arrival; otherwise the lead snaps there, like any lead that is stretched too far.
        for (Entity entity : cargo)
            if (entity instanceof Leashable leashed && leashed.getLeashHolder() instanceof LeashFenceKnotEntity post)
            {
                posts.put(entity.getUUID(), post.getPos());
                leashed.removeLeash();
            }
        for (Entity entity : cargo)
            if (entity instanceof Leashable leashed && leashed.getLeashHolder() instanceof Entity holder && (cargoIds.contains(holder.getUUID()) || crewIds.contains(holder.getUUID())))
            {
                leashes.put(entity.getUUID(), holder.getUUID());
                leashed.removeLeash();
            }
        var mounts = new LinkedHashMap<ServerPlayer, UUID>();
        for (ServerPlayer player : crew)
            if (player.getVehicle() != null && cargoIds.contains(player.getVehicle().getUUID()))
            {
                mounts.put(player, player.getVehicle().getUUID());
                player.stopRiding();
            }
        var riderPositions = new LinkedHashMap<UUID, Vec3>();
        var riderYaws = new LinkedHashMap<UUID, Float>();
        for (var entity : cargo)
            if (entity.getVehicle() != null && cargoIds.contains(entity.getVehicle().getUUID()))
            {
                riderPositions.put(entity.getUUID(), transform.apply(entity.position()));
                riderYaws.put(entity.getUUID(), transform.yaw(entity.getYRot()));
            }
        for (Entity entity : roots)
        {
            if (!entity.getType().canSerialize())
            {
                entity.discard();
                continue;
            }
            Vec3 arrival = transform.apply(entity.position());
            float yaw = transform.yaw(entity.getYRot());
            if (entity instanceof net.minecraft.world.entity.decoration.BlockAttachedEntity attached)
            {
                arrival = Vec3.atCenterOf(transform.apply(attached.getPos()));
                if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity hanging)
                {
                    hanging.rotate(transform.rotation());
                    yaw = hanging.getYRot();
                    if (hanging instanceof net.minecraft.world.entity.decoration.ItemFrame frame && frame.getDirection().getAxis().isVertical())
                        frame.setRotation(Math.floorMod(frame.getRotation() + 2 * transform.turns() * frame.getDirection().getAxisDirection().getStep(), 8));
                }
            }
            var moved = entity.teleport(new TeleportTransition(target, arrival, transform.velocity(entity.getDeltaMovement()), yaw, entity.getXRot(), TeleportTransition.DO_NOTHING));
            if (moved == null)
                throw new IllegalStateException("Could not transfer passenger entity");
        }
        for (var rider : riderPositions.entrySet())
        {
            var entity = target.getEntity(rider.getKey());
            if (entity != null)
            {
                entity.snapTo(rider.getValue().x, rider.getValue().y, rider.getValue().z, riderYaws.get(rider.getKey()), entity.getXRot());
            }
        }
        for (ServerPlayer player : crew)
            player.teleport(new TeleportTransition(target, transform.apply(player.position()), Vec3.ZERO, transform.yaw(player.getYRot()), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET));
        for (var mount : mounts.entrySet())
        {
            var vehicle = target.getEntity(mount.getValue());
            if (vehicle != null)
                mount.getKey().startRiding(vehicle, true, true);
        }
        for (var leash : leashes.entrySet())
        {
            var holder = target.getEntity(leash.getValue());
            if (target.getEntity(leash.getKey()) instanceof Leashable leashed && holder != null)
                leashed.setLeashedTo(holder, true);
        }
        for (var tied : posts.entrySet())
        {
            var at = transform.apply(tied.getValue());
            if (!(target.getEntity(tied.getKey()) instanceof Leashable leashed))
                continue;
            if (target.getBlockState(at).is(net.minecraft.tags.BlockTags.FENCES))
                leashed.setLeashedTo(LeashFenceKnotEntity.getOrCreateKnot(target, at), true);
            else
                snapped.add(tied.getKey());
        }
        for (var id : snapped)
        {
            var animal = target.getEntity(id);
            if (animal != null)
                animal.spawnAtLocation(target, net.minecraft.world.item.Items.LEAD);
        }
    }
}
