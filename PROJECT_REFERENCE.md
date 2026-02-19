# MVCGameEngine — Project Reference Document

> **Purpose:** Comprehensive living reference for AI-assisted development sessions.  
> Use this file to understand the current state of the project, locate existing implementations before adding
> new code, and understand the relationships between every component.  
> **Last updated:** 2026-02-19  
> **Java version:** 21 · **Build tool:** Maven 3

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Tech Stack & Build](#2-tech-stack--build)
3. [Top-Level Structure](#3-top-level-structure)
4. [Architecture Overview — MVC](#4-architecture-overview--mvc)
5. [Entry Point — Main.java](#5-entry-point--mainjava)
6. [Layer: Controller](#6-layer-controller)
7. [Layer: Model](#7-layer-model)
8. [Layer: View](#8-layer-view)
9. [Entity System](#9-entity-system)
10. [Physics System](#10-physics-system)
11. [Spatial Grid](#11-spatial-grid)
12. [Event-Action Pipeline](#12-event-action-pipeline)
13. [Game Rules — ActionsGenerator](#13-game-rules--actionsgenerator)
14. [World Definition System](#14-world-definition-system)
15. [Level Generator](#15-level-generator)
16. [AI Generator](#16-ai-generator)
17. [Rendering Pipeline](#17-rendering-pipeline)
18. [HUD System](#18-hud-system)
19. [Asset System](#19-asset-system)
20. [Emitter System](#20-emitter-system)
21. [Threading Model](#21-threading-model)
22. [Object Pooling](#22-object-pooling)
23. [Profiling System](#23-profiling-system)
24. [Mappers Layer](#24-mappers-layer)
25. [DTO Catalog](#25-dto-catalog)
26. [Complete File Index](#26-complete-file-index)
27. [Design Patterns Reference](#27-design-patterns-reference)
28. [Extension Points](#28-extension-points)
29. [Key Constants & Configuration](#29-key-constants--configuration)
30. [Known Gaps & TODOs](#30-known-gaps--todos)

---

## 1. Project Overview

**MVCGameEngine** is a 2D top-down space-game engine built in pure Java. It demonstrates a clean
Model-View-Controller split, and an event-driven game-rules system where game behaviour (what happens
on collision, at world limits, etc.) is fully decoupled from the simulation core.

Key capabilities already present:
- Active-rendering loop (triple-buffered Canvas at target 60 fps).
- Multi-threaded physics: each dynamic body runs on a pooled thread (BodyBatchManager).
- Spatial partitioning (SpatialGrid) for O(n) broad-phase collision detection.
- Pluggable game-rules via the `ActionsGenerator` interface.
- Pluggable world-definition via `AbstractWorldDefinitionProvider`.
- Pluggable level-generator via `AbstractLevelGenerator`.
- Pluggable AI-spawner via `AbstractIAGenerator`.
- Particle / trail emitter system (BasicEmitter).
- Weapon and projectile system.
- Extensible HUD with bar/icon/text/grid items.
- Asset catalog with typed random-selection helpers.
- Built-in profiling (per-physics-stage timing).
- Object pooling for `PhysicsValuesMDTO` and `DynamicRenderDTO` to reduce GC pressure.

---

## 2. Tech Stack & Build

| Item | Value |
|---|---|
| Language | Java 21 |
| Build | Maven (`pom.xml`) |
| Source root | `src/` |
| Main class | `Main` (default package) |
| Graphics API | Java2D (AWT/Swing + BufferStrategy) |
| Threading | `java.lang.Thread`, `java.util.concurrent` |
| Dependencies | None (pure JDK) |
| Java2D hints | `sun.java2d.opengl=true`, `sun.java2d.d3d=false` (`sun.java2d.uiScale` is present but currently commented in `Main`) |

Build & run:
```
mvn compile
mvn exec:java -Dexec.mainClass="Main"
```

---

## 3. Top-Level Structure

```
src/
├── Main.java                          ← Entry point
├── engine/                            ← Engine library (generic, reusable)
│   ├── actions/                       ← ActionDTO, ActionType enum
│   ├── assets/                        ← AssetCatalog, AssetType, AssetIntensity
│   ├── controller/                    ← Controller, mappers, ports
│   ├── events/domain/                 ← DomainEvent hierarchy
│   ├── generators/                    ← Abstract level and AI generators
│   ├── model/                         ← Model, bodies, physics, emitters, ports
│   ├── utils/                         ← Helpers, images, pooling, profiling, spatial, threading
│   ├── view/                          ← View (JFrame), Renderer, HUDs, renderables
│   └── world/                         ← WorldDefinition, DefDTOs, factory
├── gameai/                            ← Concrete AI spawner
├── gamelevel/                         ← Concrete level generator
├── gamerules/                         ← Concrete game-rules implementations
├── gameworld/                         ← Concrete world definition provider & asset list
└── src/resources/images/              ← Sprite assets
```

The split **engine/** vs **game*** is intentional:
- `engine/` is the framework — do not put game-specific logic here.
- `gameai/`, `gamelevel/`, `gamerules/`, `gameworld/` are the current game implementation.

---

## 4. Architecture Overview — MVC

```
┌─────────────────────────────────────────────────────────┐
│                         Main                            │
│  Creates: Controller, Model, View, WorldDef, Level, AI  │
└──────────────────────┬──────────────────────────────────┘
                       │
         ┌─────────────▼──────────────┐
         │         Controller          │
         │  implements WorldManager    │
         │  implements DomainEventProc │
         └──────┬──────────┬──────────┘
                │          │
    ┌───────────▼──┐   ┌───▼──────────────┐
    │    Model      │   │       View        │
    │  (physics,    │   │  (Swing JFrame,   │
    │   entities,   │   │   Renderer,       │
    │   events)     │   │   HUDs, input)    │
    └───────────────┘   └──────────────────┘
```

### Communication Rules

| Who sends | To whom | How | Data type |
|---|---|---|---|
| View (Renderer) | Controller | `snapshotDynamicsRenderData(visibleIds)` pull every frame | `ArrayList<DynamicRenderDTO>` |
| Controller | Model | Method calls (commands) | primitives / DTOs |
| Model | Controller | `DomainEventProcessor` callbacks | `DomainEvent`, `ActionDTO` |
| Controller | View | Push via `updateStaticRenderables()` _and_ `notifyNewDynamic/Static/Dead` | `ArrayList<RenderDTO>` |
| View | Controller | Key events → `playerThrustOn`, etc. | void calls |

Model and View **never communicate directly**. The Controller is the only bridge.

---

## 5. Entry Point — Main.java

**File:** `src/Main.java`  
**Package:** (default)

### What Main does (in order)

1. **Configures Java2D** — sets OpenGL pipeline and disables D3D (`uiScale` line exists but is commented).
2. **Declares dimensions** — `viewDimension` (720×720) and `worldDimension` (40000×40000). View must be smaller than screen resolution or BufferStrategy fails.
3. **Creates `ProjectAssets`** — the game-specific asset registry.
4. **Selects a game rule** — currently `InLimitsGoToCenter`. Other available options are commented out.
5. **Creates `WorldDefinitionProvider`** — currently `RandomWorldDefinitionProvider`.
6. **Creates `Controller`** — injects `worldDimension`, `viewDimension`, `View`, `Model`, and `gameRules`.
7. **Calls `controller.activate()`** — boots View + Model in sequence.
8. **Calls `worldProv.provide()`** — builds the `WorldDefinition` data container.
9. **Creates `LevelBasic`** — loads assets, places decorators/statics/players into the game.
10. **Creates `AIBasicSpawner`** — starts background asteroid spawning.

### Tunable variables in Main

| Variable | Current value | Effect |
|---|---|---|
| `viewDimension` | 720×720 | Window / canvas size |
| `worldDimension` | 40000×40000 | Simulation space |
| `maxBodies` | 1000 | Hard cap on concurrent dynamic entities |
| `maxAsteroidCreationDelay` | 3 | Upper bound used by AI tick sleep (`nextInt(maxCreationDelay)`) |
| `gameRules` | `InLimitsGoToCenter` | Determines collision + limit behaviour |
| `worldProv` | `RandomWorldDefinitionProvider` | World layout |

---

## 6. Layer: Controller

**File:** `src/engine/controller/impl/Controller.java`  
**Implements:** `WorldManager`, `DomainEventProcessor`

### Role

Central coordinator. It is the **only** class that holds references to both Model and View. It owns the
game-rules engine reference (`ActionsGenerator`) and delegates rule decisions to it.

### Fields

| Field | Type | Purpose |
|---|---|---|
| `engineState` | `volatile EngineState` | STARTING → ALIVE → PAUSED / STOPPED |
| `gameRulesEngine` | `ActionsGenerator` | Pluggable rules implementation |
| `model` | `Model` | Physics + entity layer |
| `view` | `View` | Rendering + input layer |
| `viewDimension` | `DoubleVector` | Pixel size of the render canvas |
| `worldDimension` | `DoubleVector` | Logical size of the simulation world |
| `dynamicRenderableMapper` | `DynamicRenderableMapper` | Internal pooled mapper used for dynamic frame snapshots |

### Key Methods

| Method | Description |
|---|---|
| `activate()` | Validates all deps, calls `view.activate()`, `model.activate()`, sets state ALIVE |
| `enginePause()` | Sets state PAUSED |
| `engineStop()` | Sets state STOPPED |
| `snapshotDynamicsRenderData(visibleIds)` | Pulls only visible BodyData IDs from Model and maps to pooled `DynamicRenderDTO` list |
| `addPlayer(...)` | Creates entity in Model, registers renderable in View, returns entity ID |
| `addDynamicBody(...)` | Creates dynamic entity in Model, registers renderable in View |
| `addStaticBody(...)` | Creates static entity in Model, registers static renderable, pushes static snapshot |
| `addDecorator(...)` | Same as addStaticBody but for decorator category |
| `equipTrail(playerId, def)` | Converts `DefEmitterDTO` via `EmitterMapper`, calls `model.bodyEquipTrail` |
| `equipWeapon(playerId, def, offset)` | Converts via `EmitterMapper`, calls `model.playerEquipWeapon` |
| `loadAssets(catalog)` | Delegates to `view.loadAssets()` |
| `setLocalPlayer(id)` | Delegates to `view.setLocalPlayer()` |
| `playerThrustOn/Off/Reverse(id)` | Delegates input to Model |
| `playerRotateLeft/Right/Off(id)` | Delegates input to Model |
| `playerFire(id)` | Delegates fire command to Model |
| `playerSelectNextWeapon(id)` | Delegates weapon selection to Model |
| `queryEntitiesInRegion(...)` | Delegates spatial query to Model → Renderer uses this for viewport culling |
| `getPlayerRenderData(id)` | Gets PlayerDTO from Model, maps to `PlayerRenderDTO` via `PlayerRenderableMapper` |
| `getSpatialGridStatistics()` | Gets stats from Model, maps via `SpatialGridStatisticsMapper` |
| `getProfilingHUDValues(fps)` | Gets profiling stats from Model, maps via `ProfilingStatisticsMapper` |

### DomainEventProcessor interface (callbacks from Model)

| Callback | Action taken |
|---|---|
| `provideActions(events, actions)` | Delegates to `gameRulesEngine.provideActions()` |
| `notifyNewDynamic(id, assetId)` | Calls `view.addDynamicRenderable()` |
| `notifyNewStatic(id, assetId)` | Calls `view.addStaticRenderable()` + pushes static snapshot |
| `notifyStaticIsDead(id)` | Pushes updated static snapshot to View |
| `notifyDynamicIsDead(id)` | Calls `view.notifyDynamicIsDead()` |
| `notifyPlayerIsDead(id)` | Calls `view.notifyPlayerIsDead()` |

### Setter wiring (bidirectional injection)

- `setModel(model)` → stores model and calls `model.setDomainEventProcessor(this)`
- `setView(view)` → stores view and calls `view.setController(this)`

---

## 7. Layer: Model

**File:** `src/engine/model/impl/Model.java`  
**Implements:** `BodyEventProcessor`

### Role

The simulation brain. Owns all entities, the physics update pipeline, the spatial grid, and the
event-detection logic.

### Key Constants

| Constant | Value | Meaning |
|---|---|---|
| `DEFAULT_MAX_BODIES` | 5000 | Fallback cap if not set externally |
| `SPATIAL_GRID_CELL_SIZE` | 64 | World units per grid cell |
| `MAX_CELLS_PER_BODY` | 1512 | Max grid cells a single body can occupy |
| `DEFAULT_BATCH_SIZE` | 10 | Used only for **thread-pool-size calculation** (`ceil(maxBodies/10)+50`). Actual per-runner batch size is set in `BodyBatchManager` (see §21). |

### Entity Storage

| Map | Type | Contents |
|---|---|---|
| `dynamicBodies` | `ConcurrentHashMap<String, AbstractBody>` | DYNAMIC, PLAYER, PROJECTILE |
| `gravityBodies` | `ConcurrentHashMap<String, AbstractBody>` | GRAVITY (static, collidable) |
| `decorators` | `ConcurrentHashMap<String, AbstractBody>` | DECORATOR (visual only, no collision) |

### Body Creation Family

All creation ultimately goes through `addBody(BodyType, ...)`. Public convenience wrappers:

| Method | Body type created |
|---|---|
| `addPlayer(...)` | PLAYER |
| `addDynamic(...)` | DYNAMIC |
| `addProjectile(...)` | PROJECTILE |
| `addStatic(...)` | GRAVITY |
| `addDecorator(...)` | DECORATOR |

`addBody()` does the following:
1. Guards against max-body cap (DYNAMIC type only).
2. Acquires 3 `PhysicsValuesMDTO` from the pool (for the triple-buffer pattern — current, next, snapshot).
3. Calls `BodyFactory.create()` to build the correct concrete body type.
4. Calls `body.activate()`.
5. Hands body to `BodyBatchManager`.
6. Puts body into the appropriate map.
7. Calls `spatialGridUpsert(body)`.

### Equipment Methods

| Method | Action |
|---|---|
| `bodyEquipEmitter(bodyId, config)` | Creates `BasicEmitter`, calls `body.emitterEquip()` |
| `bodyEquipTrail(bodyId, config)` | Creates `BasicEmitter`, calls `dBody.trailEquip()` (DynamicBody only) |
| `playerEquipWeapon(playerId, config)` | Calls `bodyEquipEmitter`, then `pBody.addWeapon(emitterId)` |

### Player Command Methods

`playerFire`, `playerThrustOn`, `playerThrustOff`, `playerReverseThrust`,
`playerRotateLeftOn`, `playerRotateRightOn`, `playerRotateOff`, `playerSelectNextWeapon` —
all look up the `PlayerBody` in `dynamicBodies` and delegate.

### Snapshot / Data Query Methods

| Method | Returns | Used by |
|---|---|---|
| `snapshotDynamicsRenderData(inRegionIds)` | `ArrayList<BodyData>` filtered by visible IDs | Controller → Renderer |
| `getStaticsData()` | `ArrayList<BodyData>` from decorators + gravityBodies | Controller → View static push |
| `getPlayerData(id)` | `PlayerDTO` from `PlayerBody.getData()` | Controller → PlayerHUD |
| `getSpatialGridStatistics()` | `SpatialGridStatisticsDTO` | Controller → SpatialGridHUD |
| `getProfilingStatistics()` | `ProfilingStatisticsDTO` | Controller → mapper / diagnostics |

### Event Processing — BodyEventProcessor interface

Called by each body's physics thread. The pipeline:

```
processBodyEvents(body, newPhyValues, oldPhyValues)
  → guard: isProcessable? (model ALIVE, body ALIVE)
  → body.setState(HANDS_OFF)           // lock this body
  → detectEvents()                     // fill domainEvents list
      ├── checkLimitEvents()           // posX<0, posX>=worldWidth, etc.
      ├── checkCollisions()            // SpatialGrid query → circle-circle test
      ├── checkEmissionEvents()        // emitter cooldown check
      ├── checkFireEvents()            // player fire request
      └── checkLifeOverEvents()        // age >= maxLifeInSeconds
  → provideActions()                   // forward to DomainEventProcessor (Controller → GameRules)
      └── always adds MOVE action if no movement action present
  → executeActionList()                // apply each ActionDTO
  → body.setState(ALIVE)               // unlock body
```

### Collision Detection Detail

- `checkCollisions()` queries the SpatialGrid for candidates via `queryCollisionCandidates`.
- It uses a `HashSet<String>` (scratch buffer) to deduplicate IDs from multi-cell overlaps.
- Symmetric deduplication: `myId.compareTo(otherId) >= 0 → skip` (gravity bodies exempt since they don't self-check).
- Circle-circle intersection: `(dx² + dy²) ≤ (ra + rb)²` where radius = `size * 0.5 * 0.9` (10% margin).
- Projectile immunity: a projectile cannot collide with its shooter during the emitter-immunity window.

### executeAction — Action execution mapping

| ActionType | What happens |
|---|---|
| `MOVE` | `body.doMovement(newPhyValues)` + `spatialGridUpsert` |
| `MOVE_REBOUND_IN_EAST/WEST/NORTH/SOUTH` | `body.reboundIn*()` + `spatialGridUpsert` |
| `MOVE_TO_CENTER` | Builds a frozen DTO at world-center, calls `doMovement` |
| `NO_MOVE` | Clamps to previous position with zero speed/acc |
| `SPAWN_BODY` / `SPAWN_PROJECTILE` | `spawnBody()` — creates new entity at emitter offset, notifies Controller |
| `DIE` | `removeBody(body)` — sets body DEAD, removes from maps, notifies Controller |
| `EXPLODE_IN_FRAGMENTS` | **Not yet implemented** |
| `GO_INSIDE` | **Not yet implemented** |

### removeBody

Marks body as DEAD (`body.die()`), removes from maps, removes from spatial grid, notifies
`domainEventProcessor` with the appropriate dead-notification callback.

---

## 8. Layer: View

**File:** `src/engine/view/core/View.java`  
**Extends:** `JFrame`  
**Implements:** `KeyListener`, `WindowFocusListener`

### Role

Presentation layer. Holds the Renderer (Canvas), captures keyboard input, delegates all game logic
to the Controller. Does **not** own any simulation state.

### Key Fields

| Field | Type | Description |
|---|---|---|
| `renderer` | `Renderer` | The active-rendering canvas (owns the render loop) |
| `images` | `Images` | Image catalog — raw `BufferedImage` entries keyed by assetId |
| `background` | `BufferedImage` | Selected background sprite |
| `controller` | `Controller` | Reference for command dispatch and data pulls |
| `localPlayerId` | `String` | ID of the player being tracked by this client |
| `viewDimension` | `DoubleVector` | Canvas pixel size |
| `worldDimension` | `DoubleVector` | Logical world size (for coordinate mapping) |
| `viewportDimension` | `DoubleVector` | Optional viewport override (letterboxing) |
| `pressedKeys` | `HashSet<Integer>` | Tracks currently held keys (OS event dedup) |
| `fireKeyDown` | `AtomicBoolean` | Edge-trigger for SPACE — prevents key-repeat auto-fire |
| `wasWindowFocused` | `boolean` | Tracks focus state to clean up keys on Alt+Tab |

### Lifecycle

1. `new View()` — creates `Images`, `ControlPanel`, `Renderer`, builds JFrame layout.
2. `activate()` — validates dependencies, pushes view dimension to Renderer, calls `renderer.activate()`, packs frame.

### Key Mappings

| Physical key | Action |
|---|---|
| UP or W | `playerThrustOn` |
| DOWN or X | `playerReverseThrust` |
| LEFT or A | `playerRotateLeftOn` |
| RIGHT or D | `playerRotateRightOn` |
| SPACE | `playerFire` (edge-triggered, not repeat) |
| 1 | `playerSelectNextWeapon` |
| Release of UP/W/DOWN/X | `playerThrustOff` |
| Release of LEFT/A/RIGHT/D | `playerRotateOff` |

### Input Safety

- `syncInputState()` is called from the Renderer each frame. If the window lost focus but `pressedKeys` is non-empty, it emits release events for all stuck keys.
- `windowLostFocus` clears `pressedKeys` and fires release events immediately.

### Static renderable management

- `addStaticRenderable(id, assetId)` → `renderer.addStaticRenderable()`
- `updateStaticRenderables(list)` → `renderer.updateStaticRenderables()` — called after any static entity add/remove

---

## 9. Entity System

### AbstractBody — `engine/model/bodies/core/AbstractBody.java`

Base class for **every** simulation entity. All game objects are `AbstractBody` instances.

#### State Machine

```
STARTING → (activate()) → ALIVE ↔ HANDS_OFF (during event processing)
                              ↓  (die())
                            DEAD
```

- `HANDS_OFF` is set by the Model during `processBodyEvents` to prevent concurrent event re-entry.
- `die()` is idempotent. Multiple calls are safe.

#### Physics

- Owns a `PhysicsEngine` instance (injected; `BasicPhysicsEngine` for dynamic/player/projectile, `NullPhysicsEngine` for static/decorator).
- `getPhysicsValues()` — returns the current `PhysicsValuesMDTO` snapshot (atomic reference read).
- `doMovement(phyValues)` — commits a new physics state.
- `reboundInEast/West/North/South()` — delegates rebound physics to engine.
- **`onTick()`** — **abstract method**. Each concrete subclass implements the per-tick physics update. Called by `MultiBodyRunner` in the thread pool.

#### Lifecycle tracking (static volatile counters)

| Counter | Meaning |
|---|---|
| `aliveQuantity` | Currently active bodies |
| `createdQuantity` | All-time created bodies |
| `deadQuantity` | All-time dead bodies |

#### Emitters

- `emitterEquip(emitter)` — attaches a `BasicEmitter`, returns emitterId.
- `emitterRemove(id)` — detaches emitter.
- `emitterRequest(id)` — triggers emission manually.
- `emittersList()` — returns collection of active emitters.
- `emittersListEmpty()` — **WARNING: returns `true` when list is NOT empty** (inverted name — `!emitters.isEmpty()`). Callers must negate this.
- `emitterActiveList(dtSeconds)` — decrements cooldown on each emitter and returns the full emitters collection.
- `EMITTER_IMMUNITY_TIME = 0.5 s` — a newly-spawned projectile cannot collide with its shooter for 0.5 seconds after birth.

#### Scratch Buffers (zero-allocation design)

| Buffer | Type | Purpose |
|---|---|---|
| `scratchIdxs` | `int[]` | SpatialGrid cell indices |
| `scratchCandidateIds` | `Set<String>` (HashSet) | Collision candidates |
| `scratchSeenCandidateIds` | `HashSet<String>` | Collision dedup |
| `scratchEvents` | `ArrayList<DomainEvent>` | Event accumulation |
| `scratchActions` | `List<ActionDTO>` | Action accumulation |

All `getScratch*()` methods **clear before returning**, **except `getActionsQueue()`** — the external action queue is appended-to by other threads and must not be cleared on access.

#### External action queue

- `enqueueExternalAction(action)` — used when the Model executes an action targeting a body that is not the currently-processing body. The body processes this action on its next physics tick.
- `die()` — **synchronized**. Releases all 3 pooled `PhysicsValuesMDTO` objects back to the pool (`engine.getPhysicsValues().release()`, `engine.getNextPhyValues().release()`, `engine.getSnapshotDTO().release()`). The `BodyData` and `BodyRefDTO` scratch objects are pre-allocated fields, not created per call.

### Concrete Body Implementations

#### DynamicBody — `engine/model/bodies/impl/DynamicBody.java`

Represents all moving non-player entities: DYNAMIC (generic), PROJECTILE.

- Has a **trail emitter** slot separate from the main emitter map (`trailEquip`).
- Stores `shooterId` (only relevant for PROJECTILE type) for immunity checks.
- Runs physics via `BasicPhysicsEngine`.
- Additional fields: `maxThrustForce`, `maxAngularAcc`, `angularSpeed`, `spatialCellRadius`, `spatialCellSize`, `trailId`, `BodyProfiler profiler`.
- Additional methods: `accelerationAngularInc(double)`, `accelerationReset()`, `setAngularAcceleration(double)`, `setAngularSpeed(double)`, `setMaxThrustForce(double)`, `setMaxAngularAcceleration(double)`.
- **`onTick()` implementation:** `calcNewPhysicsValues()` → spatial grid upsert directly (no intermediate Model call) → trail emitter request if currently thrusting → `processBodyEvents()`.

#### PlayerBody — `engine/model/bodies/impl/PlayerBody.java`

Extends DynamicBody. Adds weapon management.

Key constants: `PLAYERS_EXCLUSIVE = true` (marker, unused in logic).
Default constructor values: `maxThrustForce=800`, `maxAngularAcc=1000`, `angularSpeed=30`.
Additional field: `score: int` (tracked per-player).

Key methods:
- `thrustMaxOn()`, `thrustOff()`, `reverseThrust()` — thrust commands.
- `rotateLeftOn()`, `rotateRightOn()`, `rotateOff()` — rotation commands (check `angularSpeed == 0` guard before setting).
- `registerFireRequest()` — marks intent to fire; evaluated in `mustFireNow()`.
- `addWeapon(emitterId)` — registers an emitter ID as a weapon slot.
- `selectNextWeapon()` — cycles to next weapon.
- `selectWeapon(int index)` — direct weapon selection by index.
- `mustFireNow(newPhyValues)` — true when fire requested AND active weapon ready AND reload done.
- `getProjectileConfig()` — returns `BodyToEmitDTO` for the current weapon's projectile.
- `getAmmoStatusPrimary/Secondary/Mines/Missiles()` — return ammo ratio (0.0–1.0); unlimited weapons return 1.0.
- `getData()` — returns `PlayerDTO` snapshot (damage, energy, shield, temperature, weapon ammo statuses, **score**).

#### StaticBody — `engine/model/bodies/impl/StaticBody.java`

Used for DECORATOR and GRAVITY bodies.
- DECORATOR: `spatialGrid = null` → no collision detection.
- GRAVITY: has `spatialGrid` reference → participates in collision detection.
- **Implements `Runnable`**: has its own `run()` loop with 30 ms sleep. `onTick()` only calls `processBodyEvents()` when `isLifeOver()` is true (static bodies do not move; life-over check is the only event they care about).

### BodyFactory — `engine/model/bodies/ports/BodyFactory.java`

Static factory. `create(eventProcessor, spatialGrid, dto1, dto2, dto3, bodyType, maxLifeTime, emitterId, profiler)`.

| BodyType | Body class | Physics engine |
|---|---|---|
| DYNAMIC | DynamicBody | BasicPhysicsEngine |
| PLAYER | PlayerBody | BasicPhysicsEngine |
| PROJECTILE | DynamicBody (type=PROJECTILE) | BasicPhysicsEngine |
| DECORATOR | StaticBody | NullPhysicsEngine |
| GRAVITY | StaticBody | NullPhysicsEngine |

### BodyState — `engine/model/bodies/ports/BodyState.java`

Enum: `STARTING`, `ALIVE`, `HANDS_OFF`, `DEAD`.

### BodyType — `engine/model/bodies/ports/BodyType.java`

Enum: `DECORATOR`, `GRAVITY`, `DYNAMIC`, `PLAYER`, `PROJECTILE`.

---

## 10. Physics System

### AbstractPhysicsEngine — `engine/model/physics/core/AbstractPhysicsEngine.java`

Abstract base. Maintains a **triple-buffer** of `PhysicsValuesMDTO`:
- `phyValues` — authoritative current state (`AtomicReference<PhysicsValuesMDTO>`; atomic CAS swap, not just `volatile`).
- `nextPhyValues` — scratch buffer returned from the last `setPhysicsValues()` swap.
- `snapshotDTO` — snapshot for rendering/event detection.

Exposes:
- `getPhysicsValues()` — atomic read of current state.
- `setPhysicsValues(dto)` — `this.nextPhyValues = this.phyValues.getAndSet(dto)` — atomically swaps current with new.
- `getNextPhyValues()` / `getSnapshotDTO()` — direct access to the other two buffers (used by `die()` to release all three back to the pool).
- `calcNewPhysicsValues()` — abstract, implemented by subclasses.
- `stopPushing()` — resets **both** `accX`/`accY` AND `thrust` to 0.
- `resetAcceleration()` — zeroes only `accX`/`accY`; **thrust is preserved**.
- `setAngularAcceleration(double)` — `final` concrete method.

### BasicPhysicsEngine — `engine/model/physics/implementations/BasicPhysicsEngine.java`

The standard implementation. Uses **Symplectic Euler with trapezoidal speed** (MRUA):

```
// Linear
v1 = v0 + a*dt
avg_v = (v0 + v1) / 2
x1 = x0 + avg_v * dt

// Angular
ω1 = ω0 + α*dt
θ1 = θ0 + ω0*dt + 0.5*α*dt²
```

Thrust is applied as directional acceleration: `accX = cos(angle) * thrust`, `accY = sin(angle) * thrust`.

All timing stages are profiled:
`PHYSICS_DT`, `PHYSICS_THRUST`, `PHYSICS_LINEAR`, `PHYSICS_ANGULAR`, `PHYSICS_DTO`.

Rebound implementations (`reboundInEast/West/North/South`):
- Flip the velocity component perpendicular to the boundary.
- Snap position to just-inside boundary (prevents ghosting).

### NullPhysicsEngine — `engine/model/physics/implementations/NullPhysicsEngine.java`

No-op. Freezes body at construction position. Used for DECORATOR and GRAVITY bodies.

### PhysicsValuesMDTO — `engine/model/physics/ports/PhysicsValuesMDTO.java`

Mutable DTO implementing `Serializable` and `PoolableObject`.
Fields: `timeStamp, posX, posY, angle, size, speedX, speedY, accX, accY, angularSpeed, angularAcc, thrust`

Pool integration: holds a `Pool<PhysicsValuesMDTO>` reference set at acquire time.
- `reset()` — zeroes all fields.
- `release()` — `this.pool.release(this)` — self-returns to the pool.
- Two constructors: full-field, and shorthand `(timeStamp, size, x, y, angle)`.

---

## 11. Spatial Grid

**File:** `src/engine/utils/spatial/core/SpatialGrid.java`

### Design

- Fixed-topology grid dividing the world into equal cells (`cellSize = 64` world units in current `Model`).
- Each cell is a `ConcurrentHashMap<String, Boolean>` (acts as a concurrent set of entity IDs).
- `cellsPerEntity: ConcurrentHashMap<String, Cells>` maps each entity to its current cell indices.

### Key operations

| Method | Description |
|---|---|
| `upsert(id, minX, maxX, minY, maxY, scratchIdxs)` | Computes new cells from AABB, removes from old cells, inserts in new cells. Optimised: `upsertSmall` (< 3 cells) vs `upsertLarge` (linear merge diff). |
| `remove(id)` | Removes entity from all its current cells |
| `queryCollisionCandidates(id, out)` | Returns all entity IDs sharing cells with the given entity (may include duplicates) |
| `queryRegion(minX, maxX, minY, maxY, scratchIdxs, out)` | Returns all entity IDs in the AABB (used for viewport culling) |
| `getStatistics()` | Scans all cells, returns `SpatialGridStatisticsDTO` (perf monitoring only) |

### Cell index formula

`cellIdx(cx, cy) = cy * cellsX + cx`

AABB → cell ranges: `floor(pos - radius) / cellSize` to `ceil(pos + radius) / cellSize`.

---

## 12. Event-Action Pipeline

This is the core game-mechanics loop. For each physics tick of each dynamic/player body:

### Events (detected by Model)

All defined in `engine/events/domain/ports/`:

| Event class | Supertype | Triggered when |
|---|---|---|
| `LimitEvent` | `DomainEvent` | Body position exceeds any world boundary |
| `CollisionEvent` | `DomainEvent` | Two collidable bodies' circles intersect |
| `EmitEvent` | `DomainEvent` | An emitter's cooldown has elapsed (`EMIT_REQUESTED`) |
| `EmitEvent` | `DomainEvent` | Player fires (`FIRE_REQUESTED`) |
| `LifeOver` | `DomainEvent` | Body's `maxLifeInSeconds` age exceeded |

All events carry a `primaryBodyRef: BodyRefDTO` and optionally a `secondaryBodyRef` (for collisions).

### DomainEventType enum

`REACHED_EAST_LIMIT`, `REACHED_WEST_LIMIT`, `REACHED_NORTH_LIMIT`, `REACHED_SOUTH_LIMIT`,
`COLLISION`, `EMIT_REQUESTED`, `FIRE_REQUESTED`, `LIFE_OVER`

### Actions (decided by ActionsGenerator / GameRulesEngine)

All defined in `engine/actions/ActionType.java`:

| Action | What Model does |
|---|---|
| `MOVE` | Normal movement via physics engine |
| `NO_MOVE` | Clamp to previous position |
| `MOVE_REBOUND_IN_EAST/WEST/NORTH/SOUTH` | Flip velocity, reposition |
| `MOVE_TO_CENTER` | Teleport to world center with zero acc |
| `SPAWN_BODY` | Spawn new entity at emitter offset (trail/particle) |
| `SPAWN_PROJECTILE` | Spawn projectile entity at emitter offset |
| `DIE` | Remove body from simulation |
| `EXPLODE_IN_FRAGMENTS` | *(Not implemented yet)* |
| `GO_INSIDE` | *(Not implemented yet)* |

### ActionDTO — `engine/actions/ActionDTO.java`

`bodyId (String)`, `bodyType (BodyType)`, `type (ActionType)`, `relatedEvent (DomainEvent)` — all final.

---

## 13. Game Rules — ActionsGenerator

**Interface:** `engine/controller/ports/ActionsGenerator.java`  
`void provideActions(List<DomainEvent> events, List<ActionDTO> actions)`

Six concrete implementations exist in `gamerules/`:

### LimitRebound

Rebounce off all four walls. No collision response. Emitters spawn bodies/projectiles. LifeOver kills.

### ReboundAndCollision

Same as LimitRebound + resolves collisions: both colliding bodies DIE (respects projectile immunity).

### DeadInLimits

Bodies that reach any limit DIE immediately. No rebound.

### DeadInLimitsPlayerImmunity

Same as DeadInLimits but PLAYER bodies are immune to limit death.

### InLimitsGoToCenter

Bodies that exceed any limit are teleported to world center (`MOVE_TO_CENTER`). No rebound. **Currently active in Main.**

### ReboundCollisionPlayerImmunity

Rebound at limits + collision death. Players have immunity at limits (do not die at limits).

### How to add a new rule set

1. Create `gamerules/MyRules.java` implementing `ActionsGenerator`.
2. Implement `provideActions(events, actions)` using the `switch(event)` pattern.
3. In `Main.java`, instantiate and pass to the `Controller` constructor.

---

## 14. World Definition System

### WorldDefinitionProvider interface

`engine/world/ports/WorldDefinitionProvider.java`  
Single method: `WorldDefinition provide()`

### AbstractWorldDefinitionProvider

`engine/world/core/AbstractWorldDefinitionProvider.java`

Abstract base for all concrete world providers. Maintains lists:

| Field | Type | Contents |
|---|---|---|
| `decorators` | `ArrayList<DefItem>` | Background/cosmetic entities |
| `gravityBodies` | `ArrayList<DefItem>` | Static collidable bodies |
| `asteroids` | `ArrayList<DefItem>` | Dynamic asteroid definitions |
| `spaceships` | `ArrayList<DefItem>` | Player spaceship definitions |
| `trailEmitters` | `ArrayList<DefEmitterDTO>` | Trail particle systems |
| `weapons` | `ArrayList<DefEmitterDTO>` | Weapon emitter definitions |
| `gameAssets` | `AssetCatalog` | All registered sprites |

Template method: `provide()` = `reset()` → `define()` (abstract) → `validateDefinition()` → build `WorldDefinition`.

#### Protected helper methods (selection)

| Category | Methods |
|---|---|
| Asteroid adders | `addAsteroidRandomAsset`, `addAsteroidAnywhereRandomAsset`, `addAsteroidPrototypeRandomAsset`, `addAsteroidPrototypeAnywhereRandomAsset` |
| Decorator adders | `addDecorator`, `addDecoratorRandomAsset`, `addDecoratorAnywhereRandomAsset`, `addDecoratorPrototypeRandomAsset`, `addDecoratorPrototypeAnywhereRandomAsset` |
| Gravity body adders | `addGravityBody`, `addGravityBodyRandomAsset`, `addGravityBodyAnywhereRandomAsset` |
| Spaceship adders | `addSpaceship`, `addSpaceshipRandomAsset` |
| Trail emitters | `addTrailEmitterCosmetic(assetId, emissionRate, type, size)` |
| Weapon presets | `addWeaponPresetBulletRandomAsset`, `addWeaponPresetBurstRandomAsset`, `addWeaponPresetMineLauncherRandomAsset`, `addWeaponPresetMissileLauncherRandomAsset` |
| Background | `setBackgroundStatic(assetId)` |

### WorldDefinition — `engine/world/ports/WorldDefinition.java`

Immutable data container built by `provide()`:

```java
worldWidth, worldHeight
AssetCatalog gameAssets
DefBackgroundDTO background
ArrayList<DefItem> spaceDecorators
ArrayList<DefItem> gravityBodies
ArrayList<DefItem> asteroids
ArrayList<DefItem> spaceships
ArrayList<DefEmitterDTO> trailEmitters
ArrayList<DefEmitterDTO> weapons
```

### DefItem hierarchy

- `DefItem` (interface / marker) — supertype for all entity definitions.
- `DefItemDTO` — concrete definition with fixed values.
- `DefItemPrototypeDTO` — range-based prototype (min/max for size, speed, angle, etc.) resolved to concrete values by `DefItemMaterializer`.

### DefItemMaterializer — `engine/generators/DefItemMaterializer.java`

`defItemToDTO(DefItem item)` — if it's a `DefItemPrototypeDTO`, randomises all range fields using `Math.random()` / `Random.nextDouble()`. Returns a concrete `DefItemDTO`.

### WeaponDefFactory — `engine/world/core/WeaponDefFactory.java`

Static factory for building `DefEmitterDTO` weapon definitions from preset named configurations
(`bullet`, `burst`, `mine_launcher`, `missile_launcher`).

### WorldAssetsRegister — `engine/world/core/WorldAssetsRegister.java`

Bridges `ProjectAssets` → `AssetCatalog`. Registers asset IDs on demand so that
`AbstractWorldDefinitionProvider` can reference asset IDs without worrying about catalog population.

### Concrete world providers

#### RandomWorldDefinitionProvider — `gameworld/RandomWorldDefinitionProvider.java`

Implements `define()` with a mixed-composition world:
- Static background: `back_12`
- Decorators: STARS, GALAXY, HALO, cosmic_portal, stardust, stars_07
- Gravity bodies: planet_04, sun_02, moon_05, lab_01, black_hole_01/02 + random planets, moons, mines
- Dynamic asteroids: 6 random asteroids
- 1 player spaceship (SPACESHIP type, ~50–55 size, near 19000,19500)
- Trail: cosmetic stars_06
- Weapons: Bullet, Burst, Mine launcher, Missile launcher

#### EarthInCenterWorldDefinitionProvider — `gameworld/EarthInCenterWorldDefinitionProvider.java`

Alternative provider — planet placed at world center.

---

## 15. Level Generator

**Base class:** `engine/generators/AbstractLevelGenerator.java`

Receives a `WorldManager` (Controller) and a `WorldDefinition`. Constructor immediately calls `createWorld()`.

### Template methods (subclasses must implement)

```java
protected abstract void createDecorators();
protected abstract void createStatics();
protected abstract void createPlayers();
protected abstract void createDynamics();
```

### Helper methods available to subclasses

| Method | Action |
|---|---|
| `addDecoratorIntoTheGame(dto)` | Calls `worldManager.addDecorator(...)` |
| `addDynamicIntoTheGame(dto)` | Calls `worldManager.addDynamicBody(...)` |
| `addStaticIntoTheGame(dto)` | Calls `worldManager.addStaticBody(...)` |
| `addLocalPlayerIntoTheGame(dto, weapons, trails)` | Adds player, equips emitters + weapons, marks as local player |
| `equipEmitters(id, defs)` | Loops and calls `worldManager.equipTrail()` |
| `equipWeapons(id, defs)` | Loops and calls `worldManager.equipWeapon()` |
| `defItemToDTO(item)` | Materialises a `DefItem` to a concrete `DefItemDTO` |
| `randomDoubleBetween(min, max)` | Utility random double |

### Create-world pipeline (fixed order)

1. `worldManager.loadAssets(worldDef.gameAssets)` — load sprites into View
2. `createDecorators()` → `createStatics()` → `createPlayers()` → `createDynamics()`

### LevelBasic — `gamelevel/LevelBasic.java`

Current concrete level:
- `createDecorators()` — iterates `worldDef.spaceDecorators`, materialises each, adds as decorator.
- `createStatics()` — iterates `worldDef.gravityBodies`, materialises, adds as static.
- `createPlayers()` — iterates `worldDef.spaceships`, materialises, calls `addLocalPlayerIntoTheGame` with `worldDef.weapons` and `worldDef.trailEmitters`. **Each ship in the list creates one player.**
- `createDynamics()` — empty (no initial dynamic bodies; handled by AI).

---

## 16. AI Generator

**Base class:** `engine/generators/AbstractIAGenerator.java`  
**Implements:** `Runnable`

### Contract

```java
protected abstract void onTick();           // called each loop iteration
protected abstract void onActivate();       // called once before loop starts
protected abstract String getThreadName();  // thread name for debugging
```

### Loop behaviour

- `activate()` starts a `MIN_PRIORITY` thread.
- Loop runs while `worldManager.getEngineState() != STOPPED`.
- If state == `ALIVE`, calls `onTick()`.
- Then `Thread.sleep(random 0..maxCreationDelay ms)` (`nextInt(maxCreationDelay)`).

### Protected helpers available

- `addDynamicIntoTheGame(dto)` — adds a non-player body.
- `defItemToDTO(item)` — materialises a DefItem.
- Field access: `worldDefinition` (the `WorldDefinition` instance).

### AIBasicSpawner — `gameai/AIBasicSpawner.java`

`onTick()` picks a random definition from `worldDefinition.asteroids`, materialises it, and calls `addDynamicIntoTheGame()`.

---

## 17. Rendering Pipeline

### Renderer — `engine/view/core/Renderer.java`

**Extends:** `Canvas`  
**Implements:** `Runnable`

Active-rendering loop. Runs on a dedicated thread at `NORM_PRIORITY + 2`.

#### Rendering pipeline (per frame)

1. **Buffer acquire:** `BufferStrategy.getDrawGraphics()` (triple-buffered).
2. **Tiled background:** `drawTiledBackground()` — uses `Math.floorMod` for stable parallax scrolling anchored to camera position.
3. **Static renderables:** Iterates the copy-on-write static map, draws each at world position.
4. **Dynamic renderables:** Uses `queryEntitiesInRegion()` for viewport culling → only visible entities are drawn.
5. **HUDs:** Draws each registered HUD overlay.
6. Calls `view.syncInputState()` once per frame.

#### Camera system

- Tracks `localPlayerId` position.
- 30/70 viewport margins — player is kept within 30% to 70% of the canvas.
- Camera clamped to world bounds (no black space outside world).

#### DynamicRenderDTO pooling

Pooling is currently done in `Controller.snapshotDynamicsRenderData(...)` via its internal `DynamicRenderableMapper`.
Renderer consumes already pooled `DynamicRenderDTO` objects and releases previous frame data on update.

### View → Renderer interface

| View method | Renderer method called |
|---|---|
| `addDynamicRenderable(id, assetId)` | `renderer.addDynamicRenderable(id, assetId)` |
| `addStaticRenderable(id, assetId)` | `renderer.addStaticRenderable(id, assetId)` |
| `notifyDynamicIsDead(id)` | `renderer.notifyDynamicIsDead(id)` |
| `updateStaticRenderables(list)` | `renderer.updateStaticRenderables(list)` |

---

## 18. HUD System

**Base classes:** `engine/view/hud/core/`

### DataHUD

Column-based overlay. Renders a vertical stack of `Item` objects.

Available `Item` subtypes:
| Item class | Visual |
|---|---|
| `TitleItem` | Bold label header |
| `TextItem` | Plain text with optional right-aligned value |
| `BarItem` | Horizontal fill bar with label + percentage |
| `IconItem` | Sprite icon + label |
| `SeparatorItem` | Horizontal line separator |
| `SkipItem` | Vertical empty space |

### GridHUD

Draws the spatial grid cells over the world viewport. Used for debugging collision detection.

Methods: `drawGridLines(g, ...)`, `drawNonEmptyCells(g, ...)`, `draw(g, ...)` convenience overload.

### Concrete HUD implementations

| Class | Contents | Location |
|---|---|---|
| `PlayerHUD` | Energy bar, shield bar, damage bar, temperature bar, weapons + ammo icons | `engine/view/hud/impl/` |
| `SystemHUD` | FPS, render time, entities alive/created/dead, image cache stats | `engine/view/hud/impl/` |
| `SpatialGridHUD` | Grid cell stats (occupied, max per cell, collision pairs) | `engine/view/hud/impl/` |
| `RenderHUD` | Render-loop timings (background/translate/static/dynamic/HUD/show/update/frame) | `engine/view/hud/impl/` |

---

## 19. Asset System

### AssetCatalog — `engine/assets/core/AssetCatalog.java`

`HashMap<String, AssetInfoDTO>` keyed by `assetId` (caller-chosen string key).

| Method | Description |
|---|---|
| `register(id, fileName, type, intensity)` | Adds asset |
| `register(id, fileName, type)` | Adds with `AssetIntensity.MEDIUM` default |
| `get(id)` | Returns `AssetInfoDTO` |
| `exists(id)` | Presence check |
| `getAssetIds()` | All registered IDs |
| `getPath()` | Base path prefix for file resolution |
| `randomId(AssetType)` | Returns random ID among all assets of that type |
| `randomId(AssetType, AssetIntensity)` | Returns random ID filtered by type + intensity; falls back to type-only |
| `reset()` | Clears map |

### AssetType — `engine/assets/ports/AssetType.java`

26 types: `BLACK_HOLE, MOON, PLANET, SUN, STATIC, COSMIC_PORTAL, CRACKS, HALO, LIGHT,
SHOT_HOLE, ASTEROID, LAB, METEOR, BUBBLES, GALAXY, RAINBOW, STARDUST, STARS,
ROCKET, SPACESHIP, UI_SIGN, BULLET, MINE, MISSILE, BACKGROUND, TRAIL`

### AssetIntensity — `engine/assets/ports/AssetIntensity.java`

`HIGH`, `MEDIUM`, `LOW`

### ProjectAssets — `gameworld/ProjectAssets.java`

Game-specific class. Registers all image files into a given `AssetCatalog`.
Covers: asteroids, background images, black holes, bullets, cosmic_portal, craters, galaxies,
halos, labs, meteors, mines, missiles, moons, planets, rainbows, rockets, stardust, stars,
spaceships (spaceship_01–15, skipping 07), suns, signs.

Asset path base: `src/resources/images/`

### Images — `engine/utils/images/Images.java`

Simple map of `assetId → ImageDTO`. `add(id, path)` loads from disk. `getImage(id)` returns `ImageDTO`.

### ImageCache — `engine/utils/images/ImageCache.java`

`ConcurrentMap<ImageCacheKeyMDTO, BufferedImage>` keyed by `(angle, assetId, size)`.
- On miss: loads from `Images`, rotates via `g2.rotate()`, creates hardware-accelerated compatible image via `GraphicsConfiguration.createCompatibleImage()`.
- Tracks `hits` / `fails` counters for `SystemHUD`.
- Falls back to a solid red oval if asset is missing.

---

## 20. Emitter System

### BasicEmitter — `engine/model/emitter/impl/BasicEmitter.java`

Particle/trail/weapon emitter.

Key behaviour:
- **State machine:** `EmitterState` enum — `READY` or `RELOADING`.
- Thread-safe fields: `bodiesRemaining: AtomicInteger`, `lastRequest: AtomicLong`, `lastHandledRequest: AtomicLong`, `bodiesRemainingInBursts: AtomicInteger`.
- `mustEmitNow(dtSeconds)` — complex emission logic:
  1. Checks `state == RELOADING` cooldown first.
  2. Checks ammo exhaustion (`bodiesRemaining == 0`).
  3. Handles **burst mode**: when `burstSize > 1 && burstEmissionRate > 0`, fires `burstSize` shots using `burstEmissionRate` cooldown between them, then full `emissionRate` cooldown.
  4. For non-burst: checks `hasRequest()` and normal `emissionRate` cooldown.
- `registerRequest()` — sets `lastRequest = System.nanoTime()` (not a boolean flag).
- `hasRequest()` — returns `lastRequest > lastHandledRequest` (atomic comparison).
- `getBodyToEmitConfig()` — returns `BodyToEmitDTO` with the body-to-spawn configuration.
- `getBodiesRemaining()` — returns remaining ammo count as `AtomicInteger`.

### EmitterState — `engine/model/emitter/ports/EmitterState.java`

Enum: `READY`, `RELOADING`

### Emitter interface — `engine/model/emitter/ports/Emitter.java`

Defines: `decCooldown(dt)`, `getBodyToEmitConfig()`, `getConfig()`, `getEmitterId()`, `mustEmitNow(dt)`, `registerRequest()`.

### EmitterConfigDto — `engine/model/emitter/ports/EmitterConfigDto.java`

All parameters for an emitter:
- Body params: `assetId, sizeMin/Max, forwardOffset, sideOffset, speedMin/Max, thrustMin/Max, angularSpeedMin/Max, angularAccelMin/Max, maxLifetime, mass, randomAngle, randomSize, addEmitterSpeed`
- Emitter params: `emissionRate, maxBodiesEmitted, burstSize, reloadTime`

### BodyToEmitDTO — `engine/events/domain/ports/BodyToEmitDTO.java`

Resolved spawn config: `type, assetId, size, forwardOffset, sideOffset, speed, acceleration, angularSpeed, maxLifeTime, randomAngle, randomSize, addEmitterSpeed`

### EmitterMapper — `engine/controller/mappers/EmitterMapper.java`

Static `fromWorldDef(DefEmitterDTO) → EmitterConfigDto`. Converts world-definition level DTOs into model-level config.

---

## 21. Threading Model

### Body threads — BodyBatchManager + MultiBodyRunner + ThreadPoolManager

**Files:** `engine/model/impl/BodyBatchManager.java`, `engine/model/impl/MultiBodyRunner.java`, `engine/utils/threading/ThreadPoolManager.java`

#### ThreadPoolManager

- Wraps a `ThreadPoolExecutor` with a fixed-size pool.
- `DEFAULT_POOL_SIZE = 250` threads; `SHUTDOWN_TIMEOUT_SECONDS = 30`.
- Threads: priority `NORM_PRIORITY - 1`, **non-daemon**, named `"PoolThread-<nanoTime>"`.
- Methods: `submit(Runnable)`, `prestartAllCoreThreads()`, `shutdown()` (graceful then `shutdownNow()`).

#### MultiBodyRunner

- `Runnable` — the actual unit of thread-pool work.
- Holds `CopyOnWriteArrayList<AbstractBody> bodies` (up to `maxBodiesPerRunner`).
- Loop: for each ALIVE body → `body.onTick()`; `removeIf(DEAD)` after each full pass; then `Thread.sleep(SLEEP_TIME_MS = 15)`.
- Self-deregisters from `BodyBatchManager` when terminated.

#### BodyBatchManager

- `DEFAULT_BATCH_SIZE = 100` bodies per runner (used for DYNAMIC/PROJECTILE/GRAVITY/DECORATOR bodies).
- `PLAYER_BATCH_SIZE = 1` — each PLAYER body gets its own dedicated `MultiBodyRunner` (exclusive thread).
- Thread pool size = `ceil(maxBodies / Model.DEFAULT_BATCH_SIZE) + 50`.
- `submitBatched(body, batchSize)` — reuses an existing compatible runner (same batchSize, not full, not terminated); creates a new one if none available.
- Holds `CopyOnWriteArrayList<MultiBodyRunner> activeRunners`.
- `shutdown()` terminates all runners and calls `threadPoolManager.shutdown()`.

#### Physics tick entry point

```
ThreadPool thread → MultiBodyRunner.run()
  └→ body.onTick()       // abstract in AbstractBody, concrete in each subclass
       DynamicBody.onTick():
         calcNewPhysicsValues()
         spatialGrid.upsert(...)   // direct, not via Model
         trailEmitter.registerRequest() if thrusting
         processBodyEvents(...)
```

### Renderer thread

- Priority `NORM + 2` to ensure smooth 60 fps.
- Pulls dynamic snapshot via `view.snapshotDynamicsRenderData(visibleIds)` every frame.
- Calls `view.syncInputState()` every frame.

### AI Generator thread

- Priority `MIN_PRIORITY`.
- Sleeps random interval between ticks.

### Thread Safety Guarantees

| Concern | Mechanism |
|---|---|
| Entity maps | `ConcurrentHashMap` for all body maps |
| Physics state | `AtomicReference` in `AbstractPhysicsEngine`; triple-buffer prevents dirty reads |
| Body state transitions | `volatile BodyState` in `AbstractBody` |
| Engine state | `volatile EngineState` in `Controller` |
| Key state | `AtomicBoolean fireKeyDown`; `HashSet<Integer>` pressedKeys (EDT-only) |
| Static snapshot push | Occasional (low contention); copy-on-write pattern in Renderer |
| Emitter fields | `AtomicInteger`/`AtomicLong` for `bodiesRemaining`, `lastRequest`, etc. |

---

## 22. Object Pooling

### Pool — `engine/utils/pooling/Pool.java`

Generic pool: `Pool<T extends PoolableObject>`. Backed by `ConcurrentLinkedDeque<T>`.

| Method | Description |
|---|---|
| `preallocate(n)` | Creates `n` instances up front; injects pool reference via `setPool(this)` |
| `acquire()` | Polls from deque; creates new if empty; calls `setPool(this)` on returned object |
| `release(T)` | Returns object to deque (callers should call `reset()` themselves, or let the object do it) |
| `clear()` | Empties the pool |
| `getPoolSize()` | Current pool size |

### PoolableObject — `engine/utils/pooling/PoolableObject.java`

Interface: `reset()`, `setPool(Pool)`, `release()`.

Objects self-release via `this.pool.release(this)`.

**Used for:**
1. `PhysicsValuesMDTO` — 3 per body (`physicsValuesPool` in Model). Pool pre-built at `4 * maxBodies` objects.
2. `DynamicRenderDTO` — per-frame pooled mapping in Controller via `DynamicRenderableMapper`.

### AbstractPooledMapper — `engine/controller/mappers/AbstractPooledMapper.java`

Generic base for pool-backed mappers. `map(source)` acquires from pool, calls `mapToDTO(source, target)`, returns populated object.

### DynamicRenderableMapper extends AbstractPooledMapper — `engine/controller/mappers/DynamicRenderableMapper.java`

- `fromBodyDTO(BodyData)` — non-pooled allocation path.
- `fromBodyDTOPooled(list)` — pooled path, maps in-place via `target.updateFrom(...)`.

---

## 23. Profiling System

### AbstractProfiler — `engine/utils/profiling/core/AbstractProfiler.java`

Framework for metric collection. Subclasses configure metrics and provide a `customReport()` hook.

Methods:
- `startInterval()` → `System.nanoTime()`.
- `stopInterval(key, startTime)` → computes elapsed, updates metric.
- `maybeReport()` / `reportIfDue()` → periodic reporting.
- `captureSnapshot()` → snapshot all metrics.
- `resetPeriodMetrics()` → clear per-period accumulators.

### BodyProfiler — `engine/model/bodies/impl/BodyProfiler.java`

Concrete profiler for the physics loop. Tracks:
`PHYSICS_DT, PHYSICS_THRUST, PHYSICS_LINEAR, PHYSICS_ANGULAR, PHYSICS_DTO, SPATIAL_GRID,
EVENTS_DETECT, EVENTS_DECIDE, EVENTS_EXECUTE`

### RendererProfiler — `engine/view/core/RendererProfiler.java`

Render-loop profiler that tracks frame/update/draw phase timings and computed FPS (`lastFps`).

### ProfilingStatisticsMapper — `engine/controller/mappers/ProfilingStatisticsMapper.java`

`fromProfilingStatistics(stats, fps) → Object[4]`:
- [0] Physics calc ms/frame (PHYSICS_DT + THRUST + LINEAR + ANGULAR summed / fps)
- [1] PHYSICS_DTO ms/frame
- [2] Events total ms/frame (DETECT + DECIDE + EXECUTE)
- [3] SPATIAL_GRID ms/frame

Returns "N/A" strings for null/empty/zero-fps inputs.

---

## 24. Mappers Layer

All mappers are in `engine/controller/mappers/`. They translate between domain DTOs (Model) and render DTOs (View).

| Mapper | From → To |
|---|---|
| `DynamicRenderableMapper` | `BodyData` → `DynamicRenderDTO` (non-pooled and pooled variants) |
| `RenderableMapper` | `BodyData` → `RenderDTO` (generic; used for statics) |
| `PlayerRenderableMapper` | `PlayerDTO` → `PlayerRenderDTO` |
| `EmitterMapper` | `DefEmitterDTO` → `EmitterConfigDto` |
| `SpatialGridStatisticsMapper` | `SpatialGridStatisticsDTO` → `SpatialGridStatisticsRenderDTO` |
| `ProfilingStatisticsMapper` | `ProfilingStatisticsDTO` + fps → `Object[4]` |
| `PhysicsValuesDTOMapper` | **Empty file — unused** |

---

## 25. DTO Catalog

| DTO | Package | Fields |
|---|---|---|
| `PhysicsValuesMDTO` | `engine.model.physics.ports` | timeStamp, posX, posY, angle, size, speedX, speedY, accX, accY, angularSpeed, angularAcc, thrust. Implements `PoolableObject`; self-releases via `pool.release(this)`. |
| `BodyData` | `engine.model.bodies.ports` | entityId, bodyType, physicsValues |
| `PlayerDTO` | `engine.model.bodies.ports` | entityId, playerName, damage, energy, shieldLevel, temperature, activeWeapon, 4×AmmoStatus, **score** |
| `BodyRefDTO` | `engine.events.domain.ports` | id(), type() — record |
| `BodyToEmitDTO` | `engine.events.domain.ports` | type, assetId, size, forwardOffset, sideOffset, speed, acceleration, angularSpeed, maxLifeTime, randomAngle, randomSize, addEmitterSpeed |
| `EmitPayloadDTO` | `engine.events.domain.ports.payloads` | primaryBodyRef, bodyConfig |
| `CollisionPayload` | `engine.events.domain.ports.payloads` | haveImmunity |
| `ActionDTO` | `engine.actions` | bodyId, bodyType, type (ActionType), relatedEvent |
| `DynamicRenderDTO` | `engine.view.renderables.ports` | entityId, posX, posY, angle, size, assetId, timeStamp — also updateFrom() for pooled reuse |
| `RenderDTO` | `engine.view.renderables.ports` | entityId, posX, posY, angle, size, timeStamp |
| `PlayerRenderDTO` | `engine.view.renderables.ports` | entityId, playerName, damage, energy, shieldLevel, temperature, activeWeapon, 4×AmmoStatus |
| `SystemDTO` | `engine.view.core` | fps, renderTimeInMs, imagesCached, imagesCacheHitRate, entityAliveQuantity, entityDeadQuantity |
| `SpatialGridStatisticsDTO` | `engine.utils.spatial.ports` | nonEmptyBuckets, emptyBuckets, avgEntitiesPerBucket, maxEntitiesInBucket, totalCollisionPairs |
| `SpatialGridStatisticsRenderDTO` | `engine.view.renderables.ports` | mirrors SpatialGridStatisticsDTO fields |
| `ProfilingStatisticsDTO` | `engine.model.ports` | wrapper around the all-metrics map |
| `AssetInfoDTO` | `engine.assets.ports` | assetId, fileName, AssetType, AssetIntensity |
| `DefItemDTO` | `engine.world.ports` | assetId, size, angle, posX, posY, density, speedX, speedY, angularSpeed, thrust |
| `DefItemPrototypeDTO` | `engine.world.ports` | Same as DefItemDTO but with min/max ranges per field |
| `DefEmitterDTO` | `engine.world.ports` | All emitter configuration fields — mirrors EmitterConfigDto at world-def level |
| `DefWeaponDTO` | `engine.world.ports` | Weapon-specific emitter fields + shootingOffset |
| `DefBackgroundDTO` | `engine.world.ports` | assetId, scrollX, scrollY |
| `EmitterConfigDto` | `engine.model.emitter.ports` | Full emitter config after mapping |
| `ImageCacheKeyMDTO` | `engine.utils.images` | angle, assetId, size |
| `ImageDTO` | `engine.utils.images` | assetId, uri, image |
| `RenderMetricsDTO` | `engine.view.renderables.ports` | backgroundMs, translateMs, staticMs, dynamicMs, hudsMs, showMs, totalDrawMs, updateMs, frameMs |

---

## 26. Complete File Index

### engine/actions/
- `ActionDTO.java` — data record for a single action (bodyId, bodyType, ActionType, relatedDomainEvent).
- `ActionType.java` — enum of all action types (12 values).

### engine/assets/core/
- `AssetCatalog.java` — HashMap-backed asset registry with random-selection helpers.

### engine/assets/ports/
- `AssetInfoDTO.java` — DTO: assetId, fileName, AssetType, AssetIntensity.
- `AssetIntensity.java` — enum: HIGH, MEDIUM, LOW.
- `AssetType.java` — enum of 26 visual category types.

### engine/controller/impl/
- `Controller.java` *(507 lines)* — the MVC hub. Full documentation in §6.

### engine/controller/mappers/
- `AbstractPooledMapper.java` — generic pool-backed mapper base class.
- `DynamicRenderableMapper.java` — BodyData → DynamicRenderDTO (pooled + non-pooled).
- `EmitterMapper.java` — DefEmitterDTO → EmitterConfigDto.
- `PhysicsValuesDTOMapper.java` — **EMPTY FILE**.
- `PlayerRenderableMapper.java` — PlayerDTO → PlayerRenderDTO.
- `ProfilingStatisticsMapper.java` — ProfilingStatisticsDTO → Object[4].
- `RenderableMapper.java` — BodyData → RenderDTO (+ batch version).
- `SpatialGridStatisticsMapper.java` — SpatialGridStatisticsDTO → SpatialGridStatisticsRenderDTO.

### engine/controller/ports/
- `ActionsGenerator.java` — interface: `provideActions(events, actions)`.
- `EngineState.java` — enum: STARTING, ALIVE, PAUSED, STOPPED.
- `WorldManager.java` — interface: all entity-creation and equipment methods.

### engine/events/domain/core/
- `AbstractDomainEvent.java` — generic base class carrying event type, primary/secondary body refs, and optional payload.

### engine/events/domain/ports/
- `BodyRefDTO.java` — record: id(), type().
- `BodyToEmitDTO.java` — spawn configuration.
- `DomainEventType.java` — enum of all event types.
- `eventtype/CollisionEvent.java` — collision event (primary + secondary + payload).
- `eventtype/DomainEvent.java` — sealed interface or base class for events.
- `eventtype/EmitEvent.java` — emission / fire event.
- `eventtype/LifeOver.java` — life-ended event.
- `eventtype/LimitEvent.java` — limit-reached event.
- `payloads/CollisionPayload.java` — haveImmunity flag.
- `payloads/DomainEventPayload.java` — sealed payload marker interface.
- `payloads/EmitPayloadDTO.java` — primary body ref + body-to-emit config.
- `payloads/NoPayload.java` — singleton payload for events without extra data.

### engine/generators/
- `AbstractIAGenerator.java` — Runnable AI spawner skeleton.
- `AbstractLevelGenerator.java` — Template Method level builder.
- `DefItemMaterializer.java` — Converts DefItem (prototype or concrete) to DefItemDTO.

### engine/model/bodies/core/
- `AbstractBody.java` *(605 lines)* — base entity class. Full documentation in §9.

### engine/model/bodies/impl/
- `BodyProfiler.java` — concrete profiler with physics-stage metrics.
- `DynamicBody.java` — DYNAMIC and PROJECTILE bodies.
- `PlayerBody.java` — PLAYER body with weapon system.
- `StaticBody.java` — DECORATOR and GRAVITY bodies.

### engine/model/bodies/ports/
- `BodyData.java` — DTO: entityId, bodyType, PhysicsValuesMDTO.
- `BodyEventProcessor.java` — interface: `processBodyEvents(body, newPhy, oldPhy)`.
- `BodyFactory.java` — static factory. Creates concrete body + physics engine by BodyType.
- `BodyState.java` — enum: STARTING, ALIVE, HANDS_OFF, DEAD.
- `BodyType.java` — enum: DECORATOR, GRAVITY, DYNAMIC, PLAYER, PROJECTILE.
- `PlayerDTO.java` — player stats snapshot.

### engine/model/emitter/impl/
- `BasicEmitter.java` — state-machine emitter with burst, reload, and atomic ammo tracking.

### engine/model/emitter/ports/
- `Emitter.java` — interface: decCooldown, mustEmitNow, registerRequest, getConfig, getEmitterId.
- `EmitterConfigDto.java` — full emitter configuration.
- `EmitterState.java` — enum: READY, RELOADING.

### engine/model/impl/
- `BodyBatchManager.java` — manages `MultiBodyRunner` instances; assigns bodies to runners (DEFAULT_BATCH_SIZE=100, PLAYER_BATCH_SIZE=1).
- `Model.java` *(1228 lines)* — simulation core. Full documentation in §7.
- `MultiBodyRunner.java` — Runnable; calls `body.onTick()` for a list of bodies; sleeps 15 ms; removes dead bodies each cycle.

### engine/model/physics/core/
- `AbstractPhysicsEngine.java` — triple-buffer physics state management.

### engine/model/physics/implementations/
- `BasicPhysicsEngine.java` — Symplectic Euler with MRUA. Full documentation in §10.
- `NullPhysicsEngine.java` — no-op engine for static bodies.

### engine/model/physics/ports/
- `PhysicsEngine.java` — interface for physics engines.
- `PhysicsValuesMDTO.java` — mutable physics state DTO; implements `PoolableObject`; self-releases to pool.

### engine/model/ports/
- `DomainEventProcessor.java` — interface for Controller callbacks from Model.
- `ModelState.java` — enum: STARTING, ALIVE, STOPPED.
- `ProfilingStatisticsDTO.java` — wraps all profiling metrics.

### engine/utils/helpers/
- `DoubleVector.java` — immutable 2D vector. Fields: x, y, module. Methods: add, addScaled, distance, rotated, scale, withModule.
- `RandomArrayList.java` — ArrayList extension with `choice()` random picker.

### engine/utils/images/
- `ImageCache.java` — rotation-aware compatible image cache keyed by (angle, assetId, size).
- `ImageCacheKeyMDTO.java` — cache key object.
- `ImageDTO.java` — loaded image record (`assetId`, `uri`, `BufferedImage`).
- `Images.java` — raw image map: assetId → ImageDTO.

### engine/utils/pooling/
- `Pool.java` — generic concurrent object pool `Pool<T extends PoolableObject>` backed by `ConcurrentLinkedDeque`. Methods: preallocate, acquire, release, clear, getPoolSize.
- `PoolableObject.java` — interface: `reset()`, `setPool(Pool)`, `release()`.

### engine/utils/profiling/core/
- `AbstractProfiler.java` — metrics framework with interval timing and periodic report.
- `Metric.java` — per-key metric accumulator.
- `MetricDTO.java` — immutable metric snapshot DTO.
- `MetricFormatter.java` — metric formatting helper.
- `MetricType.java` — metric kind enum.
- `ProfileSnapshot.java` — immutable period snapshot.

### engine/utils/spatial/core/
- `Cells.java` — value object holding an entity's current cell index set.
- `SpatialGrid.java` *(467 lines)* — concurrent spatial hash grid. Full documentation in §11.

### engine/utils/spatial/ports/
- `SpatialGridStatisticsDTO.java` — grid state snapshot.

### engine/utils/threading/
- `ThreadPoolManager.java` — wraps `ThreadPoolExecutor` (DEFAULT_POOL_SIZE=250, NORM-1 priority, non-daemon threads, 30 s shutdown timeout).
### engine/view/core/
- `ControlPanel.java` — empty JPanel with View reference (future UI controls).
- `Renderer.java` *(740 lines)* — triple-buffered Canvas render loop. Full documentation in §17.
- `RendererProfiler.java` — profiler for render/update/frame timing + FPS.
- `SystemDTO.java` — FPS + entity count snapshot DTO.
- `View.java` *(576 lines)* — JFrame + input layer. Full documentation in §8.

### engine/view/hud/core/
- `BarItem.java` — HUD: labeled fill bar.
- `DataHUD.java` — HUD: vertical item stack.
- `GridHUD.java` — HUD: spatial grid overlay.
- `IconItem.java` — HUD: icon with label.
- `Item.java` — abstract HUD item.
- `SeparatorItem.java` — HUD: horizontal rule.
- `SkipItem.java` — HUD: vertical gap.
- `TextItem.java` — HUD: label + optional right-aligned value.
- `TitleItem.java` — HUD: bold title.

### engine/view/hud/impl/
- `PlayerHUD.java` — player stats overlay.
- `RenderHUD.java` — render timing overlay.
- `SpatialGridHUD.java` — grid statistics overlay.
- `SystemHUD.java` — FPS + entity count overlay.

### engine/view/renderables/ports/
- `DynamicRenderDTO.java` — mutable render DTO for dynamic bodies (has updateFrom()).
- `PlayerRenderDTO.java` — player stats render DTO.
- `RenderDTO.java` — generic static body render DTO.
- `RenderMetricsDTO.java` — renderer performance metrics.
- `SpatialGridStatisticsRenderDTO.java` — grid stats for view layer.

### engine/world/core/
- `AbstractWorldDefinitionProvider.java` *(661 lines)* — full documentation in §14.
- `WeaponDefFactory.java` — static factory for preset weapon DefEmitterDTOs.
- `WorldAssetsRegister.java` — bridges ProjectAssets → AssetCatalog registration.

### engine/world/ports/
- `DefBackgroundDTO.java` — background asset reference.
- `DefEmitterDTO.java` — emitter definition for world-definition level.
- `DefItem.java` — marker interface for entity definitions.
- `DefItemDTO.java` — concrete entity definition with fixed values.
- `DefItemPrototypeDTO.java` — range-based entity definition.
- `DefWeaponDTO.java` — weapon-specific emitter definition.
- `DefWeaponType.java` — enum of weapon types.
- `WorldDefinition.java` — immutable data container for all world entities.
- `WorldDefinitionProvider.java` — interface: `WorldDefinition provide()`.

### gameai/
- `AIBasicSpawner.java` — random asteroid spawner.

### gamelevel/
- `LevelBasic.java` — loads decorators, statics, and players from WorldDefinition.

### gamerules/
- `DeadInLimits.java` — die at any boundary.
- `DeadInLimitsPlayerImmunity.java` — die at boundary; player is immune.
- `InLimitsGoToCenter.java` — teleport to center at boundary. **(active in Main)**
- `LimitRebound.java` — rebound at all walls.
- `ReboundAndCollision.java` — rebound + mutual death on collision.
- `ReboundCollisionPlayerImmunity.java` — rebound + collision death; player immune at limits.

### gameworld/
- `EarthInCenterWorldDefinitionProvider.java` — planet-centered world layout.
- `ProjectAssets.java` — registers all game image assets into an AssetCatalog.
- `RandomWorldDefinitionProvider.java` — mixed random world layout. **(active in Main)**

---

## 27. Design Patterns Reference

| Pattern | Where used | Notes |
|---|---|---|
| **MVC** | Whole engine | Strict: Model↔Controller↔View, no cross-layer knowledge |
| **Template Method** | `AbstractLevelGenerator`, `AbstractIAGenerator`, `AbstractWorldDefinitionProvider`, `AbstractPhysicsEngine`, `AbstractProfiler` | Subclasses override hooks |
| **Factory** | `BodyFactory` | Creates correct concrete body + physics engine by type |
| **Strategy** | `ActionsGenerator` (game rules), `PhysicsEngine` | Pluggable algorithm implementations |
| **Object Pool** | `Pool<T>` + `PoolableObject` | `PhysicsValuesMDTO` and `DynamicRenderDTO`; objects self-release via `this.pool.release(this)` |
| **Observer / Callback** | `DomainEventProcessor` | Model calls Controller callbacks |
| **DTO** | Everywhere | Clean layer boundaries via data-transfer objects |
| **Spatial Hashing** | `SpatialGrid` | O(1) broad-phase collision |
| **Double/Triple Buffer** | `AbstractPhysicsEngine` | Thread-safe physics state reads during rendering |
| **Command** | `ActionDTO` | Decoupled action dispatch from execution |
| **Facade** | `View`, `Controller` | Each hides complex subsystem behind a simple interface |
| **Composite** | `DataHUD` + `Item` hierarchy | HUD composed of typed items |

---

## 28. Extension Points

### Add a new game rule

1. Create `gamerules/MyRules.java` implementing `ActionsGenerator`.
2. Handle desired event types in `provideActions()` using the `switch(event)` pattern.
3. In `Main.java`: `ActionsGenerator gameRules = new gamerules.MyRules();`

### Add a new world layout

1. Create `gameworld/MyWorldProvider.java` extending `AbstractWorldDefinitionProvider`.
2. Implement `define()` using the protected `add*` helpers.
3. In `Main.java`: `WorldDefinitionProvider worldProv = new gameworld.MyWorldProvider(worldDimension, projectAssets);`

### Add a new level (initial scene setup)

1. Create `gamelevel/MyLevel.java` extending `AbstractLevelGenerator`.
2. Implement `createDecorators()`, `createStatics()`, `createPlayers()`, `createDynamics()`.
3. In `Main.java`: `new gamelevel.MyLevel(controller, worldDef);`

### Add a new AI behaviour

1. Create `gameai/MySpawner.java` extending `AbstractIAGenerator`.
2. Implement `onTick()`, `onActivate()`, `getThreadName()`.
3. In `Main.java`: `new gameai.MySpawner(controller, worldDef, maxDelay).activate();`

### Add a new entity asset

1. Drop the image file in `src/resources/images/`.
2. In `ProjectAssets.java`, call `catalog.register("my_asset_id", "filename.png", AssetType.ASTEROID, AssetIntensity.MEDIUM)`.
3. Reference the assetId in your world provider.

### Add a new body type

1. Add value to `BodyType.java` enum.
2. Add case to `BodyFactory.create()` choosing the right concrete class and PhysicsEngine.
3. Add case to `Model.getBodyMap()` (storage map lookup).
4. Add case to `Model.removeBody()` (cleanup routing).
5. Add case to `Model.checkCollisions()` if needed.

### Add a new physics engine

1. Create class extending `AbstractPhysicsEngine`.
2. Implement `calcNewPhysicsValues()` and all rebound methods.
3. Use it in `BodyFactory.create()` for the relevant body type(s).

### Add a new HUD screen

1. Create class extending `DataHUD` (or `GridHUD` for grid visualisation).
2. Define HUD items in the subclass constructor/helper (`addTitle`, `addTextItem`, etc.) and call `prepareHud()`.
3. Register the HUD instance in the Renderer.

### Add a new action type

1. Add value to `ActionType.java` enum.
2. Add case to `Model.executeAction()` with desired simulation effect.
3. Handle in desired `ActionsGenerator` implementations.

### Add a new domain event type

1. Add value to `DomainEventType.java` enum.
2. Create event class implementing/extending the base DomainEvent type.
3. Emit it in the appropriate `check*Events()` method in `Model`.
4. Handle it in `ActionsGenerator` implementations that care.

---

## 29. Key Constants & Configuration

| Constant / variable | Location | Value | Meaning |
|---|---|---|---|
| `viewDimension` | `Main` | 720×720 | Canvas pixel size |
| `worldDimension` | `Main` | 40000×40000 | Simulation space size |
| `maxBodies` | `Main` | 1000 | Max concurrent dynamic bodies |
| `maxAsteroidCreationDelay` | `Main` | 3 | Upper bound used by AI sleep randomizer (`nextInt`) |
| `SPATIAL_GRID_CELL_SIZE` | `Model` | 64 | Grid cell world-unit size |
| `MAX_CELLS_PER_BODY` | `Model` | 1512 | Max cells one body can straddle |
| `DEFAULT_BATCH_SIZE` | `Model` | 10 | Used only for thread-pool-size formula: `ceil(maxBodies/10)+50` |
| `DEFAULT_BATCH_SIZE` | `BodyBatchManager` | 100 | Bodies per `MultiBodyRunner` (non-player) |
| `PLAYER_BATCH_SIZE` | `BodyBatchManager` | 1 | Each player gets an exclusive runner |
| `SLEEP_TIME_MS` | `MultiBodyRunner` | 15 ms | Sleep between physics ticks |
| `DEFAULT_POOL_SIZE` | `ThreadPoolManager` | 250 | ThreadPoolExecutor core/max thread count |
| `SHUTDOWN_TIMEOUT_SECONDS` | `ThreadPoolManager` | 30 s | Graceful shutdown wait |
| `EMITTER_IMMUNITY_TIME` | `AbstractBody` | 0.5 s | Projectile cannot collide with shooter for this duration after birth |
| physicsValuesPool prealloc | `Model` | `4 * maxBodies` | `PhysicsValuesMDTO` objects created up-front |
| `ASSET_PATH` | `AbstractWorldDefinitionProvider` | `src/resources/images/` | Resource base path |
| `TRAIL_LIFETIME` | `AbstractWorldDefinitionProvider` | 1.5 s | Default trail particle lifetime |
| `DEFAULT_TRAIL_MASS` | `AbstractWorldDefinitionProvider` | 10.0 | Default trail mass |
| Collision margin | `Model.intersectCircles` | 0.9 | Bodies collide at 90% of their radius |
| Renderer thread priority | `Renderer` | `NORM + 2` | High priority for render thread |
| AI thread priority | `AbstractIAGenerator` | `MIN_PRIORITY` | Low priority for spawner |
| BufferStrategy count | `Renderer` | 3 | Triple-buffered rendering |

---

## 30. Known Gaps & TODOs

The following items are identified as incomplete or placeholders in the codebase:

| Item | Location | Status |
|---|---|---|
| `EXPLODE_IN_FRAGMENTS` action | `Model.executeAction()` | **Not implemented** — empty case |
| `GO_INSIDE` action | `Model.executeAction()` | **Not implemented** — empty case |
| `PhysicsValuesDTOMapper.java` | `engine/controller/mappers/` | **Empty file** — never used |
| `ControlPanel.java` | `engine/view/core/` | Empty JPanel — no active functionality |
| `createDynamics()` in LevelBasic | `gamelevel/LevelBasic.java` | Empty — dynamic bodies only come from AI |
| Gravity simulation | Engine-wide | Not present — "gravity body" is naming convention only; no force calculations implemented |
| Multi-client / network multiplayer | Engine-wide | Not present |
| Win/Lose conditions | Engine-wide | Not present — no game-over logic |
| Weapon stat caps / damage model | PlayerBody | Placeholder fields (damage, energy, shieldLevel, temperature) exist in PlayerDTO but logic not detailed |
| Sound system | Engine-wide | Not present |
| Config file / hot-reload | Engine-wide | All config is hardcoded in Main or constants |
| `mustFireNow()` timing divisor | `PlayerBody` | Uses `1_000_000_0000.0d` (11 zeros) instead of `1_000_000_000.0d` (9 zeros) — potential fire-rate timing bug |
| Renderable release on body death | `Renderer.java` | Comment: "CRITICAL TO-DO: pending release renderables when bodies die" |

---

*End of PROJECT_REFERENCE.md*
