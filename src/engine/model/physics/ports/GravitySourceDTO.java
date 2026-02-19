package engine.model.physics.ports;

public class GravitySourceDTO {

    public final String bodyId;
    public final double posX;
    public final double posY;
    public final double radius;

    /**
     * Multiplier applied to this source's computed mass when evaluating
     * planet-to-planet gravitational acceleration.
     * Kept low (≤ 2.0) to preserve orbital stability between planets.
     */
    public final double massMultiplier;

    /**
     * Multiplier applied to this source's computed mass when evaluating
     * gravitational acceleration ON THE PLAYER (and the player trajectory trace).
     * Intentionally much higher than {@code massMultiplier} so that planet
     * gravity wells feel strong and visceral to the player without introducing
     * the inter-planet perturbations that would destabilise orbits.
     */
    public final double playerGravityMultiplier;

    /**
     * Current velocity of this source body (world units/s).
     * Used by the renderer to simulate the source's motion when drawing
     * a planet-relative trajectory trace for the player.
     */
    public final double velX;
    public final double velY;

    /** Full constructor. */
    public GravitySourceDTO(String bodyId, double posX, double posY, double radius,
            double massMultiplier, double playerGravityMultiplier,
            double velX, double velY) {
        this.bodyId = bodyId;
        this.posX = posX;
        this.posY = posY;
        this.radius = radius;
        this.massMultiplier = Math.max(0.0d, massMultiplier);
        this.playerGravityMultiplier = Math.max(0.0d, playerGravityMultiplier);
        this.velX = velX;
        this.velY = velY;
    }

    /** Back-compat: 5-arg — playerGravityMultiplier = massMultiplier, vel = 0. */
    public GravitySourceDTO(String bodyId, double posX, double posY, double radius,
            double massMultiplier) {
        this(bodyId, posX, posY, radius, massMultiplier, massMultiplier, 0.0d, 0.0d);
    }

    /** Convenience constructor — both multipliers = 1.0, vel = 0. */
    public GravitySourceDTO(String bodyId, double posX, double posY, double radius) {
        this(bodyId, posX, posY, radius, 1.0d, 1.0d, 0.0d, 0.0d);
    }
}
