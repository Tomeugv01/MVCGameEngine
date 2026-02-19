package gameworld;

import engine.model.bodies.ports.BodyType;
import engine.utils.helpers.DoubleVector;
import engine.world.core.AbstractWorldDefinitionProvider;

public final class SolarSystemWorldDefinitionProvider extends AbstractWorldDefinitionProvider {

    private static final double BODY_SIZE_SCALE = 0.40d;
    private static final double PLAYER_SIZE_SCALE = 0.50d;

    public SolarSystemWorldDefinitionProvider(DoubleVector worldDimension, ProjectAssets assets) {
        super(worldDimension, assets);
    }

    @Override
    protected void define() {
        this.setBackgroundStatic("back_12");
        this.addDecorator("stars_07", worldWidth * 0.5d, worldHeight * 0.5d, 1800.0d);
        this.addDecorator("galaxy_01", worldWidth * 0.22d, worldHeight * 0.28d, 1400.0d);
        this.addDecorator("stardust_01", worldWidth * 0.78d, worldHeight * 0.74d, 1200.0d);

        DoubleVector sunPos = this.orbitPosition(0.0d, 0.0d);
        this.addGravityBody("sun_02", sunPos.x, sunPos.y, this.scaledBody(2600.0d));

        this.addStaticOrbitalBody("planet_01", 5500.0d, 20.0d, this.scaledBody(320.0d));
        this.addStaticOrbitalBody("planet_02", 8200.0d, 60.0d, this.scaledBody(500.0d));

        DoubleVector earthPos = this.orbitPosition(12000.0d, 105.0d);
        this.addGravityBody("planet_04", earthPos.x, earthPos.y, this.scaledBody(560.0d));

        DoubleVector moonPos = new DoubleVector(
                earthPos.x + 1300.0d,
                earthPos.y - 350.0d);
        this.addGravityBody("moon_05", moonPos.x, moonPos.y, this.scaledBody(180.0d));

        this.addStaticOrbitalBody("planet_03", 16000.0d, 145.0d, this.scaledBody(420.0d));
        this.addStaticOrbitalBody("planet_11", 22500.0d, 210.0d, this.scaledBody(1300.0d));
        this.addStaticOrbitalBody("planet_15", 29000.0d, 265.0d, this.scaledBody(1100.0d));
        this.addStaticOrbitalBody("planet_18", 34000.0d, 315.0d, this.scaledBody(820.0d));
        this.addStaticOrbitalBody("planet_21", 37800.0d, 350.0d, this.scaledBody(760.0d));

        this.addSpaceship(
                "spaceship_10",
                earthPos.x + 1400.0d,
                earthPos.y,
                this.scaledPlayer(90.0d),
                180.0d,
                1.0d);

        this.addTrailEmitterCosmetic("stars_06", 100.0d, BodyType.DECORATOR, 100.0d);

        this.addWeaponPresetBulletRandomAsset(engine.assets.ports.AssetType.BULLET);
        this.addWeaponPresetBurstRandomAsset(engine.assets.ports.AssetType.BULLET);
        this.addWeaponPresetMineLauncherRandomAsset(engine.assets.ports.AssetType.MINE);
        this.addWeaponPresetMissileLauncherRandomAsset(engine.assets.ports.AssetType.MISSILE);
    }

    private void addStaticOrbitalBody(String assetId, double radius, double angleDeg, double size) {
        DoubleVector pos = this.orbitPosition(radius, angleDeg);
        this.addGravityBody(assetId, pos.x, pos.y, size);
    }

    private double scaledBody(double size) {
        return Math.max(8.0d, size * BODY_SIZE_SCALE);
    }

    private double scaledPlayer(double size) {
        return Math.max(18.0d, size * PLAYER_SIZE_SCALE);
    }

    private DoubleVector orbitPosition(double radius, double angleDeg) {
        double centerX = worldWidth * 0.5d;
        double centerY = worldHeight * 0.5d;
        double radians = Math.toRadians(angleDeg);

        return new DoubleVector(
                centerX + (Math.cos(radians) * radius),
                centerY + (Math.sin(radians) * radius));
    }
}
