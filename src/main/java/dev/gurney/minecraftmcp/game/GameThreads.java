package dev.gurney.minecraftmcp.game;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.util.thread.BlockableEventLoop;

/** Runs work on the client or integrated-server thread and waits for the result. */
public final class GameThreads {
	private static final long TIMEOUT_SECONDS = 10;

	private GameThreads() {
	}

	public static <T> T onClient(Supplier<T> work) {
		return on(Minecraft.getInstance(), "client", work);
	}

	public static <T> T on(BlockableEventLoop<?> loop, String name, Supplier<T> work) {
		return await(loop.submit(work), TIMEOUT_SECONDS, "the game's " + name + " thread did not respond within " + TIMEOUT_SECONDS + "s (is the game loading or frozen?)");
	}

	public static <T> T await(CompletableFuture<T> future, long timeoutSeconds, String timeoutMessage) {
		try {
			return future.get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			future.cancel(false);
			throw new ToolException(timeoutMessage);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
