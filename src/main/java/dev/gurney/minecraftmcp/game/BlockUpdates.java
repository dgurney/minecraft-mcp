package dev.gurney.minecraftmcp.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes blocks react to their surroundings as if each neighbour had just changed. Blocks placed by
 * /setblock and /fill usually only update their neighbours, not themselves, so e.g. a lamp placed
 * next to powered dust stays unlit until something near it changes.
 */
public final class BlockUpdates {
	private static final int MAX_LISTED_CHANGES = 20;

	private BlockUpdates() {
	}

	public static String update(BlockPos from, BlockPos to) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		WorldQuery.requireBoxSize(min, max);
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

			List<String> changes = new ArrayList<>();
			before.forEach((pos, state) -> {
				BlockState after = level.getBlockState(pos);
				if (after != state) {
					changes.add(WorldQuery.coordinates(pos) + ": " + WorldQuery.describe(state) + " -> " + WorldQuery.describe(after));
				}
			});

			StringBuilder out = new StringBuilder();
			out.append("updated ").append(updated).append(updated == 1 ? " block" : " blocks");
			if (skipped > 0) {
				out.append(", skipped ").append(skipped).append(" not loaded (outside render distance or build height)");
			}
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
			return out.toString();
		});
		return outcome.clientCaughtUp() ? outcome.value() : outcome.value() + ClientCatchUp.LAGGING_NOTE;
	}
}
