package com.nullandkale.nullspace.gameplay.combat;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import com.nullandkale.nullspace.model.space.SpaceRay;
import com.nullandkale.nullspace.model.space.SpaceShapes;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

/**
 * Server-side rays hit real hull geometry after a change of frame; rendered pixels are never hit
 * authority.
 */
public final class ShipCombat {
  private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);

  public record Hit(CompoundTag ship, Vec3 local, double distance) {}

  private record Candidate(CompoundTag ship, double entry) {}

  private static Candidate candidate(CompoundTag ship, Vec3 origin, Vec3 direction, double range) {
    var pose = SceneService.pose(ship);
    var bounds = WorldStore.bounds(ship);
    var base = WorldStore.pos(ship, "base");
    var interval =
        SpaceRay.box(
            pose.toLocal(origin),
            pose.directionToLocal(direction),
            new Vec3(
                bounds.max().getX() - base.getX() + 1, 32, bounds.max().getZ() - base.getZ() + 1),
            range);
    return interval == null ? null : new Candidate(ship, interval[0]);
  }

  public static Hit trace(
      ServerLevel level, CompoundTag ship, Vec3 origin, Vec3 direction, double range) {
    var pose = SceneService.pose(ship);
    var o = pose.toLocal(origin);
    var d = pose.directionToLocal(direction);
    var bounds = WorldStore.bounds(ship);
    var base = WorldStore.pos(ship, "base");
    var interval =
        SpaceRay.box(
            o,
            d,
            new Vec3(
                bounds.max().getX() - base.getX() + 1, 32, bounds.max().getZ() - base.getZ() + 1),
            range);
    if (interval == null) return null;
    var start = o.add(d.scale(interval[0] + 1e-7));
    int x = (int) Math.floor(start.x), y = (int) Math.floor(start.y), z = (int) Math.floor(start.z);
    double t = interval[0];
    var from = o.add(Vec3.atLowerCornerOf(base));
    var to = o.add(d.scale(range)).add(Vec3.atLowerCornerOf(base));
    for (int step = 0; step < 320 && t <= interval[1] + 1e-6; step++) {
      var p = base.offset(x, y, z);
      if (!bounds.contains(p)) break;
      if (!level.hasChunkAt(p))
        throw new IllegalArgumentException("Target terrain is loading; try again");
      var state = level.getBlockState(p);
      if (!state.isAir()) {
        var hit = state.getCollisionShape(level, p).clip(from, to, p);
        if (hit != null)
          return new Hit(
              ship,
              hit.getLocation().subtract(Vec3.atLowerCornerOf(base)),
              hit.getLocation().distanceTo(from));
      }
      double tx =
          Math.abs(d.x) < 1e-12 ? Double.POSITIVE_INFINITY : ((x + (d.x > 0 ? 1 : 0)) - o.x) / d.x;
      double ty =
          Math.abs(d.y) < 1e-12 ? Double.POSITIVE_INFINITY : ((y + (d.y > 0 ? 1 : 0)) - o.y) / d.y;
      double tz =
          Math.abs(d.z) < 1e-12 ? Double.POSITIVE_INFINITY : ((z + (d.z > 0 ? 1 : 0)) - o.z) / d.z;
      t = Math.min(tx, Math.min(ty, tz));
      if (tx <= t + 1e-10) x += d.x > 0 ? 1 : -1;
      if (ty <= t + 1e-10) y += d.y > 0 ? 1 : -1;
      if (tz <= t + 1e-10) z += d.z > 0 ? 1 : -1;
    }
    return null;
  }

  public static String fire(ServerPlayer player, BlockPos controller) {
    var level = player.level();
    if (TravelService.busy(player)
        || TravelService.locked(level, controller)
        || player.distanceToSqr(Vec3.atCenterOf(controller)) > 64
        || !player.mayInteract(level, controller))
      throw new IllegalArgumentException("Cannot fire from this controller");
    return fire(level, ShipTopology.resolve(level, controller), controller, player);
  }

  public static String fire(
      ServerLevel level, CompoundTag owner, BlockPos controller, Entity source) {
    if (!level.dimension().equals(TravelService.SPACE))
      throw new IllegalArgumentException("Ship weapons operate in space");
    if (TravelService.locked(level, controller)
        || !ShipSystems.connected(level, owner).contains(controller))
      throw new IllegalArgumentException("Weapon controller is unavailable");
    if (WorldStore.get(level.getServer()).list("pendingImpacts").size() >= 128)
      throw new IllegalArgumentException(
          "Combat arrival queue is full; wait for boarding to finish");
    if (!ShipSystems.enabled(owner)
        || ShipSystems.power(level, owner, PowerBus.System.WEAPONS) < 2
        || ShipSystems.power(level, owner, PowerBus.System.CONTROLS) < 1)
      throw new IllegalArgumentException("Power controls and allocate two weapon power units");
    long now = level.getGameTime();
    if (now < owner.getLongOr("weaponReadyAt", 0))
      throw new IllegalArgumentException("Weapon recharging");
    var gun =
        ShipTopology.discover(level, owner).stream()
            .filter(
                p -> level.getBlockState(p).is(ModBlocks.WEAPON) && ShipSystems.active(owner, p))
            .sorted(Comparator.comparingLong(BlockPos::asLong))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Install a weapon"));
    var facing = level.getBlockState(gun).getValue(HorizontalDirectionalBlock.FACING);
    if (!level.getBlockState(gun.relative(facing)).isAir())
      throw new IllegalArgumentException("Weapon muzzle is blocked");
    var pose = SceneService.pose(owner);
    var local =
        Vec3.atCenterOf(gun.subtract(WorldStore.pos(owner, "base")))
            .add(facing.getStepX() * .6, facing.getStepY() * .6, facing.getStepZ() * .6);
    var origin = pose.toUniverse(local);
    var direction =
        pose.toUniverse(new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ()))
            .subtract(pose.position())
            .normalize();
    Hit closest = null;
    double bodyDistance = Double.POSITIVE_INFINITY;
    String bodyName = "";
    var catalog = SceneCatalog.forShip(level.getServer(), owner);
    var definition = SpaceDefinitionCodec.decode(catalog);
    double sceneTime = level.getServer().overworld().getGameTime() / 20.0 - definition.epoch();
    for (var body : definition.bodies()) {
      double distance = SpaceShapes.intersect(origin, direction, body, sceneTime, 10000);
      if (distance < bodyDistance) {
        bodyDistance = distance;
        bodyName =
            catalog.getListOrEmpty("bodies").stream()
                .map(t -> (CompoundTag) t)
                .filter(b -> b.getStringOr("id", "").equals(body.id()))
                .map(b -> b.getStringOr("name", body.id()))
                .findFirst()
                .orElse(body.id());
      }
    }
    double range = Math.min(10000, bodyDistance);
    int queried = 0;
    var candidates =
        ships(level).stream()
            .filter(ship -> LocalSpaces.together(owner, ship))
            .map(ship -> candidate(ship, origin, direction, range))
            .filter(Objects::nonNull)
            .sorted(
                Comparator.comparingDouble(Candidate::entry)
                    .thenComparing(c -> c.ship.getStringOr("id", "")))
            .toList();
    for (var contact : candidates) {
      if (closest != null && contact.entry > closest.distance) break;
      if (queried++ >= 128) throw new IllegalArgumentException("Combat ray query budget exceeded");
      var hit =
          trace(level, contact.ship, origin, direction, closest == null ? range : closest.distance);
      if (hit != null && (closest == null || hit.distance < closest.distance)) closest = hit;
    }
    owner.putLong("weaponReadyAt", now + 40);
    if (bodyDistance < 10000 && (closest == null || bodyDistance < closest.distance))
      return "Shot blocked by " + bodyName;
    if (closest == null) return "Weapon fired: no hull hit";
    var impact = SceneService.pose(closest.ship).toUniverse(closest.local);
    explodeRegion(level, impact, 4, source, null, LocalSpaces.address(owner));
    return "Impact on " + closest.ship.getStringOr("home", closest.ship.getStringOr("id", "Ship"));
  }

  private static List<CompoundTag> ships(ServerLevel level) {
    return WorldStore.get(level.getServer()).ships().stream()
        .map(t -> (CompoundTag) t)
        .filter(
            t -> t.getStringOr("dimension", "").equals(level.dimension().identifier().toString()))
        .toList();
  }

  public static void localExplosion(ServerLevel level, Vec3 center, float power, Entity source) {
    if (APPLYING.get() || !level.dimension().equals(TravelService.SPACE) || power <= 0 || power > 8)
      return;
    var owner =
        ships(level).stream()
            .filter(t -> WorldStore.bounds(t).box().inflate(power * 2).contains(center))
            .min(
                Comparator.comparingDouble(
                    t -> WorldStore.bounds(t).box().getCenter().distanceToSqr(center)))
            .orElse(null);
    if (owner != null)
      explodeRegion(
          level,
          SceneService.pose(owner)
              .toUniverse(center.subtract(Vec3.atLowerCornerOf(WorldStore.pos(owner, "base")))),
          power,
          source,
          TravelService.locked(level, WorldStore.pos(owner, "base"))
              ? null
              : owner.getStringOr("id", ""),
          LocalSpaces.address(owner));
  }

  public static void explode(
      ServerLevel space, Vec3 center, float power, Entity attacker, String alreadyApplied) {
    explodeRegion(space, center, power, attacker, alreadyApplied, LocalSpaces.HOME);
  }

  private static void explodeRegion(
      ServerLevel space,
      Vec3 center,
      float power,
      Entity attacker,
      String alreadyApplied,
      String region) {
    if (power <= 0 || power > 8)
      throw new IllegalArgumentException("Explosion outside combat budget");
    var id = UUID.randomUUID().toString();
    for (var ship : ships(space)) {
      if (!LocalSpaces.address(ship).equals(region)) continue;
      if (ship.getStringOr("id", "").equals(alreadyApplied)) continue;
      var base = WorldStore.pos(ship, "base");
      var local = SceneService.pose(ship).toLocal(center);
      var physical = local.add(Vec3.atLowerCornerOf(base));
      var box = WorldStore.bounds(ship).box();
      double dx = Math.max(Math.max(box.minX - physical.x, 0), physical.x - box.maxX),
          dy = Math.max(Math.max(box.minY - physical.y, 0), physical.y - box.maxY),
          dz = Math.max(Math.max(box.minZ - physical.z, 0), physical.z - box.maxZ);
      if (dx * dx + dy * dy + dz * dz > power * power * 4) continue;
      if (TravelService.locked(space, base)) {
        var store = WorldStore.get(space.getServer());
        var pending = store.list("pendingImpacts");
        if (pending.size() >= 128) {
          if (space.getGameTime() >= ship.getLongOr("queueWarningUntil", Long.MIN_VALUE)) {
            ship.putLong("queueWarningUntil", space.getGameTime() + 20);
            NullSpace.LOGGER.warn(
                "Deferred blast budget exhausted for ship {}; remote blast rejected",
                ship.getStringOr("id", ""));
            for (var player : space.players())
              if (WorldStore.bounds(ship).box().inflate(8).contains(player.position()))
                player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                        "Combat arrival queue full: an external blast was rejected"));
          }
          continue;
        }
        var event = new CompoundTag();
        event.putString("id", id);
        event.putString("ship", ship.getStringOr("id", ""));
        event.putDouble("x", local.x);
        event.putDouble("y", local.y);
        event.putDouble("z", local.z);
        event.putFloat("power", power);
        if (attacker != null) event.putString("attacker", attacker.getUUID().toString());
        pending.add(event);
        store.save();
        continue;
      }
      apply(space, ship, physical, power, attacker, id);
    }
  }

  private static void apply(
      ServerLevel space, CompoundTag ship, Vec3 physical, float power, Entity attacker, String id) {
    if (ship.getStringOr("lastDamageEvent", "").equals(id)) return;
    var box = WorldStore.bounds(ship).box();
    APPLYING.set(true);
    try {
      space.explode(
          attacker,
          Explosion.getDefaultDamageSource(space, attacker),
          new ExplosionDamageCalculator() {
            @Override
            public Optional<Float> getBlockExplosionResistance(
                Explosion explosion,
                BlockGetter level,
                BlockPos pos,
                BlockState block,
                net.minecraft.world.level.material.FluidState fluid) {
              return WorldStore.bounds(ship).contains(pos)
                  ? super.getBlockExplosionResistance(explosion, level, pos, block, fluid)
                  : Optional.empty();
            }

            @Override
            public boolean shouldBlockExplode(
                Explosion explosion,
                BlockGetter level,
                BlockPos pos,
                BlockState state,
                float strength) {
              return WorldStore.bounds(ship).contains(pos);
            }

            @Override
            public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
              var other = WorldStore.get(space.getServer()).at(space, entity.blockPosition());
              return other == ship || other == null && box.inflate(72).contains(entity.position());
            }
          },
          physical,
          power,
          false,
          Level.ExplosionInteraction.TNT);
      ship.putString("lastDamageEvent", id);
    } finally {
      APPLYING.set(false);
    }
  }

  public static void initialize() {
    ServerContext.tick(
        ServerContext.Phase.COMBAT,
        server -> {
          var store = WorldStore.get(server);
          var pending = store.list("pendingImpacts");
          if (pending.isEmpty()) return;
          boolean changed = false;
          var iterator = pending.iterator();
          while (iterator.hasNext()) {
            var event = (CompoundTag) iterator.next();
            var ship =
                store.ships().stream()
                    .map(t -> (CompoundTag) t)
                    .filter(t -> t.getStringOr("id", "").equals(event.getStringOr("ship", "")))
                    .findFirst()
                    .orElse(null);
            if (ship == null) continue;
            var level = WorldStore.level(server, ship.getStringOr("dimension", ""));
            var base = WorldStore.pos(ship, "base");
            if (TravelService.locked(level, base) || !level.hasChunkAt(base)) continue;
            Entity actor = null;
            if (event.contains("attacker")) {
              var uuid = UUID.fromString(event.getStringOr("attacker", ""));
              for (var world : server.getAllLevels())
                if (world.getEntity(uuid) != null) actor = world.getEntity(uuid);
            }
            apply(
                level,
                ship,
                Vec3.atLowerCornerOf(base)
                    .add(
                        event.getDoubleOr("x", 0),
                        event.getDoubleOr("y", 0),
                        event.getDoubleOr("z", 0)),
                event.getFloatOr("power", 4),
                actor,
                event.getStringOr("id", ""));
            iterator.remove();
            changed = true;
          }
          if (changed) store.save();
        });
  }

  private ShipCombat() {}
}
