package gameworld;

import engine.model.bodies.ports.BodyType;
import engine.utils.helpers.DoubleVector;
import engine.world.core.AbstractWorldDefinitionProvider;

public final class SolarSystemWorldDefinitionProvider extends AbstractWorldDefinitionProvider {

    private static final double BODY_SIZE_SCALE   = 0.40d;
    private static final double PLAYER_SIZE_SCALE = 0.50d;

    /**
     * Gravitational parameter G*M_sun used to derive circular-orbit speeds.
     *
     * Must stay in sync with:
     *   Model.GRAVITY_MASS_COEFFICIENT = 0.08
     *   GravitySourceProvider radius   = body.size * 0.5
     *
     * Formula: GM = massCoeff * (sunHalfRadius)^3
     *        = 0.08 * (scaledBody(2600) / 2)^3
     *        = 0.08 * 520^3
     */
    private static final double GRAVITY_MASS_COEFF = 0.08d;
    private static final double SUN_HALF_RADIUS    = 2600.0d * BODY_SIZE_SCALE * 0.5d; // 520
    private static final double SUN_GM             =
            GRAVITY_MASS_COEFF * SUN_HALF_RADIUS * SUN_HALF_RADIUS * SUN_HALF_RADIUS;
    /**
     * MUST stay in sync with Model.DYNAMIC_PLANET_MASS_MULT.
     * Used here only to compute a moon's circular-orbit speed around its host planet,
     * because the physics engine multiplies planet GM by this factor for all dynamic bodies.
     */
    private static final double DYNAMIC_PLANET_MASS_MULT = 2.0d;

    public SolarSystemWorldDefinitionProvider(DoubleVector worldDimension, ProjectAssets assets) {
        super(worldDimension, assets);
    }

    @Override
    protected void define() {
        this.setBackgroundStatic("back_12");
        this.addDecorator("stars_07", worldWidth * 0.5d, worldHeight * 0.5d, 1800.0d);
        this.addDecorator("galaxy_01", worldWidth * 0.22d, worldHeight * 0.28d, 1400.0d);
        this.addDecorator("stardust_01", worldWidth * 0.78d, worldHeight * 0.74d, 1200.0d);

        // --- Sun: static GRAVITY body — the only gravity source in the scene ---
        DoubleVector sunPos = this.orbitPosition(0.0d, 0.0d);
        this.addGravityBody("sun_02", sunPos.x, sunPos.y, this.scaledBody(2600.0d));

        // --- Orbiting planets: DYNAMIC bodies with pre-calculated circular-orbit velocities ---
        //
        // Size discipline: GM_eff = 0.08*(size/2)^3 * DYNAMIC_PLANET_MASS_MULT (currently 2.0).
        // Sun GM = 11,248,640.  Outer planets spaced so neighbour perturbation < ~15% solar gravity.
        //
        //  Seed → scaled (BODY_SIZE_SCALE=0.40) → radius → GM  ×2 eff    orbital radius
        //  planet_01:  160 →  64 → 32 →   2,621  →    5,243           r =  5,500
        //  planet_02:  250 → 100 → 50 →  10,000  →   20,000           r =  8,200
        //  planet_04:  310 → 124 → 62 →  19,047  →   38,094  Earth    r = 12,000
        //  moon_05:     50 →  20 → 10 →      80   orbits Earth at +200 units (0.16 × Hill sphere)
        //  planet_03:  220 →  88 → 44 →   6,839  →   13,678  Mars     r = 16,000
        //  planet_11:  400 → 160 → 80 →  40,960  →   81,920  Jupiter  r = 21,000
        //  planet_15:  380 → 152 → 76 →  35,070  →   70,140  Saturn   r = 28,000
        //  planet_18:  300 → 120 → 60 →  17,280  →   34,560  Uranus   r = 35,000
        //  Neptune omitted: insufficient separation at world-scale orbital limits.
        this.addDynamicOrbitalBody("planet_01",  5500.0d,  20.0d, this.scaledBody(160.0d));
        this.addDynamicOrbitalBody("planet_02",  8200.0d,  60.0d, this.scaledBody(250.0d));
        this.addDynamicOrbitalBody("planet_04", 12000.0d, 105.0d, this.scaledBody(310.0d));

        DoubleVector earthPos = this.orbitPosition(12000.0d, 105.0d);

        // Moon at 200 units from Earth (0.16 × Hill sphere radius ≈ 1249 u).
        // Deep inside the stable zone; at 500 the solar tide destabilised the orbit.
        // Moon seed kept small (50 → r=10) so its GM_eff = 0.08*10³*2 = 160,
        // exerting only 0.016% of solar gravity on Earth — negligible drift.
        this.addBodyOrbitingPlanet("moon_05",
                12000.0d, 105.0d, this.scaledBody(310.0d),
                200.0d, 0.0d,
                this.scaledBody(50.0d));

        this.addDynamicOrbitalBody("planet_03", 16000.0d, 145.0d, this.scaledBody(220.0d));
        this.addDynamicOrbitalBody("planet_11", 21000.0d, 210.0d, this.scaledBody(400.0d));
        this.addDynamicOrbitalBody("planet_15", 28000.0d, 265.0d, this.scaledBody(380.0d));
        this.addDynamicOrbitalBody("planet_18", 35000.0d, 315.0d, this.scaledBody(300.0d));
        // Neptune omitted: insufficient orbital separation within world-scale limits.

        // --- Player spaceship --- starts in near-circular solar orbit just outside Earth ---
        double[] playerVel = this.orbitalVelocityAtPosition(earthPos.x + 1400.0d, earthPos.y);
        this.addSpaceship(
                "spaceship_10",
                earthPos.x + 1400.0d,
                earthPos.y,
                this.scaledPlayer(64.0d),
                180.0d,
                1.0d,
                playerVel[0], playerVel[1]);

        this.addTrailEmitterCosmetic("stars_06", 100.0d, BodyType.DECORATOR, 100.0d);

        this.addWeaponPresetBulletRandomAsset(engine.assets.ports.AssetType.BULLET);
        this.addWeaponPresetBurstRandomAsset(engine.assets.ports.AssetType.BULLET);
        this.addWeaponPresetMineLauncherRandomAsset(engine.assets.ports.AssetType.MINE);
        this.addWeaponPresetMissileLauncherRandomAsset(engine.assets.ports.AssetType.MISSILE);
    }

    // *** PRIVATE ***

    /**
     * Places an orbital body at the default circular-orbit position for the
     * given radius and angle, assigning the tangential velocity required for a
     * stable circular orbit around the sun.
     */
    private void addDynamicOrbitalBody(String assetId, double orbitalRadius, double angleDeg, double size) {
        DoubleVector pos = this.orbitPosition(orbitalRadius, angleDeg);
        double[] vel     = this.circularOrbitVelocity(orbitalRadius, angleDeg);
        this.addOrbitalBody(assetId, pos.x, pos.y, size, vel[0], vel[1], 0.0d);
    }

    /**
     * Places an orbital body at an arbitrary world position, computing its
     * orbital radius and angle from the sun centre and deriving the tangential
     * velocity for a circular orbit.
     */
    private void addOrbitalBodyAtPos(String assetId, DoubleVector pos, double size) {
        double centerX = worldWidth  * 0.5d;
        double centerY = worldHeight * 0.5d;
        double dx      = pos.x - centerX;
        double dy      = pos.y - centerY;
        double radius  = Math.sqrt(dx * dx + dy * dy);
        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
        double[] vel   = this.circularOrbitVelocity(radius, angleDeg);
        this.addOrbitalBody(assetId, pos.x, pos.y, size, vel[0], vel[1], 0.0d);
    }

    /**
     * Places a satellite that orbits a host planet.
     * The host itself orbits the sun at the given orbital parameters.
     * Satellite velocity = host solar-orbit velocity + tangential velocity around host (CCW).
     *
     * @param hostOrbitalRadius host distance from sun centre (world units)
     * @param hostAngleDeg      host angle on solar orbit (degrees)
     * @param hostScaledSize    full rendered size of host — used to derive host GM
     * @param moonOffsetX       satellite offset from host, x-axis (world units)
     * @param moonOffsetY       satellite offset from host, y-axis (world units)
     * @param moonSize          rendered size of the satellite
     */
    private void addBodyOrbitingPlanet(
            String assetId,
            double hostOrbitalRadius, double hostAngleDeg, double hostScaledSize,
            double moonOffsetX, double moonOffsetY,
            double moonSize) {

        DoubleVector hostPos = this.orbitPosition(hostOrbitalRadius, hostAngleDeg);
        double[] hostVel     = this.circularOrbitVelocity(hostOrbitalRadius, hostAngleDeg);

        double moonR     = Math.sqrt(moonOffsetX * moonOffsetX + moonOffsetY * moonOffsetY);
        double hostHalfR = hostScaledSize * 0.5d;
        // hostGM must use DYNAMIC_PLANET_MASS_MULT because the physics engine applies that
        // factor to every dynamic body's mass — without it the moon orbits too slowly and drifts.
        double hostGM    = GRAVITY_MASS_COEFF * hostHalfR * hostHalfR * hostHalfR
                         * DYNAMIC_PLANET_MASS_MULT;
        double vOrbit    = Math.sqrt(hostGM / Math.max(1.0d, moonR));

        // CCW tangent derived from host-to-moon unit vector
        double nx    = moonOffsetX / moonR;
        double ny    = moonOffsetY / moonR;
        double relVx = -ny * vOrbit;
        double relVy =  nx * vOrbit;

        this.addOrbitalBody(assetId,
                hostPos.x + moonOffsetX, hostPos.y + moonOffsetY,
                moonSize,
                hostVel[0] + relVx, hostVel[1] + relVy,
                0.0d);
    }

    /**
     * Returns the [speedX, speedY] for a CCW circular solar orbit
     * at the given world position (measured from world centre).
     */
    private double[] orbitalVelocityAtPosition(double worldPosX, double worldPosY) {
        double cx = this.worldWidth  * 0.5d;
        double cy = this.worldHeight * 0.5d;
        double dx = worldPosX - cx;
        double dy = worldPosY - cy;
        double r  = Math.sqrt(dx * dx + dy * dy);
        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
        return this.circularOrbitVelocity(r, angleDeg);
    }

    /**
     * Returns the [speedX, speedY] components for a counter-clockwise circular
     * orbit at the given radius around the sun.
     *
     *   v  = sqrt(GM / r)
     *   vx = -sin(θ) * v
     *   vy =  cos(θ) * v
     */
    private double[] circularOrbitVelocity(double orbitalRadius, double angleDeg) {
        double v   = Math.sqrt(SUN_GM / Math.max(1.0d, orbitalRadius));
        double rad = Math.toRadians(angleDeg);
        return new double[] { -Math.sin(rad) * v, Math.cos(rad) * v };
    }

    private double scaledBody(double size) {
        return Math.max(8.0d, size * BODY_SIZE_SCALE);
    }

    private double scaledPlayer(double size) {
        return Math.max(18.0d, size * PLAYER_SIZE_SCALE);
    }

    private DoubleVector orbitPosition(double radius, double angleDeg) {
        double centerX = worldWidth  * 0.5d;
        double centerY = worldHeight * 0.5d;
        double radians = Math.toRadians(angleDeg);
        return new DoubleVector(
                centerX + Math.cos(radians) * radius,
                centerY + Math.sin(radians) * radius);
    }
}
