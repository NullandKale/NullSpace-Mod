# Independent ship actions

Players can launch, land, jump, dock and recall separate ships at the same time. A pilot waiting for their loading screen does not stop other ships. Building or generating an unrelated station and calling a ship at another delivery pad also remain available.

The locks follow the objects involved:

- A ship and its crew have one travel action at a time.
- Launches reserve separate space allocations before either ship moves.
- Landing and recall reserve the destination pad. Two ships cannot claim the same pad.
- Docking keeps its berth reservation; a different ship can use another berth.
- Boarding prevents that vessel from moving until its passenger transfer finishes.
- Recall controls stay usable while waiting, including **Cancel**. Cancelling one recall does not cancel another player's.

Preparation, arrival acknowledgement, cancellation and timeout belong to one request token. A token from a different ship cannot release a lock or complete its journey. The source hull and destination stay protected while the request is active.

Physical world changes still commit on the server thread using the transfer journal. Separate ships can prepare and wait concurrently; each commit captures the latest saved metadata. Recovery of a later failed move preserves ships that already committed successfully.

The aggregate server-busy query is retained for administrative regeneration of all stations, which affects the whole station population. Ordinary ship controls use the vessel or destination involved instead.

[Verification](independent-ship-actions-verification.json): 517 unit tests, 170 mod server tests plus the native smoke fixture, and all 24 graphical scenarios passed. Five native concurrency cases exercise real acknowledgement tokens, simultaneous launch reservations, interleaved landings/jumps/recalls, local pad conflicts, usable recall cancellation, and rollback preserving another ship's earlier commit. Real process termination at `SOURCE_CLEARED` also passed for launch and landing. Test worlds were removed after verification; logs and receipts remain.
