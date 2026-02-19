package gamelevel;

import java.util.ArrayList;

import engine.controller.ports.WorldManager;
import engine.generators.AbstractLevelGenerator;
import engine.world.ports.DefEmitterDTO;
import engine.world.ports.DefItem;
import engine.world.ports.DefItemDTO;
import engine.world.ports.WorldDefinition;

/**
 * Level generator for the dynamic solar system scene.
 *
 * Differs from LevelBasic in that it also materialises the asteroids list as
 * DYNAMIC bodies so that planets (stored there with pre-calculated orbital
 * velocities) are handed to the physics engine and actually orbit the sun.
 *
 * Pipeline:
 *   createDecorators  — background stars / nebulae
 *   createStatics     — sun (GRAVITY, the only gravity source)
 *   createDynamics    — planets / moon (DYNAMIC, orbit via CentralGravityPhysicsEngine)
 *   createPlayers     — spaceship with weapons and trail
 */
public class LevelSolarSystem extends AbstractLevelGenerator {

    // *** CONSTRUCTOR ***

    public LevelSolarSystem(WorldManager worldManager, WorldDefinition worldDef) {
        super(worldManager, worldDef);
    }

    // *** PROTECTED — level creation steps ***

    @Override
    protected void createDecorators() {
        for (DefItem def : this.getWorldDefinition().spaceDecorators) {
            this.addDecoratorIntoTheGame(this.defItemToDTO(def));
        }
    }

    /**
     * Creates only GRAVITY-type bodies (sun).
     * These become the gravity sources queried by CentralGravityPhysicsEngine.
     */
    @Override
    protected void createStatics() {
        for (DefItem def : this.getWorldDefinition().gravityBodies) {
            this.addStaticIntoTheGame(this.defItemToDTO(def));
        }
    }

    /**
     * Creates orbiting planets and moon as DYNAMIC bodies.
     * Each body carries a pre-calculated tangential velocity so it enters a
     * stable circular orbit around the sun under CentralGravityPhysicsEngine.
     */
    @Override
    protected void createDynamics() {
        for (DefItem def : this.getWorldDefinition().asteroids) {
            this.addDynamicIntoTheGame(this.defItemToDTO(def));
        }
    }

    @Override
    protected void createPlayers() {
        WorldDefinition worldDef = this.getWorldDefinition();
        ArrayList<DefEmitterDTO> weaponDefs = worldDef.weapons;
        ArrayList<DefEmitterDTO> trailDefs  = worldDef.trailEmitters;

        for (DefItem def : worldDef.spaceships) {
            DefItemDTO body = this.defItemToDTO(def);
            this.addLocalPlayerIntoTheGame(body, weaponDefs, trailDefs);
        }
    }
}
