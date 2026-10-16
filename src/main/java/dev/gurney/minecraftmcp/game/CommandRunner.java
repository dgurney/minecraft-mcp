package dev.gurney.minecraftmcp.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Runs commands on the integrated server as the current player, and collects the feedback the
 * command sends back. Feedback is also shown in the player's chat, unless the sendCommandFeedback
 * game rule turns that off. Needs cheats to be enabled.
 */
public final class CommandRunner {
	public record Result(boolean succeeded, List<Integer> results, List<String> output) {
	}

	private CommandRunner() {
	}

	public static Result run(String command) {
		return OperatorAccess.onServer(player -> {
			boolean echo = player.level().getGameRules().get(GameRules.SEND_COMMAND_FEEDBACK);
			List<String> output = new ArrayList<>();
			List<Integer> results = new ArrayList<>();
			boolean[] succeeded = {false};
			CommandSource capture = new CommandSource() {
				@Override
				public void sendSystemMessage(Component message) {
					output.add(message.getString());
					if (echo) {
						player.sendSystemMessage(message);
					}
				}

				@Override
				public boolean acceptsSuccess() {
					return true;
				}

				@Override
				public boolean acceptsFailure() {
					return true;
				}

				// The player already sees the feedback; informing admins would echo it to them a second time.
				@Override
				public boolean shouldInformAdmins() {
					return false;
				}
			};
			CommandResultCallback callback = (success, result) -> {
				succeeded[0] |= success;
				if (success) {
					results.add(result);
				}
			};
			CommandSourceStack source = player.createCommandSourceStack().withSource(capture).withCallback(callback);
			String trimmed = command.startsWith("/") ? command.substring(1) : command;
			if (echo) {
				player.sendSystemMessage(Component.literal("[MCP] /" + trimmed).withStyle(ChatFormatting.GRAY));
			}
			player.level().getServer().getCommands().performPrefixedCommand(source, trimmed);
			return new Result(succeeded[0], results, output);
		});
	}
}
