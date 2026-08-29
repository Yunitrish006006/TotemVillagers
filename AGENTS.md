# TotemVillagers instructions

## Module-owned Observer UI

- Every new or modified player-facing `Screen`/`Menu` must provide a
  module-owned, read-only semantic Observer mode through TotemCore.
- TotemVanillaTweaks must not copy or redraw the Woodcutter UI. Observation is
  framebuffer-free and transports only bounded semantic state.
- Suppress every observer slot/button/edit/drag/scroll/key mutation and packet
  path. Escape only stops observing; viewer authority never becomes target
  authority and private input is never relayed.
- Require unit tests, Client GameTest screenshots, dedicated three-JVM E2E and
  Production Runtime validation for UI changes.
- Provider capture/create and handle methods are client-thread-only; GameTests
  must use their client-thread context helpers.
