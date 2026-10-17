package dev.gurney.minecraftmcp.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes blocks react to their surroundings as if each neighbour had just changed. Blocks placed by
 * /setblock and /fill usually only update their neighbours, not themselves, so e.g. a lamp placed
 * next to powered dust stays unlit until something near it changes.
 *
 * <p>In a world the game hosts with cheats on, the blocks are updated on the server directly.
 * Otherwise the same is done with /fill: a temporary light block is put in, and taken out of,
 * every air position in and around the box, which updates the blocks next to them the way any
 * placed block does. Blocks with no air next to them can't be reached that way.
 */
public final class BlockUpdates {
	private static final int MAX_LISTED_CHANGES = 20;
	/** The default of the max_block_modifications game rule, which /fill refuses to exceed. */
	private static final int MAX_FILL = 32768;

	private BlockUpdates() {
	}

	public static String update(BlockPos from, BlockPos to) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		WorldQuery.requireBoxSize(min, max);
		if (GameThreads.onClient(OperatorAccess::available)) {
			return updateOnServer(min, max);
		}
		return updateByCommands(min, max);
	}

	private static String updateOnServer(BlockPos min, BlockPos max) {
		ClientCatchUp.Outcome<String> outcome = OperatorAccess.onServer(player -> {
			ServerLevel level = player.level();
			// Taken before any update, since updating one block can change others in the box.
			Map<BlockPos, BlockState> before = new LinkedHashMap<>();
			int skipped = 0;
			for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
				if (level.isLoaded(pos)) {
					before.put(pos.immutable(), level.getBlockState(pos));
				} else {
					skipped++;
				}
			}

			int updated = 0;
			for (BlockPos pos : before.keySet()) {
				BlockState state = level.getBlockState(pos);
				if (state.isAir()) {
					continue;
				}
				updated++;
				// Shape updates connect fences, stairs and dust to their neighbours, and drop blocks that lost their support.
				Block.updateOrDestroy(state, Block.updateFromNeighbourShapes(state, level, pos), level, pos, Block.UPDATE_ALL);
				// Neighbour changes make redstone components re-check their power. Some blocks ignore a change
				// that names their own kind of block, so name each actual neighbour.
				for (Direction direction : Direction.values()) {
					level.neighborChanged(pos, level.getBlockState(pos.relative(direction)).getBlock(), null);
				}
			}

			StringBuilder out = new StringBuilder();
			out.append("updated ").append(updated).append(updated == 1 ? " block" : " blocks");
			if (skipped > 0) {
				out.append(", skipped ").append(skipped).append(" not loaded (outside render distance or build height)");
			}
			appendChanges(out, before, level::getBlockState);
			return out.toString();
		});
		return outcome.clientCaughtUp() ? outcome.value() : outcome.value() + ClientCatchUp.LAGGING_NOTE;
	}

	private static String updateByCommands(BlockPos min, BlockPos max) {
		record Plan(List<String> commands, Map<BlockPos, BlockState> before, int targets, int enclosed, List<Integer> markerLevels) {
		}
		Plan plan = GameThreads.onClient(() -> {
			ClientLevel level = WorldQuery.requireLevel();
			if (Minecraft.getInstance().player.connection.getCommands().getRoot().getChild("fill") == null) {
				throw new ToolException("this needs cheats, or operator rights on a server: it uses /fill, which the server doesn't let you run; use the other tools instead");
			}
			Map<BlockPos, BlockState> before = new LinkedHashMap<>();
			int targets = 0;
			int enclosed = 0;
			for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
				if (!level.isLoaded(pos)) {
					continue;
				}
				BlockState state = level.getBlockState(pos);
				before.put(pos.immutable(), state);
				if (state.isAir()) {
					continue;
				}
				targets++;
				boolean touchesAir = false;
				for (Direction direction : Direction.values()) {
					BlockPos neighbour = pos.relative(direction);
					touchesAir |= level.isLoaded(neighbour) && level.getBlockState(neighbour).isAir();
				}
				if (!touchesAir) {
					enclosed++;
				}
			}
			if (targets == 0) {
				return new Plan(List.of(), before, 0, 0, List.of());
			}

			// /fill needs its corners loaded; the client's loaded chunks are a rectangle, so the box is cut to it.
			BlockPos outerMin = new BlockPos(min.getX() - 1, Math.max(min.getY() - 1, level.getMinY()), min.getZ() - 1);
			BlockPos outerMax = new BlockPos(max.getX() + 1, Math.min(max.getY() + 1, level.getMaxY()), max.getZ() + 1);
			int loadedMinX = Integer.MAX_VALUE;
			int loadedMaxX = Integer.MIN_VALUE;
			int loadedMinZ = Integer.MAX_VALUE;
			int loadedMaxZ = Integer.MIN_VALUE;
			for (int chunkX = outerMin.getX() >> 4; chunkX <= outerMax.getX() >> 4; chunkX++) {
				for (int chunkZ = outerMin.getZ() >> 4; chunkZ <= outerMax.getZ() >> 4; chunkZ++) {
					if (level.getChunkSource().hasChunk(chunkX, chunkZ)) {
						loadedMinX = Math.min(loadedMinX, chunkX << 4);
						loadedMaxX = Math.max(loadedMaxX, (chunkX << 4) + 15);
						loadedMinZ = Math.min(loadedMinZ, chunkZ << 4);
						loadedMaxZ = Math.max(loadedMaxZ, (chunkZ << 4) + 15);
					}
				}
			}
			outerMin = new BlockPos(Math.max(outerMin.getX(), loadedMinX), outerMin.getY(), Math.max(outerMin.getZ(), loadedMinZ));
			outerMax = new BlockPos(Math.min(outerMax.getX(), loadedMaxX), outerMax.getY(), Math.min(outerMax.getZ(), loadedMaxZ));

			// Light blocks already there must be left alone, so the temporary ones get levels none of them have.
			boolean[] usedLevels = new boolean[LightBlock.MAX_LEVEL + 1];
			for (BlockPos pos : BlockPos.betweenClosed(outerMin, outerMax)) {
				BlockState state = level.getBlockState(pos);
				if (state.is(Blocks.LIGHT)) {
					usedLevels[state.getValue(LightBlock.LEVEL)] = true;
				}
			}
			Map<Block, Integer> markerLevels = new LinkedHashMap<>();
			int nextLevel = 0;
			List<String> place = new ArrayList<>();
			List<String> remove = new ArrayList<>();
			for (BlockPos[] piece : pieces(outerMin, outerMax)) {
				for (Block air : airKinds(level, piece[0], piece[1])) {
					if (!markerLevels.containsKey(air)) {
						while (nextLevel <= LightBlock.MAX_LEVEL && usedLevels[nextLevel]) {
							nextLevel++;
						}
						if (nextLevel > LightBlock.MAX_LEVEL) {
							throw new ToolException("the box and its surroundings hold light blocks of every level, leaving none for the temporary ones this uses");
						}
						markerLevels.put(air, nextLevel++);
					}
					String box = WorldQuery.coordinates(piece[0]) + " " + WorldQuery.coordinates(piece[1]);
					String marker = "minecraft:light[level=" + markerLevels.get(air) + "]";
					String airName = BuiltInRegistries.BLOCK.getKey(air).toString();
					place.add("fill " + box + " " + marker + " replace " + airName);
					remove.add("fill " + box + " " + airName + " replace " + marker);
				}
			}
			List<String> commands = new ArrayList<>(place);
			commands.addAll(remove);
			return new Plan(commands, before, targets, enclosed, List.copyOf(markerLevels.values()));
		});
		if (plan.targets() == 0) {
			return "no blocks to update: the loaded part of the box is all air\n";
		}
		if (plan.targets() == plan.enclosed()) {
			return "updated no blocks: without cheats this works through the air next to blocks, and none of the " + plan.targets() + " blocks in the box has loaded air next to it\n";
		}

		ClientCatchUp.Outcome<List<CommandRunner.Result>> outcome = CommandRunner.run(plan.commands());
		List<CommandRunner.Result> failed = outcome.value().stream().filter(result -> !result.succeeded()).toList();
		if (!failed.isEmpty()) {
			StringBuilder out = new StringBuilder("the /fill commands this works through failed, so the blocks may not have been updated");
			if (failed.size() < plan.commands().size()) {
				out.append(", and temporary minecraft:light blocks with level ").append(plan.markerLevels()).append(" may be left in and around the box; remove them with /fill ... air replace minecraft:light[level=...]");
			}
			out.append(':');
			failed.forEach(result -> out.append("\n/").append(result.command()).append(": ").append(String.join(" ", result.output())));
			throw new ToolException(out.toString());
		}

		StringBuilder out = new StringBuilder();
		out.append("updated ").append(plan.targets() - plan.enclosed()).append(plan.targets() - plan.enclosed() == 1 ? " block" : " blocks").append(" through the air next to them (with /fill, as this game doesn't host the world with cheats)");
		if (plan.enclosed() > 0) {
			out.append(", skipped ").append(plan.enclosed()).append(" with no air next to them");
		}
		GameThreads.onClient(() -> {
			// The player may have moved away meanwhile; a position no longer loaded isn't a change.
			ClientLevel level = WorldQuery.requireLevel();
			appendChanges(out, plan.before(), pos -> level.isLoaded(pos) ? level.getBlockState(pos) : plan.before().get(pos));
			return null;
		});
		return outcome.clientCaughtUp() ? out.toString() : out + ClientCatchUp.LAGGING_NOTE;
	}

	/** Boxes of at most {@link #MAX_FILL} positions covering the box, cut along its longest axis. */
	private static List<BlockPos[]> pieces(BlockPos min, BlockPos max) {
		int sizeX = max.getX() - min.getX() + 1;
		int sizeY = max.getY() - min.getY() + 1;
		int sizeZ = max.getZ() - min.getZ() + 1;
		long volume = (long) sizeX * sizeY * sizeZ;
		int count = (int) Math.ceilDiv(volume, MAX_FILL);
		Direction.Axis axis = sizeX >= sizeY && sizeX >= sizeZ ? Direction.Axis.X : sizeY >= sizeZ ? Direction.Axis.Y : Direction.Axis.Z;
		int length = axis.choose(sizeX, sizeY, sizeZ);
		int step = Math.ceilDiv(length, count);
		List<BlockPos[]> pieces = new ArrayList<>();
		for (int start = axis.choose(min.getX(), min.getY(), min.getZ()); start <= axis.choose(max.getX(), max.getY(), max.getZ()); start += step) {
			int end = Math.min(start + step - 1, axis.choose(max.getX(), max.getY(), max.getZ()));
			pieces.add(new BlockPos[] {
				new BlockPos(axis == Direction.Axis.X ? start : min.getX(), axis == Direction.Axis.Y ? start : min.getY(), axis == Direction.Axis.Z ? start : min.getZ()),
				new BlockPos(axis == Direction.Axis.X ? end : max.getX(), axis == Direction.Axis.Y ? end : max.getY(), axis == Direction.Axis.Z ? end : max.getZ())});
		}
		return pieces;
	}

	/** The kinds of air (air, cave_air) in the loaded part of the box, each restored as it was. */
	private static List<Block> airKinds(ClientLevel level, BlockPos min, BlockPos max) {
		List<Block> kinds = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			if (!level.isLoaded(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.isAir() && !kinds.contains(state.getBlock())) {
				kinds.add(state.getBlock());
			}
		}
		return kinds;
	}

	private static void appendChanges(StringBuilder out, Map<BlockPos, BlockState> before, Function<BlockPos, BlockState> now) {
		List<String> changes = new ArrayList<>();
		before.forEach((pos, state) -> {
			BlockState after = now.apply(pos);
			if (after != state) {
				changes.add(WorldQuery.coordinates(pos) + ": " + WorldQuery.describe(state) + " -> " + WorldQuery.describe(after));
			}
		});
		if (changes.isEmpty()) {
			out.append("; none in the box changed right away\n");
		} else {
			out.append("; ").append(changes.size()).append(" in the box changed right away:\n");
			changes.stream().limit(MAX_LISTED_CHANGES).forEach(change -> out.append("  ").append(change).append('\n'));
			if (changes.size() > MAX_LISTED_CHANGES) {
				out.append("  and ").append(changes.size() - MAX_LISTED_CHANGES).append(" more\n");
			}
		}
		out.append("Torches, repeaters, comparators and lamps turning off react a few ticks later, and the changes spread through connected circuits; check the result with get_blocks.\n");
	}
}
