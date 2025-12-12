# InfoHub (Fabric / Client-only)

This ZIP is **not** a full Fabric project on its own – it is a **drop-in patch** for the official template:
- https://github.com/FabricMC/fabric-example-mod

Reason: The template contains the Gradle wrapper / Loom configuration that should match **your** chosen Minecraft version.

## Important naming note (Fabric ID vs. Java package)

Fabric's **mod id** may **not** contain dots (`.`).  
Therefore this mod uses:

- **Mod ID:** `infohub`
- **Java package / group:** `de.cvonderstein.infohub` (matches your requested identifier)

You can change the mod id later if you want, but keep it **lowercase** and without dots.

## What this patch provides

- HUD overlay (text):
  - speed (horizontal, blocks/s)
  - time-to-night (`TN`) or time-to-day (`TD`) in seconds
  - FPS
  - light level of the block you stand on (combined + sky/block split)
  - RTT (ping) to server (0 if unavailable)
  - nearby players within 3/5/7 chunk-radius (length based, i.e. 16 blocks per chunk)
  - nearby mobs within 1/2/3/4 chunk-radius, split into hostile/non-hostile
    - **Exception implemented:** mob radius = 2 uses **real chunk borders** (per your requirement)

- Toggleable spawn markers:
  - Hotkey: `B`
  - Shows outline boxes at positions where **hostile mobs can spawn** (simple heuristic)
  - Scans in a limited radius and is throttled to avoid performance issues

- Local-only chat message:
  - When a new player enters within 4 chunks (64 blocks), you see a message **only on your client**

- F3 overlap rule:
  - If the debug HUD (F3) is open, the InfoHub overlay is hidden.

## Performance / safety

- Player + mob counts are updated only every `COUNTER_UPDATE_INTERVAL_TICKS` (default 10).
- Spawn scanning is throttled (`SPAWN_SCAN_INTERVAL_TICKS`) and capped (`SPAWN_MARKER_MAX`).
- No entity references are stored long-term; we track UUIDs + BlockPos only.
- On disconnect / world change, all cached sets/lists are cleared.

## Tunables

See `InfoHubState` constants:
- `SPAWN_SCAN_RADIUS_BLOCKS`
- `SPAWN_SCAN_VERTICAL_BLOCKS`
- `SPAWN_SCAN_INTERVAL_TICKS`
- `SPAWN_MARKER_MAX`
- `COUNTER_UPDATE_INTERVAL_TICKS`
