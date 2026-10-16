package dev.gurney.minecraftmcp.game;

import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** Runs cheat-level work on the integrated server as the current player. Needs cheats to be enabled. */
final class OperatorAccess {
	private OperatorAccess() {
	}

	static <T> T onServer(Function<ServerPlayer, T> work) {
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

		return GameThreads.on(target.server(), "server", () -> {
			ServerPlayer player = target.server().getPlayerList().getPlayer(target.player());
			if (player == null) {
				throw new ToolException("the player is not on the server yet; try again in a moment");
			}
			return work.apply(player);
		});
	}
}
