package engine.view.core;

// CRITICAL TO-DO: Is pending release renderables when they are
// removed from the model. Thats occurs when bodies die.

// region Imports
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import engine.controller.ports.EngineState;
import engine.model.bodies.ports.BodyData;
import engine.model.physics.ports.GravitySourceDTO;
import engine.model.physics.ports.PhysicsValuesMDTO;
import engine.utils.images.ImageCache;
import engine.utils.images.Images;
import engine.utils.helpers.DoubleVector;
import engine.view.hud.impl.RenderHUD;
import engine.view.hud.impl.PlayerHUD;
import engine.view.hud.impl.SpatialGridHUD;
import engine.view.hud.impl.SystemHUD;
import engine.view.renderables.impl.DynamicRenderable;
import engine.view.renderables.impl.Renderable;
import engine.view.renderables.ports.DynamicRenderDTO;
import engine.view.renderables.ports.PlayerRenderDTO;
import engine.view.renderables.ports.RenderDTO;
import engine.view.renderables.ports.RenderMetricsDTO;
import engine.view.renderables.ports.SpatialGridStatisticsRenderDTO;
// endregion

/**
 * Renderer
 * --------
 *
 * Active rendering loop responsible for drawing the current frame to the
 * screen. This class owns the rendering thread and performs all drawing using
 * a BufferStrategy-based back buffer.
 *
 * Architectural role
 * ------------------
 * The Renderer is a pull-based consumer of visual snapshots provided by the
 * View.
 * It never queries or mutates the model directly.
 *
 * Rendering is decoupled from simulation through immutable snapshot DTOs
 * (EntityInfoDTO / DBodyInfoDTO), ensuring that rendering remains deterministic
 * and free of model-side race conditions.
 *
 * Threading model
 * ---------------
 * - A dedicated render thread drives the render loop (Runnable).
 * - Rendering is active only while the engine state is ALIVE.
 * - The loop terminates cleanly when the engine reaches STOPPED.
 *
 * Data access patterns
 * --------------------
 * Three different renderable collections are used, each with a consciously
 * chosen
 * concurrency strategy based on update frequency and thread ownership:
 *
 * 1) Dynamic bodies (DBodies)
 * - Stored in a plain HashMap.
 * - Updated and rendered exclusively by the render thread.
 * - No concurrent access → no synchronization required.
 *
 * 2) Static bodies (SBodies)
 * - Rarely updated, potentially from non-render threads
 * (model → controller → view).
 * - Stored using a copy-on-write strategy:
 * * Updates create a new Map instance.
 * * The reference is swapped atomically via a volatile field.
 * - The render thread only reads stable snapshots.
 *
 * 3) Decorators
 * - Same access pattern as static bodies.
 * - Uses the same copy-on-write + atomic swap strategy.
 *
 * This design avoids locks, minimizes contention, and guarantees that the
 * render thread always iterates over a fully consistent snapshot.
 *
 * Frame tracking
 * --------------
 * A monotonically increasing frame counter (currentFrame) is used to:
 * - Track renderable liveness.
 * - Remove obsolete renderables deterministically.
 *
 * Each update method captures a local frame snapshot to ensure internal
 * consistency, even if the global frame counter advances later.
 *
 * Rendering pipeline
 * ------------------
 * Per frame:
 * 1) Background is rendered to a VolatileImage for fast blitting.
 * 2) Decorators are drawn.
 * 3) Static bodies are drawn.
 * 4) Dynamic bodies are updated and drawn.
 * 5) HUD elements (FPS) are rendered last.
 *
 * Alpha compositing is used to separate opaque background rendering from
 * transparent entities.
 *
 * Performance considerations
 * --------------------------
 * - Triple buffering via BufferStrategy.
 * - VolatileImage used for background caching.
 * - Target frame rate ~60 FPS (16 ms delay).
 * - FPS is measured using a rolling one-second window.
 *
 * Design goals
 * ------------
 * - Deterministic rendering.
 * - Zero blocking in the render loop.
 * - Clear ownership of mutable state.
 * - Explicit, documented concurrency decisions.
 *
 * This class is intended to behave as a low-level rendering component suitable
 * for a small game engine rather than a UI-centric Swing renderer.
 */
public class Renderer extends Canvas implements Runnable {

    // region Constants
    private static final int REFRESH_DELAY_IN_MILLIS = 1; //
    private static final long MONITORING_PERIOD_NS = 750_000_000L;
    private static final double MIN_ZOOM_FACTOR = 0.01d;
    private static final double MAX_ZOOM_FACTOR = 2.5d;
    private static final double ZOOM_STEP = 0.04d;
    private static final double ZOOM_SMOOTHING_FACTOR = 0.22d;
    private static final double CAMERA_SMOOTHING_FACTOR = 0.18d;
    private static final double CAMERA_MAX_SMOOTHING_FACTOR = 0.45d;
    private static final double CAMERA_SPEED_SMOOTHING_SCALE = 0.00005d;
    private static final int MAX_TRAJECTORY_STEPS = 8000;
    private static final double TRAJECTORY_STEP_SECONDS = 0.05d;
    private static final double TRAJECTORY_GRAVITY_MASS_COEFFICIENT = 0.08d;
    private static final double TRAJECTORY_GRAVITY_MIN_DISTANCE = 100.0d;
    private static final int ORBIT_CLOSE_MIN_STEPS = 320;
    private static final double ORBIT_CLOSE_DISTANCE_MULTIPLIER = 2.0d;
    private static final double ORBIT_CLOSE_DIRECTION_DOT_MIN = 0.97d;
    private static final int    PLANET_TRACE_TARGET_STEPS = 480;       // integration steps per estimated orbit
    private static final double PLANET_TRACE_MIN_STEP_SECONDS = 0.04d; // precision floor for inner planets
    private static final int    PLANET_TRACE_MAX_STEPS = 1440;         // hard safety cap (~3x target)
    /** Minimum speed (world units/s) a body must have to be given an orbit trace.
     *  Filters out near-stationary bodies like the sun that would otherwise
     *  show a phantom drift caused by n-body numerical integration. */
    private static final double PLANET_TRACE_MIN_SPEED = 0.5d;
    /**
     * One colour per orbiting body, cycling if there are more bodies than colours.
     * Ordered loosely by distance from sun — Mercury grey, Venus amber, Earth blue,
     * Moon silver, Mars red, Jupiter orange, Saturn gold, Uranus cyan.
     */
    private static final Color[] PLANET_TRACE_COLORS = {
        new Color(190, 190, 190, 155), // Mercury — grey
        new Color(255, 200,  80, 155), // Venus   — amber
        new Color( 80, 180, 255, 155), // Earth   — sky blue
        new Color(210, 210, 215, 130), // Moon    — silver
        new Color(240,  80,  50, 155), // Mars    — red
        new Color(255, 155,  60, 155), // Jupiter — deep orange
        new Color(230, 200,  90, 155), // Saturn  — gold
        new Color(100, 230, 235, 155), // Uranus  — cyan
    };
    // Hill-sphere rings drawn around every planet
    private static final Color  HILL_SPHERE_RING_COLOR  = new Color(120, 200, 255, 45);
    // Player trajectory when inside a planet's Hill sphere (green = planet-relative orbit)
    private static final Color  PLANET_RELATIVE_TRACE_COLOR = new Color(100, 255, 150, 185);
    // Player trajectory in solar inertial frame (blue)
    private static final Color  INERTIAL_TRACE_COLOR = new Color(80, 220, 255, 180);

    // Logs
    private static final boolean DIAGNOSTIC_LOGS_ENABLED = true;
    private static final long DIAGNOSTIC_LOG_EVERY_FRAMES = 120L;
    // endregion

    // region Fields
    private DoubleVector viewDimension;
    private View view;
    private int delayInMillis = 5;
    private long currentFrame = 0;
    private Thread thread;

    private BufferedImage background;
    private Images images;
    private ImageCache imagesCache;

    private double cameraX = 0.0d;
    private double cameraY = 0.0d;
    private volatile double zoomFactor = 1.0d;
    private volatile double targetZoomFactor = 1.0d;
    private double maxCameraClampY;
    private double maxCameraClampX;
    private double backgroundScrollSpeedX = 0.4;
    private double backgroundScrollSpeedY = 0.4;

    private final Map<String, DynamicRenderable> dynamicRenderables = new ConcurrentHashMap<>(2500);
    private volatile Map<String, Renderable> staticRenderables = new ConcurrentHashMap<>(100);

    // HUDs
    private final PlayerHUD playerHUD = new PlayerHUD();
    private final SystemHUD systemHUD = new SystemHUD();
    private final SpatialGridHUD spatialGridHUD = new SpatialGridHUD();
    private final RenderHUD renderHUD = new RenderHUD();

    // Buffers for zero-allocation
    private final Set<String> visibleEntityIds = new LinkedHashSet<>(1600);
    private int[] scratchIdxBuffer = new int[4096];
    private volatile boolean planetTracesEnabled = true;
    private volatile boolean followLocalPlayer = true;
    private volatile boolean cameraDragActive = false;
    private int lastDragMouseX = 0;
    private int lastDragMouseY = 0;

    private final RendererProfiler rendererProfiler = new RendererProfiler(MONITORING_PERIOD_NS);

    private long lastSpatialFallbackLogFrame = Long.MIN_VALUE;
    private long lastLocalPlayerGapLogFrame = Long.MIN_VALUE;
    // endregion

    // region Constructors
    public Renderer(View view) {
        this.view = view;

        this.setIgnoreRepaint(true);
        this.setCameraClampLimits();
    }
    // endregion

    // *** PUBLICS ***

    public boolean activate() {
        // Be sure all is ready to begin render!
        if (this.viewDimension == null) {
            throw new IllegalArgumentException("View dimensions not setted");
        }

        if ((this.viewDimension.x <= 0) || (this.viewDimension.y <= 0)) {
            throw new IllegalArgumentException("Canvas size error: ("
                    + this.viewDimension.x + "," + this.viewDimension.y + ")");
        }

        // BufferStrategy fails silently when canvas > screen
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        if (this.viewDimension.x > screenSize.width || this.viewDimension.y > screenSize.height) {
            throw new IllegalStateException(
                    "Renderer: Canvas size (" + (int) this.viewDimension.x + "x" + (int) this.viewDimension.y + ") "
                            + "exceeds screen size (" + screenSize.width + "x" + screenSize.height + "). "
                            + "Reduce viewDimension in Main.java or disable UI scaling (sun.java2d.uiScale).");
        }

        while (!this.isDisplayable()) {
            try {
                Thread.sleep(this.delayInMillis);
            } catch (InterruptedException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }

        this.setPreferredSize(
                new Dimension((int) this.viewDimension.x, (int) this.viewDimension.y));

        this.thread = new Thread(this);
        this.thread.setName("Renderer");
        this.thread.setPriority(Thread.NORM_PRIORITY + 2);
        this.thread.start();

        System.out.println("Renderer: Activated");
        return true;
    }

    // region adders (add***)
    public void addStaticRenderable(String entityId, String assetId) {
        Renderable renderable = new Renderable(entityId, assetId, this.imagesCache, this.currentFrame);
        this.staticRenderables.put(entityId, renderable);
    }

    public void addDynamicRenderable(String entityId, String assetId) {
        DynamicRenderable renderable = new DynamicRenderable(entityId, assetId, this.imagesCache, this.currentFrame);
        this.dynamicRenderables.put(entityId, renderable);
    }
    // endregion

    // region getters (get***)
    public Renderable getLocalPlayerRenderable() {
        String localPlayerId = this.view.getLocalPlayerId();

        if (localPlayerId == null || localPlayerId.isEmpty()) {
            return null; // ======= No player to follow =======>>
        }
        Renderable renderableLocalPlayer = this.dynamicRenderables.get(this.view.getLocalPlayerId());
        return renderableLocalPlayer;
    }

    /**
     * Get render metrics for HUD display
     */
    public RenderMetricsDTO getRenderMetrics() {
        return new RenderMetricsDTO(
                this.rendererProfiler.getAvgDrawBackgroundMs(),
                this.rendererProfiler.getAvgTranslateMs(),

                this.rendererProfiler.getAvgDrawStaticMs(),
                this.rendererProfiler.getAvgDrawDynamicMs(),
                this.rendererProfiler.getAvgDrawHudsMs(),
                this.rendererProfiler.getAvgShowMs(),

                this.rendererProfiler.getAvgDrawMs(),
                this.rendererProfiler.getAvgUpdateMs(),
                this.rendererProfiler.getAvgFrameMs());
    }
    // endregion

    // region notifiers (notify***)
    public void notifyDynamicIsDead(String entityId) {
        this.dynamicRenderables.remove(entityId);
    }
    // endregion

    // region setters (set***)
    public void setImages(BufferedImage background, Images images) {
        this.background = background;

        this.images = images;
        this.imagesCache = new ImageCache(this.getGraphicsConfSafe(), this.images);
    }

    public void setViewDimension(DoubleVector viewDim) {
        this.viewDimension = viewDim;
        this.setCameraClampLimits();
        this.setPreferredSize(new Dimension((int) this.viewDimension.x, (int) this.viewDimension.y));
    }

    public void zoomIn() {
        this.adjustZoom(1.0d);
    }

    public void zoomOut() {
        this.adjustZoom(-1.0d);
    }

    public void adjustZoom(double direction) {
        if (direction == 0.0d) {
            return;
        }

        double sign = Math.signum(direction);
        double baseZoom = Math.max(this.targetZoomFactor, MIN_ZOOM_FACTOR);
        double adaptiveDelta = baseZoom * ZOOM_STEP * sign;

        this.targetZoomFactor = clamp(this.targetZoomFactor + adaptiveDelta, MIN_ZOOM_FACTOR, MAX_ZOOM_FACTOR);
    }

    public boolean isPlanetTracesEnabled() {
        return this.planetTracesEnabled;
    }

    public void setPlanetTracesEnabled(boolean enabled) {
        this.planetTracesEnabled = enabled;
    }

    public void beginCameraDrag(int mouseX, int mouseY) {
        this.cameraDragActive = true;
        this.followLocalPlayer = false;
        this.lastDragMouseX = mouseX;
        this.lastDragMouseY = mouseY;
    }

    public void dragCameraTo(int mouseX, int mouseY) {
        if (!this.cameraDragActive) {
            return;
        }

        int dxPixels = mouseX - this.lastDragMouseX;
        int dyPixels = mouseY - this.lastDragMouseY;

        this.lastDragMouseX = mouseX;
        this.lastDragMouseY = mouseY;

        if (dxPixels == 0 && dyPixels == 0) {
            return;
        }

        double dxWorld = dxPixels / Math.max(this.zoomFactor, 0.001d);
        double dyWorld = dyPixels / Math.max(this.zoomFactor, 0.001d);

        this.cameraX = clamp(this.cameraX - dxWorld, 0.0, this.maxCameraClampX);
        this.cameraY = clamp(this.cameraY - dyWorld, 0.0, this.maxCameraClampY);
    }

    public void endCameraDrag() {
        this.cameraDragActive = false;
    }

    public void recenterCameraOnLocalPlayer() {
        this.followLocalPlayer = true;

        String localPlayerId = this.view.getLocalPlayerId();
        if (localPlayerId == null || localPlayerId.isBlank()) {
            return;
        }

        RenderDTO playerData = this.view.getRenderData(localPlayerId);
        if (playerData == null) {
            return;
        }

        double visibleWorldWidth = this.getVisibleWorldWidth();
        double visibleWorldHeight = this.getVisibleWorldHeight();

        this.cameraX = clamp(playerData.posX - (visibleWorldWidth * 0.5d), 0.0, this.maxCameraClampX);
        this.cameraY = clamp(playerData.posY - (visibleWorldHeight * 0.5d), 0.0, this.maxCameraClampY);
    }

    // endregion

    public void updateStaticRenderables(ArrayList<RenderDTO> renderablesData) {
        if (renderablesData == null) {
            return; // ========= Nothing to render by the moment ... =========>>
        }

        Map<String, Renderable> newRenderables = new java.util.concurrent.ConcurrentHashMap<>(this.staticRenderables);

        if (renderablesData.isEmpty()) {
            newRenderables.clear(); //
            this.staticRenderables = newRenderables;
            return;
        }

        // Update a renderable associated with each DBodyRenderInfoDTO
        long cFrame = this.currentFrame;
        for (RenderDTO renderableData : renderablesData) {
            String entityId = renderableData.entityId;
            if (entityId == null || entityId.isEmpty()) {
                continue;
            }

            Renderable renderable = newRenderables.get(entityId);
            if (renderable == null) {
                throw new IllegalStateException("Renderer: Static renderable not found: " + entityId);
            }
            renderable.update(renderableData, cFrame);
        }

        newRenderables.entrySet().removeIf(e -> e.getValue().getLastFrameSeen() != cFrame);
        this.staticRenderables = newRenderables; // atomic swap
    }

    // *** PRIVATES ***

    // region drawers (draw***)
    private void drawDynamics(Graphics2D g, Set<String> visibleIds) {
        long drawStart = this.rendererProfiler.startInterval(); // Profiler

        g.setComposite(AlphaComposite.SrcOver); // With transparency

        for (String entityId : visibleIds) {
            DynamicRenderable renderable = this.dynamicRenderables.get(entityId);
            if (renderable != null) {
                renderable.paint(g, this.currentFrame);
            }
        }

        this.rendererProfiler.stopInterval(
                RendererProfiler.METRIC_DRAW_DYNAMIC, drawStart); // Profiler
    }

    private void drawHUDs(Graphics2D g) {
        long hudsStart = this.rendererProfiler.startInterval(); // Profiler
        long fps = this.rendererProfiler.getLastFps();
        double avgDrawMs = this.rendererProfiler.getAvgDrawMs();

        g.setComposite(AlphaComposite.SrcOver); // With transparency
        this.systemHUD.draw(g,
                fps,
                String.format("%.0f", avgDrawMs) + " ms",
                this.imagesCache == null ? 0 : this.imagesCache.size(),
                String.format("%.0f", this.imagesCache == null ? 0 : this.imagesCache.getHitsPercentage()) + "%",
                this.view.getEntityAliveQuantity(),
                this.view.getEntityDeadQuantity(),
                this.currentFrame);

        this.renderHUD.draw(g, this.getRenderMetrics().toObjectArray());

        PlayerRenderDTO playerData = this.view.getLocalPlayerRenderData();
        if (playerData != null) {
            this.playerHUD.draw(g, playerData.toObjectArray());
        }

        SpatialGridStatisticsRenderDTO spatialGridStats = this.view.getSpatialGridStatistics();
        if (spatialGridStats != null) {
            this.spatialGridHUD.draw(g, spatialGridStats.toObjectArray());
        }

        this.rendererProfiler.stopInterval(
                RendererProfiler.METRIC_DRAW_HUDS, hudsStart); // Profiler
    }

    private void drawTrajectory(Graphics2D g) {
        String localPlayerId = this.view.getLocalPlayerId();
        if (localPlayerId == null || localPlayerId.isBlank()) {
            return;
        }

        BodyData playerBodyData = this.view.getBodyData(localPlayerId);
        if (playerBodyData == null) {
            return;
        }

        PhysicsValuesMDTO physics = playerBodyData.getPhysicsValues();
        if (physics == null) {
            return;
        }

        List<GravitySourceDTO> gravitySources = this.view.getGravitySources();

        double x = physics.posX;
        double y = physics.posY;
        double angle = physics.angle;
        double thrust = physics.thrust;
        double speedX = physics.speedX;
        double speedY = physics.speedY;
        double angularSpeed = physics.angularSpeed;
        double angularAcc = physics.angularAcc;

        // --- Detect if the player is inside any planet's Hill sphere ---
        // If so, switch to a planet-relative trajectory trace that shows the
        // actual orbit shape around that planet instead of a confusing inertial spiral.
        GravitySourceDTO capturePlanet = null;
        double plX = 0.0d, plY = 0.0d, plVX = 0.0d, plVY = 0.0d;
        if (gravitySources != null) {
            GravitySourceDTO sunSrc = null;
            for (GravitySourceDTO src : gravitySources) {
                if (src != null && Math.hypot(src.velX, src.velY) < PLANET_TRACE_MIN_SPEED) {
                    sunSrc = src;
                    break;
                }
            }
            if (sunSrc != null) {
                double mSun = TRAJECTORY_GRAVITY_MASS_COEFFICIENT
                        * sunSrc.radius * sunSrc.radius * sunSrc.radius;
                double bestMass = 0.0d;
                for (GravitySourceDTO src : gravitySources) {
                    if (src == null || src == sunSrc
                            || Math.hypot(src.velX, src.velY) < PLANET_TRACE_MIN_SPEED) continue;
                    double dsx = src.posX - sunSrc.posX;
                    double dsy = src.posY - sunSrc.posY;
                    double aPlanet = Math.sqrt(dsx * dsx + dsy * dsy);
                    double mPlanet = TRAJECTORY_GRAVITY_MASS_COEFFICIENT
                            * src.radius * src.radius * src.radius * src.playerGravityMultiplier;
                    double rH = aPlanet * Math.pow(mPlanet / (3.0d * mSun), 1.0d / 3.0d);
                    double dpx = src.posX - x;
                    double dpy = src.posY - y;
                    if (Math.sqrt(dpx * dpx + dpy * dpy) < rH && mPlanet > bestMass) {
                        bestMass = mPlanet;
                        capturePlanet = src;
                        plX = src.posX;  plY = src.posY;
                        plVX = src.velX; plVY = src.velY;
                    }
                }
            }
        }
        final boolean planetRelative = (capturePlanet != null);
        // In planet-relative mode the planet is anchored at its current world
        // position; player positions are drawn offset by (playerWorldPos - planetWorldPos).
        final double anchorX = plX;
        final double anchorY = plY;

        // Relative start position / speed for orbit-close detection
        double startX      = planetRelative ? (x      - plX + anchorX) : x;
        double startY      = planetRelative ? (y      - plY + anchorY) : y;
        double startSpeedX = planetRelative ? (speedX - plVX)          : speedX;
        double startSpeedY = planetRelative ? (speedY - plVY)          : speedY;
        double startSpeedMag = Math.hypot(startSpeedX, startSpeedY);

        double orbitCloseDistance = Math.max(physics.size * ORBIT_CLOSE_DISTANCE_MULTIPLIER, 25.0d);
        double movedAwayDistance  = Math.max(physics.size * 8.0d, orbitCloseDistance * 4.0d);
        boolean movedAwayFromStart = false;

        Stroke oldStroke = g.getStroke();
        Color oldColor = g.getColor();

        float lineWidth = (float) (1.8d / Math.max(this.zoomFactor, 0.001d));
        g.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(planetRelative ? PLANET_RELATIVE_TRACE_COLOR : INERTIAL_TRACE_COLOR);

        double worldWidth  = this.view.getWorldDimension().x;
        double worldHeight = this.view.getWorldDimension().y;

        for (int i = 0; i < MAX_TRAJECTORY_STEPS; i++) {
            double angleRad = Math.toRadians(angle);
            double thrustAccX = thrust == 0.0d ? 0.0d : Math.cos(angleRad) * thrust;
            double thrustAccY = thrust == 0.0d ? 0.0d : Math.sin(angleRad) * thrust;

            // -- Player gravity (uses playerGravityMultiplier, matching physics engine) --
            double gravityAccX = 0.0d;
            double gravityAccY = 0.0d;
            if (gravitySources != null) {
                for (GravitySourceDTO source : gravitySources) {
                    if (source == null || source.radius <= 0.0d) continue;
                    double dx = source.posX - x;
                    double dy = source.posY - y;
                    double distSq = dx * dx + dy * dy;
                    double soft = Math.max(TRAJECTORY_GRAVITY_MIN_DISTANCE, source.radius);
                    if (distSq < soft * soft) distSq = soft * soft;
                    double dist = Math.sqrt(distSq);
                    double sourceMass = TRAJECTORY_GRAVITY_MASS_COEFFICIENT
                            * source.radius * source.radius * source.radius
                            * source.playerGravityMultiplier;
                    if (sourceMass <= 0.0d) continue;
                    double am = sourceMass / distSq;
                    gravityAccX += am * (dx / dist);
                    gravityAccY += am * (dy / dist);
                }
            }

            double accX = thrustAccX + gravityAccX;
            double accY = thrustAccY + gravityAccY;

            double newSpeedX = speedX + accX * TRAJECTORY_STEP_SECONDS;
            double newSpeedY = speedY + accY * TRAJECTORY_STEP_SECONDS;
            double avgSpeedX = (speedX + newSpeedX) * 0.5d;
            double avgSpeedY = (speedY + newSpeedY) * 0.5d;
            double nextX = x + avgSpeedX * TRAJECTORY_STEP_SECONDS;
            double nextY = y + avgSpeedY * TRAJECTORY_STEP_SECONDS;

            // -- Planet motion (integrate from gravity sources using massMultiplier) --
            double nextPX = plX, nextPY = plY, nextPVX = plVX, nextPVY = plVY;
            if (planetRelative && gravitySources != null) {
                double pAccX = 0.0d, pAccY = 0.0d;
                for (GravitySourceDTO src : gravitySources) {
                    if (src == null || src == capturePlanet || src.radius <= 0.0d) continue;
                    double dx = src.posX - plX;
                    double dy = src.posY - plY;
                    double dSq = Math.max(dx * dx + dy * dy, src.radius * src.radius);
                    double d   = Math.sqrt(dSq);
                    double m   = TRAJECTORY_GRAVITY_MASS_COEFFICIENT
                            * src.radius * src.radius * src.radius * src.massMultiplier;
                    double am  = m / dSq;
                    pAccX += am * (dx / d);
                    pAccY += am * (dy / d);
                }
                nextPVX = plVX + pAccX * TRAJECTORY_STEP_SECONDS;
                nextPVY = plVY + pAccY * TRAJECTORY_STEP_SECONDS;
                nextPX  = plX  + ((plVX + nextPVX) * 0.5d) * TRAJECTORY_STEP_SECONDS;
                nextPY  = plY  + ((plVY + nextPVY) * 0.5d) * TRAJECTORY_STEP_SECONDS;
            }

            // -- Drawing coordinates --
            double drawX0 = planetRelative ? (x      - plX    + anchorX) : x;
            double drawY0 = planetRelative ? (y      - plY    + anchorY) : y;
            double drawX1 = planetRelative ? (nextX  - nextPX + anchorX) : nextX;
            double drawY1 = planetRelative ? (nextY  - nextPY + anchorY) : nextY;

            double newAngularSpeed = angularSpeed + angularAcc * TRAJECTORY_STEP_SECONDS;
            double newAngle = angle
                    + angularSpeed * TRAJECTORY_STEP_SECONDS
                    + 0.5d * newAngularSpeed * TRAJECTORY_STEP_SECONDS * TRAJECTORY_STEP_SECONDS;
            newAngle = ((newAngle % 360.0d) + 360.0d) % 360.0d;

            g.drawLine((int) Math.round(drawX0), (int) Math.round(drawY0),
                       (int) Math.round(drawX1), (int) Math.round(drawY1));

            if (nextX < 0 || nextX > worldWidth || nextY < 0 || nextY > worldHeight) {
                break;
            }

            speedX = newSpeedX;
            speedY = newSpeedY;
            angularSpeed = newAngularSpeed;
            angle = newAngle;
            x = nextX;
            y = nextY;
            if (planetRelative) {
                plX = nextPX;  plY = nextPY;
                plVX = nextPVX; plVY = nextPVY;
            }

            double relX = planetRelative ? (x - plX + anchorX) : x;
            double relY = planetRelative ? (y - plY + anchorY) : y;
            double distFromStart = Math.hypot(relX - startX, relY - startY);
            if (!movedAwayFromStart && distFromStart >= movedAwayDistance) {
                movedAwayFromStart = true;
            }
            if (!movedAwayFromStart || i < ORBIT_CLOSE_MIN_STEPS) continue;
            if (distFromStart > orbitCloseDistance) continue;

            double relVX = planetRelative ? (speedX - plVX) : speedX;
            double relVY = planetRelative ? (speedY - plVY) : speedY;
            double speedMag = Math.hypot(relVX, relVY);
            if (startSpeedMag <= 0.0001d || speedMag <= 0.0001d) {
                break;
            }

            double relVX2 = planetRelative ? (speedX - plVX) : speedX;
            double relVY2 = planetRelative ? (speedY - plVY) : speedY;
            double directionDot = ((startSpeedX * relVX2) + (startSpeedY * relVY2))
                    / (startSpeedMag * speedMag);

            if (directionDot >= ORBIT_CLOSE_DIRECTION_DOT_MIN) {
                break;
            }
        }

        g.setStroke(oldStroke);
        g.setColor(oldColor);
    }

    /**
     * Draws a faint ring around each orbiting planet marking the boundary of
     * its Hill sphere — the region where the planet's gravity dominates over
     * solar tidal forces and capture orbits are possible.
     *
     * r_H = a_planet * (m_planet_eff / (3 * m_sun))^(1/3)
     * where m_planet_eff uses playerGravityMultiplier (matches what the player feels).
     */
    private void drawHillSpheres(Graphics2D g) {
        if (!this.planetTracesEnabled) {
            return;
        }
        List<GravitySourceDTO> gravitySources = this.view.getGravitySources();
        if (gravitySources == null || gravitySources.isEmpty()) {
            return;
        }

        // Identify the sun: stationary gravity source (velX ≈ velY ≈ 0)
        GravitySourceDTO sunSrc = null;
        for (GravitySourceDTO src : gravitySources) {
            if (src == null) continue;
            if (Math.hypot(src.velX, src.velY) < PLANET_TRACE_MIN_SPEED) {
                sunSrc = src;
                break;
            }
        }
        if (sunSrc == null) return;

        double mSun = TRAJECTORY_GRAVITY_MASS_COEFFICIENT
                * sunSrc.radius * sunSrc.radius * sunSrc.radius;

        Stroke oldStroke = g.getStroke();
        Color  oldColor  = g.getColor();

        float lw = (float) (0.8d / Math.max(this.zoomFactor, 0.001d));
        g.setStroke(new BasicStroke(lw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(HILL_SPHERE_RING_COLOR);

        for (GravitySourceDTO src : gravitySources) {
            if (src == null || src == sunSrc
                    || Math.hypot(src.velX, src.velY) < PLANET_TRACE_MIN_SPEED) {
                continue;
            }
            double dsx = src.posX - sunSrc.posX;
            double dsy = src.posY - sunSrc.posY;
            double aPlanet = Math.sqrt(dsx * dsx + dsy * dsy);
            double mPlanet = TRAJECTORY_GRAVITY_MASS_COEFFICIENT
                    * src.radius * src.radius * src.radius * src.playerGravityMultiplier;
            double rH = aPlanet * Math.pow(mPlanet / (3.0d * mSun), 1.0d / 3.0d);
            if (rH < 1.0d) continue;

            int cx = (int) Math.round(src.posX - rH);
            int cy = (int) Math.round(src.posY - rH);
            int d  = (int) Math.round(rH * 2.0d);
            g.drawOval(cx, cy, d, d);
        }

        g.setStroke(oldStroke);
        g.setColor(oldColor);
    }

    private void drawPlanetTraces(Graphics2D g) {
        if (!this.planetTracesEnabled) {
            return;
        }

        List<GravitySourceDTO> gravitySources = this.view.getGravitySources();
        if (gravitySources == null || gravitySources.isEmpty()) {
            return;
        }

        Stroke oldStroke = g.getStroke();
        Color oldColor = g.getColor();
        DoubleVector worldDim = this.view.getWorldDimension();

        float lineWidth = (float) (1.25d / Math.max(this.zoomFactor, 0.001d));
        g.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // colour is set per-body inside the loop

        // --- Build and sort moving sources by distance from the world centre (sun)
        //     so that colour assignments are stable and predictable: Mercury=grey,
        //     Venus=amber, Earth=blue, Moon=silver, Mars=red, Jupiter=orange, etc.
        double sunCX = worldDim.x * 0.5d;
        double sunCY = worldDim.y * 0.5d;
        // find the real sun position from the stationary source
        for (GravitySourceDTO s : gravitySources) {
            if (s != null && Math.hypot(s.velX, s.velY) < PLANET_TRACE_MIN_SPEED) {
                sunCX = s.posX;
                sunCY = s.posY;
                break;
            }
        }
        final double sunFX = sunCX, sunFY = sunCY;

        List<GravitySourceDTO> sortedMoving = new ArrayList<>();
        for (GravitySourceDTO s : gravitySources) {
            if (s == null || s.bodyId == null || s.bodyId.isBlank()) continue;
            BodyData bd = this.view.getBodyData(s.bodyId);
            if (bd == null || bd.getPhysicsValues() == null) continue;
            double spd = Math.hypot(bd.getPhysicsValues().speedX, bd.getPhysicsValues().speedY);
            if (spd >= PLANET_TRACE_MIN_SPEED) sortedMoving.add(s);
        }
        sortedMoving.sort((a, b) -> {
            double da = Math.hypot(a.posX - sunFX, a.posY - sunFY);
            double db = Math.hypot(b.posX - sunFX, b.posY - sunFY);
            return Double.compare(da, db);
        });

        int colorIdx = 0;
        for (GravitySourceDTO source : sortedMoving) {
            g.setColor(PLANET_TRACE_COLORS[colorIdx % PLANET_TRACE_COLORS.length]);
            colorIdx++;

            BodyData sourceBodyData = this.view.getBodyData(source.bodyId);
            PhysicsValuesMDTO phy = sourceBodyData.getPhysicsValues();
            double speed = Math.hypot(phy.speedX, phy.speedY);

            double x = phy.posX;
            double y = phy.posY;
            double speedX = phy.speedX;
            double speedY = phy.speedY;

            // --- Adaptive step: target ~PLANET_TRACE_TARGET_STEPS per full orbit ---
            // Find the dominant attractor (highest mass) to estimate orbital period.
            double dominantMass = 0.0d;
            double dominantX    = worldDim.x * 0.5d;
            double dominantY    = worldDim.y * 0.5d;
            for (GravitySourceDTO a : gravitySources) {
                if (a == null || a.bodyId.equals(source.bodyId)) {
                    continue;
                }
                double m = TRAJECTORY_GRAVITY_MASS_COEFFICIENT
                        * a.radius * a.radius * a.radius * a.massMultiplier;
                if (m > dominantMass) {
                    dominantMass = m;
                    dominantX = a.posX;
                    dominantY = a.posY;
                }
            }
            double orbitRadius = Math.hypot(x - dominantX, y - dominantY);
            double estimatedPeriod = (speed > 0.001d)
                    ? (Math.PI * 2.0d * orbitRadius / speed)
                    : 2000.0d;
            double dt = Math.max(PLANET_TRACE_MIN_STEP_SECONDS,
                    estimatedPeriod / PLANET_TRACE_TARGET_STEPS);
            int maxSteps = (int) Math.min(PLANET_TRACE_MAX_STEPS,
                    Math.ceil(estimatedPeriod / dt) * 2.0d);

            // --- Orbit-close tracking (same logic as drawTrajectory) ---
            double startX       = x;
            double startY       = y;
            double startSpeedX  = speedX;
            double startSpeedY  = speedY;
            double startSpeedMag = speed;
            double orbitCloseDistance = Math.max(phy.size * ORBIT_CLOSE_DISTANCE_MULTIPLIER, 50.0d);
            double movedAwayDistance  = Math.max(phy.size * 6.0d, orbitCloseDistance * 3.0d);
            boolean movedAway = false;
            int earlyCloseGuard = (int) (PLANET_TRACE_TARGET_STEPS * 0.25d);

            for (int i = 0; i < maxSteps; i++) {
                double gravityAccX = 0.0d;
                double gravityAccY = 0.0d;

                for (GravitySourceDTO attractor : gravitySources) {
                    if (attractor == null || attractor.radius <= 0.0d
                            || source.bodyId.equals(attractor.bodyId)) {
                        continue;
                    }

                    double dx = attractor.posX - x;
                    double dy = attractor.posY - y;
                    double distSq = dx * dx + dy * dy;

                    double softDistance = Math.max(TRAJECTORY_GRAVITY_MIN_DISTANCE, attractor.radius);
                    double softDistanceSq = softDistance * softDistance;
                    if (distSq < softDistanceSq) {
                        distSq = softDistanceSq;
                    }

                    double dist = Math.sqrt(distSq);
                    double sourceMass = TRAJECTORY_GRAVITY_MASS_COEFFICIENT
                            * attractor.radius * attractor.radius * attractor.radius
                            * attractor.massMultiplier;
                    if (sourceMass <= 0.0d) {
                        continue;
                    }

                    double accMag = sourceMass / distSq;
                    gravityAccX += accMag * (dx / dist);
                    gravityAccY += accMag * (dy / dist);
                }

                double nextSpeedX = speedX + gravityAccX * dt;
                double nextSpeedY = speedY + gravityAccY * dt;
                double avgSpeedX  = (speedX + nextSpeedX) * 0.5d;
                double avgSpeedY  = (speedY + nextSpeedY) * 0.5d;

                double nextX = x + avgSpeedX * dt;
                double nextY = y + avgSpeedY * dt;

                g.drawLine(
                        (int) Math.round(x), (int) Math.round(y),
                        (int) Math.round(nextX), (int) Math.round(nextY));

                if (nextX < 0.0d || nextX > worldDim.x || nextY < 0.0d || nextY > worldDim.y) {
                    break;
                }

                x = nextX;
                y = nextY;
                speedX = nextSpeedX;
                speedY = nextSpeedY;

                double distFromStart = Math.hypot(x - startX, y - startY);
                if (!movedAway && distFromStart >= movedAwayDistance) {
                    movedAway = true;
                }

                if (!movedAway || i < earlyCloseGuard) {
                    continue;
                }

                if (distFromStart > orbitCloseDistance) {
                    continue;
                }

                double currentSpeedMag = Math.hypot(speedX, speedY);
                if (startSpeedMag <= 0.0001d || currentSpeedMag <= 0.0001d) {
                    break;
                }

                double directionDot = (startSpeedX * speedX + startSpeedY * speedY)
                        / (startSpeedMag * currentSpeedMag);
                if (directionDot >= ORBIT_CLOSE_DIRECTION_DOT_MIN) {
                    break;
                }
            }
        }

        g.setStroke(oldStroke);
        g.setColor(oldColor);
    }

    private void drawStatics(Graphics2D g) {
        long staticStart = this.rendererProfiler.startInterval(); // Profiler

        Map<String, Renderable> renderables = this.staticRenderables;

        g.setComposite(AlphaComposite.SrcOver); // With transparency
        for (Renderable renderable : renderables.values()) {

            if (this.isVisible(renderable)) {
                renderable.paint(g, this.currentFrame);
            }
        }

        this.rendererProfiler.stopInterval(
                RendererProfiler.METRIC_DRAW_STATIC, staticStart); // Profiler
    }

    private void drawScene(BufferStrategy bs, Set<String> visibleIds) {
        Graphics2D gg;

        do {
            gg = (Graphics2D) bs.getDrawGraphics();

            try {
                this.drawTiledBackground(gg);

                AffineTransform defaultTransform = this.worldTranslate(gg);

                this.drawStatics(gg);
                this.drawDynamics(gg, visibleIds);
                this.drawPlanetTraces(gg);
                this.drawHillSpheres(gg);
                this.drawTrajectory(gg);

                gg.setTransform(defaultTransform);

                this.drawHUDs(gg);

            } finally {
                gg.dispose();
            }

            // region Start show
            long showStart = this.rendererProfiler.startInterval();
            bs.show();
            this.rendererProfiler.stopInterval(RendererProfiler.METRIC_SHOW, showStart);
            // endregion

        } while (bs.contentsLost());
    }

    private void drawTiledBackground(Graphics2D g) {
        long bgStart = this.rendererProfiler.startInterval(); // Profiler

        g.setComposite(AlphaComposite.Src); // Opaque

        if (this.background == null || this.viewDimension == null)
            return;

        final int viewW = (int) this.viewDimension.x;
        final int viewH = (int) this.viewDimension.y;
        if (viewW <= 0 || viewH <= 0)
            return;

        final int tileW = this.background.getWidth(null);
        final int tileH = this.background.getHeight(null);
        if (tileW <= 0 || tileH <= 0)
            return;

        final double scrollX = this.cameraX * this.backgroundScrollSpeedX;
        final double scrollY = this.cameraY * this.backgroundScrollSpeedY;

        // Tile offset in [-(tile-1)..0], stable with negatives
        final int offX = -Math.floorMod((int) Math.floor(scrollX), tileW);
        final int offY = -Math.floorMod((int) Math.floor(scrollY), tileH);

        // Start 1 tile before to ensure full coverage
        final int startX = offX - tileW;
        final int startY = offY - tileH;
        for (int x = startX; x < viewW + tileW; x += tileW) {
            for (int y = startY; y < viewH + tileH; y += tileH) {
                g.drawImage(this.background, x, y, null);
            }
        }

        this.rendererProfiler.stopInterval(
                RendererProfiler.METRIC_DRAW_BACKGROUND, bgStart); // Profiler

    }
    // endregion

    // region getters (get***)
    private GraphicsConfiguration getGraphicsConfSafe() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) {
            gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();
        }

        return gc;
    }

    private VolatileImage getVolatileImage(
            VolatileImage vi, BufferedImage src, Dimension dim) {

        GraphicsConfiguration gc = this.getGraphicsConfSafe();

        if (vi == null || vi.getWidth() != dim.width || vi.getHeight() != dim.height
                || vi.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {
            // New volatile image
            vi = gc.createCompatibleVolatileImage(dim.width, dim.height, Transparency.OPAQUE);
        }

        int val;
        do {
            val = vi.validate(gc);
            if (val != VolatileImage.IMAGE_OK || vi.contentsLost()) {
                Graphics2D g = vi.createGraphics();
                g.drawImage(src, 0, 0, dim.width, dim.height, null);
                g.dispose();
            }
        } while (vi.contentsLost());

        return vi;
    }
    // endregion

    private boolean isVisible(Renderable renderable) {
        RenderDTO renderData = renderable.getRenderData();
        if (renderData == null) {
            return false;
        }

        double viewW = this.getVisibleWorldWidth();
        double viewH = this.getVisibleWorldHeight();

        double camLeft = this.cameraX;
        double camTop = this.cameraY;
        double camRight = camLeft + viewW;
        double camBottom = camTop + viewH;

        double half = renderData.size * 0.5d;
        if (renderable.getImage() != null) {
            double halfW = renderable.getImage().getWidth(null) * 0.5d;
            double halfH = renderable.getImage().getHeight(null) * 0.5d;
            half = Math.max(halfW, halfH);
        }

        double minX = renderData.posX - half;
        double maxX = renderData.posX + half;
        double minY = renderData.posY - half;
        double maxY = renderData.posY + half;

        if (maxX < camLeft || minX > camRight) {
            return false; // ==== Out of horizontal bounds ======>>
        }

        if (maxY < camTop || minY > camBottom) {
            return false; // ==== Out of vertical bounds ======>>
        }

        return true;
    }

    // region setters (set***)
    private void setCameraClampLimits() {
        DoubleVector woldDim = this.view.getWorldDimension();

        if (woldDim == null || this.viewDimension == null) {
            this.maxCameraClampX = 0.0;
            this.maxCameraClampY = 0.0;
            return; // ======= No world or view dimensions info ======= >>
        }

        this.maxCameraClampX = Math.max(0.0, woldDim.x - this.getVisibleWorldWidth());
        this.maxCameraClampY = Math.max(0.0, woldDim.y - this.getVisibleWorldHeight());
    }
    // endregion

    // region updaters (update***)
    private void updateCamera() {
        if (!this.followLocalPlayer) {
            this.cameraX = clamp(this.cameraX, 0.0, this.maxCameraClampX);
            this.cameraY = clamp(this.cameraY, 0.0, this.maxCameraClampY);
            return;
        }

        String localPlayerId = this.view.getLocalPlayerId();
        DoubleVector worldDim = this.view.getWorldDimension();

        if (localPlayerId == null || localPlayerId.isEmpty() || this.viewDimension == null || worldDim == null) {
            return; // ======== No player or data to follow =======>>
        }

        RenderDTO playerData = this.view.getRenderData(localPlayerId);
        if (playerData == null) {
            Renderable localPlayerRenderable = this.getLocalPlayerRenderable();
            if (localPlayerRenderable != null) {
                playerData = localPlayerRenderable.getRenderData();
            }
        }

        if (playerData == null) {
            return;
        }

        double visibleWorldWidth = this.getVisibleWorldWidth();
        double visibleWorldHeight = this.getVisibleWorldHeight();
        double desiredX = playerData.posX - (visibleWorldWidth * 0.5d);
        double desiredY = playerData.posY - (visibleWorldHeight * 0.5d);
        boolean isZoomTransitioning = Math.abs(this.targetZoomFactor - this.zoomFactor) > 0.001d;

        if (isZoomTransitioning) {
            this.cameraX = desiredX;
            this.cameraY = desiredY;
        } else {
            double smoothing = CAMERA_SMOOTHING_FACTOR;
            BodyData playerBodyData = this.view.getBodyData(localPlayerId);
            if (playerBodyData != null && playerBodyData.getPhysicsValues() != null) {
                PhysicsValuesMDTO phyValues = playerBodyData.getPhysicsValues();
                double speed = Math.hypot(phyValues.speedX, phyValues.speedY);
                smoothing = clamp(
                        CAMERA_SMOOTHING_FACTOR + (speed * CAMERA_SPEED_SMOOTHING_SCALE),
                        CAMERA_SMOOTHING_FACTOR,
                        CAMERA_MAX_SMOOTHING_FACTOR);
            }

            this.cameraX += (desiredX - this.cameraX) * smoothing;
            this.cameraY += (desiredY - this.cameraY) * smoothing;
        }

        // // Clamp when camera goes out of world limits
        this.cameraX = clamp(cameraX, 0.0, this.maxCameraClampX);
        this.cameraY = clamp(cameraY, 0.0, this.maxCameraClampY);
    }

    private void updateDynamicRenderables(ArrayList<DynamicRenderDTO> renderDataList) {
        if (renderDataList == null || renderDataList.isEmpty()) {
            return; // ========= Nothing to render by the moment ... =========>>
        }

        // Update or create a renderable associated with each DBodyRenderInfoDTO
        long cFrame = this.currentFrame;

        for (DynamicRenderDTO newRenderData : renderDataList) {
            String entityId = newRenderData.entityId;
            if (entityId == null || entityId.isEmpty()) {
                newRenderData.release();
                continue; // ======= No entityId, cannot be rendered =======>>
            }

            DynamicRenderable renderable = this.dynamicRenderables.get(entityId);
            if (renderable == null) {
                newRenderData.release();
                continue; // ======= No renderable, cannot update render data =======>>
            }

            renderable.releaseRenderData();
            renderable.update(newRenderData, cFrame);
        }
    }
    // endregion

    private AffineTransform worldTranslate(Graphics2D gg) {
        long translateStart = this.rendererProfiler.startInterval(); // Profiler

        AffineTransform defaultTransform = gg.getTransform();

        double visibleWorldWidth = this.getVisibleWorldWidth();
        double visibleWorldHeight = this.getVisibleWorldHeight();
        double centerWorldX = this.cameraX + (visibleWorldWidth * 0.5d);
        double centerWorldY = this.cameraY + (visibleWorldHeight * 0.5d);

        gg.translate(this.viewDimension.x * 0.5d, this.viewDimension.y * 0.5d);
        gg.scale(this.zoomFactor, this.zoomFactor);
        gg.translate(-centerWorldX, -centerWorldY);

        this.rendererProfiler.stopInterval(
                RendererProfiler.METRIC_TRANSLATE, translateStart); // Profiler

        return defaultTransform;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private double getVisibleWorldWidth() {
        if (this.viewDimension == null) {
            return 0.0d;
        }

        return this.viewDimension.x / this.zoomFactor;
    }

    private double getVisibleWorldHeight() {
        if (this.viewDimension == null) {
            return 0.0d;
        }

        return this.viewDimension.y / this.zoomFactor;
    }

    private void updateZoom() {
        double delta = this.targetZoomFactor - this.zoomFactor;

        if (Math.abs(delta) <= 0.001d) {
            this.zoomFactor = this.targetZoomFactor;
            return;
        }

        this.zoomFactor += delta * ZOOM_SMOOTHING_FACTOR;
        this.zoomFactor = clamp(this.zoomFactor, MIN_ZOOM_FACTOR, MAX_ZOOM_FACTOR);

        this.setCameraClampLimits();
        this.cameraX = clamp(this.cameraX, 0.0, this.maxCameraClampX);
        this.cameraY = clamp(this.cameraY, 0.0, this.maxCameraClampY);
    }

    private int parseRequiredCells(String message) {
        if (message == null || message.isBlank()) {
            return -1;
        }

        String marker = "requires ";
        int markerIdx = message.indexOf(marker);
        if (markerIdx < 0) {
            return -1;
        }

        int start = markerIdx + marker.length();
        int end = start;

        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }

        if (end <= start) {
            return -1;
        }

        try {
            return Integer.parseInt(message.substring(start, end));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private Set<String> queryVisibleEntitiesSafe(double minX, double maxX, double minY, double maxY) {
        while (true) {
            try {
                return this.view.queryEntitiesInRegion(
                        minX, maxX,
                        minY, maxY,
                        this.scratchIdxBuffer,
                        this.visibleEntityIds);
            } catch (IllegalArgumentException ex) {
                int required = this.parseRequiredCells(ex.getMessage());
                if (required <= this.scratchIdxBuffer.length) {
                    throw ex;
                }

                int newSize = required + 512;
                if (newSize <= this.scratchIdxBuffer.length) {
                    throw ex;
                }

                this.scratchIdxBuffer = new int[newSize];
            }
        }
    }

    // *** INTERFACE IMPLEMENTATIONS ***

    // region Runnable
    @Override
    public void run() {
        this.createBufferStrategy(3);
        BufferStrategy bs = getBufferStrategy();

        if (bs == null) {
            throw new IllegalStateException(
                    "Renderer: BufferStrategy creation failed (canvas too large): "
                            + (int) this.viewDimension.x + "x" + (int) this.viewDimension.y);
        }

        while (true) {
            EngineState engineState = this.view.getEngineState();
            if (engineState == EngineState.STOPPED) {
                break; // ======= Engine stopped, exit render loop =======>>
            }

            // region Start Total Frame
            long totalFrameStart = this.rendererProfiler.startInterval();

            if (engineState == EngineState.ALIVE) { // TO-DO Pause condition

                this.currentFrame++;
                this.rendererProfiler.addFrame();
                this.updateZoom();

                // 1) Calculate Visible Entities (at frame -1)
                String localPlayerId = this.view.getLocalPlayerId();
                double minX, maxX, minY, maxY;
                double visibleWorldWidth = this.getVisibleWorldWidth();
                double visibleWorldHeight = this.getVisibleWorldHeight();

                if (localPlayerId == null || localPlayerId.isEmpty()) {
                    minX = 0;
                    minY = 0;
                    maxX = visibleWorldWidth;
                    maxY = visibleWorldHeight;
                } else {
                    RenderDTO renderLocalPlayerData = this.view.getRenderData(localPlayerId);

                    double halfVisibleWorldWidth = visibleWorldWidth * 0.5d;
                    double halfVisibleWorldHeight = visibleWorldHeight * 0.5d;

                    minX = renderLocalPlayerData.posX - halfVisibleWorldWidth;
                    minY = renderLocalPlayerData.posY - halfVisibleWorldHeight;
                    maxX = renderLocalPlayerData.posX + halfVisibleWorldWidth;
                    maxY = renderLocalPlayerData.posY + halfVisibleWorldHeight;
                }

                Set<String> visibleIds = this.queryVisibleEntitiesSafe(minX, maxX, minY, maxY);

                if (localPlayerId != null && !localPlayerId.isEmpty()) {
                    visibleIds.add(localPlayerId);
                }

                // 2) Snapshot of dynamic render data

                // region PROFILER L-2: Start Update Phase
                long updatePhaseStart = this.rendererProfiler.startInterval();

                ArrayList<DynamicRenderDTO> newRenderData = this.view.snapshotDynamicsRenderData(visibleIds);

                this.updateDynamicRenderables(newRenderData);

                this.rendererProfiler.stopInterval(RendererProfiler.METRIC_UPDATE_PHASE, updatePhaseStart);
                this.updateCamera();
                // endregion PROFILER L-2: Stop Update Phase

                // 3) Draw the scene with the current snapshot

                // region PROFILER L-2: Start Draw Phase
                long drawPhaseStart = this.rendererProfiler.startInterval();

                this.drawScene(bs, visibleIds);

                this.rendererProfiler.stopInterval(RendererProfiler.METRIC_DRAW_PHASE, drawPhaseStart);
                // endregion PROFILER L-2: Stop Draw Phase

                this.view.syncInputState(); // To prevent staus keys inconsistencies
            }

            try {
                Thread.sleep(REFRESH_DELAY_IN_MILLIS);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }

            this.rendererProfiler.stopInterval(RendererProfiler.METRIC_TOTAL_FRAME, totalFrameStart);
            // endregion Stop Total Frame
        }
    }
    // endregion
}
