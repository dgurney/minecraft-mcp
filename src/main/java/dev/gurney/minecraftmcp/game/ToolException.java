package dev.gurney.minecraftmcp.game;

/** A failure the agent should see as a tool error, with a message that tells it what went wrong. */
public class ToolException extends RuntimeException {
	public ToolException(String message) {
		super(message);
	}
}
