package dev.gurney.minecraftmcp.mixin;

import dev.gurney.minecraftmcp.MinecraftMcp;
import dev.gurney.minecraftmcp.game.PausingMenu;
import java.util.function.BooleanSupplier;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
abstract class IntegratedServerMixin {
	@Unique
	private boolean minecraftMcp$menuWasOpen;

	/**
	 * The game used to save when the pause menu paused it; it no longer pauses, so save when the
	 * menu opens. Not for a world open to LAN, which the menu never paused.
	 */
	@Inject(method = "tickServer", at = @At("HEAD"))
	private void minecraftMcp$saveWhenPauseMenuOpens(BooleanSupplier haveTime, CallbackInfo ci) {
		boolean open = PausingMenu.isOpen();
		IntegratedServer server = (IntegratedServer) (Object) this;
		if (open && !minecraftMcp$menuWasOpen && !server.isPublished()) {
			MinecraftMcp.LOGGER.info("Saving game, as a menu that would have paused it opened...");
			server.saveEverything(false, false, false);
		}
		minecraftMcp$menuWasOpen = open;
	}
}
