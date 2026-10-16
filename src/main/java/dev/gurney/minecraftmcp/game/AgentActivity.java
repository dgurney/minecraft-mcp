package dev.gurney.minecraftmcp.game;

/**
 * Tracks whether an agent is currently driving the game. While it is, the game behaves as if its
 * window had focus: it does not auto-pause and held attack keeps mining.
 */
public final class AgentActivity {
	private static final long ACTIVE_WINDOW_MILLIS = 5 * 60 * 1000;

	private static volatile long lastToolCallMillis = Long.MIN_VALUE / 2;

	private AgentActivity() {
	}

	public static void touch() {
		lastToolCallMillis = System.currentTimeMillis();
	}

	public static boolean isActive() {
		return System.currentTimeMillis() - lastToolCallMillis < ACTIVE_WINDOW_MILLIS;
	}
}
