package com.nullandkale.nullspace.content;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Checked, stable definitions shared by gameplay and deterministic resource generation. */
public final class ContentCatalog {
  public record EquipmentDefinition(String id, String kind, Set<String> capabilities) {
    public EquipmentDefinition {
      capabilities = Set.copyOf(capabilities);
    }

    public boolean has(String capability) {
      return capabilities.contains(capability);
    }
  }

  public record PlanetDefinition(
      String id,
      String system,
      String systemName,
      String name,
      long seed,
      int color,
      boolean moon,
      boolean breathable,
      String dimension) {}

  private static JsonArray read(String name) {
    try (var in =
        ContentCatalog.class.getResourceAsStream("/data/nullspace/catalog/" + name + ".json")) {
      if (in == null) throw new IllegalStateException("Missing content catalog " + name);
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> List<T> unique(List<T> values, java.util.function.Function<T, String> id) {
    var seen = new HashSet<String>();
    for (T value : values)
      if (!seen.add(id.apply(value)) || !id.apply(value).matches("[a-z_]+"))
        throw new IllegalStateException("Invalid or duplicate content identity");
    return List.copyOf(values);
  }

  public static final List<EquipmentDefinition> EQUIPMENT = equipment();
  public static final List<PlanetDefinition> PLANETS = planets();

  /**
   * The P9 surface pool: one landable dimension per slot, assigned to planets by the world's
   * galaxy chart. Ocean and jungle worlds have breathable air.
   */
  public static final List<PlanetDefinition> SURFACE_POOL = surfacePool();

  /** Every surface dimension, keyed by surface id: Sol's fixed worlds, then the pool. */
  public static final List<PlanetDefinition> SURFACES =
      java.util.stream.Stream.concat(PLANETS.stream(), SURFACE_POOL.stream()).toList();

  private static List<PlanetDefinition> surfacePool() {
    var pool = new ArrayList<PlanetDefinition>();
    for (var entry : com.nullandkale.nullspace.navigation.GalaxyPlan.SURFACE_POOL.entrySet()) {
      var type = entry.getKey();
      boolean breathable =
          type == com.nullandkale.nullspace.navigation.GalaxyPlan.BodyType.OCEAN
              || type == com.nullandkale.nullspace.navigation.GalaxyPlan.BodyType.JUNGLE;
      for (int i = 0; i < entry.getValue(); i++) {
        String id = com.nullandkale.nullspace.navigation.GalaxyPlan.surfaceSlot(type, i);
        pool.add(
            new PlanetDefinition(
                id, "", "", type.label, id.hashCode(), type.color, false, breathable,
                "nullspace:" + id));
      }
    }
    return unique(pool, PlanetDefinition::id);
  }

  /** The surface whose dimension this is, if any. */
  public static Optional<PlanetDefinition> surfaceOfDimension(String dimension) {
    return SURFACES.stream().filter(s -> s.dimension().equals(dimension)).findFirst();
  }

  private static List<EquipmentDefinition> equipment() {
    var values = new ArrayList<EquipmentDefinition>();
    for (var value : read("equipment")) {
      var o = value.getAsJsonObject();
      var caps = new HashSet<String>();
      for (var cap : o.getAsJsonArray("capabilities")) caps.add(cap.getAsString());
      values.add(
          new EquipmentDefinition(o.get("id").getAsString(), o.get("kind").getAsString(), caps));
    }
    return unique(values, EquipmentDefinition::id);
  }

  private static List<PlanetDefinition> planets() {
    var values = new ArrayList<PlanetDefinition>();
    for (var value : read("planets")) {
      var o = value.getAsJsonObject();
      values.add(
          new PlanetDefinition(
              o.get("id").getAsString(),
              o.get("system").getAsString(),
              o.get("systemName").getAsString(),
              o.get("name").getAsString(),
              o.get("seed").getAsLong(),
              o.get("color").getAsInt(),
              o.get("moon").getAsBoolean(),
              o.get("breathable").getAsBoolean(),
              o.get("dimension").getAsString()));
    }
    return unique(values, PlanetDefinition::id);
  }

  private static final Map<String, EquipmentDefinition> EQUIPMENT_BY_ID =
      EQUIPMENT.stream()
          .collect(java.util.stream.Collectors.toUnmodifiableMap(EquipmentDefinition::id, d -> d));
  private static final Map<String, PlanetDefinition> PLANETS_BY_ID =
      SURFACES.stream()
          .collect(java.util.stream.Collectors.toUnmodifiableMap(PlanetDefinition::id, d -> d));

  public static Optional<EquipmentDefinition> equipment(String id) {
    return Optional.ofNullable(EQUIPMENT_BY_ID.get(id));
  }

  public static PlanetDefinition planet(String id) {
    var definition = PLANETS_BY_ID.get(id);
    if (definition == null) throw new IllegalArgumentException("Unknown planet");
    return definition;
  }

  private ContentCatalog() {}
}
