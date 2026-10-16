package dev.gurney.minecraftmcp.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Draws the ground of an area as a grid of heights and top blocks, so terrain can be read in one
 * go instead of slicing it into boxes of blocks.
 */
public final class SurfaceMap {
	public static final int MAX_COLUMNS = 4096;
	public static final int DEFAULT_RADIUS = 12;
	/** No digits: a cell is a height followed by its letter. */
	private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

	private SurfaceMap() {
	}

	/** The area within {@link #DEFAULT_RADIUS} columns of the player. */
	public static String aroundPlayer() {
		BlockPos at = GameThreads.onClient(() -> {
			WorldQuery.requireLevel();
			return Minecraft.getInstance().player.blockPosition();
		});
		return of(at.getX() - DEFAULT_RADIUS, at.getZ() - DEFAULT_RADIUS, at.getX() + DEFAULT_RADIUS, at.getZ() + DEFAULT_RADIUS);
	}

	/** The area between two corner columns, inclusive. */
	public static String of(int x1, int z1, int x2, int z2) {
		int minX = Math.min(x1, x2);
		int minZ = Math.min(z1, z2);
		int maxX = Math.max(x1, x2);
		int maxZ = Math.max(z1, z2);
		long columns = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
		if (columns > MAX_COLUMNS) {
			throw new ToolException("the area contains " + columns + " columns, more than the limit of " + MAX_COLUMNS + "; use a smaller area");
		}
		return GameThreads.onClient(() -> {
			ClientLevel level = WorldQuery.requireLevel();
			int width = maxX - minX + 1;
			int depth = maxZ - minZ + 1;
			// The top block's y, or null where the chunk isn't loaded or the column is empty.
			Integer[][] heights = new Integer[depth][width];
			String[][] blocks = new String[depth][width];
			Map<String, Integer> counts = new HashMap<>();
			int unloaded = 0;
			int empty = 0;
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					if (!isLoaded(level, x, z)) {
						unloaded++;
						continue;
					}
					// The heightmap gives the lowest y above every block that blocks movement or holds a fluid, leaves excepted.
					int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
					if (top < level.getMinY()) {
						empty++;
						continue;
					}
					String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, top, z)).getBlock()).toString();
					heights[z - minZ][x - minX] = top;
					blocks[z - minZ][x - minX] = block;
					counts.merge(block, 1, Integer::sum);
				}
			}

			Map<String, Character> letters = assignLetters(counts);
			int cellWidth = Math.max(Integer.toString(minX).length(), Integer.toString(maxX).length());
			for (Integer[] row : heights) {
				for (Integer height : row) {
					if (height != null) {
						cellWidth = Math.max(cellWidth, height.toString().length() + 1);
					}
				}
			}
			int labelWidth = Math.max(Integer.toString(minZ).length(), Integer.toString(maxZ).length());

			LocalPlayer player = Minecraft.getInstance().player;
			BlockPos playerPos = player.blockPosition();
			StringBuilder out = new StringBuilder();
			out.append(String.format(Locale.ROOT, "surface of x %d..%d, z %d..%d (%d columns)%n", minX, maxX, minZ, maxZ, columns));
			out.append("each cell is the y of the column's top block and its letter from the key; plants, leaves, snow layers, torches, carpets and the like are skipped, fluids are not\n");
			out.append("rows go north to south (z increasing), columns west to east (x increasing)\n");
			out.append(String.format(Locale.ROOT, "you are at x %d, y %d, z %d, facing %s%s%n",
				playerPos.getX(), playerPos.getY(), playerPos.getZ(), StatusReport.facing(player),
				playerPos.getX() >= minX && playerPos.getX() <= maxX && playerPos.getZ() >= minZ && playerPos.getZ() <= maxZ ? "" : ", outside this area"));

			out.append(" ".repeat(labelWidth));
			for (int x = minX; x <= maxX; x++) {
				out.append(' ').append(pad(Integer.toString(x), cellWidth));
			}
			out.append('\n');
			for (int z = minZ; z <= maxZ; z++) {
				out.append(pad(Integer.toString(z), labelWidth));
				for (int x = minX; x <= maxX; x++) {
					Integer height = heights[z - minZ][x - minX];
					String cell;
					if (height != null) {
						cell = height + String.valueOf(letters.get(blocks[z - minZ][x - minX]));
					} else {
						cell = isLoaded(level, x, z) ? "-" : "?";
					}
					out.append(' ').append(pad(cell, cellWidth));
				}
				out.append('\n');
			}

			if (!letters.isEmpty()) {
				List<String> key = new ArrayList<>();
				letters.forEach((block, letter) -> key.add(letter + " " + block + " (" + counts.get(block) + ")"));
				out.append("key: ").append(String.join(", ", key)).append('\n');
			}
			if (empty > 0) {
				out.append("- no blocks in the column (").append(empty).append(")\n");
			}
			if (unloaded > 0) {
				out.append("? not loaded, outside render distance (").append(unloaded).append(")\n");
			}
			return out.toString();
		});
	}

	/**
	 * Gives each block a letter, the most common first, preferring a letter from its name so the
	 * grid stays readable, e.g. g for grass_block and d for dirt.
	 */
	private static Map<String, Character> assignLetters(Map<String, Integer> counts) {
		if (counts.size() > LETTERS.length()) {
			throw new ToolException("the area's surface has " + counts.size() + " kinds of block, more than the "
				+ LETTERS.length() + " the map can label; use a smaller area, or get_blocks");
		}
		List<String> byCount = new ArrayList<>(counts.keySet());
		byCount.sort((a, b) -> counts.get(b) - counts.get(a));
		Map<String, Character> letters = new LinkedHashMap<>();
		Set<Character> used = new HashSet<>();
		for (String block : byCount) {
			String candidates = block.substring(block.indexOf(':') + 1) + LETTERS;
			for (char candidate : candidates.toCharArray()) {
				if (LETTERS.indexOf(candidate) >= 0 && used.add(candidate)) {
					letters.put(block, candidate);
					break;
				}
			}
		}
		return letters;
	}

	private static boolean isLoaded(ClientLevel level, int x, int z) {
		// ClientLevel.hasChunk always says yes; its chunk source knows.
		return level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
	}

	private static String pad(String text, int width) {
		return " ".repeat(width - text.length()) + text;
	}
}
