package dev.gurney.minecraftmcp.game;

/**
 * Whether a menu that would normally pause the game, such as the pause menu, is open. The game
 * no longer pauses for it, but still saves when one opens, as it did when pausing.
 */
public final class PausingMenu {
	private static volatile boolean open;

	private PausingMenu() {
	}

	/** Called on the client thread every frame. */
	public static void setOpen(boolean isOpen) {
		open = isOpen;
	}

	public static boolean isOpen() {
		return open;
	}
}
