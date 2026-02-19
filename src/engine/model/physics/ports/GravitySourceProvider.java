package engine.model.physics.ports;

import java.util.List;

public interface GravitySourceProvider {

    List<GravitySourceDTO> getGravitySources();
}
