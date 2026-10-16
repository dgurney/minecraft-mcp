package dev.gurney.minecraftmcp.game;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lets server work wait until the client has received what it changed, so the tools that read
 * the client's world see it. The server sends changed blocks and entities to the client at the
 * end of its next tick; a ping sent after that arrives once the client has handled them too.
 */
public final class ClientCatchUp {
	private static final long TIMEOUT_SECONDS = 10;
	public static final String LAGGING_NOTE = "the game hadn't received the changes after " + TIMEOUT_SECONDS
		+ "s, so get_blocks, get_surface, get_entities and screenshots may not show them yet\n";

	private static final AtomicInteger NEXT_ID = new AtomicInteger();
	/** Completed with true once the client handles the ping, or false if it never will. */
	private static final Map<Integer, CompletableFuture<Boolean>> WAITING = new ConcurrentHashMap<>();
	private static final Queue<Runnable> AFTER_TICK = new ConcurrentLinkedQueue<>();

	/** What server work returned, and whether the client had received its changes when the call returned. */
	public record Outcome<T>(T value, boolean clientCaughtUp) {
	}

	private ClientCatchUp() {
	}

	/** Call on the server thread after changing the world; completes once the client has seen the change. */
	static CompletableFuture<Boolean> afterChanges(ServerPlayer player) {
		int id = NEXT_ID.getAndIncrement();
		CompletableFuture<Boolean> seen = new CompletableFuture<>();
		WAITING.put(id, seen);
		AFTER_TICK.add(() -> player.connection.send(new ClientboundPingPacket(id)));
		return seen;
	}

	/** Waits for {@link #afterChanges}. Returns false if the client didn't catch up within the timeout. */
	static boolean await(CompletableFuture<Boolean> seen) {
		boolean caughtUp = seen.completeOnTimeout(false, TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
		WAITING.values().remove(seen);
		return caughtUp;
	}

	/** Called on the server thread at the end of every tick, once it has sent the tick's changes. */
	public static void onServerTickEnd() {
		for (Runnable task; (task = AFTER_TICK.poll()) != null; ) {
			task.run();
		}
	}

	/** Called on the server thread as it stops, e.g. when the world is closed; nothing more will reach the client. */
	public static void onServerStop() {
		AFTER_TICK.clear();
		WAITING.values().forEach(seen -> seen.complete(false));
	}

	/** Called on the client thread for every ping from the server, in order with the packets before it. */
	public static void onClientPing(int id) {
		CompletableFuture<Boolean> seen = WAITING.remove(id);
		if (seen != null) {
			seen.complete(true);
		}
	}
}
