package engine.model.impl;

// region imports
import static java.lang.System.nanoTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.List;

import engine.actions.ActionType;
import engine.actions.ActionDTO;
import engine.events.domain.ports.BodyRefDTO;
import engine.events.domain.ports.BodyToEmitDTO;
import engine.events.domain.ports.DomainEventType;
import engine.events.domain.ports.eventtype.CollisionEvent;
import engine.events.domain.ports.eventtype.DomainEvent;
import engine.events.domain.ports.eventtype.EmitEvent;
import engine.events.domain.ports.eventtype.LifeOver;
import engine.events.domain.ports.eventtype.LimitEvent;
import engine.events.domain.ports.payloads.CollisionPayload;
import engine.events.domain.ports.payloads.EmitPayloadDTO;
import engine.model.bodies.core.AbstractBody;
import engine.model.bodies.impl.BodyProfiler;
import engine.model.bodies.impl.DynamicBody;
import engine.model.bodies.impl.PlayerBody;
import engine.model.bodies.ports.BodyData;
import engine.model.bodies.ports.BodyEventProcessor;
import engine.model.bodies.ports.BodyFactory;
import engine.model.bodies.ports.BodyState;
import engine.model.bodies.ports.BodyType;
import engine.model.bodies.ports.PlayerDTO;
import engine.model.emitter.impl.BasicEmitter;
import engine.model.emitter.ports.EmitterConfigDto;
import engine.model.physics.ports.GravitySourceDTO;
import engine.model.physics.ports.GravitySourceProvider;
import engine.model.physics.ports.PhysicsValuesMDTO;
import engine.model.ports.DomainEventProcessor;
import engine.model.ports.ModelState;
import engine.model.ports.ProfilingStatisticsDTO;
import engine.utils.helpers.DoubleVector;
import engine.utils.pooling.Pool;
import engine.utils.spatial.core.SpatialGrid;
import engine.utils.spatial.ports.SpatialGridStatisticsDTO;
// endregion

/**
 * Model
 * -----
 *
 * Core simulation layer of the MVC triad. The Model owns and manages all
 * entities (dynamic bodies, gravity bodies, decorators) and orchestrates
 * their lifecycle, physics updates, collision detection, and domain event
 * processing.
 *
 * Responsibilities
 * ----------------
 * - Entity management: create, activate, and track all simulation entities
 * - Provide thread-safe snapshot data (BodyDTO) to the Controller for rendering
 * - Delegate physics updates to individual entity threads
 * - Detect domain events (collisions, limits, emissions, life over)
 * - Decide and execute actions based on domain events
 * - Manage spatial partitioning for efficient collision detection
 * - Enforce world boundaries and entity limits
 *
 * Entity types
 * ------------
 * The Model manages several distinct entity categories:
 *
 * 1) Dynamic Bodies (dynamicBodies)
 * - Entities with active physics simulation
 * - Includes: DYNAMIC, PLAYER, and PROJECTILE body types
 * - Each runs on its own thread, continuously updating position/velocity
 * - Players support thrust, rotation, firing commands, and weapon/emitter
 * equipment
 * - Projectiles track shooter ID and immunity period
 * - Stored in ConcurrentHashMap for thread-safe access
 *
 * 2) Gravity Bodies (gravityBodies)
 * - Static bodies that exert gravitational influence
 * - No physics thread
 * - Used for planetary bodies or black holes
 *
 * 3) Decorators (decorators)
 * - Visual-only entities with no gameplay impact (background elements,
 * particles)
 * - No physics thread
 * - Support limited lifetime
 *
 * Lifecycle
 * ---------
 * Construction:
 * - Model is created in STARTING state
 * - Requires worldWidth, worldHeight, and maxDynamicBodies parameters
 * - Entity maps are pre-allocated with expected capacities
 * - SpatialGrid is initialized with cell size 48 and max entities per cell 24
 *
 * Activation (activate()):
 * - Validates that DomainEventProcessor is set
 * - Transitions to ALIVE state
 * - After activation, entities can be created and activated
 *
 * Snapshot generation
 * -------------------
 * The Model provides snapshot methods that return DTO lists:
 * - getDynamicsData(): returns ArrayList<BodyDTO> for all dynamic bodies
 * - getStaticsData(): returns ArrayList<BodyDTO> for decorators + gravity
 * bodies
 * - getPlayerData(playerId): returns PlayerDTO for specific player
 *
 * These snapshots are pulled by the Controller and pushed to the View/Renderer.
 * The pattern ensures clean separation: rendering never accesses mutable
 * entity state directly.
 *
 * Event-Action Pipeline
 * ---------------------
 * The Model implements BodyEventProcessor interface to handle physics updates:
 *
 * 1) Event Detection (detectEvents):
 * - Limit events: when bodies reach world boundaries
 * - Collision events: detected via SpatialGrid broad-phase + circle
 * intersection
 * - Emission events: for particle systems and trails
 * - Fire events: weapon discharge from players
 * - Life over events: when maxLifeTime is reached
 *
 * 2) Action Decision (provideActions):
 * - Domain events are forwarded to DomainEventProcessor
 * - DomainEventProcessor returns ActionDTO list
 * - Movement action is added by default (unless body rebounded)
 *
 * 3) Action Execution (doActions):
 * - Actions are sorted by priority
 * - BODY executor: movement and rebounds delegated to entity
 * - MODEL executor: spawning, projectile emission, death handling
 *
 * Collision Detection
 * -------------------
 * - SpatialGrid provides O(1) broad-phase collision detection
 * - Only nearby entities are tested for collision
 * - Circle-circle intersection test for precise collision
 * - Projectile immunity system prevents self-collision after firing
 * - Collision deduplication via symmetry check (A-B = B-A)
 *
 * Concurrency strategy
 * --------------------
 * - All entity maps use ConcurrentHashMap for thread-safe access
 * - Individual entities manage their own thread synchronization
 * - Body state machine prevents concurrent event processing (HANDS_OFF state)
 * - Model state transitions are protected by volatile fields
 * - Snapshot methods create independent DTO lists to avoid concurrent
 * modification during rendering
 * - Scratch buffers reduce allocation pressure during snapshot generation
 *
 * Design goals
 * ------------
 * - Keep simulation logic isolated from view concerns
 * - Provide deterministic, thread-safe entity management
 * - Support high entity counts (up to MAX_ENTITIES = 5000)
 * - Enable efficient parallel physics updates via per-entity threads
 * - Minimize garbage collection via object pooling (scratch buffers)
 * - Spatial partitioning for O(n) collision detection instead of O(n²)
 */

public class Model implements BodyEventProcessor, GravitySourceProvider {

    // region Constants
    private static final int DEFAULT_MAX_BODIES = 5000;
    private static final int SPATIAL_GRID_CELL_SIZE = 64;
    private static final int MAX_CELLS_PER_BODY = 4096;
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final boolean ENABLE_OBJECT_GRAVITY = true;
    private static final double GRAVITY_MASS_COEFFICIENT = 0.08d;
    private static final double GRAVITY_MIN_DISTANCE = 250.0d;
    /**
     * Mass multiplier applied to DYNAMIC planet bodies when exposed as gravity
     * sources. Must stay at 1.0 for orbital stability.
     *
     * Why 1.0: the outer gas-giants have raw GM values already equal to 0.3-1 %
     * of the sun's GM at their orbital radii; multiplying by 4 made them a
     * 12-50 % perturbation on neighbouring planets, which destabilises orbits
     * At 2.0, inter-planet perturbations reach ~10–17 % of solar gravity at
     * closest approach — quasi-stable for typical session durations.
     *
     * PLANET_PLAYER_GRAVITY_MULT is used ONLY when computing gravity on the
     * player (via CentralGravityPhysicsEngine.isPlayer = true).  It does not
     * affect planet-planet interactions, so it can be raised freely without
     * any impact on orbital stability.
     * At 12.0 × Earth: Hill sphere ≈ 2260 units, surface acceleration ≈ 18 u/s².
     */
    private static final double DYNAMIC_PLANET_MASS_MULT   = 2.0d;
    private static final double PLANET_PLAYER_GRAVITY_MULT = 12.0d;
    // endregion

    // region Fields
    private int maxBodies;
    private DomainEventProcessor domainEventProcessor = null;
    private volatile ModelState state = ModelState.STARTING;
    private double worldWidth;
    private double worldHeight;
    private SpatialGrid spatialGrid;
    private final Map<String, AbstractBody> decorators = new ConcurrentHashMap<>(200);
    private final Map<String, AbstractBody> dynamicBodies = new ConcurrentHashMap<>(DEFAULT_MAX_BODIES);
    private final Map<String, AbstractBody> gravityBodies = new ConcurrentHashMap<>(200);
    private final BodyProfiler bodyProfiler;
    private final BodyBatchManager bodyBatchManager;
    // endregion

    // region Buffer (for zero-allocation snapshot generation)
    private Pool<PhysicsValuesMDTO> physicsValuesPool;
    private ArrayList<BodyData> scratchDynamicsBuffer;
    // endregion

    // region Constructors
    public Model(DoubleVector worldDimension, int maxDynamicBodies) {
        if (worldDimension == null || worldDimension.x <= 0 || worldDimension.y <= 0)
            throw new IllegalArgumentException("Invalid world dimension");

        if (maxDynamicBodies <= 0)
            throw new IllegalArgumentException("Invalid maxDynamicBodies: must be > 0");

        this.maxBodies = maxDynamicBodies;
        this.worldWidth = worldDimension.x;
        this.worldHeight = worldDimension.y;

        // Create scratch buffer and preallocate physics DTO pool
        scratchDynamicsBuffer = new ArrayList<>(maxDynamicBodies);
        this.physicsValuesPool = new Pool<PhysicsValuesMDTO>(
                () -> new PhysicsValuesMDTO(0L, 0, 0, 0, 0));
        this.physicsValuesPool.preallocate(4 * this.maxBodies);

        // Calculate thread pool size based on expected batching (maxBodies/batchSize +
        // margin for players)
        int threadPoolSize = (int) Math.ceil(maxDynamicBodies / (double) DEFAULT_BATCH_SIZE) + 50;
        this.bodyBatchManager = new BodyBatchManager(threadPoolSize);

        this.bodyProfiler = new BodyProfiler();

        this.spatialGrid = new SpatialGrid(worldDimension.x, worldDimension.y,
                SPATIAL_GRID_CELL_SIZE, MAX_CELLS_PER_BODY);
    }
    // endregion

    // *** PUBLICS ***

    public void activate() {
        if (this.domainEventProcessor == null) {
            throw new IllegalArgumentException("Controller is not set");
        }
        if (this.spatialGrid == null) {
            throw new IllegalArgumentException("World dimension is not set");
        }
        if (this.worldHeight <= 0 || this.worldWidth <= 0) {
            throw new IllegalArgumentException("Invalid world dimension");
        }

        System.out.println("Model: Activated");
        this.bodyBatchManager.activate();
        this.state = ModelState.ALIVE;
    }

    // region Body adders (add***)
    public String addBody(BodyType bodyType,
            double size,
            double posX, double posY, double speedX, double speedY,
            double accX, double accY,
            double angle, double angularSpeed, double angularAcc,
            double thrust, double maxLifeTime, String shooterId) {

        if (AbstractBody.getAliveQuantity() >= this.maxBodies && bodyType == BodyType.DYNAMIC) {
            return null; // ========= Max vObject quantity reached ==========>>
        }
        if (bodyType == null) {
            throw new IllegalArgumentException("Body type is null");
        }
        if (bodyType == BodyType.PROJECTILE && (shooterId == null || shooterId.isEmpty())) {
            throw new IllegalArgumentException("Projectile body requires shooterId");
        }
        if (maxLifeTime == 0) {
            throw new IllegalArgumentException("maxLifeInSeconds must be greater than zero o -1 for infinite life");
        }

        // Acquire 3 DTOs from pool for physics engine double buffer + snapshot
        // in order to avoid allocations in the critical path of body creation
        // and activation and also to ensure thread-safe access to physics values.
        PhysicsValuesMDTO phyValues1 = this.physicsValuesPool.acquire();
        PhysicsValuesMDTO phyValues2 = this.physicsValuesPool.acquire();
        PhysicsValuesMDTO phyValues3 = this.physicsValuesPool.acquire();

        // Initialize dto1 with physics values
        phyValues1.update(nanoTime(), posX, posY, angle, size,
                speedX, speedY, accX, accY, angularSpeed, angularAcc, thrust);

        // Create body (WITHOUT threading concerns)
        AbstractBody body = BodyFactory.create(
                this, this.spatialGrid,
                phyValues1, phyValues2, phyValues3, // Three for thread-safety
                bodyType,
                maxLifeTime,
                shooterId,
            this.bodyProfiler,
            ENABLE_OBJECT_GRAVITY,
            this,
            GRAVITY_MASS_COEFFICIENT,
            GRAVITY_MIN_DISTANCE);

        // Prepare body state
        body.activate();

        // Assign body to thread pool (BodyBatchManager decides batch size based on
        // type)
        this.bodyBatchManager.activateBody(body);

        Map<String, AbstractBody> bodyMap = this.getBodyMap(bodyType);
        bodyMap.put(body.getBodyId(), body);

        this.spatialGridUpsert(body);

        return body.getBodyId();
    }

    public String addDecorator(double size, double posX, double posY, double angle, double maxLifeInSeconds) {

        String entityId = this.addBody(BodyType.DECORATOR, size, posX, posY,
                0, 0, 0, 0,
                angle, 0, 0,
                0, maxLifeInSeconds, null);

        return entityId;
    }

    public String addDynamic(double size,
            double posX, double posY, double speedX, double speedY,
            double accX, double accY,
            double angle, double angularSpeed, double angularAcc,
            double thrust, double maxLifeInSeconds) {

        String entityId = this.addBody(BodyType.DYNAMIC,
                size, posX, posY, speedX, speedY, accX, accY,
                angle, angularSpeed, angularAcc,
                thrust, maxLifeInSeconds, null);

        return entityId;
    }

    public String addStatic(double size, double posX, double posY, double angle, double maxLifeInSeconds) {
        return this.addStatic(size, posX, posY, 0, 0, angle, 0, maxLifeInSeconds);
    }

    public String addStatic(double size,
            double posX, double posY, double speedX, double speedY,
            double angle, double angularSpeed,
            double maxLifeInSeconds) {

        String entityId = this.addBody(BodyType.GRAVITY,
                size, posX, posY, speedX, speedY, 0, 0,
                angle, angularSpeed, 0,
                0, maxLifeInSeconds, null);

        return entityId;
    }

    public String addPlayer(double size,
            double posX, double posY, double speedX, double speedY,
            double accX, double accY,
            double angle, double angularSpeed, double angularAcc,
            double thrust, double maxLifeInSeconds) {

        String entityId = this.addBody(BodyType.PLAYER,
                size, posX, posY, speedX, speedY, accX, accY,
                angle, angularSpeed, angularAcc,
                thrust, maxLifeInSeconds, null);

        return entityId;
    }

    public String addProjectile(double size,
            double posX, double posY, double speedX, double speedY,
            double accX, double accY,
            double angle, double angularSpeed, double angularAcc,
            double thrust, double maxLifeInSeconds,
            String shooterId) {

        String entityId = this.addBody(BodyType.PROJECTILE,
                size, posX, posY, speedX, speedY, accX, accY,
                angle, angularSpeed, angularAcc,
                thrust, maxLifeInSeconds, shooterId);

        return entityId;
    }
    // endregion

    // region Body equipment (bodyEquip***)
    public String bodyEquipEmitter(String bodyId, EmitterConfigDto emitterConfig) {
        if (bodyId == null || bodyId.isEmpty()) {
            throw new IllegalArgumentException("BodyId cannot be null or empty");
        }
        if (emitterConfig == null) {
            throw new IllegalArgumentException("EmitterConfig cannot be null");
        }

        AbstractBody body = this.dynamicBodies.get(bodyId);
        if (body == null) {
            return ""; // ========= Body not found =========>
        }

        BasicEmitter emitter = new BasicEmitter(emitterConfig);
        String emitterId = body.emitterEquip(emitter);

        return emitterId;
    }

    public String bodyEquipTrail(String bodyId, EmitterConfigDto trailConfig) {
        if (bodyId == null || bodyId.isEmpty()) {
            throw new IllegalArgumentException("BodyId cannot be null or empty");
        }
        if (trailConfig == null) {
            throw new IllegalArgumentException("EmitterConfig cannot be null");
        }

        AbstractBody body = this.dynamicBodies.get(bodyId);
        if (body == null) {
            return ""; // ========= Body not found =========>
        }

        if (!(body instanceof DynamicBody)) {
            throw new IllegalArgumentException("bodyEquipTrail() -> body is not DynamicBody");
        }
        DynamicBody dBody = (DynamicBody) body;

        BasicEmitter trailEmitter = new BasicEmitter(trailConfig);
        String emitterId = dBody.trailEquip(trailEmitter);

        return emitterId;
    }
    // endregion

    // region Getters (get***)
    public int getAliveQuantity() {
        return AbstractBody.getAliveQuantity();
    }

    public AbstractBody getBody(String entityId, BodyType bodyType) {

        switch (bodyType) {
            case DECORATOR:
                return this.decorators.get(entityId);

            case DYNAMIC:
            case PLAYER:
            case PROJECTILE:
                return this.dynamicBodies.get(entityId);

            case GRAVITY:
                return this.gravityBodies.get(entityId);

            default:
                return null;
        }
    }

    public BodyData getBodyData(String entityId) {
        AbstractBody body = this.dynamicBodies.get(entityId);
        if (body == null) {
            body = this.decorators.get(entityId);
        }
        if (body == null) {
            body = this.gravityBodies.get(entityId);
        }
        if (body == null) {
            return null; // ========= Body not found ========>
        }

        return body.getBodyData();
    }

    public int getCreatedQuantity() {
        return AbstractBody.getCreatedQuantity();
    }

    public int getDeadQuantity() {
        return AbstractBody.getDeadQuantity();
    }

    public ArrayList<BodyData> snapshotDynamicsRenderData(Set<String> inRegionIds) {
        this.scratchDynamicsBuffer.clear();

        for (String entityId : inRegionIds) {
            AbstractBody body = this.dynamicBodies.get(entityId);
            if (body == null) {
                body = this.gravityBodies.get(entityId);
            }

            if (body != null) {
                PhysicsValuesMDTO phyValues = body.getPhysicsValues();
                if (phyValues == null) {
                    throw new IllegalStateException("Physics values are null for body " + entityId);
                }

                BodyData bodyInfo = body.getBodyData();
                this.scratchDynamicsBuffer.add(bodyInfo);
            }
        }

        return this.scratchDynamicsBuffer;
    }

    public int getDefaultMaxBodies() {
        return this.maxBodies;
    }

    public Pool<PhysicsValuesMDTO> getPhysicsValuesPool() {
        return this.physicsValuesPool;
    }

    public ProfilingStatisticsDTO getProfilingStatistics() {
        return new ProfilingStatisticsDTO(this.bodyProfiler.getAllMetrics());
    }

    public PlayerDTO getPlayerData(String playerId) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody == null) {
            return null;
        }

        PlayerDTO playerData = pBody.getData();
        return playerData;
    }

    public ArrayList<BodyData> getStaticsData() {
        ArrayList<BodyData> staticsInfo;

        staticsInfo = this.getBodiesData(this.decorators);
        staticsInfo.addAll(this.getBodiesData(this.gravityBodies));

        return staticsInfo;
    }

    public ModelState getState() {
        return this.state;
    }

    public SpatialGridStatisticsDTO getSpatialGridStatistics() {
        return this.spatialGrid.getStatistics();
    }

    public DoubleVector getWorldDimension() {
        return new DoubleVector(this.worldWidth, this.worldHeight);
    }
    // endregion Getters

    // region Boolean getters (is***)
    public boolean isModelAlive() {
        return this.state == ModelState.ALIVE;
    }
    // endregion

    // region Player equipment (playerEquip***)
    public void playerEquipWeapon(String playerId, EmitterConfigDto emitterConfig) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody == null) {
            throw new IllegalArgumentException("Equip weapon: Player not found");
        }

        String emitterId = this.bodyEquipEmitter(playerId, emitterConfig);

        pBody.addWeapon(emitterId);
    }
    // endregion

    // region Player Actions (player***)
    public void playerFire(String playerId) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody != null) {
            pBody.registerFireRequest();
        }
    }

    public void playerThrustOn(String playerId) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody != null) {
            pBody.thrustMaxOn();
        }
    }

    public void playerThrustOff(String playerId) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody != null) {
            pBody.thrustOff();
        }
    }

    public void playerReverseThrust(String playerId) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody != null) {
            pBody.reverseThrust();
        }
    }

    public void playerRotateLeftOn(String playerId) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody != null) {
            pBody.rotateLeftOn();
        }
    }

    public void playerRotateOff(String playerId) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody != null) {
            pBody.rotateOff();
        }
    }

    public void playerRotateRightOn(String playerId) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody != null) {
            pBody.rotateRightOn();
        }
    }

    public void playerSelectNextWeapon(String playerId) {
        PlayerBody pBody = (PlayerBody) this.dynamicBodies.get(playerId);
        if (pBody == null) {
            return;
        }

        pBody.selectNextWeapon();
    }
    // endregion Player Actions

    // region Query methods (query***)
    public Set<String> queryEntitiesInRegion(
            double minX, double maxX, double minY, double maxY,
            int[] scratchCellIdxs, Set<String> outEntityIds) {

        if (this.spatialGrid == null) {
            throw new IllegalStateException("Model: SpatialGrid is not initialized");
        }

        this.spatialGrid.queryRegion(
                minX, maxX, minY, maxY,
                scratchCellIdxs, outEntityIds);

        return outEntityIds;
    }
    // endregion

    // region Remove and destroy (remove***)
    public void removeBody(AbstractBody body) {
        body.die();

        switch (body.getBodyType()) {
            case PLAYER:
                this.domainEventProcessor.notifyPlayerIsDead(body.getBodyId());
                this.spatialGrid.remove(body.getBodyId());
                this.dynamicBodies.remove(body.getBodyId());
                break;

            case DYNAMIC:
            case PROJECTILE:
                this.domainEventProcessor.notifyDynamicIsDead(body.getBodyId());
                this.spatialGrid.remove(body.getBodyId());
                this.dynamicBodies.remove(body.getBodyId());
                break;

            case DECORATOR:
                this.decorators.remove(body.getBodyId());
                this.domainEventProcessor.notifyStaticIsDead(body.getBodyId());
                break;

            case GRAVITY:
                this.gravityBodies.remove(body.getBodyId());
                break;
            default:
                // Nada
        }

    }
    // endregion

    // region Setters
    public void setDomainEventProcessor(DomainEventProcessor domainEventProcessor) {
        this.domainEventProcessor = domainEventProcessor;
    }

    public void setWorldDimension(DoubleVector worldDim) {
        if (worldDim == null || worldDim.x <= 0 || worldDim.y <= 0) {
            throw new IllegalArgumentException("Invalid world dimension");
        }

        this.worldWidth = worldDim.x;
        this.worldHeight = worldDim.y;

        this.spatialGrid = new SpatialGrid(
                worldDim.x, worldDim.y,
                SPATIAL_GRID_CELL_SIZE, MAX_CELLS_PER_BODY);
    }
    // endregion

    // *** INTERFACE IMPLEMENTATIONS ***

    // region BodyEventProcessor
    @Override
    public void processBodyEvents(AbstractBody checkBody,
            PhysicsValuesMDTO checkBodyNewPhyValues, PhysicsValuesMDTO checkBodyOldPhyValues) {

        if (!isProcessable(checkBody)) {
            return; // To avoid duplicate or unnecesary event processing ======>
        }

        BodyState previousState = checkBody.getBodyState();
        checkBody.setState(BodyState.HANDS_OFF);

        try {
            // 1 => Detect events -------------------
            List<DomainEvent> domainEvents = checkBody.getScratchClearEvents();
            long detectStart = this.bodyProfiler.startInterval();
            this.detectEvents(checkBody, checkBodyNewPhyValues, checkBodyOldPhyValues, domainEvents);
            this.bodyProfiler.stopInterval("EVENTS_DETECT", detectStart);

            // 2 => Decide actions ------------------
            List<ActionDTO> actions = checkBody.getActionsQueue();
            if (actions.size() > 0) {
            }
            long decideStart = this.bodyProfiler.startInterval();
            this.provideActions(checkBody, domainEvents, actions);
            this.bodyProfiler.stopInterval("EVENTS_DECIDE", decideStart);

            // 3 => Execute actions -----------------
            long executeStart = this.bodyProfiler.startInterval();
            this.executeActionList(checkBody.getBodyId(), actions, checkBodyNewPhyValues);
            this.bodyProfiler.stopInterval("EVENTS_EXECUTE", executeStart);

        } catch (Exception e) { // Fallback anti-zombi
            if (checkBody.getBodyState() == BodyState.HANDS_OFF) {
                checkBody.setState(previousState);
            }

        } finally { // Getout: off HANDS_OFF ... if leaving
            if (checkBody.getBodyState() == BodyState.HANDS_OFF) {
                checkBody.setState(BodyState.ALIVE);
            }
        }
    }
    // endregion

    // region GravitySourceProvider
    @Override
    public List<GravitySourceDTO> getGravitySources() {
        int capacity = this.gravityBodies.size() + this.dynamicBodies.size();
        ArrayList<GravitySourceDTO> sources = new ArrayList<>(capacity);

        // Static gravity sources (e.g. sun) — both multipliers = 1.0, vel = 0
        this.gravityBodies.forEach((entityId, body) -> {
            if (body == null || body.getBodyState() == BodyState.DEAD) {
                return;
            }

            PhysicsValuesMDTO phyValues = body.getPhysicsValues();
            if (phyValues == null) {
                return;
            }

            double radius = Math.max(1.0d, phyValues.size * 0.5d);
            sources.add(new GravitySourceDTO(entityId, phyValues.posX, phyValues.posY, radius,
                    1.0d, 1.0d, 0.0d, 0.0d));
        });

        // Dynamic planetary bodies — massMultiplier (planet-planet) stays low for
        // orbital stability; playerGravityMultiplier is high so the player feels
        // strong gravity near a planet.  velocities passed for planet-relative trace.
        this.dynamicBodies.forEach((entityId, body) -> {
            if (body == null || body.getBodyState() == BodyState.DEAD) {
                return;
            }
            if (body.getBodyType() != BodyType.DYNAMIC) {
                return; // skip PLAYER and PROJECTILE bodies
            }

            PhysicsValuesMDTO phyValues = body.getPhysicsValues();
            if (phyValues == null) {
                return;
            }

            double radius = Math.max(1.0d, phyValues.size * 0.5d);
            sources.add(new GravitySourceDTO(entityId, phyValues.posX, phyValues.posY, radius,
                    DYNAMIC_PLANET_MASS_MULT, PLANET_PLAYER_GRAVITY_MULT,
                    phyValues.speedX, phyValues.speedY));
        });

        return sources;
    }
    // endregion

    // *** PRIVATE ***

    // region Check methods (check***)
    private void checkCollisions(AbstractBody checkBody, PhysicsValuesMDTO newPhyValues,
            List<DomainEvent> domainEvents) {

        if (checkBody == null)
            throw new IllegalArgumentException("checkCollisions() -> checkBody is null");
        if (newPhyValues == null)
            throw new IllegalArgumentException("checkCollisions() -> newPhyValues is null");
        if (domainEvents == null)
            throw new IllegalArgumentException("checkCollisions() -> domainEvents is null");

        if (!this.isCollidable(checkBody))
            return; // =========== Non-collidable body ============>

        Set<String> candidates = checkBody.getScratchClearCandidateIds();
        if (!this.checkCollisionCandidates(checkBody, candidates))
            return; // =========== No candidates -> No collision ============>

        HashSet<String> seen = checkBody.getScratchClearSeenCandidateIds();
        for (String otherBodyId : candidates) {
            AbstractBody otherBody = this.getBody(otherBodyId);

            // Dedupe multiple references in differents cells
            if (!seen.add(otherBodyId))
                continue;

            // Dedupe by symmetry for all collidable bodies.
            // Gravity bodies are now simulated and can also perform collision checks.
            if (checkBody.getBodyId().compareTo(otherBodyId) >= 0)
                continue; // ======== Symmetric dedupe ON =========>

            if (!this.isCollidable(otherBody)) {
                continue;
            }

            final PhysicsValuesMDTO otherPhyValues = otherBody.getPhysicsValues();
            if (!intersectCircles(newPhyValues, otherPhyValues))
                continue;

            // Immunity check inmunity for projectiles and their shooters
            boolean haveInmunity = this.checkCollisionImmunity(checkBody, otherBody);

            // Create collision event ALSO when inmunity is active!!!!
            CollisionPayload payload = new CollisionPayload(haveInmunity);
            CollisionEvent collisionEvent = new CollisionEvent(
                    checkBody.getBodyRef(), otherBody.getBodyRef(), payload);
            domainEvents.add(collisionEvent);
        }
    }

    private boolean checkCollisionCandidates(AbstractBody checkBody, Set<String> candidates) {
        final String checkBodyId = checkBody.getBodyId();
        this.spatialGrid.queryCollisionCandidates(checkBodyId, candidates);

        if (candidates.isEmpty())
            return false; // ------ No collision candidates ------>

        return true;
    }

    private boolean checkCollisionImmunity(AbstractBody checkBody, AbstractBody otherBody) {
        AbstractBody projectile = null;
        AbstractBody nonProjectile = null;

        projectile = otherBody.getBodyType() == BodyType.PROJECTILE ? otherBody
                : checkBody.getBodyType() == BodyType.PROJECTILE ? checkBody
                        : null;

        if (projectile == null) {
            return false; // ===== No projectile -> No immunity =====>
        }

        nonProjectile = projectile == otherBody ? checkBody : otherBody;

        if (projectile.getBodyEmitterId().equals(nonProjectile.getBodyId())) {
            return projectile.isEmitterImmune();
        }

        return false;
    }

    private AbstractBody getBody(String bodyId) {
        if (bodyId == null || bodyId.isEmpty())
            throw new IllegalArgumentException("loadBody() -> bodyId is null or empty");

        // Pay attention to gravity bodies
        AbstractBody body = this.dynamicBodies.get(bodyId);
        if (body == null)
            body = this.gravityBodies.get(bodyId);

        return body;
    }

    private void checkEmissionEvents(AbstractBody checkBody, PhysicsValuesMDTO newPhyValues,
            PhysicsValuesMDTO oldPhyValues, List<DomainEvent> domainEvents) {

        BodyRefDTO primaryBodyRef = checkBody.getBodyRef();

        if (!(checkBody.emittersListEmpty())) {
            return; // ======= No emitters =======>
        }
        double dtSeconds = ((double) (newPhyValues.timeStamp - checkBody.getPhysicsValues().timeStamp))
                / 1_000_000_000.0;
        if (dtSeconds <= 0.0) {
            // Guard against negative/zero dt to prevent emitter cooldown from growing
            dtSeconds = 0.001;
        }

        for (BasicEmitter emitter : checkBody.emittersList()) {
            if (emitter.mustEmitNow(dtSeconds)) {
                EmitPayloadDTO payload = new EmitPayloadDTO(primaryBodyRef,
                        emitter.getBodyToEmitConfig());

                domainEvents.add(new EmitEvent(
                        DomainEventType.EMIT_REQUESTED,
                        primaryBodyRef,
                        payload));
            }
        }
    }

    private void checkFireEvents(AbstractBody checkBody,
            PhysicsValuesMDTO newPhyValues, List<DomainEvent> domainEvents) {

        BodyType bodyType = checkBody.getBodyType();
        BodyRefDTO primaryBodyRef = checkBody.getBodyRef();

        if (bodyType != BodyType.PLAYER) {
            return; // ======= Only players can fire =======>
        }

        PlayerBody pBody = (PlayerBody) checkBody;

        if (pBody.mustFireNow(newPhyValues)) {
            EmitPayloadDTO payload = new EmitPayloadDTO(
                    primaryBodyRef, pBody.getProjectileConfig());

            EmitEvent fireEvent = new EmitEvent(DomainEventType.FIRE_REQUESTED,
                    primaryBodyRef, payload);

            domainEvents.add(fireEvent);
        }
    }

    private void checkLifeOverEvents(AbstractBody checkBody, List<DomainEvent> domainEvents) {
        if (checkBody.isLifeOver()) {
            domainEvents.add(new LifeOver(checkBody.getBodyRef()));
        }
    }

    private void checkLimitEvents(AbstractBody body, PhysicsValuesMDTO phyValues,
            List<DomainEvent> domainEvents) {

        if (phyValues.posX < 0) {
            domainEvents.add(new LimitEvent(DomainEventType.REACHED_EAST_LIMIT, body.getBodyRef()));
        }

        if (phyValues.posX >= this.worldWidth) {
            domainEvents.add(new LimitEvent(DomainEventType.REACHED_WEST_LIMIT, body.getBodyRef()));
        }

        if (phyValues.posY < 0) {
            domainEvents.add(new LimitEvent(DomainEventType.REACHED_NORTH_LIMIT, body.getBodyRef()));
        }

        if (phyValues.posY >= this.worldHeight) {
            domainEvents.add(new LimitEvent(DomainEventType.REACHED_SOUTH_LIMIT, body.getBodyRef()));
        }
    }
    // endregion

    // region Clamp coordinates (clamp***)
    private double clampX(double posX) {
        if (posX < 0) {
            return 0;
        }
        if (posX >= this.worldWidth) {
            return this.worldWidth - 1;
        }
        return posX;
    }

    private double clampY(double posY) {
        if (posY < 0) {
            return 0;
        }
        if (posY >= this.worldHeight) {
            return this.worldHeight - 1;
        }
        return posY;
    }
    // endregion

    // region Execute actions (executeAction***)
    private void executeAction(ActionDTO action, AbstractBody body,
            PhysicsValuesMDTO newPhyValues) {

        if (body == null) {
            throw new IllegalArgumentException("doModelAction() -> body is null");
        }
        if (action == null) {
            throw new IllegalArgumentException("doModelAction() -> action is null");
        }
        if (newPhyValues == null) {
            throw new IllegalArgumentException("doModelAction() -> newPhyValues is null");
        }

        switch (action.type) {
            case MOVE:
                body.doMovement(newPhyValues);
                spatialGridUpsert((AbstractBody) body);
                break;

            case MOVE_REBOUND_FROM_BODY:
                if (action.relatedEvent instanceof CollisionEvent collisionEvent) {
                    this.reboundFromBody(body, collisionEvent, newPhyValues);
                } else {
                    body.doMovement(newPhyValues);
                    spatialGridUpsert((AbstractBody) body);
                }
                break;

            case MOVE_REBOUND_IN_EAST:
                body.reboundInEast(newPhyValues, this.worldWidth, this.worldHeight);
                spatialGridUpsert((AbstractBody) body);
                break;

            case MOVE_REBOUND_IN_WEST:
                body.reboundInWest(newPhyValues, this.worldWidth, this.worldHeight);
                spatialGridUpsert((AbstractBody) body);
                break;

            case MOVE_REBOUND_IN_NORTH:
                body.reboundInNorth(newPhyValues, this.worldWidth, this.worldHeight);
                spatialGridUpsert((AbstractBody) body);
                break;

            case MOVE_REBOUND_IN_SOUTH:
                body.reboundInSouth(newPhyValues, this.worldWidth, this.worldHeight);
                spatialGridUpsert((AbstractBody) body);
                break;

            case MOVE_TO_CENTER:
                PhysicsValuesMDTO moveToCenter = this.physicsValuesPool.acquire();
                moveToCenter.update(
                        newPhyValues.timeStamp,
                        this.worldWidth / 2, this.worldHeight / 2,
                        newPhyValues.angle,
                        newPhyValues.size,
                        newPhyValues.speedX, newPhyValues.speedY,
                        0D, 0D,
                        newPhyValues.angularSpeed,
                        newPhyValues.angularAcc,
                        0D);

                body.doMovement(moveToCenter);
                spatialGridUpsert((AbstractBody) body);

                break;

            case NO_MOVE:
                PhysicsValuesMDTO oldPhyValues = body.getPhysicsValues();
                PhysicsValuesMDTO frozen = this.physicsValuesPool.acquire();
                frozen.update(
                        newPhyValues.timeStamp,
                        this.clampX(oldPhyValues.posX), this.clampY(oldPhyValues.posY), newPhyValues.angle,
                        newPhyValues.size,
                        0D, 0D,
                        0D, 0D,
                        newPhyValues.angularSpeed,
                        newPhyValues.angularAcc,
                        0D);
                body.doMovement(frozen);
                spatialGridUpsert((AbstractBody) body);
                break;

            case GO_INSIDE:
                // To-Do: lógica futura
                break;

            case SPAWN_BODY:
            case SPAWN_PROJECTILE:
                if (!(action.relatedEvent instanceof EmitEvent emitEvent))
                    break;

                EmitPayloadDTO payload = emitEvent.payload;
                if (payload == null || payload.bodyConfig == null)
                    break;

                this.spawnBody(body, payload.bodyConfig, newPhyValues);
                break;

            case DIE:
                this.removeBody(body);
                break;

            case EXPLODE_IN_FRAGMENTS:
                break;

            default:
                break;
        }
    }

    private void reboundFromBody(AbstractBody body, CollisionEvent collisionEvent, PhysicsValuesMDTO newPhyValues) {
        if (collisionEvent == null || collisionEvent.secondaryBodyRef == null) {
            body.doMovement(newPhyValues);
            spatialGridUpsert(body);
            return;
        }

        String bodyId = body.getBodyId();
        String primaryId = collisionEvent.primaryBodyRef.id();
        String secondaryId = collisionEvent.secondaryBodyRef.id();

        String otherBodyId = bodyId.equals(primaryId) ? secondaryId : primaryId;
        AbstractBody otherBody = this.getBody(otherBodyId);
        if (otherBody == null) {
            body.doMovement(newPhyValues);
            spatialGridUpsert(body);
            return;
        }

        PhysicsValuesMDTO otherPhyValues = otherBody.getPhysicsValues();

        // --- Collision normal (from other body centre toward this body) ---
        double normalX = newPhyValues.posX - otherPhyValues.posX;
        double normalY = newPhyValues.posY - otherPhyValues.posY;

        double normalLength = Math.sqrt(normalX * normalX + normalY * normalY);
        if (normalLength < 1e-9d) {
            normalX = 1.0d;
            normalY = 0.0d;
            normalLength = 1.0d;
        }

        normalX /= normalLength;
        normalY /= normalLength;

        // --- Mass-based elastic collision (1-D impulse on collision axis) ---
        // Mass is proportional to radius^3 (volume), matching the gravity model.
        // This ensures massive planets barely deflect on player contact while
        // the player gets kicked away at the correct impulse speed.
        double r1 = newPhyValues.size * 0.5d;
        double r2 = otherPhyValues.size * 0.5d;
        double m1 = r1 * r1 * r1;  // proportional mass of this body
        double m2 = r2 * r2 * r2;  // proportional mass of other body
        double mTotal = m1 + m2;

        double v1n = newPhyValues.speedX * normalX + newPhyValues.speedY * normalY;
        double v2n = otherPhyValues.speedX * normalX + otherPhyValues.speedY * normalY;

        // Post-collision normal-component velocity (elastic, 1-D)
        double v1nAfter = ((m1 - m2) * v1n + 2.0d * m2 * v2n) / mTotal;

        // Update only the normal component, preserve tangential component
        double dv1n = v1nAfter - v1n;
        double reboundSpeedX = newPhyValues.speedX + dv1n * normalX;
        double reboundSpeedY = newPhyValues.speedY + dv1n * normalY;

        // --- Separation correction (prevent overlap) ---
        double bodyRadius = r1 * 0.9d;
        double otherRadius = r2 * 0.9d;
        double separation = bodyRadius + otherRadius + 0.5d;

        double correctedPosX = otherPhyValues.posX + normalX * separation;
        double correctedPosY = otherPhyValues.posY + normalY * separation;

        PhysicsValuesMDTO reboundValues = this.physicsValuesPool.acquire();
        reboundValues.update(
                newPhyValues.timeStamp,
                correctedPosX,
                correctedPosY,
                newPhyValues.angle,
                newPhyValues.size,
                reboundSpeedX,
                reboundSpeedY,
                newPhyValues.accX,
                newPhyValues.accY,
                newPhyValues.angularSpeed,
                newPhyValues.angularAcc,
                newPhyValues.thrust);

        body.doMovement(reboundValues);
        spatialGridUpsert(body);
    }

    private void executeActionList(
            String primaryBodyId, List<ActionDTO> actions, PhysicsValuesMDTO primaryBodyNewPhyValues) {

        if (actions == null || actions.isEmpty()) {
            return; // ===== No actions to execute ======>
        }

        boolean isPrimaryBody = false;
        for (ActionDTO action : actions) {
            if (action == null || action.type == null) {
                throw new IllegalArgumentException("executeActionList() -> action is null");
            }

            isPrimaryBody = (primaryBodyId.compareTo(action.bodyId) == 0);

            AbstractBody targetBody = this.getBody(action.bodyId, action.bodyType);
            if (targetBody == null) {
                continue; // Body already removed, skip this action
            }

            if (isPrimaryBody) {
                this.executeAction(action, targetBody, primaryBodyNewPhyValues);
            } else {
                // If not primary body, relay action to body itself
                // Every body action will be processed in their body thread
                targetBody.enqueueExternalAction(action);
            }
        }

        actions.clear(); // All actions executed -> clear the list

    }
    // endregion

    private void detectEvents(AbstractBody checkBody,
            PhysicsValuesMDTO newPhyValues, PhysicsValuesMDTO oldPhyValues, List<DomainEvent> domainEvents) {

        // 1 => Limits (all bodies) -----------------------
        this.checkLimitEvents(checkBody, newPhyValues, domainEvents);

        // 2 => Collisions (all bodies) -------------------
        this.checkCollisions(checkBody, newPhyValues, domainEvents);

        // 3 => Emission on (dynamics and players) ----------
        this.checkEmissionEvents(checkBody, newPhyValues, oldPhyValues, domainEvents);

        // 4 => Fire (only players) -----------------------
        this.checkFireEvents(checkBody, newPhyValues, domainEvents);

        // 5 => Life over (all body types) -------------------
        this.checkLifeOverEvents(checkBody, domainEvents);
    }

    // region getters private (get***)
    private ArrayList<BodyData> getBodiesData(Map<String, AbstractBody> bodies) {
        ArrayList<BodyData> bodyInfos = new ArrayList<>(bodies.size());

        bodies.forEach((entityId, body) -> {
            BodyData bodyInfo = new BodyData(entityId, body.getBodyType(), body.getPhysicsValues());
            if (bodyInfo != null) {
                bodyInfos.add(bodyInfo);
            }
        });

        return bodyInfos;
    }

    private Map<String, AbstractBody> getBodyMap(BodyType bodyType) {
        Map<String, AbstractBody> bodyMap = null;

        switch (bodyType) {
            case DECORATOR:
                bodyMap = this.decorators;
                break;

            case DYNAMIC:
            case PLAYER:
            case PROJECTILE:
                bodyMap = this.dynamicBodies;
                break;
            case GRAVITY:
                bodyMap = this.gravityBodies;
                break;
        }

        if (bodyMap == null) {
            throw new IllegalArgumentException("Invalid body type: " + bodyType);
        }

        return bodyMap;
    }
    // endregion

    private boolean intersectCircles(PhysicsValuesMDTO a, PhysicsValuesMDTO b) {
        // OJO: asumo size = diámetro
        // Recortamos a un 90% el radio para evitar colisiones falsas por margenes

        final double ra = a.size * 0.5 * 0.9;
        final double rb = b.size * 0.5 * 0.9;

        final double dx = a.posX - b.posX;
        final double dy = a.posY - b.posY;
        final double r = ra + rb;

        return (dx * dx + dy * dy) <= (r * r);
    }

    // region boolean checks (is***)
    private boolean isCollidable(AbstractBody body) {
        return body != null
                && body.getBodyType() != BodyType.DECORATOR
                && body.getBodyState() != BodyState.DEAD
                && (body.getSpatialGrid() != null);
    }

    private boolean isProcessable(AbstractBody entity) {
        return entity != null
                && this.state == ModelState.ALIVE
                && entity.getBodyState() == BodyState.ALIVE;
    }
    // endregion

    private void provideActions(AbstractBody body, List<DomainEvent> domainEvents, List<ActionDTO> actions) {
        if (!domainEvents.isEmpty())
            this.domainEventProcessor.provideActions(domainEvents, actions);

        boolean actionWithMovementImplicit = actions.stream()
                .anyMatch(a -> a.type != null && a.type.name().contains("MOVE"));

        if (!actionWithMovementImplicit)
            // Always add MOVE action except if body rebounded
            actions.add(new ActionDTO(
                    body.getBodyId(), body.getBodyType(), ActionType.MOVE, null));
    }

    private void spawnBody(AbstractBody body, BodyToEmitDTO bodyConfig, PhysicsValuesMDTO newPhyValues) {
        if (body == null) {
            throw new IllegalArgumentException("Spawner body is null");
        }
        if (bodyConfig == null) {
            throw new IllegalArgumentException("BodyEmittedDTO is null");
        }
        if (newPhyValues == null) {
            throw new IllegalArgumentException("PhysicsValuesDTO is null");
        }

        double angleDeg = newPhyValues.angle;
        double angleRad = Math.toRadians(angleDeg);

        // Direction vector
        double directorX = Math.cos(angleRad);
        double directorY = Math.sin(angleRad);

        // Apply Offsets
        double posX = newPhyValues.posX + directorX * bodyConfig.forwardOffset;
        double posY = newPhyValues.posY + directorY * bodyConfig.forwardOffset;

        posX = posX - directorY * bodyConfig.sideOffset;
        posY = posY + directorX * bodyConfig.sideOffset;
        // Body initial speed
        double speedX = bodyConfig.speed * directorX;
        double speedY = bodyConfig.speed * directorY;

        // Body acceleration
        double accX = bodyConfig.acceleration * directorX;
        double accY = bodyConfig.acceleration * directorY;
        // Spawn body
        if (bodyConfig.randomAngle) {
            angleDeg = Math.random() * 360d;
        }
        double size = bodyConfig.size;
        if (bodyConfig.randomSize) {
            size = bodyConfig.size * (3 * Math.random());
        }

        double maxLifeTime = bodyConfig.maxLifeTime;
        maxLifeTime = maxLifeTime * (0.5 + 2 * Math.random());

        String entityId = this.addBody(bodyConfig.type,
                size, posX, posY, speedX, speedY, accX, accY,
                angleDeg, 0, 0,
                0, maxLifeTime, body.getBodyId());

        if (entityId == null || entityId.isEmpty()) {
            return; // ======= Max entity quantity reached =======>
        }

        if (bodyConfig.type == BodyType.DECORATOR ||
                bodyConfig.type == BodyType.GRAVITY) {

            this.domainEventProcessor.notifyNewStatic(
                    entityId, bodyConfig.assetId);
            return;
        }

        this.domainEventProcessor.notifyNewDynamic(
                entityId, bodyConfig.assetId);
    }

    private void spatialGridUpsert(AbstractBody body) {
        if (body == null)
            return;

        long spatialGridStart = this.bodyProfiler.startInterval();
        body.spatialGridUpsert();
        this.bodyProfiler.stopInterval("SPATIAL_GRID", spatialGridStart);
    }

    // *** SHUTDOWN ***

    /**
     * Gracefully shutdown the model and all managed resources.
     * 
     * Stops all running threads and runners in the batch manager.
     */
    public void shutdown() {
        this.state = ModelState.STOPPED;
        this.bodyBatchManager.shutdown();
    }
}