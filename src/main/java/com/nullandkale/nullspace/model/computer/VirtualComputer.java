package com.nullandkale.nullspace.model.computer;

import com.google.gson.*;
import com.nullandkale.nullspace.ui.terminal.TerminalBuffer;
import java.util.*;

/** Script scheduler, shell and virtual disk. All native capabilities are supplied explicitly. */
public final class VirtualComputer implements ScriptLanguage.Host {
  public interface Devices {
    Object call(String name, List<Object> arguments);
  }

  public record Process(
      int id, String source, ScriptLanguage.Syntax syntax, ScriptLanguage.Job job) {}

  public final VirtualFiles files = new VirtualFiles();
  public final TerminalBuffer display = new TerminalBuffer();
  public final LinkedHashMap<Integer, Process> processes = new LinkedHashMap<>();
  public final LinkedHashMap<String, Object> variables = new LinkedHashMap<>();
  private final Devices devices;
  private String cwd = "/home/crew", line = "", escape = "", editing = "";
  private final StringBuilder edited = new StringBuilder();
  private final ArrayList<String> history = new ArrayList<>();
  private int historyAt, caret, nextPid = 1, foreground = -1, outputBudget = 8192;
  private long clock;
  private int revision;
  private final FairScheduler<Process> scheduler = new FairScheduler<>();

  public VirtualComputer(Devices devices) {
    this.devices = devices;
    files.write(
        "/etc/motd",
        "NullOS: isolated ship scripting environment\n"
            + "help shows commands; Shift-Esc closes this screen.\n");
    files.write(
        "/home/crew/air.py",
        "# Spatial air readings and native ship API\n"
            + "for i in range(5):\n"
            + "    print(ship.rooms())\n"
            + "    sleep(1)\n");
    files.write(
        "/home/crew/seal.sh",
        "# Close all powered doors using the shared engineering API\n"
            + "ship doors all close\n"
            + "ship rooms\n");
    write(
        "\033[96mNullOS 1 / 80-column console\033[0m\r\n"
            + files.read("/etc/motd").replace("\n", "\r\n"));
    prompt();
  }

  public long clock() {
    return clock;
  }

  public int revision() {
    return revision;
  }

  public void write(String text) {
    if (text.length() > outputBudget) text = text.substring(0, Math.max(0, outputBudget));
    outputBudget -= text.length();
    if (!text.isEmpty()) {
      display.write(text);
      revision++;
    }
  }

  private void prompt() {
    write("\033[92mcrew:" + cwd + "$ \033[0m");
  }

  private void redraw() {
    write("\r\033[2K");
    if (editing.isEmpty()) prompt();
    else write("edit> ");
    write(line);
    if (caret < line.length()) write("\033[" + (line.length() - caret) + "D");
  }

  public void input(String input) {
    if (input.length() > 2048) throw new IllegalArgumentException("Input is too large");
    outputBudget = 8192;
    boolean repaint = false;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c == 0) continue;
      if (!escape.isEmpty()) {
        escape += c;
        if (escape.equals("\033[")) continue;
        if (escape.length() > 8) {
          escape = "";
          continue;
        }
        if (c >= '@' && c <= '~') {
          String sequence = escape;
          escape = "";
          if (sequence.equals("\033[A") && !history.isEmpty()) {
            historyAt = Math.max(0, historyAt - 1);
            line = history.get(historyAt);
            caret = line.length();
          } else if (sequence.equals("\033[B")) {
            historyAt = Math.min(history.size(), historyAt + 1);
            line = historyAt == history.size() ? "" : history.get(historyAt);
            caret = line.length();
          } else if (sequence.equals("\033[D")) caret = Math.max(0, caret - 1);
          else if (sequence.equals("\033[C")) caret = Math.min(line.length(), caret + 1);
          repaint = true;
        }
        continue;
      }
      if (c == 27) {
        escape = "\033";
        continue;
      }
      if (c == 3) {
        if (foreground >= 0) {
          var p = processes.get(foreground);
          if (p != null) p.job.done = true;
          foreground = -1;
        }
        editing = "";
        edited.setLength(0);
        line = "";
        caret = 0;
        write("^C\r\n");
        prompt();
        repaint = false;
        continue;
      }
      if (c == 19 && !editing.isEmpty()) {
        saveEdit();
        continue;
      }
      if (foreground >= 0) continue;
      if (c == '\r' || c == '\n') {
        if (repaint) {
          redraw();
          repaint = false;
        }
        String command = line;
        line = "";
        caret = 0;
        write("\r\n");
        if (!editing.isEmpty()) {
          if (command.equals(".")) saveEdit();
          else {
            if (edited.length() + command.length() + 1 > VirtualFiles.MAX_FILE) {
              write("Editor limit reached. Ctrl-C cancels.\r\n");
            } else edited.append(command).append('\n');
            write("edit> ");
          }
          continue;
        }
        if (!command.isBlank()) {
          history.add(command);
          if (history.size() > 50) history.removeFirst();
          historyAt = history.size();
          try {
            String result = shell(command, variables);
            if (!result.isEmpty()) write(result.replace("\n", "\r\n") + "\r\n");
          } catch (RuntimeException e) {
            write("\033[91m" + e.getMessage() + "\033[0m\r\n");
          }
        }
        if (foreground < 0 && editing.isEmpty()) prompt();
        continue;
      }
      if (c == 127 || c == 8) {
        if (caret > 0) {
          line = line.substring(0, caret - 1) + line.substring(caret);
          caret--;
          repaint = true;
        }
      } else if (c >= 32 && c <= 126 && line.length() < 1024) {
        line = line.substring(0, caret) + c + line.substring(caret);
        caret++;
        repaint = true;
      }
    }
    if (repaint) redraw();
  }

  private void saveEdit() {
    try {
      files.write(editing, edited.toString());
      write("Saved " + editing + "\r\n");
    } catch (IllegalArgumentException e) {
      write(e.getMessage() + "\r\n");
    }
    editing = "";
    edited.setLength(0);
    line = "";
    caret = 0;
    prompt();
  }

  public void tick(boolean attended, boolean powered) {
    tick(attended, powered, 256);
  }

  public int tick(boolean attended, boolean powered, int allowance) {
    if (powered) clock++;
    outputBudget = 8192;
    int budget = Math.clamp(allowance, 0, 256), initial = budget;
    var jobs = scheduler.order(processes.values());
    int count = jobs.size();
    for (int n = 0; n < count; n++) {
      var p = jobs.get(n);
      var job = p.job;
      if (budget > 0 && !job.done && powered && (attended || job.detached))
        budget -= job.step(this, clock, FairScheduler.quantum(budget, count - n, 64));
      if (job.done) {
        if (!job.failure.isEmpty()) write("\r\n[" + p.id + "] " + job.failure + "\r\n");
        if (p.id == foreground) {
          foreground = -1;
          write("\r\n");
          prompt();
        }
        processes.remove(p.id);
      }
    }
    return initial - budget;
  }

  public int run(String path, ScriptLanguage.Syntax syntax, boolean detached) {
    if (processes.size() >= 8)
      throw new IllegalArgumentException("At most eight processes may run");
    String source = files.read(path);
    var job = new ScriptLanguage.Job(ScriptLanguage.compile(source, syntax));
    job.detached = detached;
    job.variables.putAll(variables);
    int id = nextPid++;
    processes.put(id, new Process(id, source, syntax, job));
    if (!detached) foreground = id;
    return id;
  }

  @Override
  public Object call(String name, List<Object> args) {
    ScriptLanguage.checkValue(args, 32768);
    return switch (name) {
      case "print" -> {
        write(
            String.join(" ", args.stream().map(ScriptLanguage::text).toList())
                    .replace("\r\n", "\n")
                    .replace("\n", "\r\n")
                + "\r\n");
        yield null;
      }
      case "str" -> {
        arity(args, 1);
        yield ScriptLanguage.text(args.getFirst());
      }
      case "int" -> {
        arity(args, 1);
        yield (double) (long) ScriptLanguage.number(args.getFirst());
      }
      case "len" -> {
        arity(args, 1);
        yield args.getFirst() instanceof Collection<?> c
            ? (double) c.size()
            : (double) ScriptLanguage.text(args.getFirst()).length();
      }
      case "read" -> {
        arity(args, 1);
        yield files.read(VirtualFiles.path(cwd, ScriptLanguage.text(args.getFirst())));
      }
      case "write" -> {
        arity(args, 2);
        files.write(
            VirtualFiles.path(cwd, ScriptLanguage.text(args.getFirst())),
            ScriptLanguage.text(args.get(1)));
        yield null;
      }
      case "get" -> {
        arity(args, 2);
        Object object = args.getFirst();
        String key = ScriptLanguage.text(args.get(1));
        yield object instanceof Map<?, ?> m
            ? m.get(key)
            : object instanceof List<?> l ? l.get((int) ScriptLanguage.number(args.get(1))) : null;
      }
      default -> {
        if (!name.startsWith("ship."))
          throw new IllegalArgumentException("Unknown function: " + name);
        yield devices.call(name.substring(5), args);
      }
    };
  }

  private static void arity(List<?> args, int count) {
    if (args.size() != count)
      throw new IllegalArgumentException("Expected " + count + " arguments");
  }

  @Override
  public String shell(String command, Map<String, Object> vars) {
    var tokens = new ArrayList<String>();
    int expandedSize = 0;
    for (var token : ScriptLanguage.words(command, true)) {
      String expanded = ScriptLanguage.expand(token, vars);
      expandedSize += expanded.length();
      if (expandedSize > 32768)
        throw new IllegalArgumentException("Expanded command exceeds memory limit");
      tokens.add(expanded);
    }
    if (tokens.isEmpty()) return "";
    int redirect = -1;
    boolean append = false;
    for (int i = 0; i < tokens.size(); i++)
      if (tokens.get(i).equals("\u0001>") || tokens.get(i).equals("\u0001>>")) {
        if (redirect >= 0 || i != tokens.size() - 2)
          throw new IllegalArgumentException("Use one output redirection");
        redirect = i;
        append = tokens.get(i).equals("\u0001>>");
      }
    String output = "";
    int start = 0, end = redirect < 0 ? tokens.size() : redirect;
    for (int i = 0; i <= end; i++)
      if (i == end || tokens.get(i).equals("\u0001|")) {
        if (i == start) throw new IllegalArgumentException("Missing pipeline command");
        output = command(tokens.subList(start, i), output, vars);
        if (output.length() > 32768)
          throw new IllegalArgumentException("Pipeline output limit exceeded");
        start = i + 1;
      }
    if (redirect >= 0) {
      String p = VirtualFiles.path(cwd, tokens.getLast());
      files.write(p, (append ? files.files().getOrDefault(p, "") : "") + output + "\n");
      return "";
    }
    return output;
  }

  private String command(List<String> w, String stdin, Map<String, Object> vars) {
    String name = w.getFirst();
    List<String> args = w.subList(1, w.size());
    return switch (name) {
      case "help" ->
          "help pwd ls cd cat echo mkdir cp mv rm edit sh bash python run ps kill clear\n"
              + "Variables: name=value; scripts: bash file.sh / python file.py\n"
              + "Background: run --detach file.py (or file.sh); jobs persist explicitly\n"
              + "Pipes: grep/head/wc; redirection: > and >>; history: Up/Down\n"
              + "Shift-Esc closes the console; Ctrl-C stops the foreground job.\n"
              + "ship status | rooms | power air 2 | doors all close | scan local\n"
              + "ship jump sol_luna | launch | land <pad name> | fire | cameras\n"
              + "Python: ship.status(), ship.power('air',2), sleep(1), print(...).";
      case "pwd" -> cwd;
      case "ls" ->
          String.join(
              "  ", files.list(args.isEmpty() ? cwd : VirtualFiles.path(cwd, args.getFirst())));
      case "cd" -> {
        arity(args, 1);
        String p = VirtualFiles.path(cwd, args.getFirst());
        if (!files.directory(p)) throw new IllegalArgumentException("No such directory");
        cwd = p;
        yield "";
      }
      case "echo" -> String.join(" ", args);
      case "cat" -> {
        if (args.isEmpty()) yield stdin;
        arity(args, 1);
        yield files.read(VirtualFiles.path(cwd, args.getFirst()));
      }
      case "mkdir" -> {
        arity(args, 1);
        files.mkdir(VirtualFiles.path(cwd, args.getFirst()));
        yield "";
      }
      case "rm" -> {
        arity(args, 1);
        files.remove(VirtualFiles.path(cwd, args.getFirst()));
        yield "";
      }
      case "cp", "mv" -> {
        arity(args, 2);
        String from = VirtualFiles.path(cwd, args.getFirst());
        String to = VirtualFiles.path(cwd, args.get(1));
        String contents = files.read(from);
        if (from.equals(to)) yield "";
        files.write(to, contents);
        if (name.equals("mv")) files.remove(from);
        yield "";
      }
      case "edit" -> {
        arity(args, 1);
        editing = VirtualFiles.path(cwd, args.getFirst());
        edited.setLength(0);
        write(
            "Enter replacement file contents. A single '.' or Ctrl-S saves; Ctrl-C cancels.\r\n"
                + "edit> ");
        yield "";
      }
      case "clear" -> {
        write("\033[2J\033[H");
        yield "";
      }
      case "grep" -> {
        arity(args, 1);
        yield String.join("\n", stdin.lines().filter(l -> l.contains(args.getFirst())).toList());
      }
      case "head" -> {
        if (args.size() > 1 || !args.isEmpty() && !args.getFirst().matches("[+-]?\\d{1,10}"))
          throw new IllegalArgumentException(
              "Usage: head [line count]. Reads piped input; counts clamp to 0..1000.");
        long count = args.isEmpty() ? 10 : Long.parseLong(args.getFirst());
        yield String.join("\n", stdin.lines().limit(Math.clamp(count, 0, 1000)).toList());
      }
      case "wc" -> Long.toString(stdin.lines().count());
      case "ps" ->
          processes.isEmpty()
              ? "No running processes"
              : String.join(
                  "\n",
                  processes.values().stream()
                      .map(
                          p ->
                              p.id
                                  + " "
                                  + p.syntax
                                  + " "
                                  + (p.job.detached ? "DETACHED" : "FOREGROUND")
                                  + " pc="
                                  + p.job.pc
                                  + (p.job.waiting.isEmpty() ? "" : " WAIT " + p.job.waiting))
                      .toList());
      case "kill" -> {
        arity(args, 1);
        var p = processes.get(Integer.parseInt(args.getFirst()));
        if (p == null) throw new IllegalArgumentException("No such process");
        p.job.done = true;
        yield "Stopped " + p.id;
      }
      case "sh", "bash", "python", "run" -> {
        boolean detached = args.contains("--detach");
        var actual = args.stream().filter(a -> !a.equals("--detach")).toList();
        arity(actual, 1);
        String file = VirtualFiles.path(cwd, actual.getFirst());
        var syntax =
            name.equals("python") || name.equals("run") && file.endsWith(".py")
                ? ScriptLanguage.Syntax.PYTHON
                : ScriptLanguage.Syntax.BASH;
        int pid = run(file, syntax, detached);
        yield detached ? "[" + pid + "] detached" : "";
      }
      case "ship" -> {
        if (args.isEmpty())
          yield "ship status / rooms / power / doors / scan / jump / launch / land / fire /"
              + " cameras";
        yield ScriptLanguage.text(
            devices.call(args.getFirst(), new ArrayList<Object>(args.subList(1, args.size()))));
      }
      default -> {
        if (w.size() == 1 && name.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
          int eq = name.indexOf('=');
          if (vars.size() >= 128 && !vars.containsKey(name.substring(0, eq)))
            throw new IllegalArgumentException("Variable limit reached");
          vars.put(name.substring(0, eq), name.substring(eq + 1));
          yield "";
        }
        throw new IllegalArgumentException("Unknown virtual command: " + name);
      }
    };
  }

  private record JobImage(Process process, int pc, long wake, Map<String, Object> variables) {}

  private record ImageStamp(long diskRevision, long clock, String cwd, List<JobImage> jobs) {}

  private ImageStamp savedStamp;
  private String savedImage;

  public String save() {
    var jobsState = new ArrayList<JobImage>();
    for (var p : processes.values())
      if (p.job.detached && !p.job.done)
        jobsState.add(new JobImage(p, p.job.pc, p.job.wake, new LinkedHashMap<>(p.job.variables)));
    var stamp =
        new ImageStamp(
            files.revision(), jobsState.isEmpty() ? 0 : clock, cwd, List.copyOf(jobsState));
    if (stamp.equals(savedStamp)) return savedImage;
    var root = new JsonObject();
    root.addProperty("version", 1);
    root.addProperty("compilerAbi", ScriptLanguage.COMPILER_ABI);
    root.addProperty("cwd", cwd);
    root.addProperty("clock", clock);
    root.add("files", new Gson().toJsonTree(files.files()));
    root.add("directories", new Gson().toJsonTree(files.directories()));
    var jobs = new JsonArray();
    for (var p : processes.values())
      if (p.job.detached && !p.job.done) {
        var j = new JsonObject();
        j.addProperty("source", p.source);
        j.addProperty("syntax", p.syntax.name());
        j.addProperty("pc", p.job.pc);
        j.addProperty("wake", p.job.wake);
        j.add("variables", new Gson().toJsonTree(p.job.variables));
        jobs.add(j);
      }
    root.add("jobs", jobs);
    savedImage = root.toString();
    savedStamp = stamp;
    return savedImage;
  }

  public void restore(String image) {
    // Build and validate a complete replacement before touching the running machine.
    var restored = new VirtualComputer(devices);
    try {
      restored.restoreImage(image);
    } catch (JsonParseException malformed) {
      throw new IllegalArgumentException("Invalid computer image JSON", malformed);
    }
    files.replaceWith(restored.files);
    processes.clear();
    processes.putAll(restored.processes);
    clock = restored.clock;
    cwd = restored.cwd;
    nextPid = restored.nextPid;
    foreground = -1;
    variables.clear();
    line = "";
    caret = 0;
    escape = "";
    editing = "";
    edited.setLength(0);
    history.clear();
    historyAt = 0;
    outputBudget = 8192;
    write("\r\nDisk restored; " + processes.size() + " detached jobs resumed.\r\n");
    prompt();
  }

  private void restoreImage(String image) {
    if (image == null) throw new IllegalArgumentException("Computer image is missing");
    if (image.length() > 8 * 1024 * 1024)
      throw new IllegalArgumentException("Computer image exceeds limit");
    var parsed = JsonParser.parseString(image);
    if (!parsed.isJsonObject())
      throw new IllegalArgumentException("Computer image must be an object");
    var root = parsed.getAsJsonObject();
    if (number(root, "version") != 1)
      throw new IllegalArgumentException("Unknown computer image version");
    if (!root.has("compilerAbi") || number(root, "compilerAbi") != ScriptLanguage.COMPILER_ABI)
      throw new IllegalArgumentException("Saved scripts require a different compiler ABI");
    clock = Math.max(0, number(root, "clock"));
    files.clear();
    if (root.has("directories"))
      for (var value : array(root, "directories")) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
          throw new IllegalArgumentException("Image directory names must be strings");
        String dir = value.getAsString();
        if (!files.directory(dir)) files.mkdir(VirtualFiles.path("/", dir));
      }
    var savedFiles = object(root, "files");
    for (var entry : savedFiles.entrySet())
      files.write(VirtualFiles.path("/", entry.getKey()), string(savedFiles, entry.getKey()));
    cwd = VirtualFiles.path("/", string(root, "cwd"));
    if (!files.directory(cwd)) cwd = "/home/crew";
    if (root.has("jobs"))
      for (var value : array(root, "jobs")) {
        if (processes.size() >= 8) throw new IllegalArgumentException("Too many saved processes");
        if (!value.isJsonObject())
          throw new IllegalArgumentException("Image job must be an object");
        var j = value.getAsJsonObject();
        String source = string(j, "source");
        var syntax = ScriptLanguage.Syntax.valueOf(string(j, "syntax"));
        var job = new ScriptLanguage.Job(ScriptLanguage.compile(source, syntax));
        job.detached = true;
        long pc = number(j, "pc");
        if (pc < 0 || pc > Integer.MAX_VALUE)
          throw new IllegalArgumentException("Invalid saved instruction position");
        job.pc = (int) pc;
        if (job.pc < 0 || job.pc > job.program.code().size())
          throw new IllegalArgumentException("Invalid saved instruction position");
        job.wake = Math.max(0, number(j, "wake"));
        for (var e : object(j, "variables").entrySet()) {
          if (job.variables.size() >= 128 || e.getValue().toString().length() > 8192)
            throw new IllegalArgumentException("Saved variables exceed limit");
          Object variable = new Gson().fromJson(e.getValue(), Object.class);
          ScriptLanguage.checkValue(variable, 8192);
          job.variables.put(e.getKey(), variable);
        }
        ScriptLanguage.checkValue(job.variables, 65536);
        int id = nextPid++;
        processes.put(id, new Process(id, source, syntax, job));
      }
  }

  private static JsonElement required(JsonObject root, String key) {
    var value = root.get(key);
    if (value == null || value.isJsonNull())
      throw new IllegalArgumentException("Computer image is missing field: " + key);
    return value;
  }

  private static String string(JsonObject root, String key) {
    var value = required(root, key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Computer image field must be a string: " + key);
    return value.getAsString();
  }

  private static long number(JsonObject root, String key) {
    var value = required(root, key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Computer image field must be an integer: " + key);
    try {
      return value.getAsBigDecimal().longValueExact();
    } catch (ArithmeticException invalid) {
      throw new IllegalArgumentException(
          "Computer image field must be a whole 64-bit integer: " + key);
    }
  }

  private static JsonObject object(JsonObject root, String key) {
    var value = required(root, key);
    if (!value.isJsonObject())
      throw new IllegalArgumentException("Computer image field must be an object: " + key);
    return value.getAsJsonObject();
  }

  private static JsonArray array(JsonObject root, String key) {
    var value = required(root, key);
    if (!value.isJsonArray())
      throw new IllegalArgumentException("Computer image field must be an array: " + key);
    return value.getAsJsonArray();
  }
}
