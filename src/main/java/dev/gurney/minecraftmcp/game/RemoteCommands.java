package dev.gurney.minecraftmcp.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.ChatVisiblity;

/**
 * Runs commands by sending them to the server, as typing them in chat does, and collects the
 * feedback the server sends back to chat. For when the game isn't hosting the world with cheats
 * on: multiplayer servers, and singleplayer worlds without cheats. The server runs what the
 * player's permissions allow.
 *
 * <p>Each command is followed by a /tellraw of a marker to the player, which tells the commands'
 * output apart and says when the server has run them. The server sends a command's chat feedback
 * as soon as it runs, but the blocks and entities it changed only with the next tick, so a last
 * marker is sent a few ticks later: when it arrives, the changes have too. A dedicated server
 * kicks a player who isn't in its op list for sending a 10th command within 10 seconds, and the
 * client can't see that list, so only operators send like that. Other players run commands one
 * at a time, each waiting a moment for its output, which also keeps them under the limit.
 */
public final class RemoteCommands {
	private static final String MARKER_PREFIX = "minecraft-mcp:";
	/** Found anywhere in the text, as a server may decorate what it sends to chat. */
	private static final Pattern MARKER = Pattern.compile(Pattern.quote(MARKER_PREFIX) + "(\\d{1,9})");
	/** Each unmarked command waits {@link #OUTPUT_WAIT_MILLIS} for its output, so a call stays short. */
	static final int MAX_UNMARKED_COMMANDS = 8;
	private static final long OUTPUT_WAIT_MILLIS = 1000;
	private static final long SERVER_TIMEOUT_SECONDS = 60;
	/** Client ticks between the last command running and the catch-up marker: at least two server ticks, as the client ticks at the server's rate. */
	private static final int CATCH_UP_TICKS = 3;

	private static int nextMarker;
	/** The batch whose output is being collected; only touched on the client thread. */
	private static Batch current;

	private static final class Batch {
		final List<String> commands;
		final List<CommandRunner.Result> results = new ArrayList<>();
		final List<String> output = new ArrayList<>();
		boolean failed;
		/** The marker that ends the command whose output is being collected, or the catch-up marker once all have run. */
		int expectedMarker;
		final CompletableFuture<List<CommandRunner.Result>> done = new CompletableFuture<>();
		final CompletableFuture<Boolean> caughtUp = new CompletableFuture<>();
		int ticksUntilCatchUp = -1;

		Batch(List<String> commands) {
			this.commands = commands;
		}
	}

	private RemoteCommands() {
	}

	static ClientCatchUp.Outcome<List<CommandRunner.Result>> run(List<String> commands) {
		for (String command : commands) {
			// The server disconnects a player who sends these, rather than answering with an error.
			if (command.chars().anyMatch(c -> c < ' ' || c == 127 || c == '\u00a7')) {
				throw new ToolException("commands can't contain line breaks, control characters or \u00a7: /" + strip(command));
			}
		}
		if (GameThreads.onClient(RemoteCommands::canMark)) {
			return runMarked(commands);
		}
		if (commands.size() > MAX_UNMARKED_COMMANDS) {
			throw new ToolException("without operator rights, commands run one at a time, each waiting a second for its output, so a call takes at most "
				+ MAX_UNMARKED_COMMANDS + "; split the batch");
		}
		List<CommandRunner.Result> results = new ArrayList<>();
		for (String command : commands) {
			results.add(runUnmarked(command));
		}
		return new ClientCatchUp.Outcome<>(results, true);
	}

	/** Whether the player may use /tellraw (the server lists only the commands the player may use) without risking the spam kick. Call on the client thread. */
	private static boolean canMark() {
		LocalPlayer player = requirePlayer();
		if (Minecraft.getInstance().options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
			throw new ToolException("the server doesn't send command output while chat is hidden; set Chat to Shown or Commands Only in Options > Chat Settings");
		}
		return player.connection.getCommands().getRoot().getChild("tellraw") != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static ClientCatchUp.Outcome<List<CommandRunner.Result>> runMarked(List<String> commands) {
		Batch batch = GameThreads.onClient(() -> {
			LocalPlayer player = requirePlayer();
			Batch started = new Batch(commands);
			started.expectedMarker = nextMarker;
			for (String command : commands) {
				send(player, command);
				sendMarker(player, nextMarker++);
			}
			current = started;
			return started;
		});
		try {
			List<CommandRunner.Result> results = GameThreads.await(batch.done, SERVER_TIMEOUT_SECONDS, "the server hadn't run all of this after " + SERVER_TIMEOUT_SECONDS
				+ "s (it may be lagging, or not be sending chat); it may still finish, so check the world before trying again");
			return new ClientCatchUp.Outcome<>(results, ClientCatchUp.await(batch.caughtUp));
		} finally {
			Minecraft.getInstance().execute(() -> {
				if (current == batch) {
					current = null;
				}
			});
		}
	}

	private static CommandRunner.Result runUnmarked(String command) {
		Batch batch = GameThreads.onClient(() -> {
			Batch started = new Batch(List.of(command));
			// Not a marker's number, so a stale marker can't end this command early.
			started.expectedMarker = -1;
			send(requirePlayer(), command);
			current = started;
			return started;
		});
		try {
			Thread.sleep(OUTPUT_WAIT_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		return GameThreads.onClient(() -> {
			if (batch.done.isCompletedExceptionally()) {
				// Only onTick fails it, with a ToolException, when the player leaves the world.
				throw (ToolException) batch.done.exceptionNow();
			}
			current = null;
			return new CommandRunner.Result(strip(command), !batch.failed, List.of(), List.copyOf(batch.output));
		});
	}

	private static void send(LocalPlayer player, String command) {
		String trimmed = strip(command);
		Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[MCP] /" + trimmed).withStyle(ChatFormatting.GRAY));
		player.connection.sendCommand(trimmed);
	}

	/** The player's name rather than @s, which needs a permission of its own. */
	private static void sendMarker(LocalPlayer player, int marker) {
		player.connection.sendCommand("tellraw " + player.getGameProfile().name() + " \"" + MARKER_PREFIX + marker + "\"");
	}

	private static String strip(String command) {
		return command.startsWith("/") ? command.substring(1) : command;
	}

	private static LocalPlayer requirePlayer() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			throw new ToolException("not in a world");
		}
		return player;
	}

	/**
	 * Called on the client thread for every system chat message from the server, in order with the
	 * packets before it. Returns true if the message was a marker, which is kept out of chat.
	 */
	public static boolean onSystemMessage(Component message) {
		String text = message.getString();
		Matcher found = MARKER.matcher(text);
		if (!found.find()) {
			if (current != null && current.results.size() < current.commands.size()) {
				current.output.add(text);
				current.failed |= isFailure(message);
			}
			return false;
		}
		int marker = Integer.parseInt(found.group(1));
		// Markers of a batch that was given up on may still arrive; they are stale, but still not chat.
		if (current == null || marker != current.expectedMarker) {
			return true;
		}
		Batch batch = current;
		if (batch.results.size() < batch.commands.size()) {
			batch.results.add(new CommandRunner.Result(strip(batch.commands.get(batch.results.size())), !batch.failed, List.of(), List.copyOf(batch.output)));
			batch.output.clear();
			batch.failed = false;
			batch.expectedMarker++;
			if (batch.results.size() == batch.commands.size()) {
				batch.ticksUntilCatchUp = CATCH_UP_TICKS;
				batch.done.complete(List.copyOf(batch.results));
			}
		} else {
			batch.caughtUp.complete(true);
		}
		return true;
	}

	/**
	 * Command failures arrive as an empty red component holding the message (Paper adds the
	 * context of a syntax error after it); other red messages, like broadcasts, hold their text.
	 */
	private static boolean isFailure(Component message) {
		List<Component> parts = message.getSiblings();
		return TextColor.RED.equals(message.getStyle().getColor()) && message.getContents() == PlainTextContents.EMPTY
			&& (parts.size() == 1 || !parts.isEmpty() && parts.getFirst().getContents() instanceof TranslatableContents);
	}

	/** Called on the client thread every tick: sends the catch-up marker when due, and gives up on a batch once the player has left the world. */
	public static void onTick() {
		if (current == null) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			current.done.completeExceptionally(new ToolException("the world was left before the server had run all of this"));
			current.caughtUp.complete(false);
			current = null;
			return;
		}
		if (current.ticksUntilCatchUp > 0 && --current.ticksUntilCatchUp == 0) {
			current.expectedMarker = nextMarker;
			sendMarker(player, nextMarker++);
		}
	}
}
