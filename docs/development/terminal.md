# Terminal device

The display is an actual **80-column by 25-row cell buffer**. Pixel size and Minecraft GUI scale affect its presentation, never its character coordinates. Columns 0..79 and rows 0..24 are addressable. Each cell holds an ASCII glyph, foreground/background ARGB colors, and a bold attribute. Unsupported output glyphs render as `?`; Unicode character widths and CP437 are not implemented.

## Components

- [`TerminalBuffer`](../../src/main/java/com/nullandkale/nullspace/ui/terminal/TerminalBuffer.java): character memory, cursor, wrapping, erase, bounded 200-line immutable scrollback snapshots, and incremental ANSI parsing. Direct `put` writes clip to the display without changing the stream cursor.
- [`TerminalInput`](../../src/main/java/com/nullandkale/nullspace/ui/terminal/TerminalInput.java): separate committed text, physical keys, control codes, and cell-coordinate mouse events. Text is never inferred from keyboard key codes.
- [`TerminalDevice`](../../src/main/java/com/nullandkale/nullspace/ui/terminal/TerminalDevice.java): a display/input port implemented by applications or computer adapters.
- [`ControllerTerminal`](../../src/main/java/com/nullandkale/nullspace/ui/terminal/ControllerTerminal.java): the pad/ship application. Owns its menu, name editor, pending actions, feedback, and timeout. It contains no Minecraft screen or networking code.
- [`StreamTerminal`](../../src/main/java/com/nullandkale/nullspace/ui/terminal/StreamTerminal.java): a reusable stream endpoint. `write(String)` feeds output into its persistent display; an input callback receives UTF-8 text and ANSI key bytes. Output does not disappear between frames. There is no automatic local echo: the connected program controls what is displayed.
- [`TerminalScreen`](../../src/client/java/com/nullandkale/nullspace/ui/screen/TerminalScreen.java): Minecraft rendering and native input adapter. It accepts any `TerminalDevice`, draws cells at fixed spacing, scales wider bold glyphs into a cell, shows the cursor, and forwards input. Pad/ship actions use typed session-bound packets; developer commands remain separately validated.

Minecraft 26.3 requires explicit native text-input activation. The screen starts it during initialization, stops it on removal, and updates the input rectangle at the terminal cursor. Previously the screen only implemented `charTyped`, so synthetic character calls passed while actual typing was disabled. The graphical regression now checks SDL text-input state and routes text through Minecraft's keyboard handler.

## Output protocol

`write` accepts chunks, including escape sequences split across calls. Printable ASCII writes one cell per character. Writing column 80 defers wrapping until the next printable character. CR returns to column 1; LF moves down without changing the column; scrolling at the bottom preserves up to 200 rows. Backspace moves left; tab uses eight-column stops.

Supported escape sequences:

| Sequence | Meaning |
| --- | --- |
| CSI A/B/C/D, G, H/f | Relative/absolute cursor movement; ANSI positions start at 1 |
| CSI J/K | Erase display/line, modes 0, 1, 2; J mode 3 clears history |
| CSI m | Reset, bold, inverse, default foreground/background, 16 ANSI colors and RGB (`38;2;r;g;b` / `48;2;r;g;b`) |
| CSI s/u or ESC 7/8 | Save/restore cursor |
| CSI ?25h / ?25l | Show/hide cursor |
| ESC c | Reset display, attributes, parser, and scrollback |

Unknown sequences are ignored. Parameter length and numbers are bounded. This is an explicit ANSI subset, not a complete VT100/xterm implementation: no alternate screen, scroll regions, device reports, OSC, mouse reporting, or Unicode widths.

## Input and application connection

Controller terminals support arrow keys, Enter, Tab, Home/End, Backspace/Delete, Ctrl+A, and Ctrl+V. Typing focuses the name/destination field automatically. The pad-name character policy and 32-character limit belong to the controller application, not the underlying terminal.

For a computer, construct [`StreamTerminal`](../../src/main/java/com/nullandkale/nullspace/ui/terminal/StreamTerminal.java) with a callback that consumes keyboard bytes, feed its output with `write`, and open `TerminalScreen(device)` on the client thread. Arrows and navigation produce ANSI sequences; Enter produces CR, Backspace DEL, and Ctrl+A..Z produce control bytes (Ctrl+V pastes). Escape is delivered to the computer; Shift+Escape closes its screen using either Shift key. Closing releases native text input and the computer session. Ordinary controller screens retain Escape-to-close. The previous Ctrl+Escape binding conflicted with the Windows Start shortcut.

The graphical regression sends both SDL Shift modifier flags through Minecraft's keyboard handler, checks one close callback with no guest input, and checks native text-input shutdown. The real-computer scenario also checks server-session removal. Fabric 26.3's `TestInputImpl.pressOrReleaseKey` hard-codes event modifiers to zero, so these chord tests supply modifier-bearing `KeyEvent`s to the keyboard callback rather than relying on simulated held Shift keys.

Client display/input calls run on their owning UI thread. The implemented [NullOS computer](nullos.md) runs bounded scripts on the server through a separate authenticated transport. Guest commands, virtual files and explicit ship capabilities remain server-owned. Keyboard packets accept at most 2,048 characters; client paste queues batch them into smaller packets. Full 80 x 25 display snapshots accept at most 131,072 characters, enough for every cell to carry RGB colors. Rejoining viewers receive exact cell colors, including inverted defaults.

## Controller network sessions

[`ComputerTerminal.Open`](../../src/main/java/com/nullandkale/nullspace/ui/terminal/ComputerTerminal.java) supplies a server-owned session UUID, controller mode/location, feedback, default field value, and up to 128 destination aliases (ID/name/state). `Request` carries that UUID, a typed action, a bounded argument, the selected destination ID, and the current engineering configuration revision (-1 for pad/ship controller actions). `Close` closes only the sender's matching session; `Rejected` reports an error only to its matching screen. Opening and response updates reuse a valid session; closing/reopening issues a new one.

The server binds each session to player UUID, controller block position/type, and dimension. It validates life, interaction permission, loaded controller, eight-block range, action compatibility, and travel state. Sessions expire after 1,200 server ticks without a valid action; disconnect, invalidation, and server shutdown clean them up. A stale request cannot revive a session. Packet actions have a two-tick minimum interval; controller operations also retain their existing 500 ms throttle.

Names are at most 32 characters using the controller character policy; destination IDs are at most 36 characters, feedback at most 2,048, and rejected feedback at most 256. Catalog aliases preserve issued IDs through renaming. Reopening refreshes the catalog; pad states are advisory snapshots and the Destinations action performs a fresh state query. Landing always revalidates the chosen ID against the live registry/build.

The GUI submits typed controller packets with bounded arguments; engineering parses its small command vocabulary behind the same session/revision checks. NullOS uses separate [`ComputerService.Screen`](../../src/main/java/com/nullandkale/nullspace/gameplay/computer/ComputerService.java) and `Input` payloads. A delayed update is applied only to the matching open computer session; it cannot reopen a closed screen.

## Verification

Pure JUnit tests cover the 80th column, split escapes, cursor addressing/clamping, colors/attributes, erase semantics, wrapping, bounded scrollback, input encoding, and editing/action behavior. Graphical tests verify native text-input lifecycle, generic stream-device routing, column 80/row 25 access, pad naming/persistence, and the ship launch/landing workflow.


## Ship Controller and engineering applications

`nullspace:ship_computer` is the Ship Controller for travel, weapons and Advanced engineering on the shared server-owned bus. These text applications use the same 80 x 25 cell buffer; camera video is rendered on a separate monitor.

Engineering has arrow-key actions for refresh, explicit upgrade, open/close all doors, and common power changes. Typing selects its Command field; Enter advances to Execute and Enter submits a typed `CONFIGURE` action with the current server revision. `help` lists bounded commands for every subsystem allocation, priority ordering, device on/off, door listings, individual doors, and named groups. Ctrl+A replaces a command. Stale configuration packets are rejected; Refresh obtains the current revision. The server validates the session, real controller, distance, access, ship membership, transfer state, and power before mutation. Closing or losing the controlling terminal cancels queued door work.

`rooms <page>` lists eight room readings per page; `room here` checks the player's eye position; `air audit` reports stored/displaced/lost gas. `profiles` describes `profile cruise`, `profile combat`, and `profile emergency`. Requests respect hardware capacity and individual switches. Status queries tolerate a stale editing revision and remain available during a blackout; mutation checks still apply to writes. Each viewer's selected query updates once per second without reverting to the summary or taking over another viewer's door operation. Refresh or an edit returns to the normal summary. See the improvement record for exact preset requests and construction-loss rules.

Engineering overview readings update once per second over a separate session-bound update packet. Updates do not open screens, overwrite an in-progress command, or reset diagnostic scrolling. Command replies/errors remain visible briefly; help/device/door-list pages stay static until Refresh. Configuration revisions still come from the server.

Every equipment block also opens a local 80 x 25 application with multiline live readings, explicit enable/disable switches, and actions suited to that hardware. These menus have no text field. Arrows/Enter and pointer selection use the same terminal input adapter; PgUp/PgDn scroll readings. A generator or battery offers Manual restart instead of resetting merely on inspection. Monitors offer Next camera and View feed; cameras offer their own View feed. Equipment sessions cannot acquire ship controller or engineering privileges. See the [equipment controls table](../design/ship-systems-and-viewports.md#3-equipment) for the readings and actions.

Equipment panels use a compact six-line readings area and keep the block name only in the header. They omit coordinates, UUIDs, tutorial text, the static legend, and descriptions that merely repeat an action label. Engineering retains detailed diagnostics. The controller application caches its cells and status coloring until content or selection changes; cursor blinking remains handled by the screen adapter.

The applications share a dark navy palette with bright role headers, a blue selected row, cyan editable fields, white readings, and muted help text. Green marks healthy/connected states, amber marks cautions and partial power, and red marks faults, disabled equipment, leaks, and dangerous actions. Power actions use gold; air uses green; cameras/navigation use cyan; engineering uses purple. State labels, numbers, and the selection pointer accompany the colors.

Delivered/requested power is green when fulfilled, amber when partial, red when empty, and muted when the request is zero. Room oxygen is green at 60% and above, amber below 60%, and red at 25% or below. Battery charge uses green at 50% and above, amber below 50%, and red at 20% or below. Zero leak edges is green; a positive count is red. These are display thresholds for the existing readings. Camera status strips use green for LIVE, amber for loading, and red for missing power/disconnection/stale frames. Transit uses the same navy, cyan, and amber palette. The reusable terminal still exposes individual cell colors and program-controlled ANSI colors for future computers.
