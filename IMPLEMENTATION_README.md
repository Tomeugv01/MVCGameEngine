# MVCGameEngine — Implementation Tracking README

This document is the running log of **every feature implemented** and **every file modified**.

## How this file is maintained

For every new feature, append one entry in **Feature Log** with:
1. Feature name and status
2. Functional summary
3. Full list of files added/modified
4. Notes, constraints, and next steps

---

## Feature Log

### 2026-02-19 — Input Hardening for Stuck Ship Controls

**Status:** Implemented

**Summary**
- Added window-level key bindings as fallback input path so ship controls work even if component focus drifts.
- Removed first-press gating in `keyPressed` that could leave controls blocked after missed key release events.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/view/core/View.java`

**Behavior implemented in this phase**
- Key bindings (`WHEN_IN_FOCUSED_WINDOW`) now route press/release for movement/action keys.
- Renderer focus is requested on window focus regain.
- `keyPressed` now always forwards movement commands, preventing stale-key lockups.

**Notes**
- Existing key listener flow remains; key bindings act as robust fallback.

### 2026-02-19 — Input Focus Recovery + Predictive Planet Traces + Drag Camera

**Status:** Implemented

**Summary**
- Fixed player movement lock by restoring input focus to the renderer canvas and removing control-panel UI interference.
- Reworked planet traces from historical orbit paths to forward-predicted trajectory traces.
- Added camera click-drag panning and `Y` recenter-to-player control.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/view/core/View.java`
- `src/engine/view/core/Renderer.java`
- `src/engine/view/core/ControlPanel.java`

**Behavior implemented in this phase**
- Renderer now receives key listeners/focus directly.
- Removed traces button from UI panel and from frame layout.
- Planet traces are now calculated as future trajectory lines per gravity body each frame.
- Mouse press/drag/release pans camera in world space and disables auto-follow.
- Pressing `Y` recenters camera on the local player and restores auto-follow.

**Notes**
- `T` hotkey still toggles trace visibility at runtime.

### 2026-02-19 — Scaled Bodies + Toggleable Planet Traces + Player Mobility Fix

**Status:** Implemented

**Summary**
- Reduced solar-system body sizes and player sprite size to improve visual scale proportions.
- Added a toggleable planet-trace overlay controlled by a UI button (and `T` keyboard shortcut).
- Increased player thrust so the ship can maneuver again under the current gravity setup.

**Files Added**
- *(none)*

**Files Modified**
- `src/gameworld/SolarSystemWorldDefinitionProvider.java`
- `src/engine/model/bodies/impl/PlayerBody.java`
- `src/engine/view/core/ControlPanel.java`
- `src/engine/view/core/View.java`
- `src/engine/view/core/Renderer.java`

**Behavior implemented in this phase**
- New constants in the solar provider scale planet/sun/moon sizes (`BODY_SIZE_SCALE`) and player size (`PLAYER_SIZE_SCALE`).
- Planet traces now keep short history trails per gravity body and render as world-space lines.
- `ControlPanel` now includes a `Planet Traces` toggle button (enabled by default).
- Pressing `T` toggles traces at runtime.
- Player thrust updated from `500` to `1400` for improved control authority.

**Notes**
- Trace history is cleared automatically when traces are disabled.

### 2026-02-19 — Dynamic N-Body Gravity Planets (Solar Interaction)

**Status:** Implemented

**Summary**
- Converted `GRAVITY` bodies from static/no-physics entities to fully simulated bodies using the gravity physics engine.
- Added world-generation support for initial velocity on gravity bodies and seeded orbital tangential velocities in the solar-system provider.
- Updated level/controller/model flow so gravity bodies are created via a dedicated path and rendered through the dynamic snapshot pipeline.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/controller/ports/WorldManager.java`
- `src/engine/controller/impl/Controller.java`
- `src/engine/generators/AbstractLevelGenerator.java`
- `src/gamelevel/LevelBasic.java`
- `src/engine/model/bodies/ports/BodyFactory.java`
- `src/engine/model/impl/Model.java`
- `src/engine/world/core/AbstractWorldDefinitionProvider.java`
- `src/gameworld/SolarSystemWorldDefinitionProvider.java`

**Behavior implemented in this phase**
- `GRAVITY` bodies now use `DynamicBody` with active gravity integration in `BodyFactory`.
- New `WorldManager.addGravityBody(...)` API supports initial `speedX/speedY` and angular speed for gravity entities.
- `LevelBasic` now instantiates gravity definitions through the dedicated gravity path.
- Gravity entities are included in dynamic render snapshots (`Model.snapshotDynamicsRenderData`) so they visually move.
- Collision deduplication was generalized for moving gravity bodies to avoid duplicate pair processing.
- Solar-system provider now seeds near-circular initial orbital velocities (including Earth+Moon composition) from the same mass model (`0.08 * radius^3`).

**Notes**
- This is an n-body approximation tuned for gameplay stability; long-term orbital drift can still occur without a symplectic multi-body integrator upgrade.

### 2026-02-19 — Adaptive Zoom Step Smoothing

**Status:** Implemented

**Summary**
- Updated mouse-wheel zoom stepping to be relative to current zoom level.
- Prevents very large world-distance jumps when zoomed far out.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/view/core/Renderer.java`

**Behavior implemented in this phase**
- `zoomIn/zoomOut` now pass direction only (`+1/-1`).
- `adjustZoom(...)` computes adaptive delta as `targetZoom * ZOOM_STEP * direction`.
- Deep zoom-out now changes smoothly per wheel notch, matching closer to low-zoom feel.

**Notes**
- This changes zoom stepping behavior only; camera math and rendering pipeline are unchanged.

### 2026-02-19 — Spatial Grid Capacity Fix for Large Solar Bodies

**Status:** Implemented

**Summary**
- Fixed startup crash when inserting very large gravity bodies in the solar-system world.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/model/impl/Model.java`

**Behavior implemented in this phase**
- Increased `MAX_CELLS_PER_BODY` from `1512` to `4096`.
- This enlarges per-body spatial scratch buffers used during grid upsert/query for large-radius entities.
- Prevents `Query region requires ... cells, but buffer only has ...` during static gravity-body creation.

**Notes**
- This is an engine-level capacity adjustment; gameplay logic/rules are unchanged.

### 2026-02-19 — Solar World Crash Fix (Oversized Decorator)

**Status:** Implemented

**Summary**
- Fixed startup crash caused by creating an excessively large decorator image in the solar world provider.

**Files Added**
- *(none)*

**Files Modified**
- `src/gameworld/SolarSystemWorldDefinitionProvider.java`

**Behavior implemented in this phase**
- Replaced giant `stars_07` decorator (`~96000` size) with safe-sized space decorations.
- Added moderate-size decorative assets to preserve visual depth without exhausting image memory.

**Notes**
- Crash source was Java2D compatible image allocation for enormous sprite dimensions.

### 2026-02-19 — Solar System World Recreation

**Status:** Implemented

**Summary**
- Added a new world-definition provider that recreates a simplified solar-system layout with multiple gravity generators.
- Kept the current game rule (`SolidEarthCenterRule`) and switched the active world provider to the new solar system scenario.

**Files Added**
- `src/gameworld/SolarSystemWorldDefinitionProvider.java`

**Files Modified**
- `src/Main.java`

**Behavior implemented in this phase**
- World now spawns a central sun-like gravity body plus multiple planet-like gravity bodies at orbital positions.
- Added an Earth-like planet plus a nearby moon-like body and player spawn near that planet.
- Preserved current weapon/trail setup and background.
- Asteroid spawner behavior remains governed by current `SolidEarthCenterRule` logic in `Main` (disabled when that rule is active).

**Notes**
- This is a deterministic “solar system inspired” static layout (no orbital animation of gravity bodies themselves).

### 2026-02-19 — Renderer Stability Fix: Local Ship Visibility Pinning

**Status:** Implemented

**Summary**
- Fixed intermittent local ship disappearance and camera snap-back behavior.
- Renderer now forcibly includes local player ID in per-frame visible set as a safety pin.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/view/core/Renderer.java`

**Behavior implemented in this phase**
- After spatial culling query, local player ID is always added to `visibleIds` when available.
- Prevents transient missed-culling frames from dropping local player rendering/update.
- Camera tracking remains continuous because local player render data is refreshed every frame.

**Notes**
- This is a renderer-level robustness fix; core physics and spatial grid logic are unchanged.

### 2026-02-19 — Second Gravity Generator Body (Earth Scenario)

**Status:** Implemented

**Summary**
- Added a second `GRAVITY` body to the Earth-centered world setup.
- Existing `SolidEarthCenterRule` automatically applies to this new gravity source (solid-body rebound behavior already generic for GRAVITY collisions).

**Files Added**
- *(none)*

**Files Modified**
- `src/gameworld/EarthInCenterWorldDefinitionProvider.java`

**Behavior implemented in this phase**
- Added `moon_05` as an additional gravity generator body at an offset from center.
- World now has two gravity attractors in the active Earth simulation scenario.

**Notes**
- No rule logic changes were required because the collision rule already handles all `GRAVITY` body instances.

### 2026-02-19 — Orbit-Closure Trajectory + Extended Zoom-Out

**Status:** Implemented

**Summary**
- Replaced fixed trajectory-step plotting with orbit-closure based plotting (with safety max limit).
- Increased zoom-out range and hardened visible-entity query buffering for large zoomed views.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/view/core/Renderer.java`

**Behavior implemented in this phase**
- Trajectory now plots until orbit closure is detected (return near start with aligned velocity direction), after a minimum prediction horizon.
- Prediction still has a safety cap (`MAX_TRAJECTORY_STEPS`) to avoid infinite loops.
- Min zoom factor reduced from `0.4` to `0.15`.
- Spatial query index buffer now auto-expands at runtime when zoomed-out region requires more cells.

**Notes**
- Orbit closure detection is heuristic-based and tuned for stable visual guidance rather than exact orbital period solving.

### 2026-02-19 — Trajectory Tuning: More Steps + W-Hold Prediction Fix

**Status:** Implemented

**Summary**
- Increased trajectory prediction depth substantially.
- Fixed remaining prediction mismatch while holding thrust (`W`) by matching runtime integration order more closely.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/view/core/Renderer.java`

**Behavior implemented in this phase**
- Steps increased from `320` to `720`.
- Step duration adjusted to `0.05s` (long horizon with stable resolution).
- Predictor now mirrors engine-like update order per step:
	- Compute thrust/gravity acceleration from current predicted state
	- Integrate linear speed and position (avg speed)
	- Integrate angular speed/angle after linear step
- This removes the straight-line drift artifact observed during sustained thrust.

**Notes**
- Trajectory remains a predictive overlay and does not execute collision responses/rebounds.

### 2026-02-19 — Trajectory Predictor Upgrade (Far Ahead + No Straight-Line Drift)

**Status:** Implemented

**Summary**
- Extended the trajectory preview to project much farther ahead.
- Fixed prediction behavior while accelerating (`W`) by recomputing acceleration each step from heading/thrust and gravity sources.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/controller/impl/Controller.java`
- `src/engine/view/core/View.java`
- `src/engine/view/core/Renderer.java`

**Behavior implemented in this phase**
- Trajectory horizon increased to `320` steps at `0.06s` each.
- Prediction now integrates angular motion (`angle`, `angularSpeed`, `angularAcc`) per step.
- Thrust acceleration is recomputed from predicted heading each step.
- Gravity acceleration is recomputed from live gravity sources each step using same mass model (`coefficient * radius^3`) and min-distance softening.

**Notes**
- Prediction remains collision-agnostic (does not resolve rebounds/collisions in forecast path).

### 2026-02-19 — Rocket Trajectory Preview Line

**Status:** Implemented

**Summary**
- Added an ahead-of-ship trajectory line overlay to help pilot planning and simulator feel.
- The preview is rendered in world space and updates live with current player physics.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/controller/impl/Controller.java`
- `src/engine/view/core/View.java`
- `src/engine/view/core/Renderer.java`

**Behavior implemented in this phase**
- Renderer draws a forward trajectory polyline for the local player each frame.
- Prediction uses current player kinematics (`pos`, `speed`, `acc`) with fixed-step integration.
- Preview line scales nicely across zoom levels by adapting stroke width.
- Trajectory drawing stops when projected points leave world bounds.

**Notes**
- This first version is a lightweight kinematic forecast; it does not run full collision-aware simulation.

### 2026-02-19 — SolidEarthCenterRule: Disable Asteroid Spawner

**Status:** Implemented

**Summary**
- Disabled asteroid AI spawning when `SolidEarthCenterRule` is the active game rule.
- Keeps the Earth-centered simulator scenario free of newly spawned asteroids.

**Files Added**
- *(none)*

**Files Modified**
- `src/Main.java`

**Behavior implemented in this phase**
- `AIBasicSpawner` activation is now conditional.
- If active rules are `SolidEarthCenterRule`, asteroid spawner does not start.

**Notes**
- Existing non-asteroid systems (player, gravity body, controls, weapons) remain unchanged.

### 2026-02-19 — Zoom Smoothing + Ship-Centered Zoom Camera

**Status:** Implemented

**Summary**
- Made mouse-wheel zoom transitions smooth instead of abrupt.
- Updated camera-follow behavior to keep the local ship centered, especially during zoom transitions.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/view/core/Renderer.java`

**Behavior implemented in this phase**
- Zoom now interpolates toward a target zoom factor each frame.
- Wheel step adjusted to `0.08` and smoothed with factor `0.22`.
- Camera now targets ship-centered framing using current visible world size.
- During active zoom transitions, camera snaps to centered target to avoid off-center zoom drift.
- Outside zoom transitions, camera uses smooth follow interpolation.

**Notes**
- This is purely render/camera behavior; world physics and control inputs are unchanged.

### 2026-02-19 — Crash Fix: Scroll Zoom + AI Delay Guard

**Status:** Implemented

**Summary**
- Fixed renderer crash when zooming out via mouse wheel (`Query region requires ... cells`).
- Fixed AI spawner crash when sleep delay bound is non-positive.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/view/core/Renderer.java`
- `src/engine/generators/AbstractIAGenerator.java`

**Behavior implemented in this phase**
- Renderer visible-region query now uses viewport-sized bounds (instead of doubled range).
- Renderer scratch cell-index buffer increased from `1600` to `4096` for safer query headroom.
- AI loop now guards `maxCreationDelay <= 0` and falls back to `1 ms` sleep instead of calling `Random.nextInt(0)`.

**Notes**
- This patch addresses the two runtime exceptions observed in the latest run log.

### 2026-02-19 — Mouse Wheel Zoom (In/Out)

**Status:** Implemented

**Summary**
- Added camera zoom control via mouse wheel.
- Zoom now changes world-to-screen scale in the renderer while keeping camera clamping and visibility queries consistent.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/view/core/View.java`
- `src/engine/view/core/Renderer.java`

**Behavior implemented in this phase**
- Mouse wheel up: zoom in.
- Mouse wheel down: zoom out.
- Zoom range constrained to `0.4x` to `2.5x` with `0.1` step per wheel notch.
- Camera limits and viewport-culling bounds are recalculated using the active zoom factor.

**Notes**
- Zoom is rendering/camera-only; gameplay physics and world coordinates remain unchanged.

### 2026-02-19 — Flight Tuning: Lower Thrust + Stronger Gravity

**Status:** Implemented

**Summary**
- Reduced player propulsion to make orbital/attraction effects more evident.
- Increased gravity pull coefficient so gravity sources have stronger influence.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/model/bodies/impl/PlayerBody.java`
- `src/engine/model/impl/Model.java`

**Behavior implemented in this phase**
- Player max thrust changed from `800` to `500`.
- Object-gravity mass coefficient changed from `0.02` to `0.08`.

**Notes**
- These are first-pass balancing values and can be adjusted further after playtesting.

---

### 2026-02-19 — Gravity Mapped to Actual Bodies (Mass from Radius)

**Status:** Implemented

**Summary**
- Reworked gravity to use real `GRAVITY` bodies as attractors instead of a fixed center point.
- Added gravity-source provider contracts and wired `Model` as a live provider.
- `CentralGravityPhysicsEngine` now computes acceleration by summing contributions from each gravity source.
- Source mass is estimated from radius using: `mass = coefficient * radius^3`.

**Files Added**
- `src/engine/model/physics/ports/GravitySourceDTO.java`
- `src/engine/model/physics/ports/GravitySourceProvider.java`

**Files Modified**
- `src/engine/model/physics/implementations/CentralGravityPhysicsEngine.java`
- `src/engine/model/bodies/ports/BodyFactory.java`
- `src/engine/model/impl/Model.java`

**Behavior implemented in this phase**
- Gravity is now tied to object positions/sizes from current `GRAVITY` bodies.
- Dynamic bodies, players, and projectiles receive summed gravitational pull from all gravity sources.
- Gravity softening now uses configurable minimum interaction distance to avoid singular accelerations.

**Notes**
- Current tunables are in `Model`: `GRAVITY_MASS_COEFFICIENT`, `GRAVITY_MIN_DISTANCE`, `ENABLE_OBJECT_GRAVITY`.

---

### 2026-02-19 — Central Gravity Engine Runtime Integration

**Status:** Implemented

**Summary**
- Wired `CentralGravityPhysicsEngine` into body creation flow for moving bodies.
- Model now passes center-of-world gravity configuration into `BodyFactory`.
- Dynamic bodies, players, and projectiles now use central gravitational attraction at runtime.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/model/bodies/ports/BodyFactory.java`
- `src/engine/model/impl/Model.java`

**Behavior implemented in this phase**
- `BodyFactory` selects `CentralGravityPhysicsEngine` for `DYNAMIC`, `PLAYER`, and `PROJECTILE` when enabled.
- Gravity center is set to `(worldWidth/2, worldHeight/2)` from `Model`.
- Gravity strength and minimum radius are currently controlled by:
	- `ENABLE_CENTRAL_GRAVITY`
	- `CENTRAL_GRAVITY_PARAMETER`
	- `CENTRAL_GRAVITY_MIN_RADIUS`

**Notes**
- Static `GRAVITY`/`DECORATOR` bodies continue using `NullPhysicsEngine`.
- Existing behavior remains compatible with Earth-centered world and bounce-on-touch rule.

---

### 2026-02-19 — Earth-Adjacent Spawn + Bounce-on-Touch Earth

**Status:** Implemented

**Summary**
- Updated Earth collision handling so touching Earth rebounds the colliding body instead of killing it.
- Added a new action execution path in `Model` to resolve body-to-body rebound based on collision normal.
- Spawned player deterministically near Earth (east side offset) in Earth-centered world provider.

**Files Added**
- *(none)*

**Files Modified**
- `src/engine/actions/ActionType.java`
- `src/engine/model/impl/Model.java`
- `src/gamerules/SolidEarthCenterRule.java`
- `src/gameworld/EarthInCenterWorldDefinitionProvider.java`

**Behavior implemented in this phase**
- On collision where one body is `GRAVITY`, the non-gravity body receives `MOVE_REBOUND_FROM_BODY`.
- Rebound vector is computed from collision normal (target body position vs other body position) and velocity reflection.
- Position correction pushes the rebounding body outside overlap radius to reduce re-penetration.
- Earth remains indestructible and no longer causes instant death on touch.

**Notes**
- This preserves existing kill-on-collision behavior for non-gravity collisions in `SolidEarthCenterRule`.
- Gravity physics engine wiring is still pending (foundation class already exists).

---

### 2026-02-19 — Solid Earth Center Rule + Gravity Engine Foundation

**Status:** Implemented (initial phase)

**Summary**
- Added a new game rule where `GRAVITY` bodies (e.g., Earth) behave as **solid and indestructible** on collisions.
- Added a new physics engine implementation for **central gravitational attraction** toward a configurable center point.
- Updated the Earth-centered world provider to use valid assets and place Earth as a large center gravity body.
- Switched runtime defaults in `Main` to use the Earth-centered world and new solid-Earth game rule.

**Files Added**
- `src/gamerules/SolidEarthCenterRule.java`
- `src/engine/model/physics/implementations/CentralGravityPhysicsEngine.java`

**Files Modified**
- `src/gameworld/EarthInCenterWorldDefinitionProvider.java`
- `src/Main.java`
- `PROJECT_REFERENCE.md`

**Behavior implemented in this phase**
- Limit events: rebound behavior.
- Collision with `GRAVITY`: gravity body survives; non-gravity collider dies.
- Non-gravity collision (non-decorator): both bodies die.
- Emit and fire events: spawn body/projectile.
- Life-over events: die.

**Pending integration (next phase)**
- Wire `CentralGravityPhysicsEngine` into `BodyFactory` / runtime path so dynamic bodies actually use gravitational acceleration during simulation.

---

## Next Entries Template

### YYYY-MM-DD — <Feature Name>

**Status:** Planned | In Progress | Implemented

**Summary**
- <what was implemented>
- <gameplay/engine impact>

**Files Added**
- `<path>`

**Files Modified**
- `<path>`

**Notes**
- <technical notes>
- <follow-up tasks>
