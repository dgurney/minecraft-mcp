package dev.gurney.minecraftmcp.game;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * Runs cheat-level work on the integrated server as the current player, then waits until the
 * client has received what it changed. Needs cheats to be enabled.
 */
final class OperatorAccess {
	/** Longer than other waits on the game, since a batch of large /fill commands can take a while. */
	private static final long SERVER_TIMEOUT_SECONDS = 60;

	private OperatorAccess() {
	}

	static <T> ClientCatchUp.Outcome<T> onServer(Function<ServerPlayer, T> work) {
		record Done<T>(T value, CompletableFuture<Boolean> seen) {
		}
		Done<T> done = run(player -> new Done<>(work.apply(player), ClientCatchUp.afterChanges(player)));
		return new ClientCatchUp.Outcome<>(done.value(), ClientCatchUp.await(done.seen()));
	}

	/** Like {@link #onServer} for work that only reads the server's world, so there is nothing for the client to catch up on. */
	static <T> T readOnServer(Function<ServerPlayer, T> work) {
		return run(work);
	}

	private static <T> T run(Function<ServerPlayer, T> work) {
		record Target(IntegratedServer server, UUID player) {
		}
		Target target = GameThreads.onClient(() -> {
			Minecraft minecraft = Minecraft.getInstance();
			IntegratedServer server = minecraft.getSingleplayerServer();
			if (server == null || minecraft.player == null) {
				throw new ToolException("not in a singleplayer world");
			}
			if (!minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
				throw new ToolException("this needs cheats, which are off in this world (Allow Commands in the pause menu's World Options); use the other tools instead");
			}
			return new Target(server, minecraft.player.getUUID());
		});

		return GameThreads.await(target.server().submit(() -> {
			ServerPlayer player = target.server().getPlayerList().getPlayer(target.player());
			if (player == null) {
				throw new ToolException("the player is not on the server yet; try again in a moment");
			}
			return work.apply(player);
		}), SERVER_TIMEOUT_SECONDS, "the server was still busy with this after " + SERVER_TIMEOUT_SECONDS
			+ "s (or the game is frozen); it may still finish, so check the world before trying again");
	}
}
