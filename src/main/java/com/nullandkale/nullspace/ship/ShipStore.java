package com.nullandkale.nullspace.ship;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.StandardOpenOption;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** World-scoped metadata and durable write-ahead records. No player inventories are stored here. */
public final class ShipStore {
  public record WriteProgress(Path file, String stage, long bytes) {}

  /** Fault-injection seam used by the opt-in local process-crash tests. */
  public static java.util.function.Consumer<WriteProgress> writeObserver = progress -> {};

  private static final com.nullandkale.nullspace.server.ServerContext.Key<ShipStore> KEY =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();
  private final ShipRepository repository;
  private CompoundTag lastSaved;
  private boolean requested;
  public final Path directory;

  private ShipStore(MinecraftServer server) {
    directory = server.getWorldPath(LevelResource.ROOT).resolve("nullspace");
    try {
      Files.createDirectories(directory.resolve("transfers"));
      repository = new ShipRepository(read(directory.resolve("ships.nbt")));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot open ship metadata", e);
    }
    if (repository.encoded().getIntOr("version", 1) != 1)
      throw new IllegalStateException("Unsupported NullSpace save version");
    repository.encoded().putInt("version", 1);
    lastSaved = repository.snapshot();
    repository.onChange(
        change -> {
          com.nullandkale.nullspace.systems.ShipSystems.edited(server, change);
          requestSave();
        });
    repository.onReplace(
        kind -> {
          com.nullandkale.nullspace.systems.ShipSystems.replaced(server);
          com.nullandkale.nullspace.computer.ComputerService.replaced(server, kind);
          requestSave();
        });
  }

  public static ShipStore get(MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server)
        .service(KEY, () -> new ShipStore(server));
  }

  public static void forget(MinecraftServer server) {
    com.nullandkale.nullspace.server.ServerContext.get(server).remove(KEY);
  }

  public ShipRepository repository() {
    return repository;
  }

  public CompoundTag snapshot() {
    return repository.snapshot();
  }

  public CompoundTag section(String name) {
    return repository.section(name);
  }

  public ListTag list(String name) {
    return repository.list(name);
  }

  public void replace(CompoundTag snapshot, ShipRepository.Mutation kind) {
    repository.replace(snapshot, kind);
  }

  public void document(String name, Tag value) {
    repository.document(name, value);
    requestSave();
  }

  public void requestSave() {
    requested = true;
  }

  public static void initialize() {
    com.nullandkale.nullspace.server.ServerContext.tick(
        com.nullandkale.nullspace.server.ServerContext.Phase.SAVE,
        server -> {
          get(server).saveIfRequested(server.getTickCount());
        });
  }

  void saveIfRequested(int tick) {
    if (tick % 20 != 0 || !requested) return;
    try {
      save();
    } catch (RuntimeException error) {
      com.nullandkale.nullspace.NullSpace.LOGGER.error(
          "Ordinary ship save failed; pending changes will retry", error);
    }
  }

  public synchronized void save() {
    // Disconnect and shutdown callbacks can overlap. One store owns one temporary file.
    // Write and validate one frozen document. Live metadata can change through callbacks or an
    // integrated client's shared payloads while IO is in progress.
    var encoded = repository.snapshot();
    if (lastSaved != null
        && lastSaved.equals(encoded)
        && Files.exists(directory.resolve("ships.nbt"))) {
      requested = false;
      return;
    }
    write(directory.resolve("ships.nbt"), encoded);
    lastSaved = encoded;
    requested = !repository.encoded().equals(encoded);
  }

  public static CompoundTag read(Path file) throws java.io.IOException {
    return Files.exists(file)
        ? NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024 * 1024))
        : new CompoundTag();
  }

  public static void write(Path file, CompoundTag tag) {
    try {
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      try (var output = Files.newOutputStream(temp)) {
        NbtIo.writeCompressed(
            tag,
            new java.io.FilterOutputStream(output) {
              private long written;

              @Override
              public void write(int value) throws java.io.IOException {
                out.write(value);
                written++;
                writeObserver.accept(
                    new WriteProgress(file, tag.getStringOr("stage", "metadata"), written));
              }

              @Override
              public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
                out.write(bytes, offset, length);
                written += length;
                writeObserver.accept(
                    new WriteProgress(file, tag.getStringOr("stage", "metadata"), written));
              }
            });
      }
      // Reject a snapshot that recovery could not read, before promoting it or touching world
      // blocks.
      var verified = read(temp);
      if (!tag.equals(verified))
        throw new java.io.IOException(
            "Saved NBT differs at "
                + difference(tag, verified, "root")
                + "; refusing to replace the previous file");
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      com.nullandkale.nullspace.server.AtomicFiles.promote(temp, file);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Could not durably save " + file, error);
    }
  }

  public ListTag ships() {
    return repository.ships();
  }

  private static String difference(Tag expected, Tag actual, String path) {
    if (expected instanceof CompoundTag a && actual instanceof CompoundTag b) {
      var keys = new java.util.TreeSet<>(a.keySet());
      keys.addAll(b.keySet());
      for (var key : keys)
        if (!java.util.Objects.equals(a.get(key), b.get(key)))
          return difference(a.get(key), b.get(key), path + "." + key);
    } else if (expected instanceof ListTag a && actual instanceof ListTag b) {
      if (a.size() != b.size()) return path + ".length";
      for (int i = 0; i < a.size(); i++)
        if (!java.util.Objects.equals(a.get(i), b.get(i)))
          return difference(a.get(i), b.get(i), path + "[" + i + "]");
    }
    return path
        + " ("
        + (expected == null ? "missing" : expected.getClass().getSimpleName())
        + "/"
        + (actual == null ? "missing" : actual.getClass().getSimpleName())
        + ")";
  }

  public CompoundTag at(ServerLevel level, BlockPos pos) {
    return ships().stream()
        .map(t -> (CompoundTag) t)
        .filter(
            ship ->
                ship.getStringOr("dimension", "").equals(level.dimension().identifier().toString())
                    && bounds(ship).contains(pos))
        .max(java.util.Comparator.comparingInt(ship -> pos(ship, "base").getY()))
        .orElse(null);
  }

  public static BlockPos pos(CompoundTag tag, String key) {
    return BlockPos.of(tag.getLongOr(key, 0));
  }

  public static ServerLevel level(MinecraftServer server, String name) {
    var result =
        server.getLevel(
            ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(name)));
    if (result == null) throw new IllegalArgumentException("Dimension unavailable: " + name);
    return result;
  }

  public static ShipScan.Bounds bounds(CompoundTag ship) {
    BlockPos base = pos(ship, "base");
    return new ShipScan.Bounds(
        base,
        base.offset(
            ship.getIntOr("width", 32) - 1,
            Math.clamp(ship.getIntOr("height", 32), 0, 32) - 1,
            ship.getIntOr("depth", 32) - 1));
  }

  public static ListTag targets(CompoundTag root) {
    if (!root.contains("targets")) root.put("targets", new ListTag());
    return root.getListOrEmpty("targets");
  }

  public static void target(
      CompoundTag root,
      UUID id,
      String dimension,
      double x,
      double y,
      double z,
      float yaw,
      float pitch) {
    var targets = targets(root);
    targets.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id.toString()));
    var target = new CompoundTag();
    target.putString("id", id.toString());
    target.putString("dimension", dimension);
    target.putDouble("x", x);
    target.putDouble("y", y);
    target.putDouble("z", z);
    target.putFloat("yaw", yaw);
    target.putFloat("pitch", pitch);
    targets.add(target);
  }

  private ShipStore() {
    throw new AssertionError();
  }
}
