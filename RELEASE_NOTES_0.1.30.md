# TotemVillagers 0.1.30

Maintenance release for the village utility terrain-placement fix.

- Village lumberyards and mines use `terrain_matching` projection so remote utility pieces resolve against local ground instead of inheriting the town-center elevation.
- Runtime and resource regressions verify both utility template pools retain `TERRAIN_MATCHING`.
- No other gameplay behavior changes from the validated 0.1.29 codebase.

This version bump preserves release immutability because DeadRecall 2.4.18 already recorded a distinct TotemVillagers 0.1.29 artifact and SHA-512 before the terrain-placement fix was merged.
