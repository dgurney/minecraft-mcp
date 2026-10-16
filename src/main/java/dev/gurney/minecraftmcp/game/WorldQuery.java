package dev.gurney.minecraftmcp.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Reads blocks and entities from the world as the client has it loaded. */
public final class WorldQuery {
	public static final int MAX_BLOCKS = 4096;
	public static final int MAX_ENTITY_RADIUS = 64;
	private static final int MAX_ENTITIES = 100;

	private WorldQuery() {
	}

	/** Describes a block state the way commands write it, e.g. minecraft:oak_stairs[facing=north,...]. */
	public static String describe(BlockState state) {
		return state.toString().replaceFirst("^Block\\{(.*?)}", "$1");
	}

	/**
	 * Lists the non-air blocks in the box between two corners, grouped by block state, with runs
	 * along the x axis merged into "x1..x2 y z". Corners must be within the world's size limit.
	 */
	public static String blocks(BlockPos from, BlockPos to) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		long volume = requireBoxSize(min, max);
		return GameThreads.onClient(() -> {
			ClientLevel level = requireLevel();
			Map<String, List<String>> runsByState = new LinkedHashMap<>();
			int air = 0;
			int unloaded = 0;
			for (int y = min.getY(); y <= max.getY(); y++) {
				for (int z = min.getZ(); z <= max.getZ(); z++) {
					String runState = null;
					int runStart = 0;
					for (int x = min.getX(); x <= max.getX() + 1; x++) {
						String state = null;
						if (x <= max.getX()) {
							BlockPos pos = new BlockPos(x, y, z);
							if (!level.isLoaded(pos)) {
								unloaded++;
							} else if (level.getBlockState(pos).isAir()) {
								air++;
							} else {
								state = describe(level.getBlockState(pos));
							}
						}
						if (state == null ? runState == null : state.equals(runState)) {
							continue;
						}
						if (runState != null) {
							String xs = runStart == x - 1 ? Integer.toString(runStart) : runStart + ".." + (x - 1);
							runsByState.computeIfAbsent(runState, key -> new ArrayList<>()).add(xs + " " + y + " " + z);
						}
						runState = state;
						runStart = x;
					}
				}
			}

			StringBuilder out = new StringBuilder();
			out.append(String.format(Locale.ROOT, "box %d %d %d to %d %d %d (%d positions): %d air",
				min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(), volume, air));
			if (unloaded > 0) {
				out.append(", ").append(unloaded).append(" not loaded (outside render distance or build height)");
			}
			out.append('\n');
			if (runsByState.isEmpty()) {
				out.append("no other blocks\n");
			}
			runsByState.forEach((state, runs) -> out.append(state).append(": ").append(String.join("; ", runs)).append('\n'));
			return out.toString();
		});
	}

	/** Block coordinates the way commands write them, e.g. "3 64 -7". */
	public static String coordinates(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** The states of a block and its six neighbours, in command syntax, the block itself first. */
	public static Map<BlockPos, String> statesAround(BlockPos pos) {
		return GameThreads.onClient(() -> {
			ClientLevel level = requireLevel();
			Map<BlockPos, String> states = new LinkedHashMap<>();
			states.put(pos, describe(level.getBlockState(pos)));
			for (Direction direction : Direction.values()) {
				BlockPos neighbour = pos.relative(direction);
				states.put(neighbour, describe(level.getBlockState(neighbour)));
			}
			return states;
		});
	}

	/** Returns the number of positions in the box between two sorted corners, if it is within the limit. */
	static long requireBoxSize(BlockPos min, BlockPos max) {
		long volume = ((long) max.getX() - min.getX() + 1) * ((long) max.getY() - min.getY() + 1) * ((long) max.getZ() - min.getZ() + 1);
		if (volume > MAX_BLOCKS) {
			throw new ToolException("the box contains " + volume + " blocks, more than the limit of " + MAX_BLOCKS + "; use a smaller box");
		}
		return volume;
	}

	/** Lists entities within the radius of the player, nearest first. */
	public static String entities(double radius) {
		if (radius <= 0 || radius > MAX_ENTITY_RADIUS) {
			throw new ToolException("radius must be more than 0 and at most " + MAX_ENTITY_RADIUS);
		}
		return GameThreads.onClient(() -> {
			ClientLevel level = requireLevel();
			LocalPlayer player = Minecraft.getInstance().player;
			List<Entity> found = level.getEntities(player, new AABB(player.blockPosition()).inflate(radius),
				entity -> entity.distanceTo(player) <= radius);
			found.sort(Comparator.comparingDouble(entity -> entity.distanceTo(player)));

			StringBuilder out = new StringBuilder();
			out.append(found.size()).append(found.size() == 1 ? " entity" : " entities").append(" within ").append(radius).append(" blocks");
			if (found.size() > MAX_ENTITIES) {
				out.append(", showing the nearest ").append(MAX_ENTITIES);
			}
			out.append('\n');
			for (Entity entity : found.subList(0, Math.min(found.size(), MAX_ENTITIES))) {
				out.append(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
				if (entity instanceof ItemEntity item) {
					out.append(" (").append(BuiltInRegistries.ITEM.getKey(item.getItem().getItem())).append(" x").append(item.getItem().getCount()).append(')');
				} else if (entity.hasCustomName() || entity instanceof Player) {
					out.append(" \"").append(entity.getName().getString()).append('"');
				}
				out.append(String.format(Locale.ROOT, " at %.1f %.1f %.1f, %.1f blocks away", entity.getX(), entity.getY(), entity.getZ(), entity.distanceTo(player)));
				if (entity instanceof LivingEntity living) {
					out.append(String.format(Locale.ROOT, ", health %.1f/%.1f", living.getHealth(), living.getMaxHealth()));
				}
				out.append('\n');
			}
			return out.toString();
		});
	}

	private static ClientLevel requireLevel() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			throw new ToolException("not in a world");
		}
		return minecraft.level;
	}
}
