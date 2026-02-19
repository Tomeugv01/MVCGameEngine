package gamerules;

import java.util.List;

import engine.actions.ActionDTO;
import engine.actions.ActionType;
import engine.controller.ports.ActionsGenerator;
import engine.events.domain.ports.DomainEventType;
import engine.events.domain.ports.eventtype.CollisionEvent;
import engine.events.domain.ports.eventtype.DomainEvent;
import engine.events.domain.ports.eventtype.EmitEvent;
import engine.events.domain.ports.eventtype.LifeOver;
import engine.events.domain.ports.eventtype.LimitEvent;
import engine.model.bodies.ports.BodyType;

/**
 * Game rule set for an "Earth in center" style world where GRAVITY bodies
 * (e.g. Earth) behave as solid and indestructible obstacles.
 *
 * Current policy:
 * - World limits: rebound
 * - Collision with GRAVITY: only non-GRAVITY body dies
 * - Other collisions: both colliding non-decorator bodies die
 * - Emitter events: spawn body / projectile
 * - Life over: die
 */
public class SolidEarthCenterRule implements ActionsGenerator {

    @Override
    public void provideActions(List<DomainEvent> domainEvents, List<ActionDTO> actions) {
        if (domainEvents == null) {
            return;
        }

        for (DomainEvent event : domainEvents) {
            this.applyGameRules(event, actions);
        }
    }

    private void applyGameRules(DomainEvent event, List<ActionDTO> actions) {
        switch (event) {
            case LimitEvent limitEvent -> {
                ActionType action;

                switch (limitEvent.type) {
                    case REACHED_EAST_LIMIT:
                        action = ActionType.MOVE_REBOUND_IN_EAST;
                        break;
                    case REACHED_WEST_LIMIT:
                        action = ActionType.MOVE_REBOUND_IN_WEST;
                        break;
                    case REACHED_NORTH_LIMIT:
                        action = ActionType.MOVE_REBOUND_IN_NORTH;
                        break;
                    case REACHED_SOUTH_LIMIT:
                        action = ActionType.MOVE_REBOUND_IN_SOUTH;
                        break;
                    default:
                        action = ActionType.NO_MOVE;
                        break;
                }

                actions.add(new ActionDTO(
                        limitEvent.primaryBodyRef.id(),
                        limitEvent.primaryBodyRef.type(),
                        action,
                        event));
            }

            case LifeOver lifeOver -> actions.add(new ActionDTO(
                    lifeOver.primaryBodyRef.id(),
                    lifeOver.primaryBodyRef.type(),
                    ActionType.DIE,
                    event));

            case EmitEvent emitEvent -> {
                ActionType action = (emitEvent.type == DomainEventType.EMIT_REQUESTED)
                        ? ActionType.SPAWN_BODY
                        : ActionType.SPAWN_PROJECTILE;

                actions.add(new ActionDTO(
                        emitEvent.primaryBodyRef.id(),
                        emitEvent.primaryBodyRef.type(),
                        action,
                        event));
            }

            case CollisionEvent collisionEvent -> this.resolveCollision(collisionEvent, actions);

            default -> {
                // No action for unhandled event types
            }
        }
    }

    private void resolveCollision(CollisionEvent event, List<ActionDTO> actions) {
        BodyType primaryType = event.primaryBodyRef.type();
        BodyType secondaryType = event.secondaryBodyRef.type();

        if (primaryType == BodyType.DECORATOR || secondaryType == BodyType.DECORATOR) {
            return;
        }

        if (event.payload.haveImmunity) {
            return;
        }

        boolean primaryIsGravity = primaryType == BodyType.GRAVITY;
        boolean secondaryIsGravity = secondaryType == BodyType.GRAVITY;

        if (primaryIsGravity && secondaryIsGravity) {
            return;
        }

        if (primaryIsGravity ^ secondaryIsGravity) {
            if (!primaryIsGravity) {
                actions.add(new ActionDTO(
                        event.primaryBodyRef.id(),
                        event.primaryBodyRef.type(),
                        ActionType.MOVE_REBOUND_FROM_BODY,
                        event));
            } else {
                actions.add(new ActionDTO(
                        event.secondaryBodyRef.id(),
                        event.secondaryBodyRef.type(),
                        ActionType.MOVE_REBOUND_FROM_BODY,
                        event));
            }
            return;
        }

        actions.add(new ActionDTO(
                event.primaryBodyRef.id(),
                event.primaryBodyRef.type(),
                ActionType.DIE,
                event));

        actions.add(new ActionDTO(
                event.secondaryBodyRef.id(),
                event.secondaryBodyRef.type(),
                ActionType.DIE,
                event));
    }
}
