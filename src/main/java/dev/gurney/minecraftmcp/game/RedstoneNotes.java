package dev.gurney.minecraftmcp.game;

import dev.gurney.minecraftmcp.mixin.DiodeBlockInvoker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Spells out what block states leave easy to misread in redstone: which way components face, and
 * which blocks are powered and how.
 */
final class RedstoneNotes {
	private RedstoneNotes() {
	}

	/** Which sides of the block its state makes it work with, or null for blocks that need no explaining. */
	static String orientation(BlockState state) {
		return switch (state.getBlock()) {
			case DiodeBlock _ -> {
				Direction input = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
				yield "input on its " + side(input) + " side, output on its " + side(input.getOpposite()) + " side";
			}
			case ObserverBlock _ -> {
				Direction watched = state.getValue(BlockStateProperties.FACING);
				yield "watches the block on its " + side(watched) + " side, outputs on its " + side(watched.getOpposite()) + " side";
			}
			case PistonBaseBlock _ -> "pushes toward its " + side(state.getValue(BlockStateProperties.FACING)) + " side";
			case ButtonBlock _, LeverBlock _ -> "attached to the block on its " + side(attachedSide(state)) + " side, which it strongly powers";
			case RedstoneWallTorchBlock _ -> "attached to the block on its " + side(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite())
				+ " side, which turns it off when powered; strongly powers the block above it";
			case RedstoneTorchBlock _ -> "turned off when the block below it is powered; strongly powers the block above it";
			case HopperBlock _ -> "outputs on its " + side(state.getValue(BlockStateProperties.FACING_HOPPER)) + " side";
			default -> null;
		};
	}

	/** Whether the block makes, carries or reacts to redstone power, so the box's power is worth describing. */
	static boolean isRedstone(BlockState state) {
		return state.isSignalSource() || drivenByRedstone(state.getBlock());
	}

	/**
	 * Describes the power of each solid block and redstone-driven component in the box that gets
	 * any, one line each. Redstone dust is left out, as its state shows its power. Call on the
	 * level's thread. The server's world is exact; the client's lacks what block entities hold,
	 * which it isn't told about, so comparators' output, trapped chests' openers and what a
	 * comparator reads from a container show as nothing.
	 */
	static List<String> power(Level level, BlockPos min, BlockPos max) {
		List<String> lines = new ArrayList<>();
		for (int y = min.getY(); y <= max.getY(); y++) {
			for (int z = min.getZ(); z <= max.getZ(); z++) {
				for (int x = min.getX(); x <= max.getX(); x++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!level.isLoaded(pos)) {
						continue;
					}
					BlockState state = level.getBlockState(pos);
					// Some components, like lamps and dispensers, are also solid blocks; what they react to matters more.
					String power = switch (state.getBlock()) {
						case DiodeBlock diode -> diodePower(level, pos, state, diode);
						case PistonBaseBlock _ -> activatorPower(level, pos, state, BlockStateProperties.EXTENDED,
							"powered but retracted: it extends at its next block update, if what it pushes can move",
							"unpowered but extended: it retracts at its next block update");
						case DispenserBlock _ -> activatorPower(level, pos, state, BlockStateProperties.TRIGGERED,
							"powered but not triggered: it fires at its next block update",
							"unpowered but still triggered: it resets at its next block update");
						case Block block when drivenByRedstone(block) -> received(level, pos, null);
						default -> state.isRedstoneConductor(level, pos) ? conductorPower(level, pos) : null;
					};
					if (power != null) {
						lines.add(WorldQuery.coordinates(pos) + " " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + ": " + power);
					}
				}
			}
		}
		return lines;
	}

	/**
	 * A solid block is strongly powered by a source pointing into it, like a repeater or a button on
	 * it, and then powers the dust and components next to it. Powered only by dust, it is weakly
	 * powered and powers only components.
	 */
	private static String conductorPower(Level level, BlockPos pos) {
		List<String> strong = new ArrayList<>();
		List<String> dust = new ArrayList<>();
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			// Dust's direct signal is its power too; vanilla tells them apart with a flag on the dust block while dust updates.
			int signal = level.getDirectSignal(neighbour, direction);
			if (signal > 0) {
				(level.getBlockState(neighbour).is(Blocks.REDSTONE_WIRE) ? dust : strong).add(signal + " from the " + side(direction));
			}
		}
		if (strong.isEmpty()) {
			return dust.isEmpty() ? null : "weakly powered by dust: " + String.join(", ", dust);
		}
		return "strongly powered: " + String.join(", ", strong) + (dust.isEmpty() ? "" : "; dust also gives it " + String.join(", ", dust));
	}

	/** What a repeater or comparator reads: its input, and the sides that lock a repeater or a comparator compares with. */
	private static String diodePower(Level level, BlockPos pos, BlockState state, DiodeBlock diode) {
		DiodeBlockInvoker invoker = (DiodeBlockInvoker) diode;
		int input = invoker.minecraftMcp$getInputSignal(level, pos, state);
		int sides = invoker.minecraftMcp$getAlternateSignal(level, pos, state);
		if (input == 0 && sides == 0) {
			return null;
		}
		String sideMeaning = diode instanceof RepeaterBlock ? "a powered side locks it" : "subtracted from or compared with the input, by its mode";
		return "input " + input + ", sides " + sides + " (" + sideMeaning + ")";
	}

	/**
	 * Pistons and dispensers count as powered through the position above them too
	 * (quasi-connectivity), but only notice a change in their power at a block update.
	 */
	private static String activatorPower(Level level, BlockPos pos, BlockState state, BooleanProperty active, String poweredButIdle, String unpoweredButActive) {
		// A piston ignores power coming from the side it pushes toward.
		Direction ignored = state.getBlock() instanceof PistonBaseBlock ? state.getValue(BlockStateProperties.FACING) : null;
		String received = received(level, pos, ignored);
		BlockPos above = pos.above();
		boolean throughAbove = Arrays.stream(Direction.values())
			.anyMatch(direction -> direction != Direction.DOWN && level.hasSignal(above.relative(direction), direction));
		boolean powered = received != null || throughAbove;
		List<String> parts = new ArrayList<>();
		if (received != null) {
			parts.add(received);
		} else if (throughAbove) {
			parts.add("powered only through the position above it (quasi-connectivity)");
		}
		if (powered != state.getValue(active)) {
			parts.add(powered ? poweredButIdle : unpoweredButActive);
		}
		return parts.isEmpty() ? null : String.join("; ", parts);
	}

	/** E.g. "receives 15 from the west (-x)", or null if no side gives the block power. */
	private static String received(Level level, BlockPos pos, Direction ignored) {
		List<String> received = new ArrayList<>();
		for (Direction direction : Direction.values()) {
			int signal = direction == ignored ? 0 : level.getSignal(pos.relative(direction), direction);
			if (signal > 0) {
				received.add(signal + " from the " + side(direction));
			}
		}
		return received.isEmpty() ? null : "receives " + String.join(", ", received);
	}

	/** Components whose state follows the power they receive; others, like torches, observers and buttons, are explained by {@link #orientation}. */
	private static boolean drivenByRedstone(Block block) {
		return switch (block) {
			case DiodeBlock _, PistonBaseBlock _, DispenserBlock _, CrafterBlock _, HopperBlock _, RedstoneLampBlock _, DoorBlock _, TrapDoorBlock _,
				FenceGateBlock _, NoteBlock _, TntBlock _, PoweredRailBlock _, CopperBulbBlock _, BellBlock _ -> true;
			default -> false;
		};
	}

	/** The side a button or lever is attached on. */
	private static Direction attachedSide(BlockState state) {
		return switch (state.getValue(BlockStateProperties.ATTACH_FACE)) {
			case FLOOR -> Direction.DOWN;
			case CEILING -> Direction.UP;
			case WALL -> state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
		};
	}

	/** E.g. "east (+x)" or "top (+y)". */
	private static String side(Direction direction) {
		return switch (direction) {
			case UP -> "top (+y)";
			case DOWN -> "bottom (-y)";
			case NORTH -> "north (-z)";
			case SOUTH -> "south (+z)";
			case WEST -> "west (-x)";
			case EAST -> "east (+x)";
		};
	}
}
