import com.google.gson.GsonBuilder;
import com.nullandkale.nullspace.ship.ShipStore;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Explicit offline repair, using a committed journal as evidence; never edits world blocks. */
public final class RepairShipUpgrade {
  private static void require(boolean condition, String explanation) {
    if (!condition) throw new IllegalStateException(explanation);
  }

  private static List<CompoundTag> ships(CompoundTag root) {
    return root.getListOrEmpty("ships").stream().map(t -> (CompoundTag) t).toList();
  }

  private static boolean sameLocation(CompoundTag a, CompoundTag b) {
    return a.getStringOr("dimension", "").equals(b.getStringOr("dimension", ""))
        && ShipStore.pos(a, "base").equals(ShipStore.pos(b, "base"))
        && ShipStore.pos(a, "controller").equals(ShipStore.pos(b, "controller"));
  }

  private static String sha(Path file) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  private static CompoundTag plan(
      CompoundTag original, CompoundTag journal, String prefix, Map<String, Object> receipt) {
    require(journal.getStringOr("stage", "").equals("COMMITTED"), "Transfer is not committed");
    var matches =
        ships(original).stream().filter(s -> s.getStringOr("id", "").startsWith(prefix)).toList();
    require(matches.size() == 1, "Ship prefix is ambiguous or absent");
    var active = matches.getFirst();
    require(!active.contains("systems"), "Ship already has systems; refusing to replace them");
    var id = active.getStringOr("id", "");
    var after =
        ships(journal.getCompoundOrEmpty("after")).stream()
            .filter(s -> s.getStringOr("id", "").equals(id))
            .findFirst()
            .orElseThrow();
    require(sameLocation(active, after), "Ship has moved since this journal");
    require(!after.contains("systems"), "Journal does not demonstrate a lost upgrade");
    var delta = ShipStore.pos(journal, "delta");
    var sourceBase = ShipStore.pos(active, "base").subtract(delta);
    var sourceHelm = ShipStore.pos(active, "controller").subtract(delta);
    var sourceDimension = journal.getStringOr("source", "");
    var before = ships(journal.getCompoundOrEmpty("before"));
    var donors =
        before.stream()
            .filter(
                s ->
                    s.contains("systems")
                        && s.getStringOr("dimension", "").equals(sourceDimension)
                        && ShipStore.pos(s, "base").equals(sourceBase)
                        && ShipStore.pos(s, "controller").equals(sourceHelm)
                        && s.getStringOr("homeId", "").equals(active.getStringOr("homeId", "")))
            .toList();
    require(donors.size() == 1, "Exactly one matching saved upgrade is required");
    var donor = donors.getFirst();
    var systems = donor.getCompoundOrEmpty("systems");
    require(systems.getIntArray("air").orElseThrow().length == 32768, "Invalid saved air grid");
    var template = journal.getCompoundOrEmpty("template");
    var palette = template.getListOrEmpty("palette");
    var captured = new HashMap<BlockPos, String>();
    for (var tag : template.getListOrEmpty("blocks")) {
      var block = (CompoundTag) tag;
      var position = BlockPos.CODEC.parse(NbtOps.INSTANCE, block.get("pos")).result().orElseThrow();
      var state = palette.getCompoundOrEmpty(block.getIntOr("state", -1));
      captured.put(position, state.getStringOr("id", state.getStringOr("Name", "")));
    }
    var minimum = ShipStore.pos(journal, "min");
    var devices = systems.getListOrEmpty("devices");
    require(!devices.isEmpty(), "No saved equipment to verify");
    for (var tag : devices) {
      var device = (CompoundTag) tag;
      var position =
          sourceBase.offset(BlockPos.of(device.getLongOr("position", 0))).subtract(minimum);
      require(
          device.getStringOr("type", "").equals(captured.get(position)),
          "Saved equipment does not match the hull actually transferred at " + position);
    }
    var oldRecords = new HashMap<String, CompoundTag>();
    for (var s : before)
      if (s.getStringOr("dimension", "").equals(sourceDimension)
          && ShipStore.pos(s, "base").equals(sourceBase))
        oldRecords.put(s.getStringOr("id", ""), s);
    var repaired = original.copy();
    var retired = new ArrayList<String>();
    repaired
        .getListOrEmpty("ships")
        .removeIf(
            t -> {
              var s = (CompoundTag) t;
              var old = oldRecords.get(s.getStringOr("id", ""));
              if (old == null || !sameLocation(old, s)) return false;
              retired.add(s.getStringOr("id", ""));
              return true;
            });
    var current =
        ships(repaired).stream()
            .filter(s -> s.getStringOr("id", "").equals(id))
            .findFirst()
            .orElseThrow();
    current.put("systems", systems.copy());
    current.putString("systemsRecoveryJournal", journal.getStringOr("id", ""));
    require(current.getListOrEmpty("crew").equals(active.getListOrEmpty("crew")), "Crew changed");
    for (var key : original.keySet())
      if (!key.equals("ships"))
        require(Objects.equals(original.get(key), repaired.get(key)), "Unrelated data changed");
    receipt.put("ship", id);
    receipt.put("upgradeSource", donor.getStringOr("id", ""));
    receipt.put("verifiedDevices", devices.size());
    receipt.put("retiredDuplicateRecords", retired);
    receipt.put("recordsBefore", ships(original).size());
    receipt.put("recordsAfter", ships(repaired).size());
    receipt.put(
        "savedSystemsRestoredExactly", current.getCompoundOrEmpty("systems").equals(systems));
    return repaired;
  }

  public static void main(String[] args) throws Exception {
    require(
        args.length == 4 || args.length == 5,
        "Usage: world ship-id-prefix journal-filename output-directory [expected-original-sha256]");
    var world = Path.of(args[0]).toRealPath();
    require(args[2].matches("[a-f0-9-]{36}\\.nbt"), "Invalid journal filename");
    var directory = world.resolve("nullspace");
    var source = directory.resolve("ships.nbt");
    var output = Path.of(args[3]).toAbsolutePath().normalize();
    Files.createDirectories(output);
    var originalSha = sha(source);
    var original = ShipStore.read(source);
    var receipt = new LinkedHashMap<String, Object>();
    receipt.put("originalSha256", originalSha);
    var repaired =
        plan(
            original,
            ShipStore.read(directory.resolve("transfers").resolve(args[2])),
            args[1],
            receipt);
    var candidate = output.resolve("ships-repaired.nbt");
    ShipStore.write(candidate, repaired);
    require(ShipStore.read(candidate).equals(repaired), "Candidate failed round trip");
    receipt.put("candidate", candidate.toString());
    receipt.put("candidateSha256", sha(candidate));
    receipt.put("applied", false);
    if (args.length == 5) {
      require(args[4].equals(originalSha), "Save changed since plan review");
      // Minecraft owns this OS lock while the world is open. Never edit a live save.
      try (var channel =
              FileChannel.open(
                  world.resolve("session.lock"),
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE);
          var lock = channel.tryLock()) {
        require(lock != null && lock.isValid(), "Close this world before applying the repair");
        require(sha(source).equals(originalSha), "Save changed while acquiring its lock");
        try (var journals = Files.list(directory.resolve("transfers"))) {
          for (var file : journals.filter(p -> p.toString().endsWith(".nbt")).toList()) {
            var stage = ShipStore.read(file).getStringOr("stage", "");
            require(
                stage.equals("COMMITTED") || stage.equals("ROLLED_BACK"),
                "Unfinished transfer needs recovery first");
          }
        }
        var backups = directory.resolve("backups");
        Files.createDirectories(backups);
        var backup =
            backups.resolve(
                "ships-before-identity-repair-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + ".nbt");
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        require(sha(backup).equals(originalSha), "Backup differs from source");
        ShipStore.write(source, repaired);
        require(
            ShipStore.read(source).equals(repaired),
            "Applied repair differs from verified candidate");
        receipt.put("backup", backup.toString());
        receipt.put("applied", true);
      }
    }
    String json = new GsonBuilder().setPrettyPrinting().create().toJson(receipt);
    Files.writeString(output.resolve("receipt.json"), json + System.lineSeparator());
    System.out.println(json);
  }
}
