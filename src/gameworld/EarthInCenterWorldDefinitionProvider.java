package gameworld;

import engine.assets.ports.AssetType;
import engine.model.bodies.ports.BodyType;
import engine.utils.helpers.DoubleVector;
import engine.world.core.AbstractWorldDefinitionProvider;

public final class EarthInCenterWorldDefinitionProvider extends AbstractWorldDefinitionProvider {

    // *** CONSTRUCTORS ***

    public EarthInCenterWorldDefinitionProvider(DoubleVector worldDimension, ProjectAssets assets) {
        super(worldDimension, assets);
    }
    // *** PROTECTED (alphabetical order) ***

    @Override
    protected void define() {

		this.setBackgroundStatic("back_12");

        // region Statics
        this.addGravityBody("planet_04", worldWidth / 2.0, worldHeight / 2.0, 1200);
        this.addGravityBody("moon_05", (worldWidth / 2.0) + 5200.0, (worldHeight / 2.0) - 1800.0, 650);
        // endregion

        // region Dynamic bodies
        this.addAsteroidPrototypeAnywhereRandomAsset(
                6, AssetType.ASTEROID,
                1, 40,
                10, 175,
                0, 150);
        // endregion

        // region Players
        double earthCenterX = worldWidth / 2.0;
        double earthCenterY = worldHeight / 2.0;
        double playerSpawnOffset = 1600.0;

        this.addSpaceshipRandomAsset(
            1,
            AssetType.SPACESHIP,
            180.0,
            1.0,
            90.0,
            earthCenterX + playerSpawnOffset,
            earthCenterY);

        this.addTrailEmitterCosmetic("stars_06", 100.0, BodyType.DECORATOR, 100.0);
        // endregion

        // region Weapons
        this.addWeaponPresetBulletRandomAsset(AssetType.BULLET);

        this.addWeaponPresetBurstRandomAsset(AssetType.BULLET);

        this.addWeaponPresetMineLauncherRandomAsset(AssetType.MINE);

        this.addWeaponPresetMissileLauncherRandomAsset(AssetType.MISSILE);
        // endregion
    }
}
