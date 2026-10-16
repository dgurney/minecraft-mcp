package dev.gurney.minecraftmcp.game;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Turns the player's view so the crosshair is on a block, the way a player would aim at it: at a
 * part of its outline that is in reach and not hidden behind anything. Thin blocks like levers and
 * buttons only fill part of their space, so aiming at the middle of the space misses them.
 */
final class BlockAim {
	/** Where to aim on each face, as fractions across it; the centre is tried first. */
	private static final double[] SPOTS = {0.5, 0.2, 0.8};

	private BlockAim() {
	}

	/** Aims at the block, at the given face or (if null) the visible face pointing most toward the player. */
	static void aim(Minecraft minecraft, BlockPos pos, Direction face) {
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.getCameraEntity() == null) {
			throw new ToolException("cannot aim: not in a world");
		}
		if (minecraft.getCameraEntity() != player) {
			throw new ToolException("cannot aim while viewing the world through another entity (spectator mode); sneak to leave it first");
		}
		String where = WorldQuery.coordinates(pos);
		if (level.isOutsideBuildHeight(pos)) {
			throw new ToolException(where + " is outside the world's build height");
		}
		if (!level.isLoaded(pos)) {
			throw new ToolException("the block at " + where + " is not loaded");
		}
		BlockState state = level.getBlockState(pos);
		List<AABB> boxes = state.getShape(level, pos, CollisionContext.of(player)).toAabbs().stream().map(box -> box.move(pos)).toList();
		if (boxes.isEmpty()) {
			throw new ToolException("there is nothing to aim at at " + where + ": " + (state.isAir() ? "it is air" : WorldQuery.describe(state) + " cannot be targeted"));
		}

		Vec3 eye = player.getEyePosition();
		Vec3 toEye = eye.subtract(Vec3.atCenterOf(pos));
		List<Direction> sides = face != null ? List.of(face)
			: Arrays.stream(Direction.values()).sorted(Comparator.comparingDouble((Direction side) -> side.getUnitVec3().dot(toEye)).reversed()).toList();
		float oldYaw = player.getYRot();
		float oldPitch = player.getXRot();
		HitResult firstMiss = null;
		for (double u : SPOTS) {
			for (double v : SPOTS) {
				for (Direction side : sides) {
					for (AABB box : boxes) {
						if (!facesPoint(box, side, eye)) {
							continue;
						}
						Vec3 point = pointOnFace(box, side, u, v);
						VirtualInput.lookAt(minecraft, point.x, point.y, point.z);
						// The same raycast the game uses for the crosshair, so a click now lands on what it found.
						HitResult hit = player.raycastHitResult(1.0f, player);
						if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
							&& blockHit.getBlockPos().equals(pos) && (face == null || blockHit.getDirection() == face)) {
							return;
						}
						if (firstMiss == null) {
							firstMiss = hit;
						}
					}
				}
			}
		}
		VirtualInput.look(minecraft, oldYaw, oldPitch);
		throw new ToolException(whyUnreachable(level, player, pos, boxes, eye, face, firstMiss));
	}

	private static String whyUnreachable(ClientLevel level, LocalPlayer player, BlockPos pos, List<AABB> boxes, Vec3 eye, Direction face, HitResult firstMiss) {
		String where = WorldQuery.coordinates(pos);
		if (firstMiss == null) {
			return face == null
				? "cannot aim at the block at " + where + " from inside it"
				: String.format(Locale.ROOT, "the %s face of the block at %s points away from you; to aim at it, your eyes must be %s", face.getName(), where,
					switch (face) {
						case UP -> "above it";
						case DOWN -> "below it";
						default -> "on its " + face.getName() + " side";
					});
		}
		double distance = Math.sqrt(boxes.stream().mapToDouble(box -> box.distanceToSqr(eye)).min().orElseThrow());
		double reach = player.blockInteractionRange();
		if (distance > reach) {
			return String.format(Locale.ROOT, "the block at %s is out of reach: %.1f blocks from your eyes, and you can reach %.1f", where, distance, reach);
		}
		return switch (firstMiss) {
			case BlockHitResult blockHit when blockHit.getType() == HitResult.Type.BLOCK && blockHit.getBlockPos().equals(pos) ->
				String.format(Locale.ROOT, "the %s face of the block at %s is hidden from here; only its other faces can be aimed at", face.getName(), where);
			case BlockHitResult blockHit when blockHit.getType() == HitResult.Type.BLOCK -> {
				BlockPos in = blockHit.getBlockPos();
				yield String.format(Locale.ROOT, "the block at %s is hidden behind %s at %d %d %d from here",
					where, WorldQuery.describe(level.getBlockState(in)), in.getX(), in.getY(), in.getZ());
			}
			case EntityHitResult entityHit -> String.format(Locale.ROOT, "the block at %s is hidden behind the entity %s at %.1f %.1f %.1f",
				where, BuiltInRegistries.ENTITY_TYPE.getKey(entityHit.getEntity().getType()),
				entityHit.getEntity().getX(), entityHit.getEntity().getY(), entityHit.getEntity().getZ());
			default -> String.format(Locale.ROOT, "the visible parts of the block at %s are out of reach (you can reach %.1f blocks); move closer", where, reach);
		};
	}

	/** Whether a point is on the outer side of a face of the box, so that the face can be seen from it. */
	private static boolean facesPoint(AABB box, Direction side, Vec3 point) {
		Direction.Axis axis = side.getAxis();
		return side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? point.get(axis) > box.max(axis) : point.get(axis) < box.min(axis);
	}

	/** A point on a face of the box, u and v of the way across its two other axes. */
	private static Vec3 pointOnFace(AABB box, Direction side, double u, double v) {
		double[] coordinates = new double[3];
		double[] across = {u, v};
		int next = 0;
		for (Direction.Axis axis : Direction.Axis.values()) {
			if (axis == side.getAxis()) {
				coordinates[axis.ordinal()] = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(axis) : box.min(axis);
			} else {
				coordinates[axis.ordinal()] = box.min(axis) + (box.max(axis) - box.min(axis)) * across[next++];
			}
		}
		return new Vec3(coordinates[0], coordinates[1], coordinates[2]);
	}
}
