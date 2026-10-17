package dev.gurney.minecraftmcp.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Records every change to the blocks in a box, in the order they happen, for a number of ticks.
 * Snapshots taken between tool calls miss most of what a circuit does: a piston can extend and
 * retract between two of them.
 *
 * <p>In a world the game hosts with cheats on, the changes are recorded on the server as they
 * happen. Otherwise they are recorded as the client receives them, which is less exact: the
 * server sends only how each block ended up in each tick, the changes of a tick arrive together,
 * and the ticks are the client's, which keep in step with the server's but not perfectly.
 */
public final class BlockWatch {
	public static final int DEFAULT_TICKS = 60;
	private static final int MAX_LISTED_CHANGES = 200;
	private static final int MAX_LISTED_DIFFERENCES = 20;
	private static final long STALL_SECONDS = 10;

	private static final List<BlockWatch> ACTIVE = new CopyOnWriteArrayList<>();
	/** Counts the client's ticks, so the client's changes can be stamped; only touched on the client thread. */
	private static int clientTicks;

	private record Change(int tick, BlockPos pos, BlockState from, BlockState to) {
	}

	private record Done(String text, CompletableFuture<Boolean> seen) {
	}

	/** The player on the server, or null when watching from the client. */
	private final ServerPlayer player;
	private final Level level;
	private final BlockPos min;
	private final BlockPos max;
	private final int ticks;
	private final int startTick;
	/** The first changes, in order; only touched on the level's thread. */
	private final List<Change> changes = new ArrayList<>();
	private int unlisted;
	/** What each changed position held before its first change; only touched on the level's thread. */
	private final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
	private volatile int lastTick;
	private final CompletableFuture<Done> done = new CompletableFuture<>();

	private BlockWatch(ServerPlayer player, Level level, BlockPos min, BlockPos max, int ticks) {
		this.player = player;
		this.level = level;
		this.min = min;
		this.max = max;
		this.ticks = ticks;
		this.startTick = currentTick();
		this.lastTick = startTick;
	}

	/** Starts recording the changes in the box from now on, in the player's dimension. */
	public static BlockWatch start(BlockPos from, BlockPos to, int ticks) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		if (GameThreads.onClient(OperatorAccess::available)) {
			return OperatorAccess.readOnServer(player -> activate(new BlockWatch(player, player.level(), min, max, ticks)));
		}
		return GameThreads.onClient(() -> activate(new BlockWatch(null, WorldQuery.requireLevel(), min, max, ticks)));
	}

	private static BlockWatch activate(BlockWatch watch) {
		ACTIVE.add(watch);
		return watch;
	}

	/**
	 * Waits until the watch has run for its ticks and the client has received the changes, then
	 * describes them. Gives up only if the game stops ticking, since the tick rate can be changed.
	 */
	public String await() {
		int seenTick = lastTick;
		long lastProgress = System.nanoTime();
		while (true) {
			try {
				Done result = done.get(1, TimeUnit.SECONDS);
				return ClientCatchUp.await(result.seen()) ? result.text() : result.text() + ClientCatchUp.LAGGING_NOTE;
			} catch (TimeoutException e) {
				if (lastTick != seenTick) {
					seenTick = lastTick;
					lastProgress = System.nanoTime();
				} else if (System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(STALL_SECONDS)) {
					ACTIVE.remove(this);
					throw new ToolException("the game stopped ticking for " + STALL_SECONDS + "s while watching (is it loading or frozen?), so the watch was abandoned");
				}
			} catch (ExecutionException e) {
				if (e.getCause() instanceof ToolException toolException) {
					throw toolException;
				}
				throw new IllegalStateException(e.getCause());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
	}

	/** Called for every block change in a loaded chunk, on the client and the server, right as the block changes. */
	public static void onBlockChanged(Level level, BlockPos pos, BlockState from, BlockState to) {
		if (ACTIVE.isEmpty() || from == to) {
			return;
		}
		for (BlockWatch watch : ACTIVE) {
			if (watch.level == level && watch.contains(pos)) {
				watch.record(pos.immutable(), from, to);
			}
		}
	}

	/**
	 * Called on the server thread at the end of every tick, after {@link ClientCatchUp#onServerTickEnd}:
	 * a tick sends pistons' changes with the next tick's, so the ping for a watch ending now must
	 * wait for the end of the next tick.
	 */
	public static void onServerTickEnd(MinecraftServer server) {
		int tick = server.getTickCount();
		for (BlockWatch watch : ACTIVE) {
			if (watch.player != null) {
				watch.tickEnded(tick);
			}
		}
	}

	/** Called on the client thread at the start of every tick, before the tick's changes; the packets of a server tick arrive between client ticks. */
	public static void onClientTick() {
		int tick = ++clientTicks;
		Level level = Minecraft.getInstance().level;
		for (BlockWatch watch : ACTIVE) {
			if (watch.player != null) {
				continue;
			}
			if (watch.level != level) {
				ACTIVE.remove(watch);
				watch.done.completeExceptionally(new ToolException(level == null ? "the world was left while watching" : "the player changed dimension while watching, so the box is no longer in view"));
				continue;
			}
			watch.tickEnded(tick);
		}
	}

	/** Called on the server thread as it stops; the watches can't finish. */
	public static void onServerStop() {
		for (BlockWatch watch : ACTIVE) {
			if (watch.player != null) {
				ACTIVE.remove(watch);
				watch.done.completeExceptionally(new ToolException("the world was closed while watching"));
			}
		}
	}

	private void tickEnded(int tick) {
		lastTick = tick;
		if (tick - startTick >= ticks) {
			ACTIVE.remove(this);
			done.complete(new Done(describe(), player == null ? CompletableFuture.completedFuture(true) : ClientCatchUp.afterChanges(player)));
		}
	}

	private int currentTick() {
		return player == null ? clientTicks : level.getServer().getTickCount();
	}

	private boolean contains(BlockPos pos) {
		return pos.getX() >= min.getX() && pos.getX() <= max.getX()
			&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
			&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	private void record(BlockPos pos, BlockState from, BlockState to) {
		before.putIfAbsent(pos, from);
		if (changes.size() < MAX_LISTED_CHANGES) {
			changes.add(new Change(currentTick() - startTick, pos, from, to));
		} else {
			unlisted++;
		}
	}

	private String describe() {
		StringBuilder out = new StringBuilder("watched ").append(WorldQuery.coordinates(min)).append(" to ").append(WorldQuery.coordinates(max))
			.append(" for ").append(ticks).append(ticks == 1 ? " tick" : " ticks");
		if (player == null) {
			out.append(", as the client received the changes (a tick's changes arrive together, and a block that changed twice in one tick shows only how it ended up)");
		}
		int total = changes.size() + unlisted;
		if (total == 0) {
			return out.append("; nothing in the box changed\n").toString();
		}
		out.append("; ").append(total).append(total == 1 ? " change" : " changes").append(", in order, by ticks since watching began:\n");
		for (Change change : changes) {
			out.append("tick ").append(change.tick()).append(": ").append(WorldQuery.coordinates(change.pos())).append(' ').append(describe(change.from(), change.to())).append('\n');
		}
		if (unlisted > 0) {
			out.append("and ").append(unlisted).append(" more; watch a smaller box or fewer ticks to see them\n");
		}

		List<String> differences = new ArrayList<>();
		before.forEach((pos, state) -> {
			// Reading an unloaded chunk would load it; the player may have moved away while watching.
			if (!level.isLoaded(pos)) {
				differences.add(WorldQuery.coordinates(pos) + " is no longer loaded");
				return;
			}
			BlockState now = level.getBlockState(pos);
			if (now != state) {
				differences.add(WorldQuery.coordinates(pos) + ' ' + describe(state, now));
			}
		});
		if (differences.isEmpty()) {
			out.append("at the end, every block that changed was back as it started\n");
		} else {
			out.append("at the end, ").append(differences.size()).append(differences.size() == 1 ? " position differed" : " positions differed").append(" from the start:\n");
			differences.stream().limit(MAX_LISTED_DIFFERENCES).forEach(difference -> out.append("  ").append(difference).append('\n'));
			if (differences.size() > MAX_LISTED_DIFFERENCES) {
				out.append("  and ").append(differences.size() - MAX_LISTED_DIFFERENCES).append(" more\n");
			}
		}
		return out.toString();
	}

	/** E.g. "minecraft:repeater powered false -> true", or both full states when the block itself changed. */
	private static String describe(BlockState from, BlockState to) {
		if (from.getBlock() != to.getBlock()) {
			return WorldQuery.describe(from) + " -> " + WorldQuery.describe(to);
		}
		List<String> properties = new ArrayList<>();
		for (Property<?> property : from.getProperties()) {
			String was = valueName(from, property);
			String is = valueName(to, property);
			if (!was.equals(is)) {
				properties.add(property.getName() + " " + was + " -> " + is);
			}
		}
		return BuiltInRegistries.BLOCK.getKey(from.getBlock()) + " " + String.join(", ", properties);
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}
}
