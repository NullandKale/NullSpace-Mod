package com.nullandkale.nullspace.client.scene;

import com.nullandkale.nullspace.scene.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Bounded per-connection section cache, independent of the player's native chunk cache. */
public final class TerrainClient {
  private record Data(long revision, CompoundTag data) {}

  private static final LinkedHashMap<String, Data> CACHE = new LinkedHashMap<>(192, .75f, true);
  private static final IdentityHashMap<CompoundTag, CompoundTag> VIEWS = new IdentityHashMap<>();
  private static long version;

  public static int state(String dimension, net.minecraft.core.BlockPos p) {
    var base =
        new net.minecraft.core.BlockPos(
            Math.floorDiv(p.getX(), 16) * 16,
            Math.floorDiv(p.getY(), 16) * 16,
            Math.floorDiv(p.getZ(), 16) * 16);
    var data = CACHE.get(dimension + "/" + base.asLong());
    if (data == null) return -1;
    int[] blocks = data.data.getIntArray("blocks").orElse(new int[0]);
    int x = p.getX() - base.getX(), y = p.getY() - base.getY(), z = p.getZ() - base.getZ();
    return blocks.length == 4096 ? blocks[x + z * 16 + y * 256] : -1;
  }

  public static int size() {
    return CACHE.size();
  }

  public static long version() {
    return version;
  }

  public static void accept(CompoundTag packet) {
    String id = packet.getStringOr("id", "");
    long revision = packet.getLongOr("revision", -1);
    if (id.isEmpty() || id.length() > 128 || revision < 0)
      throw new IllegalArgumentException("Invalid terrain identity");
    var old = CACHE.get(id);
    if (old != null && old.revision >= revision) return;
    int[] blocks =
        VoxelVolume.unpack(
            new VoxelVolume.Packed(
                4096,
                packet.getIntOr("bits", -1),
                packet.getIntArray("palette").orElseThrow(),
                packet.getLongArray("words").orElseThrow()));
    int[] halo =
        VoxelVolume.unpack(
            new VoxelVolume.Packed(
                5832,
                packet.getIntOr("haloBits", -1),
                packet.getIntArray("haloPalette").orElseThrow(),
                packet.getLongArray("haloWords").orElseThrow()));
    var data = new CompoundTag();
    data.putIntArray("blocks", blocks);
    data.putIntArray("halo", halo);
    data.putByteArray(
        "light", VoxelVolume.unpackLight(packet.getByteArray("light").orElseThrow(), 4096));
    data.putInt("size", 16);
    data.putInt("grass", packet.getIntOr("grass", 0x90bc70));
    data.putInt("foliage", packet.getIntOr("foliage", 0x90bc70));
    data.putInt("water", packet.getIntOr("water", 0x3f76e4));
    var entities = packet.getListOrEmpty("blockEntities");
    VisualSnapshot.validate(entities, 4096);
    data.put("blockEntities", entities);
    CACHE.put(id, new Data(revision, data));
    while (CACHE.size() > 192) CACHE.remove(CACHE.keySet().iterator().next());
    VIEWS.clear();
    version++;
  }

  public static CompoundTag resolve(CompoundTag ref) {
    var entry = CACHE.get(ref.getStringOr("id", ""));
    if (entry == null) return null;
    var cached = VIEWS.get(ref);
    if (cached != null) return cached;
    var out = SceneViewport.cameraView(ref);
    for (String key : entry.data.keySet()) out.put(key, entry.data.get(key));
    out.putLong("geometryRevision", entry.revision);
    if (VIEWS.size() >= 192) VIEWS.clear();
    VIEWS.put(ref, out);
    return out;
  }

  public static void clear() {
    CACHE.clear();
    VIEWS.clear();
    version++;
  }

  private TerrainClient() {}
}
