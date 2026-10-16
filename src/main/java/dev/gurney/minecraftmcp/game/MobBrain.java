package dev.gurney.minecraftmcp.game;

import dev.gurney.minecraftmcp.mixin.AvoidEntityGoalAccessor;
import dev.gurney.minecraftmcp.mixin.LookAtPlayerGoalAccessor;
import dev.gurney.minecraftmcp.mixin.MobAccessor;
import dev.gurney.minecraftmcp.mixin.NearestAttackableTargetGoalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Describes what a mob's AI is doing, read from the integrated server, where the AI runs. Newer
 * mobs (villagers, piglins, axolotls, goats, frogs, wardens...) think with a brain: activities,
 * behaviours and memories filled in by sensors. Older ones pick from prioritized goals.
 */
public final class MobBrain {
	/** Far enough to watch a mob from a distance without disturbing it. */
	private static final double LOOK_DISTANCE = 64;
	private static final int MAX_LISTED_ENTITIES = 10;

	private MobBrain() {
	}

	/**
	 * Describes the mob given by UUID or entity type (the nearest of that type), or without one, the
	 * one the player is looking at.
	 */
	public static String describe(String entity) {
		UUID looked = entity == null ? lookedAtEntity() : null;
		UUID uuid = looked != null ? looked : parseUuid(entity);
		EntityType<?> type = looked == null && uuid == null ? parseType(entity) : null;
		return OperatorAccess.readOnServer(player -> {
			LivingEntity mob = uuid != null ? byUuid(player, uuid) : nearest(player, type);
			return describe(player.level(), mob);
		});
	}

	private static UUID lookedAtEntity() {
		return GameThreads.onClient(() -> {
			WorldQuery.requireLevel();
			Minecraft minecraft = Minecraft.getInstance();
			if (ProjectileUtil.getHitResultOnViewVector(minecraft.player, candidate -> candidate instanceof LivingEntity && !candidate.isSpectator(), LOOK_DISTANCE)
				instanceof EntityHitResult hit) {
				return hit.getEntity().getUUID();
			}
			throw new ToolException("the player isn't looking at a mob within " + (int) LOOK_DISTANCE
				+ " blocks; aim at one, or give entity as a UUID (from get_entities) or an entity type");
		});
	}

	private static UUID parseUuid(String entity) {
		try {
			return UUID.fromString(entity);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static EntityType<?> parseType(String entity) {
		Identifier id = Identifier.tryParse(entity);
		if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
			throw new ToolException("entity must be a UUID or an entity type like minecraft:villager, not \"" + entity + "\"");
		}
		return BuiltInRegistries.ENTITY_TYPE.getValue(id);
	}

	private static LivingEntity byUuid(ServerPlayer player, UUID uuid) {
		Entity entity = player.level().getEntity(uuid);
		if (entity == null) {
			throw new ToolException("no entity with UUID " + uuid + " in the player's dimension");
		}
		if (!(entity instanceof LivingEntity living)) {
			throw new ToolException(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " isn't a mob, so it has no AI");
		}
		return living;
	}

	private static LivingEntity nearest(ServerPlayer player, EntityType<?> type) {
		AABB area = player.getBoundingBox().inflate(WorldQuery.MAX_ENTITY_RADIUS);
		return player.level().getEntities(player, area, entity -> entity.getType() == type && entity instanceof LivingEntity).stream()
			.map(LivingEntity.class::cast)
			.min(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)))
			.orElseThrow(() -> new ToolException("no " + BuiltInRegistries.ENTITY_TYPE.getKey(type) + " within " + WorldQuery.MAX_ENTITY_RADIUS + " blocks"));
	}

	private static String describe(ServerLevel level, LivingEntity mob) {
		StringBuilder out = new StringBuilder();
		out.append(entity(mob, true));
		out.append(String.format(Locale.ROOT, ", health %.1f/%.1f; game time %d%n", mob.getHealth(), mob.getMaxHealth(), level.getGameTime()));

		Mob asMob = mob instanceof Mob m ? m : null;
		if (asMob != null && asMob.isNoAi()) {
			out.append("its AI is turned off (NoAI), so it doesn't think or move on its own\n");
		}
		if (asMob != null) {
			LivingEntity target = asMob.getTarget();
			out.append("target: ").append(target == null ? "none" : entity(target, true)).append('\n');
			out.append("path: ").append(path(asMob)).append('\n');
		}

		Brain<?> brain = mob.getBrain();
		if (!brain.isBrainDead()) {
			appendBrain(out, level, brain);
		}
		if (asMob != null) {
			appendGoals(out, "goals", asMob.getGoalSelector());
			appendGoals(out, "target goals (choosing whom to attack)", ((MobAccessor) asMob).minecraftMcp$targetSelector());
		}
		if (brain.isBrainDead() && (asMob == null || asMob.getGoalSelector().getAvailableGoals().isEmpty())) {
			out.append("it has no brain or goals");
			out.append(mob instanceof Player ? " (it's a player)\n" : "\n");
		}
		return out.toString();
	}

	@SuppressWarnings("deprecation") // Deprecated only to discourage use outside debugging, which this is.
	private static void appendBrain(StringBuilder out, ServerLevel level, Brain<?> brain) {
		Set<Activity> active = brain.getActiveActivities();
		out.append("brain:\n");
		out.append("  activities: ").append(active.stream().map(Activity::getName).sorted().collect(Collectors.joining(", "))).append('\n');
		List<String> behaviors = brain.getRunningBehaviors().stream().map(MobBrain::behaviorName).toList();
		out.append("  running behaviors: ").append(behaviors.isEmpty() ? "none" : String.join(", ", behaviors)).append('\n');

		List<String> remembered = new ArrayList<>();
		List<String> empty = new ArrayList<>();
		brain.forEach(new Brain.Visitor() {
			@Override
			public <U> void acceptEmpty(MemoryModuleType<U> type) {
				empty.add(memoryName(type));
			}

			@Override
			public <U> void accept(MemoryModuleType<U> type, U value) {
				remembered.add(memoryName(type) + ": " + value(level, value));
			}

			@Override
			public <U> void accept(MemoryModuleType<U> type, U value, long timeToLive) {
				remembered.add(memoryName(type) + ": " + value(level, value) + " (forgotten in " + timeToLive + " ticks)");
			}
		});
		remembered.sort(null);
		empty.sort(null);
		out.append("  memories:").append(remembered.isEmpty() ? " none\n" : "\n");
		remembered.forEach(memory -> out.append("    ").append(memory).append('\n'));
		if (!empty.isEmpty()) {
			out.append("  empty memories: ").append(String.join(", ", empty)).append('\n');
		}
	}

	private static void appendGoals(StringBuilder out, String title, GoalSelector selector) {
		Set<WrappedGoal> goals = selector.getAvailableGoals();
		if (goals.isEmpty()) {
			return;
		}
		out.append(title).append(", by priority (lower first), * running:\n");
		goals.stream().sorted(Comparator.comparingInt(WrappedGoal::getPriority)).forEach(goal -> {
			out.append(goal.isRunning() ? "  * " : "    ").append(goal.getPriority()).append(' ').append(goalName(goal.getGoal()));
			if (!goal.getFlags().isEmpty()) {
				out.append(" [").append(goal.getFlags().stream().map(flag -> flag.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "))).append(']');
			}
			out.append('\n');
		});
	}

	/** The goal's class, and for goals aimed at a kind of entity, which kind, e.g. "NearestAttackableTargetGoal Player". */
	private static String goalName(Goal goal) {
		Class<?> aimedAt = switch (goal) {
			case NearestAttackableTargetGoalAccessor attack -> attack.minecraftMcp$targetType();
			case AvoidEntityGoalAccessor avoid -> avoid.minecraftMcp$avoidClass();
			case LookAtPlayerGoalAccessor look -> look.minecraftMcp$lookAtType();
			default -> null;
		};
		return aimedAt == null ? className(goal.getClass()) : className(goal.getClass()) + " " + className(aimedAt);
	}

	private static String path(Mob mob) {
		Path path = mob.getNavigation().getPath();
		if (path == null || path.isDone()) {
			return "not following one";
		}
		StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "to %s, at node %d of %d, next %s",
			WorldQuery.coordinates(path.getTarget()), path.getNextNodeIndex() + 1, path.getNodeCount(), WorldQuery.coordinates(path.getNextNodePos())));
		if (!path.canReach()) {
			out.append(", ends short of the target at ").append(WorldQuery.coordinates(path.getEndNode().asBlockPos()));
		}
		if (mob.getNavigation().isStuck()) {
			out.append(", stuck");
		}
		return out.toString();
	}

	private static String behaviorName(BehaviorControl<?> behavior) {
		String name = behavior.debugString();
		return name.isEmpty() ? className(behavior.getClass()) : name;
	}

	/** The simple name, or for an anonymous class, what it extends and where it's declared, e.g. "MeleeAttackGoal in Spider". */
	private static String className(Class<?> type) {
		if (!type.isAnonymousClass()) {
			return type.getSimpleName();
		}
		String outer = type.getEnclosingClass() == null ? "" : " in " + className(type.getEnclosingClass());
		Class<?> parent = type.getInterfaces().length > 0 && type.getSuperclass() == Object.class ? type.getInterfaces()[0] : type.getSuperclass();
		return className(parent) + outer;
	}

	private static String memoryName(MemoryModuleType<?> type) {
		Identifier id = BuiltInRegistries.MEMORY_MODULE_TYPE.getKey(type);
		return id == null ? type.toString() : id.getPath();
	}

	private static String value(ServerLevel level, Object value) {
		return switch (value) {
			case null -> "-";
			case Unit unit -> "yes";
			case UUID uuid -> {
				Entity entity = level.getEntity(uuid);
				yield entity == null ? uuid + " (not loaded here)" : entity(entity, true);
			}
			case Entity entity -> entity(entity, true);
			case WalkTarget walk -> String.format(Locale.ROOT, "%s at speed %.2f, until within %d blocks",
				value(level, walk.getTarget()), walk.getSpeedModifier(), walk.getCloseEnoughDist());
			case EntityTracker tracker -> value(level, tracker.getEntity());
			case BlockPosTracker tracker -> position(tracker.currentPosition());
			case GlobalPos global -> WorldQuery.coordinates(global.pos()) + " in " + global.dimension().identifier();
			case BlockPos pos -> WorldQuery.coordinates(pos);
			case Vec3 pos -> position(pos);
			case DamageSource damage -> damage.getMsgId() + (damage.getEntity() == null ? "" : " from " + entity(damage.getEntity(), true));
			case Path path -> String.format(Locale.ROOT, "path to %s, %d nodes", WorldQuery.coordinates(path.getTarget()), path.getNodeCount());
			case NearestVisibleLivingEntities visible -> entities(visible.nearbyEntities());
			case Collection<?> collection when collection.stream().allMatch(Entity.class::isInstance) -> entities(collection);
			case Collection<?> collection -> "[" + collection.stream().map(element -> value(level, element)).collect(Collectors.joining(", ")) + "]";
			default -> value.toString();
		};
	}

	/** Lists entities briefly, without UUIDs, since there can be many. */
	private static String entities(Collection<?> entities) {
		if (entities.isEmpty()) {
			return "none";
		}
		String listed = entities.stream().limit(MAX_LISTED_ENTITIES).map(entity -> entity((Entity) entity, false)).collect(Collectors.joining("; "));
		return entities.size() > MAX_LISTED_ENTITIES ? listed + "; and " + (entities.size() - MAX_LISTED_ENTITIES) + " more" : listed;
	}

	/** E.g. minecraft:player "Steve" at 1.5 64.0 -3.2, with its UUID if asked for. */
	private static String entity(Entity entity, boolean withUuid) {
		StringBuilder out = new StringBuilder(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		if (entity.hasCustomName() || entity instanceof Player) {
			out.append(" \"").append(entity.getName().getString()).append('"');
		}
		out.append(" at ").append(position(entity.position()));
		if (!entity.isAlive()) {
			out.append(" (dead)");
		}
		if (withUuid) {
			out.append(" (uuid ").append(entity.getUUID()).append(')');
		}
		return out.toString();
	}

	private static String position(Vec3 pos) {
		return String.format(Locale.ROOT, "%.1f %.1f %.1f", pos.x, pos.y, pos.z);
	}
}
