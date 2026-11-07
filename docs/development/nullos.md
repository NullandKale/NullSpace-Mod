# NullOS ship computer

The Computer Terminal is a new block. Existing ship controllers and equipment menus remain available. Right-click to open its 80-column console; Shift-Esc closes it. Up/Down recalls commands. Ctrl-C stops the foreground job. Computers can be used as standalone virtual consoles; ship device calls require a connected registered ship and the controls bus.

## Isolation and execution

This is a script-focused abstract runtime, with two custom language frontends. It does not emulate a physical CPU and does not launch a host shell, Python interpreter, Linux process, or external application. Guest files are an in-memory namespace serialized into the Minecraft save. There are no host filesystem, process, network, reflection, native-call, or Java-evaluation functions. Unknown commands fail inside the virtual shell.

The server owns execution and validates console sessions, range, block identity and transit state. Different players can share the same computer but cannot submit input through another player's session. Virtual files travel with the ship and are included in its transfer journal. A standalone computer is adopted into a ship's saved computer map when that ship becomes registered.

A computer has at most eight jobs, 128 variables and 64 KiB of variable values per job, 64 virtual files, 16 KiB per file and 128 KiB of total file contents. Expressions, code size, nesting, output and native-call rates are bounded. Scheduling allows at most 256 instructions per computer per server tick and 4096 across the server. These limits yield jobs rather than blocking a Minecraft tick. Native device calls are limited to 32 per computer and 64 across the server per tick; expensive preflight inspections are limited to two per server tick. Runtime values also have iterative depth/item limits, so deeply nested guest lists cannot overflow the host stack.

Foreground jobs run while a viewer is present and hardware is powered/loaded. `run --detach file.py` or `run --detach file.sh` explicitly leaves a job running. Detached jobs are persisted with their instruction position, variables and compiler ABI, and resume after restarting. Jobs receive rotating budget priority, including when fewer instructions are available than there are jobs. Expressions are compiled once per program. They keep computing without a viewer or loaded chunks, using the last known power state; world calls wait until the entire hull is loaded and equipment is valid. Availability checks query chunk state without requesting loading. On loading, power and connectivity are checked again. Computers never force-load their ship. Foreground jobs are not restarted after a server restart; files are preserved.

## Shell

`help`, `pwd`, `ls`, `cd`, `cat`, `echo`, `mkdir`, `cp`, `mv`, `rm`, `edit`, `clear`, `ps`, `kill`, `bash`, `python`, and `run` are implemented. Pipelines support `grep`, `head`, and `wc`; `>` replaces a file and `>>` appends. Variables use `name=value`, `$name`, and `${name}`. Single quotes preserve literal dollar signs. All paths stay inside the virtual root; there is no host-command passthrough.

`edit name.py` opens a simple replacement editor. Enter file contents one line at a time. A single `.` line or Ctrl-S saves; Ctrl-C cancels. `cat name.py` reads it back. Initial example files are `air.py` and `seal.sh` under `/home/crew`.

## Bash-style scripts

Run with `bash file.sh` or leave running with `run --detach file.sh`.

```sh
i=0
while [ "$i" -lt 5 ]; do
    ship rooms
    echo "sample $i"
    i=$((i+1))
    sleep 1
done
```

Assignments, arithmetic assignments, `if`/`else`/`fi`, `while`/`do`/`done`, comparisons, shell commands, variable expansion and sleep share the same bounded scheduler as Python-style scripts. This language is a defined subset, not full Bash compatibility: no host commands, command substitution, shell-function definitions, arbitrary executable files, or POSIX process model.

## Python-style scripts

Run with `python file.py` or leave running with `run --detach file.py`.

```python
while True:
    oxygen = ship.pressure(5, 4, 5)
    if oxygen < 250:
        print(ship.doors("all", "close"))
        print(ship.power("air", 4))
    print(ship.rooms())
    sleep(1)
```

Implemented constructs: numeric/string/boolean values, lists, assignment, arithmetic, comparisons, short-circuit `and`/`or`, `not`, indented `if`/`else`, `while`, and `for i in range(n)`. Builtins: `print`, `sleep`, `str`, `int`, `len`, `read`, `write`, and `get` for a list index or map field. Structured room results can be read with `get`. Diagnostics identify the script line. Imports, classes, user-defined functions, comprehensions, exceptions, break/continue and full Python compatibility are not implemented. Native functions are implemented in Java and exposed through an explicit name table.

## Shared native ship API

Shell calls use `ship <action> <arguments>`; Python-style calls use `ship.action(arguments)`.

| API | Purpose |
| --- | --- |
| `status()` | Systems summary |
| `rooms()` | Structured room oxygen, volume and leak readings |
| `pressure(x,y,z)` | Gas quantity in a local 0..31 cell; nominal full is 1000 |
| `power(system, amount)` | Allocation request, 0..8 |
| `profile(name)`, `order(...)`, `supply(state)` | Shared engineering commands |
| `doors(group, state)`, `groups()`, `group(...)` | Door groups and open/close commands |
| `devices()`, `device(id, state)` | Equipment discovery and enablement |
| `targets()`, `contacts()` | Structured jump targets and local ship contacts |
| `scan("local")`, `scan("long")` | Powered local or long-range sensor readings |
| `cameras()`, `camera(index)` | Camera discovery and monitor channel selection |
| `inspect()` | Real ship preflight diagnostics |
| `thrust("forward left", ticks)` | Script flight, 1..200 ticks, with normal collision/power checks |
| `stop()` | Stop simulated motion |
| `fire()` | Fire an installed powered weapon, with recharge and normal hit authority |
| `launch()`, `land(pad)`, `jump(address)` | Existing loading-screen/journaled travel |

Flight words include forward/reverse, left/right, up/down, pitch_up/pitch_down, roll_left/roll_right and brake. A crew pilot takes ownership over script flight. Two computers cannot simultaneously own flight control. Commands expire and stop motion if hardware, power, cabin safety or chunk availability is lost.

All normal actions are exposed; no extra computer-specific permission tier is imposed. Device power, connectivity, recharge, safe cabin, collision and transit rules still apply. Travel requires an online crew member aboard the ship controller because the existing transfer protocol needs a player readiness handshake. An unattended job waits when that prerequisite is unavailable. Native actions do not bypass that handshake or manufacture a fake player.


Computer image restoration validates a complete replacement before committing. Invalid images leave the existing virtual disk and processes intact. Repeated restoration replaces saved detached jobs instead of duplicating them. Guest files, working directories and detached sleep deadlines survive valid restoration.

## Disk persistence

Computer images now use a versioned UTF-8 byte envelope with integrity checking instead of a single NBT string. This preserves legal disks above the native string limit, including Unicode text and detached programs. Existing string images migrate on a successful save. Invalid saved images are preserved and the terminal reports that its disk is read-only until repaired. See [runtime architecture](architecture.md) for save scheduling and migration boundaries.
