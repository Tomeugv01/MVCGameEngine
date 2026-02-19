package engine.model.physics.implementations;

import static java.lang.System.nanoTime;

import java.util.List;

import engine.model.bodies.impl.BodyProfiler;
import engine.model.physics.core.AbstractPhysicsEngine;
import engine.model.physics.ports.GravitySourceDTO;
import engine.model.physics.ports.GravitySourceProvider;
import engine.model.physics.ports.PhysicsValuesMDTO;

/**
 * Physics engine with central gravitational attraction.
 *
 * Acceleration model:
 * - Thrust contributes directional acceleration based on heading.
 * - Gravity contributes acceleration from each gravity source body.
 * - Source mass is estimated as massCoefficient * radius^3.
 * - Contribution magnitude for each source: a = sourceMass / r^2.
 */
public class CentralGravityPhysicsEngine extends AbstractPhysicsEngine {

    private final BodyProfiler profiler;
     private final GravitySourceProvider gravitySourceProvider;
     private volatile double massCoefficient;
     private volatile double minDistance;
    /**
     * When true, gravity is computed using {@code source.playerGravityMultiplier}
     * instead of {@code source.massMultiplier}.  Set for PLAYER bodies so that
     * planet gravity feels strong to the player without destabilising planet orbits.
     */
    private final boolean isPlayer;

    public CentralGravityPhysicsEngine(
            PhysicsValuesMDTO dto1,
            PhysicsValuesMDTO dto2,
            PhysicsValuesMDTO dto3,
            BodyProfiler profiler,
            GravitySourceProvider gravitySourceProvider,
            double massCoefficient,
            double minDistance,
            boolean isPlayer) {

        super(dto1, dto2, dto3);

        this.profiler = profiler;
        this.gravitySourceProvider = gravitySourceProvider;
        this.massCoefficient = Math.max(0.0d, massCoefficient);
        this.minDistance = Math.max(1.0d, minDistance);
        this.isPlayer = isPlayer;
    }

    /** Back-compat overload — isPlayer defaults to false. */
    public CentralGravityPhysicsEngine(
            PhysicsValuesMDTO dto1,
            PhysicsValuesMDTO dto2,
            PhysicsValuesMDTO dto3,
            BodyProfiler profiler,
            GravitySourceProvider gravitySourceProvider,
            double massCoefficient,
            double minDistance) {
        this(dto1, dto2, dto3, profiler, gravitySourceProvider, massCoefficient, minDistance, false);
    }

    @Override
    public void angularAccelerationInc(double angularAcc) {
        PhysicsValuesMDTO old = this.getPhysicsValues();

        nextPhyValues.update(
                old.timeStamp,
                old.posX, old.posY, old.angle,
                old.size,
                old.speedX, old.speedY,
                old.accX, old.accY,
                old.angularSpeed,
                old.angularAcc + angularAcc,
                old.thrust);

        this.setPhysicsValues(nextPhyValues);
    }

    @Override
    public PhysicsValuesMDTO calcNewPhysicsValues() {
        long dtStart = this.profiler.startInterval();
        PhysicsValuesMDTO phyVals = this.getPhysicsValues();

        long now = nanoTime();
        long elapsedNanos = now - phyVals.timeStamp;
        double dt = ((double) elapsedNanos) / 1_000_000_000.0d;

        if (dt <= 0.0d) {
            dt = 0.001d;
        } else if (dt > 0.5d) {
            dt = 0.5d;
        }

        this.profiler.stopInterval("PHYSICS_DT", dtStart);
        return integrateWithCentralGravity(phyVals, dt);
    }

    @Override
    public boolean isThrusting() {
        return this.isEffectiveThrusting(this.getPhysicsValues());
    }

    @Override
    public void reboundInEast(PhysicsValuesMDTO phyValues, double worldDim_x, double worldDim_y) {
        nextPhyValues.update(
                phyValues.timeStamp,
                0.0001d,
                phyValues.posY,
                phyValues.angle,
                phyValues.size,
                -phyValues.speedX,
                phyValues.speedY,
                phyValues.accX,
                phyValues.accY,
                phyValues.angularSpeed,
                phyValues.angularAcc,
                phyValues.thrust);

        this.setPhysicsValues(nextPhyValues);
    }

    @Override
    public void reboundInWest(PhysicsValuesMDTO phyValues, double worldDim_x, double worldDim_y) {
        nextPhyValues.update(
                phyValues.timeStamp,
                worldDim_x - 0.0001d,
                phyValues.posY,
                phyValues.angle,
                phyValues.size,
                -phyValues.speedX,
                phyValues.speedY,
                phyValues.accX,
                phyValues.accY,
                phyValues.angularSpeed,
                phyValues.angularAcc,
                phyValues.thrust);

        this.setPhysicsValues(nextPhyValues);
    }

    @Override
    public void reboundInNorth(PhysicsValuesMDTO phyValues, double worldDim_x, double worldDim_y) {
        nextPhyValues.update(
                phyValues.timeStamp,
                phyValues.posX,
                0.0001d,
                phyValues.angle,
                phyValues.size,
                phyValues.speedX,
                -phyValues.speedY,
                phyValues.accX,
                phyValues.accY,
                phyValues.angularSpeed,
                phyValues.angularAcc,
                phyValues.thrust);

        this.setPhysicsValues(nextPhyValues);
    }

    @Override
    public void reboundInSouth(PhysicsValuesMDTO phyValues, double worldDim_x, double worldDim_y) {
        nextPhyValues.update(
                phyValues.timeStamp,
                phyValues.posX,
                worldDim_y - 0.0001d,
                phyValues.angle,
                phyValues.size,
                phyValues.speedX,
                -phyValues.speedY,
                phyValues.accX,
                phyValues.accY,
                phyValues.angularSpeed,
                phyValues.angularAcc,
                phyValues.thrust);

        this.setPhysicsValues(nextPhyValues);
    }

    @Override
    public void setAngularSpeed(double angularSpeed) {
        PhysicsValuesMDTO old = this.getPhysicsValues();

        nextPhyValues.update(
                old.timeStamp,
                old.posX, old.posY, old.angle,
                old.size,
                old.speedX, old.speedY,
                old.accX, old.accY,
                angularSpeed,
                old.angularAcc,
                old.thrust);

        this.setPhysicsValues(nextPhyValues);
    }

    public void setMassCoefficient(double massCoefficient) {
        this.massCoefficient = Math.max(0.0d, massCoefficient);
    }

    public void setMinDistance(double minDistance) {
        this.minDistance = Math.max(1.0d, minDistance);
    }

    private PhysicsValuesMDTO integrateWithSourceGravity(PhysicsValuesMDTO phyVals, double dt) {
        long thrustStart = this.profiler.startInterval();
        double angleRad = Math.toRadians(phyVals.angle);
        double effectiveThrust = this.getEffectiveThrust(phyVals);

        double accThrustX = 0.0d;
        double accThrustY = 0.0d;
        if (effectiveThrust != 0.0d) {
            accThrustX = Math.cos(angleRad) * effectiveThrust;
            accThrustY = Math.sin(angleRad) * effectiveThrust;
        }

        this.profiler.stopInterval("PHYSICS_THRUST", thrustStart);

        double accGravX = 0.0d;
        double accGravY = 0.0d;

        List<GravitySourceDTO> gravitySources = this.gravitySourceProvider == null
                ? null
                : this.gravitySourceProvider.getGravitySources();

        if (gravitySources != null) {
            for (GravitySourceDTO source : gravitySources) {
                if (source == null || source.radius <= 0.0d) {
                    continue;
                }

                double dx = source.posX - phyVals.posX;
                double dy = source.posY - phyVals.posY;
                double distSq = dx * dx + dy * dy;

                double softDistance = Math.max(this.minDistance, source.radius);
                double softDistanceSq = softDistance * softDistance;
                if (distSq < softDistanceSq) {
                    distSq = softDistanceSq;
                }

                double dist = Math.sqrt(distSq);
                double mult = this.isPlayer ? source.playerGravityMultiplier : source.massMultiplier;
                double sourceMass = this.massCoefficient * source.radius * source.radius * source.radius
                        * mult;
                if (sourceMass <= 0.0d) {
                    continue;
                }

                double accMag = sourceMass / distSq;
                accGravX += accMag * (dx / dist);
                accGravY += accMag * (dy / dist);
            }
        }

        long linearStart = this.profiler.startInterval();
        double accX = accThrustX + accGravX;
        double accY = accThrustY + accGravY;

        double oldSpeedX = phyVals.speedX;
        double oldSpeedY = phyVals.speedY;
        double newSpeedX = oldSpeedX + accX * dt;
        double newSpeedY = oldSpeedY + accY * dt;

        double avgSpeedX = (oldSpeedX + newSpeedX) * 0.5d;
        double avgSpeedY = (oldSpeedY + newSpeedY) * 0.5d;

        double newPosX = phyVals.posX + avgSpeedX * dt;
        double newPosY = phyVals.posY + avgSpeedY * dt;
        this.profiler.stopInterval("PHYSICS_LINEAR", linearStart);

        long angularStart = this.profiler.startInterval();
        double newAngularSpeed = phyVals.angularSpeed + phyVals.angularAcc * dt;
        double newAngle = (
                phyVals.angle
                        + phyVals.angularSpeed * dt
                        + 0.5d * newAngularSpeed * dt * dt) % 360;
        this.profiler.stopInterval("PHYSICS_ANGULAR", angularStart);

        long dtoStart = this.profiler.startInterval();
        long newTimeStamp = phyVals.timeStamp + (long) (dt * 1_000_000_000.0d);

        nextPhyValues.update(
                newTimeStamp,
                newPosX,
                newPosY,
                newAngle,
                phyVals.size,
                newSpeedX,
                newSpeedY,
                accX,
                accY,
                newAngularSpeed,
                phyVals.angularAcc,
                effectiveThrust);

        this.profiler.stopInterval("PHYSICS_DTO", dtoStart);
        return nextPhyValues;
    }

    private PhysicsValuesMDTO integrateWithCentralGravity(PhysicsValuesMDTO phyVals, double dt) {
        return integrateWithSourceGravity(phyVals, dt);
    }
}
