
import engine.controller.impl.Controller;
import engine.controller.ports.ActionsGenerator;
import engine.model.impl.Model;
import engine.utils.helpers.DoubleVector;
import engine.view.core.View;
import engine.world.ports.WorldDefinition;
import engine.world.ports.WorldDefinitionProvider;
import gameworld.ProjectAssets;

public class Main {

	public static void main(String[] args) {

		// region Graphics configuration
		// System.setProperty("sun.java2d.uiScale", "1.0");
		System.setProperty("sun.java2d.opengl", "true");
		System.setProperty("sun.java2d.d3d", "false"); // OpenGL
		// endregion
		
		// region Dimensions and limits
		// Due a recognized issue with BufferStrategy when
		// Canvas size > screen size causes BufferStrategy to fail (blank window)
		// in that case engine whill throw an error and exit.
		//
		// => **********************************************************
		// => *** Keep viewDimension smaller than actual screen size ***
		// => *** or... no set viewDimension                         ***
		// => **********************************************************
		DoubleVector viewDimension = new DoubleVector(1900, 1000);
		DoubleVector worldDimension = new DoubleVector(80000, 80000);
		// endregion
		
		 int maxBodies = 1000;
		 int maxAsteroidCreationDelay = 0; // Used by AIBasicSpawner

		ProjectAssets projectAssets = new ProjectAssets();

		// ActionsGenerator gameRules = new gamerules.LimitRebound();
		// ActionsGenerator gameRules = new gamerules.ReboundAndCollision();
		// ActionsGenerator gameRules = new gamerules.InLimitsGoToCenter();
		// ActionsGenerator gameRules = new gamerules.SolidEarthCenterRule();
		ActionsGenerator gameRules = new gamerules.OrbitalSolarSystemRule();

		// *** WORLD DEFINITION PROVIDER ***
		// WorldDefinitionProvider worldProv = new gameworld.RandomWorldDefinitionProvider(
		// 		worldDimension, projectAssets);
		WorldDefinitionProvider worldProv = new gameworld.SolarSystemWorldDefinitionProvider(
				worldDimension, projectAssets);

		// *** CORE ENGINE ***

		// region Controller
		Controller controller = new Controller(
				worldDimension, viewDimension, 
				new View(), new Model(worldDimension, maxBodies),
				gameRules);

		controller.activate();
		// endregion

		// *** SCENE ***

		// region World definition
		WorldDefinition worldDef = worldProv.provide();
		// endregion

		// region Level generator (Level***)
		new gamelevel.LevelSolarSystem(controller, worldDef);
		// endregion

		// region AI generator (AI***)
		// Asteroid spawner is disabled for orbital solar system — random bodies
		// would disturb the carefully calibrated planetary orbits.
		boolean asteroidSpawnerEnabled = !(gameRules instanceof gamerules.OrbitalSolarSystemRule)
				&& !(gameRules instanceof gamerules.SolidEarthCenterRule);
		if (asteroidSpawnerEnabled) {
			new gameai.AIBasicSpawner(controller, worldDef, maxAsteroidCreationDelay).activate();
		} else {
			System.out.println("Main: Asteroid spawner disabled for " + gameRules.getClass().getSimpleName());
		}
		// endregion
	}
}
