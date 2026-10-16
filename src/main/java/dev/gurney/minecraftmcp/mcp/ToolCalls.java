package dev.gurney.minecraftmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.gurney.minecraftmcp.game.AgentActivity;
import dev.gurney.minecraftmcp.game.CommandRunner;
import dev.gurney.minecraftmcp.game.GameThreads;
import dev.gurney.minecraftmcp.game.ScreenCapture;
import dev.gurney.minecraftmcp.game.StatusReport;
import dev.gurney.minecraftmcp.game.ToolException;
import dev.gurney.minecraftmcp.game.VirtualInput;
import dev.gurney.minecraftmcp.game.WorldQuery;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Implements the tools described in tools.json. Calls are serialized because they all drive one player. */
final class ToolCalls {
	private static final int MAX_SEQUENCE_TICKS = 1200;
	/** Lets the frame showing the last action render before a screenshot is taken. */
	private static final int SETTLE_TICKS = 2;
	private static final long STALL_SECONDS = 10;

	private static final ReentrantLock LOCK = new ReentrantLock(true);

	private ToolCalls() {
	}

	/** Returns a CallToolResult. Throws {@link UnknownToolException} for names that aren't tools. */
	static JsonObject call(String name, JsonObject arguments) {
		LOCK.lock();
		try {
			AgentActivity.touch();
			return switch (name) {
				case "get_status" -> result(false, text(StatusReport.detailed()));
				case "screenshot" -> result(false, image(ScreenCapture.captureJpeg(arguments.has("hud") && bool(arguments.get("hud"), "hud"))), text(StatusReport.brief()));
				case "get_blocks" -> result(false, text(WorldQuery.blocks(blockPos(arguments, "from"), blockPos(arguments, "to"))));
				case "get_entities" -> result(false, text(WorldQuery.entities(arguments.has("radius") ? number(arguments, "radius") : 16)));
				case "run_command" -> runCommand(arguments);
				case "input" -> input(arguments);
				default -> throw new UnknownToolException(name);
			};
		} catch (ToolException e) {
			return result(true, text(e.getMessage()));
		} finally {
			AgentActivity.touch();
			LOCK.unlock();
		}
	}

	static final class UnknownToolException extends RuntimeException {
		UnknownToolException(String name) {
			super("unknown tool: " + name);
		}
	}

	private static JsonObject runCommand(JsonObject arguments) {
		String command = requiredString(arguments, "command");
		CommandRunner.Result run = CommandRunner.run(command);
		StringBuilder out = new StringBuilder(run.succeeded() ? "command succeeded" : "command failed");
		if (!run.results().isEmpty()) {
			out.append(" (result ").append(run.results().size() == 1 ? run.results().getFirst() : run.results()).append(')');
		}
		if (run.output().isEmpty()) {
			out.append(", no output");
		} else {
			out.append(", output:");
			run.output().forEach(line -> out.append("\n").append(line));
		}
		return result(!run.succeeded(), text(out.toString()));
	}

	private static JsonObject input(JsonObject arguments) {
		JsonElement actions = arguments.get("actions");
		if (actions == null || !actions.isJsonArray() || actions.getAsJsonArray().isEmpty()) {
			throw new ToolException("actions must be a non-empty array");
		}
		boolean screenshot = !arguments.has("screenshot") || bool(arguments.get("screenshot"), "screenshot");
		List<VirtualInput.Step> steps = GameThreads.onClient(() -> parseActions(actions.getAsJsonArray()));
		int ticks = steps.stream().mapToInt(step -> step instanceof VirtualInput.Wait(int waitTicks) ? waitTicks : 1).sum();
		if (ticks > MAX_SEQUENCE_TICKS) {
			throw new ToolException("the sequence takes " + ticks + " ticks, more than the limit of " + MAX_SEQUENCE_TICKS + "; split it into several calls");
		}
		if (screenshot) {
			steps.add(new VirtualInput.Wait(SETTLE_TICKS));
		}

		if (!awaitSequence(GameThreads.onClient(() -> VirtualInput.run(steps)))) {
			if (VirtualInput.stepsStarted() == 0) {
				throw new ToolException("the game stopped ticking for " + STALL_SECONDS + "s before any action ran (is it loading or frozen?); nothing was done");
			}
			// Some actions did happen (e.g. a click that started loading a world); an error result would invite repeating them.
			return result(false, text("part of the sequence ran, then the game stopped ticking for " + STALL_SECONDS
				+ "s (it may be loading or saving a world); the rest was abandoned and all keys were released. Check the current state before continuing."));
		}
		if (screenshot) {
			try {
				return result(false, image(ScreenCapture.captureJpeg(false)), text(StatusReport.brief()));
			} catch (ToolException e) {
				// The actions did happen; an error result would invite the agent to repeat them.
				return result(false, text("the actions were performed, but the screenshot failed: " + e.getMessage() + "\n" + StatusReport.brief()));
			}
		}
		return result(false, text(StatusReport.brief()));
	}

	/**
	 * Waits for an input sequence, giving up only if the game stops ticking (the tick rate can be
	 * changed). Returns false if it gave up.
	 */
	private static boolean awaitSequence(CompletableFuture<Void> done) {
		long lastTicks = VirtualInput.ticksRun();
		long lastProgress = System.nanoTime();
		while (true) {
			try {
				done.get(1, TimeUnit.SECONDS);
				return true;
			} catch (TimeoutException e) {
				long ticks = VirtualInput.ticksRun();
				if (ticks != lastTicks) {
					lastTicks = ticks;
					lastProgress = System.nanoTime();
				} else if (System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(STALL_SECONDS)) {
					// Not waited for: the client thread may be busy too. Queued tasks run before the next tick, so
					// the sequence can't continue first.
					Minecraft.getInstance().execute(VirtualInput::reset);
					return false;
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

	private static List<VirtualInput.Step> parseActions(JsonArray actions) {
		List<VirtualInput.Step> steps = new ArrayList<>();
		for (int i = 0; i < actions.size(); i++) {
			if (!actions.get(i).isJsonObject()) {
				throw new ToolException("actions[" + i + "] must be an object");
			}
			JsonObject action = actions.get(i).getAsJsonObject();
			String type = requiredString(action, "type");
			try {
				switch (type) {
					case "press" -> {
						List<InputConstants.Key> keys = keyCombination(requiredString(action, "key"));
						keys.forEach(key -> steps.add(new VirtualInput.KeyDown(key)));
						keys.reversed().forEach(key -> steps.add(new VirtualInput.KeyUp(key)));
					}
					case "hold" -> {
						List<InputConstants.Key> keys = keyCombination(requiredString(action, "key"));
						keys.forEach(key -> steps.add(new VirtualInput.KeyDown(key)));
						steps.add(new VirtualInput.Wait(positiveInt(action, "ticks")));
						keys.reversed().forEach(key -> steps.add(new VirtualInput.KeyUp(key)));
					}
					case "key_down" -> keyCombination(requiredString(action, "key")).forEach(key -> steps.add(new VirtualInput.KeyDown(key)));
					case "key_up" -> keyCombination(requiredString(action, "key")).reversed().forEach(key -> steps.add(new VirtualInput.KeyUp(key)));
					case "click" -> {
						if (action.has("x") || action.has("y")) {
							steps.add(new VirtualInput.MoveCursor(number(action, "x"), number(action, "y")));
						}
						String button = action.has("button") ? requiredString(action, "button") : "left";
						if (!List.of("left", "right", "middle").contains(button)) {
							throw new ToolException("button must be left, right or middle");
						}
						InputConstants.Key key = VirtualInput.key("mouse." + button);
						steps.add(new VirtualInput.KeyDown(key));
						steps.add(new VirtualInput.KeyUp(key));
					}
					case "move" -> steps.add(new VirtualInput.MoveCursor(number(action, "x"), number(action, "y")));
					case "scroll" -> steps.add(new VirtualInput.Scroll(number(action, "amount")));
					case "type" -> steps.add(new VirtualInput.TypeText(requiredString(action, "text")));
					case "look" -> {
						if (!action.has("yaw") && !action.has("pitch")) {
							throw new ToolException("look needs yaw, pitch or both");
						}
						steps.add(new VirtualInput.Look(
							action.has("yaw") ? (float) number(action, "yaw") : null,
							action.has("pitch") ? (float) number(action, "pitch") : null));
					}
					case "look_at" -> steps.add(new VirtualInput.LookAt(number(action, "x"), number(action, "y"), number(action, "z")));
					case "wait" -> steps.add(new VirtualInput.Wait(positiveInt(action, "ticks")));
					default -> throw new ToolException("unknown action type \"" + type + "\"");
				}
			} catch (ToolException e) {
				throw new ToolException("actions[" + i + "]: " + e.getMessage());
			}
		}
		return steps;
	}

	private static List<InputConstants.Key> keyCombination(String combination) {
		return List.of(combination.split("\\+")).stream().map(VirtualInput::key).toList();
	}

	private static BlockPos blockPos(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) {
			throw new ToolException(field + " must be an array of three integers [x, y, z]");
		}
		int[] coordinates = new int[3];
		for (int i = 0; i < 3; i++) {
			JsonElement coordinate = value.getAsJsonArray().get(i);
			if (!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber() || coordinate.getAsDouble() != Math.rint(coordinate.getAsDouble())) {
				throw new ToolException(field + " must be an array of three integers [x, y, z]");
			}
			if (Math.abs(coordinate.getAsDouble()) > Level.MAX_LEVEL_SIZE) {
				throw new ToolException(field + " coordinates must be within " + Level.MAX_LEVEL_SIZE + " of 0");
			}
			coordinates[i] = coordinate.getAsInt();
		}
		return new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
	}

	private static String requiredString(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new ToolException(field + " must be a string");
		}
		return value.getAsString();
	}

	private static double number(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new ToolException(field + " must be a number");
		}
		return value.getAsDouble();
	}

	private static int positiveInt(JsonObject object, String field) {
		double value = number(object, field);
		if (value != Math.rint(value) || value < 1 || value > MAX_SEQUENCE_TICKS) {
			throw new ToolException(field + " must be a whole number from 1 to " + MAX_SEQUENCE_TICKS);
		}
		return (int) value;
	}

	private static boolean bool(JsonElement value, String field) {
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw new ToolException(field + " must be true or false");
		}
		return value.getAsBoolean();
	}

	private static JsonObject text(String text) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		return content;
	}

	private static JsonObject image(byte[] jpeg) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "image");
		content.addProperty("data", Base64.getEncoder().encodeToString(jpeg));
		content.addProperty("mimeType", "image/jpeg");
		return content;
	}

	private static JsonObject result(boolean isError, JsonObject... content) {
		JsonObject result = new JsonObject();
		JsonArray blocks = new JsonArray();
		for (JsonObject block : content) {
			blocks.add(block);
		}
		result.add("content", blocks);
		if (isError) {
			result.add("isError", new JsonPrimitive(true));
		}
		return result;
	}
}
