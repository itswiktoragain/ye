# Wallfall 2.2.0 — Fabric 1.21.4

Walk toward a wall to attach your gravity to it. While gravity is sideways,
WASD follows your *current camera direction*, projected onto the wall or ceiling.
Look upward while pressing W to climb a tall wall. Jump launches away from the
current gravity surface and gravity pulls you back.

**Walking off a ledge does not reset gravity.** The old auto-detach logic is gone.
Press **G** to return to normal gravity yourself.

To walk upside down, reach a ceiling while climbing upward and keep holding W:
gravity will transition to UP. You can also press **C** to manually switch to
UP gravity if the inverted player hitbox has enough space.

The current rotated slab placement feature is still included.
Install the mod on both client and server with Fabric API (1.21.4, Java 21).
