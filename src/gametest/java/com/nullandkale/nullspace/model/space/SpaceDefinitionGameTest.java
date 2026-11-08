package com.nullandkale.nullspace.model.space;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

public final class SpaceDefinitionGameTest
{
    @GameTest
    public void generatedCompanionsFitTheSurfaceAngularSizeBound(GameTestHelper ctx)
    {
        for (var destination : LocalSpaces.DESTINATIONS)
        {
            var scene = SceneCatalog.region(ctx.getLevel().getServer(), destination.id());
            var bodies = scene.getListOrEmpty("bodies").stream().map(t -> (CompoundTag) t).toList();
            var primary = bodies.stream().filter(b -> b.getStringOr("name", "").equals(destination.name())).findFirst().orElseThrow();
            for (var other : bodies)
                if (other != primary && other.getStringOr("kind", "").equals("moon"))
                {
                    var center = new Vec3(primary.getDoubleOr("x", 0), primary.getDoubleOr("y", 0), primary.getDoubleOr("z", 0));
                    var position = new Vec3(other.getDoubleOr("x", 0), other.getDoubleOr("y", 0), other.getDoubleOr("z", 0));
                    double near = position.distanceTo(center) - primary.getDoubleOr("radius", 0);
                    ctx.assertTrue(2 * Math.asin(other.getDoubleOr("radius", 0) / near) <= CelestialLayout.MOON_DIAMETER + 1e-10, "Generated companion must fit the surface angular-size bound in " + destination.id());
                }
        }
        ctx.succeed();
    }

    @GameTest
    public void wireRoundTripAndRevisionValidation(GameTestHelper ctx)
    {
        var body = new SpaceDefinition.Body("test:body", 1, new Vec3(1e9, 0, 10), new Vec3(.1, 0, 0), 200, 0xff3366aa, 0, .02, 51, .03);
        var scene = new SpaceDefinition("test:scene", 4, 41, 1, 20, List.of(body), List.of(new SpaceDefinition.Volume("test:gas", Vec3.ZERO, new Vec3(20, 10, 30), Vec3.ZERO, 0xff775599, .2, .01, .5, .2, 9, 0)), List.of());
        var encoded = SpaceDefinitionCodec.encode(scene);
        ctx.assertTrue(SpaceDefinitionCodec.decode(encoded).equals(scene), "Wire round-trip must retain all double coordinates and material/volume metadata");
        var cache = new SpaceDefinitionCodec.Cache();
        ctx.assertTrue(cache.accept(encoded) && !cache.accept(encoded), "Repeated revision must reuse the active definition");
        var old = encoded.copy();
        old.putLong("revision", 3);
        ctx.assertTrue(!cache.accept(old) && cache.scene().revision() == 4, "Late definitions cannot roll back client state");
        var malformed = encoded.copy();
        ((CompoundTag) malformed.getListOrEmpty("bodies").getFirst()).putDouble("radius", Double.NaN);
        boolean rejected = false;
        try
        {
            cache.accept(malformed);
        }
        catch (IllegalArgumentException e)
        {
            rejected = true;
        }
        ctx.assertTrue(rejected && cache.scene().equals(scene), "Invalid revisions cannot partially replace active scene data");
        cache.clear();
        ctx.assertTrue(cache.scene() == null, "Disconnect must discard scene definitions");
        ctx.succeed();
    }

    @GameTest
    public void migrationPreservesExistingBodyIdentitiesAndPositions(GameTestHelper ctx)
    {
        var store = WorldStore.get(ctx.getLevel().getServer());
        var original = store.snapshot().get("scene");
        try
        {
            var legacy = new CompoundTag();
            legacy.putInt("version", 1);
            legacy.putLong("seed", 19);
            var bodies = new ListTag();
            var b = new CompoundTag();
            b.putString("id", "test:existing");
            b.putString("kind", "planet");
            b.putString("name", "Saved world");
            b.putDouble("x", 1234.5);
            b.putDouble("radius", 25);
            b.putInt("color", 0xff998877);
            bodies.add(b);
            legacy.put("bodies", bodies);
            store.document("scene", legacy);
            var migrated = SceneCatalog.get(ctx.getLevel().getServer());
            var decoded = SpaceDefinitionCodec.decode(migrated);
            ctx.assertTrue(decoded.bodies().getFirst().id().equals("test:existing") && decoded.bodies().getFirst().center().x == 1234.5, "Migration must preserve stable saved bodies and coordinates");
            ctx.assertTrue(!decoded.volumes().isEmpty() && !decoded.fields().isEmpty() && decoded.bodies().stream().anyMatch(body -> body.kind() == 4), "Migration adds procedural nebula, belt and comet definitions");
            ctx.assertTrue(SceneCatalog.get(ctx.getLevel().getServer()).equals(migrated), "Repeated reads cannot remigrate or reset a scene");
            ctx.succeed();
        }
        finally
        {
            if (original == null)
                store.document("scene", null);
            else
                store.document("scene", original);
            store.save();
        }
    }

    @GameTest
    public void oversizedAndUnsupportedDefinitionsFailBeforeAllocation(GameTestHelper ctx)
    {
        var encoded = SpaceDefinitionCodec.encode(new SpaceDefinition("test:empty", 0, 0, 1, 0, List.of(), List.of(), List.of()));
        for (int mode = 0; mode < 4; mode++)
        {
            var bad = encoded.copy();
            if (mode == 0)
                bad.putInt("generator", 2);
            if (mode == 1)
                bad.putString("oversized", "x".repeat(70000));
            if (mode == 2)
            {
                var list = new ListTag();
                for (int i = 0; i < 65; i++)
                    list.add(new CompoundTag());
                bad.put("bodies", list);
            }
            if (mode == 3)
            {
                var list = new ListTag();
                list.add(StringTag.valueOf("wrong type"));
                bad.put("bodies", list);
            }
            boolean rejected = false;
            try
            {
                SpaceDefinitionCodec.decode(bad);
            }
            catch (IllegalArgumentException e)
            {
                rejected = true;
            }
            ctx.assertTrue(rejected, "Invalid schema/count/size must reject before GPU work");
        }
        ctx.succeed();
    }
}
