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
 * Game rule for a physically simulated solar system.
 *
 * Collision policy:
 *  - GRAVITY (sun):  indestructible — any non-GRAVITY body that collides
 *                    rebounds; the sun is never affected.
 *  - DYNAMIC (planet): solid — rebounds elastically off other planets, the sun,
 *                       and the player spacecraft.
 *                       Projectiles are absorbed (projectile dies, planet survives).
 *  - PLAYER:         rebounds off GRAVITY and DYNAMIC bodies; can still be
 *                    destroyed by enemy projectiles.
 *  - PROJECTILE:     destroyed on contact with GRAVITY, DYNAMIC, or PLAYER.
 *
 * Limit policy:    all bodies rebound at world edges (keeps planets in orbit area).
 * Life-over policy: the body dies.
 * Emit policy:     spawn body / spawn projectile as appropriate.
 */
public class OrbitalSolarSystemRule implements ActionsGenerator {

    // *** INTERFACE ***

    @Override
    public void provideActions(List<DomainEvent> domainEvents, List<ActionDTO> actions) {
        if (domainEvents == null) {
            return;
        }
        for (DomainEvent event : domainEvents) {
            applyGameRules(event, actions);
        }
    }

    // *** PRIVATE ***

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

            case CollisionEvent collisionEvent -> resolveCollision(collisionEvent, actions);

            default -> { /* unhandled event type — no action */ }
        }
    }

    private void resolveCollision(CollisionEvent event, List<ActionDTO> actions) {
        BodyType primary   = event.primaryBodyRef.type();
        BodyType secondary = event.secondaryBodyRef.type();

        // Decorators are visual-only — never participate in gameplay collisions
        if (primary == BodyType.DECORATOR || secondary == BodyType.DECORATOR) {
            return;
        }

        // Projectile immunity prevents self-collision immediately after firing
        if (event.payload.haveImmunity) {
            return;
        }

        boolean primaryIsGravity   = (primary   == BodyType.GRAVITY);
        boolean secondaryIsGravity = (secondary == BodyType.GRAVITY);

        // --- Sun (GRAVITY) is indestructible ---
        // Any non-GRAVITY body that touches it rebounds; the sun is untouched.
        if (primaryIsGravity || secondaryIsGravity) {
            if (!primaryIsGravity) {
                actions.add(new ActionDTO(
                        event.primaryBodyRef.id(), primary,
                        ActionType.MOVE_REBOUND_FROM_BODY, event));
            }
            if (!secondaryIsGravity) {
                actions.add(new ActionDTO(
                        event.secondaryBodyRef.id(), secondary,
                        ActionType.MOVE_REBOUND_FROM_BODY, event));
            }
            return;
        }

        boolean primaryIsDynamic   = (primary   == BodyType.DYNAMIC);
        boolean secondaryIsDynamic = (secondary == BodyType.DYNAMIC);

        boolean primaryIsProjectile   = (primary   == BodyType.PROJECTILE);
        boolean secondaryIsProjectile = (secondary == BodyType.PROJECTILE);

        // --- Solid planets (DYNAMIC) ---

        // Planet vs Planet: elastic rebound — both survive
        if (primaryIsDynamic && secondaryIsDynamic) {
            actions.add(new ActionDTO(
                    event.primaryBodyRef.id(), primary,
                    ActionType.MOVE_REBOUND_FROM_BODY, event));
            actions.add(new ActionDTO(
                    event.secondaryBodyRef.id(), secondary,
                    ActionType.MOVE_REBOUND_FROM_BODY, event));
            return;
        }

        // Planet vs Projectile: projectile absorbed by solid surface
        if (primaryIsDynamic && secondaryIsProjectile) {
            actions.add(new ActionDTO(
                    event.secondaryBodyRef.id(), secondary,
                    ActionType.DIE, event));
            return;
        }
        if (secondaryIsDynamic && primaryIsProjectile) {
            actions.add(new ActionDTO(
                    event.primaryBodyRef.id(), primary,
                    ActionType.DIE, event));
            return;
        }

        // Planet vs Player (or Player vs Planet): both rebound — planets are solid
        if (primaryIsDynamic || secondaryIsDynamic) {
            actions.add(new ActionDTO(
                    event.primaryBodyRef.id(), primary,
                    ActionType.MOVE_REBOUND_FROM_BODY, event));
            actions.add(new ActionDTO(
                    event.secondaryBodyRef.id(), secondary,
                    ActionType.MOVE_REBOUND_FROM_BODY, event));
            return;
        }

        // All other collisions (Player vs Projectile, Projectile vs Projectile, etc.): both die
        actions.add(new ActionDTO(
                event.primaryBodyRef.id(), primary,
                ActionType.DIE, event));
        actions.add(new ActionDTO(
                event.secondaryBodyRef.id(), secondary,
                ActionType.DIE, event));
    }
}
