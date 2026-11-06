package com.nullandkale.nullspace.scene;

import java.util.*;
import net.minecraft.nbt.*;

/** Self-contained geometry packets; no delta acknowledgement or missing-baseline state. */
public final class SceneVolumes {
  public static CompoundTag blocks(int[] cells) {
    if (cells.length != VoxelVolume.MAX_CELLS)
      throw new IllegalArgumentException("Invalid scene volume");
    var result = new CompoundTag();
    var packed = VoxelVolume.pack(cells);
    if (packed.bytes() < cells.length * 4) {
      result.putInt("bits", packed.bits());
      result.putIntArray("palette", packed.palette());
      result.putLongArray("words", packed.words());
    } else result.putIntArray("raw", cells);
    return result;
  }

  public static CompoundTag geometry(CompoundTag blocks, byte[] light) {
    if (light.length != VoxelVolume.MAX_CELLS)
      throw new IllegalArgumentException("Invalid scene lighting");
    var result = shallow(blocks);
    result.putByteArray("lightPacked", VoxelVolume.packLight(light));
    return result;
  }

  public static CompoundTag decode(CompoundTag geometry) {
    int[] blocks;
    if (geometry.contains("raw")) {
      blocks =
          geometry
              .getIntArray("raw")
              .orElseThrow(() -> new IllegalArgumentException("Missing raw geometry"));
      if (blocks.length != VoxelVolume.MAX_CELLS)
        throw new IllegalArgumentException("Invalid raw volume");
      for (int id : blocks)
        if (id < 0) throw new IllegalArgumentException("Negative block-state ID");
    } else
      blocks =
          VoxelVolume.unpack(
              new VoxelVolume.Packed(
                  VoxelVolume.MAX_CELLS,
                  geometry.getIntOr("bits", -1),
                  geometry
                      .getIntArray("palette")
                      .orElseThrow(() -> new IllegalArgumentException("Missing voxel palette")),
                  geometry
                      .getLongArray("words")
                      .orElseThrow(() -> new IllegalArgumentException("Missing voxel indices"))));
    byte[] light =
        VoxelVolume.unpackLight(
            geometry
                .getByteArray("lightPacked")
                .orElseThrow(() -> new IllegalArgumentException("Missing scene lighting")),
            VoxelVolume.MAX_CELLS);
    var result = new CompoundTag();
    result.putIntArray("blocks", blocks);
    result.putByteArray("light", light);
    var visuals = geometry.getListOrEmpty("blockEntities");
    VisualSnapshot.validate(visuals, VoxelVolume.MAX_CELLS);
    result.put("blockEntities", visuals);
    return result;
  }

  public static int bytes(CompoundTag geometry) {
    int blocks =
        geometry.contains("raw")
            ? geometry.getIntArray("raw").orElseThrow().length * 4
            : 12
                + geometry.getIntArray("palette").orElseThrow().length * 4
                + geometry.getLongArray("words").orElseThrow().length * 8;
    return blocks
        + geometry.getByteArray("lightPacked").orElseThrow().length
        + geometry.getListOrEmpty("blockEntities").sizeInBytes();
  }

  public static CompoundTag shallow(CompoundTag source) {
    var result = new CompoundTag();
    for (String key : source.keySet()) result.put(key, source.get(key));
    return result;
  }

  /** One per client connection; frame channels share decoded immutable volume arrays. */
  public static final class Cache {
    private record Decoded(long revision, CompoundTag geometry) {}

    private final LinkedHashMap<String, Decoded> volumes = new LinkedHashMap<>(16, .75f, true);
    private final int limit;
    private long decoded, reused;

    public Cache() {
      this(64);
    }

    public Cache(int limit) {
      if (limit < 1 || limit > 256)
        throw new IllegalArgumentException("Invalid scene cache budget");
      this.limit = limit;
    }

    public CompoundTag expand(CompoundTag scene) {
      var subjects = scene.getListOrEmpty("subjects");
      if (subjects.size() > SceneService.MAX_SUBJECTS)
        throw new IllegalArgumentException("Too many scene subjects");
      if (subjects.isEmpty()) return scene;
      var result = shallow(scene);
      var expanded = new ListTag();
      for (Tag tag : subjects) {
        if (!(tag instanceof CompoundTag subject))
          throw new IllegalArgumentException("Invalid scene subject");
        if (expanded.size() + 1 + subject.getListOrEmpty("sections").size()
            > SceneService.MAX_RENDER_SECTIONS)
          throw new IllegalArgumentException("Scene render-section budget exceeded");
        expanded.add(volume(subject));
        var sections = subject.getListOrEmpty("sections");
        if (sections.size() >= SceneService.MAX_RENDER_SECTIONS)
          throw new IllegalArgumentException("Too many vessel render sections");
        for (var section : sections) {
          if (!(section instanceof CompoundTag data)
              || !data.getStringOr("parent", "").equals(subject.getStringOr("id", "")))
            throw new IllegalArgumentException("Invalid station render section");
          expanded.add(volume(data));
        }
      }
      result.put("subjects", expanded);
      return result;
    }

    private CompoundTag volume(CompoundTag subject) {
      if (!subject.contains("geometry")) {
        return subject;
      }
      String id = subject.getStringOr("geometryId", "");
      long revision = subject.getLongOr("geometryRevision", -1);
      if (id.isEmpty() || id.length() > 80 || revision < 0)
        throw new IllegalArgumentException("Invalid geometry identity");
      var old = volumes.get(id);
      CompoundTag geometry;
      if (old != null && old.revision == revision) {
        geometry = old.geometry;
        reused++;
      } else {
        geometry = decode(subject.getCompoundOrEmpty("geometry"));
        decoded++;
        if (old == null || revision > old.revision) {
          volumes.put(id, new Decoded(revision, geometry));
          while (volumes.size() > limit) volumes.remove(volumes.keySet().iterator().next());
        }
      }
      var copy = shallow(subject);
      copy.remove("geometry");
      copy.remove("sections");
      copy.put("blocks", geometry.get("blocks"));
      copy.put("light", geometry.get("light"));
      copy.put("blockEntities", geometry.get("blockEntities"));
      return copy;
    }

    public int size() {
      return volumes.size();
    }

    public long decoded() {
      return decoded;
    }

    public long reused() {
      return reused;
    }

    public void clear() {
      volumes.clear();
      decoded = 0;
      reused = 0;
    }
  }

  private SceneVolumes() {}
}
