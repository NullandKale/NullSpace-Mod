package com.nullandkale.nullspace.core.save;

import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.space.ScenePose;

import java.util.*;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;

/** Owns persisted state. NBT is retained as a compatible codec, not a replaceable public field. */
public final class ShipRepository
{
    public enum Mutation
    {
        CONFIGURATION, SIMULATION, DAMAGE, TRANSFER, RECOVERY
    }

    public record ShipId(String value)
    {
        public ShipId
        {
            if (value == null || value.isBlank() || value.length() > 80)
                throw new IllegalArgumentException("Invalid ship identity");
        }
    }

    public record Location(String dimension, BlockPos base, HullScan.Bounds bounds)
    {}

    private CompoundTag root;
    private long generation;
    private final List<Consumer<Mutation>> listeners = new ArrayList<>();

    public record Change(ShipId ship, Mutation kind, Set<String> fields)
    {
        public Change
        {
            fields = Set.copyOf(fields);
        }
    }

    private final List<Consumer<Change>> edits = new ArrayList<>();

    public void onChange(Consumer<Change> listener)
    {
        edits.add(listener);
    }

    public ShipRepository(CompoundTag root)
    {
        this.root = root;
    }

    public CompoundTag snapshot()
    {
        return root.copy();
    }

    public long generation()
    {
        return generation;
    }

    public void onReplace(Consumer<Mutation> listener)
    {
        listeners.add(listener);
    }

    public void replace(CompoundTag snapshot, Mutation mutation)
    {
        if (mutation != Mutation.TRANSFER && mutation != Mutation.RECOVERY)
            throw new IllegalArgumentException("Root replacement requires a transaction boundary");
        if (root == snapshot)
            return;
        root = snapshot;
        generation++;
        for (var listener : listeners)
            listener.accept(mutation);
    }

    public CompoundTag section(String name)
    {
        if (!root.contains(name))
            root.put(name, new CompoundTag());
        return root.getCompoundOrEmpty(name);
    }

    public ListTag list(String name)
    {
        if (!root.contains(name))
            root.put(name, new ListTag());
        return root.getListOrEmpty(name);
    }

    public void document(String name, Tag value)
    {
        if (value == null)
            root.remove(name);
        else
            root.put(name, value);
        generation++;
    }

    public ListTag ships()
    {
        return list("ships");
    }

    public CompoundTag find(String id)
    {
        return ships().stream().map(t -> (CompoundTag) t).filter(s -> s.getStringOr("id", "").equals(id)).findFirst().orElse(null);
    }

    public void mutate(ShipId id, Mutation kind, Consumer<CompoundTag> change)
    {
        if (kind == Mutation.TRANSFER || kind == Mutation.RECOVERY)
            throw new IllegalArgumentException("Use the journal transaction for transfers and recovery");
        var current = find(id.value());
        if (current == null)
            throw new IllegalArgumentException("Ship no longer exists");
        var candidate = current.copy();
        change.accept(candidate);
        if (!candidate.getStringOr("id", "").equals(id.value()))
            throw new IllegalArgumentException("Ship identity cannot change during an edit");
        if (candidate.equals(current))
            return;
        // Validate the edit before publishing; retain the record identity expected by domain adapters.
        var fields = new HashSet<String>();
        for (String key : new ArrayList<>(current.keySet()))
            if (!candidate.contains(key))
            {
                current.remove(key);
                fields.add(key);
            }
        for (String key : candidate.keySet())
            if (!Objects.equals(current.get(key), candidate.get(key)))
            {
                current.put(key, candidate.get(key));
                fields.add(key);
            }
        generation++;
        var event = new Change(id, kind, fields);
        for (var listener : edits)
            listener.accept(event);
    }

    public static Location location(CompoundTag ship)
    {
        return new Location(ship.getStringOr("dimension", ""), WorldStore.pos(ship, "base"), WorldStore.bounds(ship));
    }

    public static ScenePose pose(CompoundTag ship)
    {
        return new ScenePose(new Vec3(ship.getDoubleOr("simX", WorldStore.pos(ship, "base").getX() / (double) TravelService.SPACE_SPACING * 180), ship.getDoubleOr("simY", 0), ship.getDoubleOr("simZ", 0)), new Quaterniond().rotationYXZ(Math.toRadians(ship.getDoubleOr("simYaw", 0)), Math.toRadians(ship.getDoubleOr("simPitch", 0)), Math.toRadians(ship.getDoubleOr("simRoll", 0))));
    }

    CompoundTag encoded()
    {
        return root;
    }
}
