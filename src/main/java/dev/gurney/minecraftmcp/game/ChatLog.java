package dev.gurney.minecraftmcp.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Recent chat lines as plain text. Only touched from the client thread. */
public final class ChatLog {
	private static final int CAPACITY = 100;
	private static final ArrayDeque<String> LINES = new ArrayDeque<>();

	private ChatLog() {
	}

	public static void add(String line) {
		if (LINES.size() == CAPACITY) {
			LINES.removeFirst();
		}
		LINES.addLast(line);
	}

	public static List<String> latest(int count) {
		List<String> all = new ArrayList<>(LINES);
		return all.subList(Math.max(0, all.size() - count), all.size());
	}
}
