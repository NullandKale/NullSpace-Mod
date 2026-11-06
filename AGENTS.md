# Work and verification

The user wants short implementation loops and focused tests during development.

- While implementing a slice, run the smallest tests that exercise its changed behavior and real dependencies. Use one focused native or graphical reproduction where needed.
- Do not repeatedly run the full unit/native/graphical/multiplayer sweep between small fixes. Keep a record of what has passed and what remains for integration.
- Run one integration sweep at the end of the agreed implementation batch, after the design and code are ready. Freeze sources during that sweep.
- If integration finds a failure, reproduce and fix it with focused tests. Repeat the affected integration checks; repeat the entire sweep only when the change crosses systems or invalidates the other results. Explain that reason before starting another broad run.
- Do not expand the implementation scope during final verification for speculative edge cases. Record follow-ups separately; address a demonstrated blocker with an explicit scope change.
- Documentation and design changes do not need Minecraft launches or regression suites.
- A passing structural test does not establish that a ship, station or screen is enjoyable or well designed. Review the actual build and player actions.
