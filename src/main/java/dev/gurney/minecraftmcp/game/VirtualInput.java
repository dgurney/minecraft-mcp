package dev.gurney.minecraftmcp.game;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.gurney.minecraftmcp.mixin.KeyMappingAccessor;
import dev.gurney.minecraftmcp.mixin.MouseHandlerAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * A virtual keyboard and mouse that feeds the game's own input handlers, so input works the same
 * way a human's would, including while the window is unfocused. Steps run one per client tick so
 * that each one sees the effect of the previous one (e.g. a screen opened by a key press).
 * Everything here runs on the client thread.
 */
public final class VirtualInput {
	public sealed interface Step {
	}

	public record KeyDown(InputConstants.Key key) implements Step {
	}

	public record KeyUp(InputConstants.Key key) implements Step {
	}

	/** Moves the cursor to a position in screenshot image coordinates. */
	public record MoveCursor(double imageX, double imageY) implements Step {
	}

	public record Scroll(double amount) implements Step {
	}

	public record TypeText(String text) implements Step {
	}

	/** Sets the player's view direction; a null angle keeps the current one. */
	public record Look(Float yaw, Float pitch) implements Step {
	}

	/** Turns the player's view toward a point in the world. */
	public record LookAt(double x, double y, double z) implements Step {
	}

	/**
	 * Aims at a block the way a player would; a null face aims at whichever visible face points most
	 * toward the player. Takes no tick of its own, so a click right after it uses the aim that was checked.
	 */
	public record AimAtBlock(BlockPos pos, Direction face) implements Step {
	}

	public record Wait(int ticks) implements Step {
	}

	private static final Map<String, String> KEY_ALIASES = Map.of(
		"shift", "left.shift",
		"ctrl", "left.control",
		"control", "left.control",
		"alt", "left.alt",
		"esc", "escape",
		"return", "enter"
	);

	private record ImagePoint(double x, double y) {
	}

	private static final ArrayDeque<Step> pending = new ArrayDeque<>();
	private static CompletableFuture<Void> pendingDone;
	private static int waitRemaining;
	/** Ticks spent running sequences; lets waiting callers tell a slow game from a stuck one. */
	private static volatile long ticksRun;
	private static volatile int stepsStarted;
	private static final Set<InputConstants.Key> held = new LinkedHashSet<>();
	private static MouseButtonInfo heldButton;
	/** Where the agent last put the cursor; null until it first moves it. */
	private static ImagePoint agentCursor;

	private VirtualInput() {
	}

	/** Resolves a key name such as "w", "left.shift", "f3" or "mouse.left". */
	public static InputConstants.Key key(String name) {
		String lower = KEY_ALIASES.getOrDefault(name.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
		String full = lower.startsWith("key.") ? lower : lower.startsWith("mouse.") ? "key." + lower : "key.keyboard." + lower;
		try {
			InputConstants.Key key = InputConstants.getKey(full);
			if (key == InputConstants.UNKNOWN) {
				throw new IllegalArgumentException();
			}
			return key;
		} catch (IllegalArgumentException e) {
			throw new ToolException("unknown key \"" + name + "\"; use names like w, space, left.shift, left.control, escape, enter, e, 1, f3, mouse.left, mouse.right");
		}
	}

	public static CompletableFuture<Void> run(List<Step> steps) {
		if (pendingDone != null) {
			throw new IllegalStateException("an input sequence is already running");
		}
		pending.addAll(steps);
		waitRemaining = 0;
		stepsStarted = 0;
		pendingDone = new CompletableFuture<>();
		return pendingDone;
	}

	public static long ticksRun() {
		return ticksRun;
	}

	/** Non-wait steps of the current or last sequence that have started; one may still be running. */
	public static int stepsStarted() {
		return stepsStarted;
	}

	/** Releases every held key and button and drops any unfinished sequence. */
	public static void reset() {
		pending.clear();
		waitRemaining = 0;
		for (InputConstants.Key key : new ArrayList<>(held)) {
			release(Minecraft.getInstance(), key);
		}
		if (pendingDone != null) {
			finish();
		}
	}

	public static List<String> heldKeyNames() {
		return held.stream().map(VirtualInput::keyName).toList();
	}

	private static String keyName(InputConstants.Key key) {
		return key.getName().replaceFirst("^key\\.(keyboard\\.)?", "");
	}

	public static void onTick() {
		if (!held.isEmpty() && !AgentActivity.isActive()) {
			// The agent went away; don't leave the player walking or mining unattended.
			reset();
		}
		if (pendingDone == null) {
			return;
		}
		AgentActivity.touch();
		ticksRun++;
		if (waitRemaining > 0) {
			waitRemaining--;
		} else {
			Step step;
			do {
				step = pending.poll();
				if (step instanceof Wait wait) {
					waitRemaining = wait.ticks() - 1;
				} else if (step != null) {
					try {
						// Counted before running, since a step like "Save and Quit" can block inside perform for a while.
						stepsStarted++;
						perform(Minecraft.getInstance(), step);
					} catch (ToolException e) {
						StringBuilder message = new StringBuilder(e.getMessage());
						if (stepsStarted > 1) {
							message.append("; the actions before it ran and the rest were skipped");
						}
						// The skipped steps would have released these keys, so don't leave the player walking.
						List<InputConstants.Key> releasing = new ArrayList<>();
						for (Step skipped : pending) {
							if (skipped instanceof KeyUp(InputConstants.Key key) && held.contains(key) && !releasing.contains(key)) {
								releasing.add(key);
							}
						}
						pending.clear();
						if (!releasing.isEmpty()) {
							message.append("; released the keys the rest would have released (").append(String.join(", ", releasing.stream().map(VirtualInput::keyName).toList())).append(')');
							releasing.forEach(key -> release(Minecraft.getInstance(), key));
						}
						CompletableFuture<Void> done = pendingDone;
						pendingDone = null;
						done.completeExceptionally(new ToolException(message.toString()));
						return;
					}
				}
				// The player moves later in this tick, after the game has handled clicks, so a click in the
				// next tick could miss what the aim checked.
			} while (step instanceof AimAtBlock && !(pending.peek() instanceof Wait));
		}
		if (pending.isEmpty() && waitRemaining == 0) {
			finish();
		}
	}

	/**
	 * Closing a screen leaves the game's key bindings released. Like a physical keyboard, keys the
	 * agent still holds count as pressed again.
	 */
	public static void onScreenClosed() {
		for (KeyMapping mapping : KeyMappingAccessor.minecraftMcp$all().values()) {
			// The same filter vanilla uses when it re-reads the physical keyboard; it skips toggle keys.
			if (((KeyMappingAccessor) mapping).minecraftMcp$shouldSetOnIngameFocus() && held.stream().anyMatch(mapping::matches)) {
				mapping.setDown(true);
			}
		}
	}

	private static void finish() {
		CompletableFuture<Void> done = pendingDone;
		pendingDone = null;
		done.complete(null);
	}

	private static void perform(Minecraft minecraft, Step step) {
		long handle = minecraft.getWindow().handle();
		switch (step) {
			case KeyDown(InputConstants.Key key) -> {
				held.add(key);
				if (key.getType() == InputConstants.Type.MOUSE) {
					restoreAgentCursor(minecraft);
					heldButton = new MouseButtonInfo(key.getValue(), modifiers());
					minecraft.mouseHandler.onButton(handle, heldButton, 1);
				} else {
					minecraft.keyboardHandler.keyPress(handle, 1, keyEvent(key));
				}
			}
			case KeyUp(InputConstants.Key key) -> release(minecraft, key);
			case MoveCursor(double imageX, double imageY) -> moveCursor(minecraft, imageX, imageY);
			case Scroll(double amount) -> {
				restoreAgentCursor(minecraft);
				minecraft.mouseHandler.onScroll(handle, 0, amount);
			}
			case TypeText(String text) -> minecraft.keyboardHandler.textInput(handle, text);
			case Look(Float yaw, Float pitch) -> look(minecraft, yaw, pitch);
			case LookAt(double x, double y, double z) -> lookAt(minecraft, x, y, z);
			case AimAtBlock(BlockPos pos, Direction face) -> BlockAim.aim(minecraft, pos, face);
			case Wait wait -> throw new IllegalStateException("waits are handled by onTick");
		}
	}

	private static void release(Minecraft minecraft, InputConstants.Key key) {
		held.remove(key);
		long handle = minecraft.getWindow().handle();
		if (key.getType() == InputConstants.Type.MOUSE) {
			restoreAgentCursor(minecraft);
			minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(key.getValue(), modifiers()), 0);
			if (heldButton != null && heldButton.button() == key.getValue()) {
				heldButton = null;
			}
		} else {
			minecraft.keyboardHandler.keyPress(handle, 0, keyEvent(key));
		}
	}

	private static KeyEvent keyEvent(InputConstants.Key key) {
		int modifiers = modifiers();
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(key.getValue(), (short) modifiers, true);
		return new KeyEvent(key.getValue(), keycode, modifiers);
	}

	private static int modifiers() {
		int modifiers = 0;
		for (InputConstants.Key key : held) {
			modifiers |= switch (key.getName()) {
				case "key.keyboard.left.shift", "key.keyboard.right.shift" -> InputConstants.MOD_SHIFT;
				case "key.keyboard.left.control", "key.keyboard.right.control" -> InputConstants.MOD_CONTROL;
				case "key.keyboard.left.alt", "key.keyboard.right.alt" -> InputConstants.MOD_ALT;
				case "key.keyboard.left.win", "key.keyboard.right.win" -> InputConstants.MOD_SUPER;
				default -> 0;
			};
		}
		return modifiers;
	}

	/**
	 * The game also takes the cursor position from the user's real mouse (when it moves over the
	 * window, and whenever a screen opens), so put the agent's cursor back before using it.
	 */
	private static void restoreAgentCursor(Minecraft minecraft) {
		if (agentCursor != null) {
			setCursor(minecraft, agentCursor);
		}
	}

	private static void setCursor(Minecraft minecraft, ImagePoint point) {
		Window window = minecraft.getWindow();
		ScreenCapture.Size image = ScreenCapture.imageSize(window);
		if (image.width() == 0 || image.height() == 0) {
			// Minimized, so there is no image to map from; the cursor is put back once the window is restored.
			return;
		}
		MouseHandlerAccessor mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
		mouse.minecraftMcp$setXpos(point.x() * window.getScreenWidth() / image.width());
		mouse.minecraftMcp$setYpos(point.y() * window.getScreenHeight() / image.height());
	}

	private static void moveCursor(Minecraft minecraft, double imageX, double imageY) {
		Window window = minecraft.getWindow();
		if (window.isIconified()) {
			throw new ToolException("cannot move the cursor: the game window is minimized, so there is no screenshot for its coordinates; ask the user to restore it");
		}
		restoreAgentCursor(minecraft);
		double oldGuiX = minecraft.mouseHandler.getScaledXPos(window);
		double oldGuiY = minecraft.mouseHandler.getScaledYPos(window);
		agentCursor = new ImagePoint(imageX, imageY);
		setCursor(minecraft, agentCursor);

		// The game doesn't pass cursor movement to screens while unfocused, so do it directly.
		Screen screen = minecraft.gui.screen();
		if (screen == null) {
			return;
		}
		double guiX = minecraft.mouseHandler.getScaledXPos(window);
		double guiY = minecraft.mouseHandler.getScaledYPos(window);
		screen.mouseMoved(guiX, guiY);
		if (heldButton != null) {
			screen.mouseDragged(new MouseButtonEvent(guiX, guiY, heldButton), guiX - oldGuiX, guiY - oldGuiY);
		}
		screen.afterMouseMove();
	}

	static void lookAt(Minecraft minecraft, double x, double y, double z) {
		if (minecraft.player == null) {
			throw new ToolException("cannot look around: not in a world");
		}
		Vec3 eye = minecraft.player.getEyePosition();
		double dx = x - eye.x;
		double dy = y - eye.y;
		double dz = z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		look(minecraft, yaw, pitch);
	}

	static void look(Minecraft minecraft, Float yaw, Float pitch) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			throw new ToolException("cannot look around: not in a world");
		}
		if (yaw != null) {
			player.setYRot(yaw);
			player.yRotO = yaw;
		}
		if (pitch != null) {
			float clamped = Mth.clamp(pitch, -90f, 90f);
			player.setXRot(clamped);
			player.xRotO = clamped;
		}
	}
}
