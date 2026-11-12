# Work and verification

The user wants large, coherent implementation chunks because Minecraft test launches are expensive.

- Read the relevant implementation and call paths before changing code or launching reproductions. Prefer code inspection to repeated test runs. Performance measurement/tuning is deferred; retain existing changes unless a demonstrated correctness failure requires a fix.

- Implement the related changes in an agreed chunk before launching its tests. Use compile checks during development; do not launch Minecraft after every small edit.
- Run one combined set of relevant tests per completed chunk, exercising changed behavior and real dependencies. Review actual native or graphical behavior where needed.
- If the combined check fails, isolate and fix the demonstrated failure with focused checks. Do not repeat unaffected suites.
- Do not repeatedly run the full unit/native/graphical/multiplayer sweep between chunks. Keep a record of what has passed and what remains for integration.
- Run one integration sweep at the end of the agreed implementation batch, after the design and code are ready. Freeze sources during that sweep.
- Collect outcomes for every selected check before fixing failures. One failed graphical class must not prevent the remaining classes from running. Fix the complete collected failure list as one batch, then run the affected checks together; do not alternate one failure, one fix and one launch.
- If integration finds a failure, reproduce and fix it with focused tests. Repeat the affected integration checks; repeat the entire sweep only when the change crosses systems or invalidates the other results. Explain that reason before starting another broad run.
- Do not expand the implementation scope during final verification for speculative edge cases. Record follow-ups separately; address a demonstrated blocker with an explicit scope change.
- Documentation and design changes do not need Minecraft launches or regression suites.
- A passing structural test does not establish that a ship, station or screen is enjoyable or well designed. Review the actual build and player actions.
