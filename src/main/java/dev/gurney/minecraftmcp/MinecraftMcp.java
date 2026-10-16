package dev.gurney.minecraftmcp;

import com.mojang.logging.LogUtils;
import dev.gurney.minecraftmcp.mcp.McpHttpServer;
import java.io.IOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class MinecraftMcp implements ClientModInitializer {
	public static final Logger LOGGER = LogUtils.getLogger();
	public static final int PORT = 25599;

	public static String version() {
		return FabricLoader.getInstance().getModContainer("minecraft-mcp").orElseThrow().getMetadata().getVersion().getFriendlyString();
	}

	@Override
	public void onInitializeClient() {
		try {
			McpHttpServer.start(PORT);
			LOGGER.info("Minecraft MCP server listening on http://127.0.0.1:{}/mcp", PORT);
		} catch (IOException e) {
			throw new IllegalStateException("Minecraft MCP could not listen on 127.0.0.1:" + PORT + " (is another Minecraft instance already running it?)", e);
		}
	}
}
