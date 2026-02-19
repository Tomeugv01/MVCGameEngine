package engine.model.physics.ports;

public class GravitySourceDTO {

    public final String bodyId;
    public final double posX;
    public final double posY;
    public final double radius;

    public GravitySourceDTO(String bodyId, double posX, double posY, double radius) {
        this.bodyId = bodyId;
        this.posX = posX;
        this.posY = posY;
        this.radius = radius;
    }
}
