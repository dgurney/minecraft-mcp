package dev.gurney.minecraftmcp.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.gurney.minecraftmcp.game.AgentActivity;
import dev.gurney.minecraftmcp.game.PausingMenu;
import dev.gurney.minecraftmcp.game.VirtualInput;
import dev.gurney.minecraftmcp.mcp.McpHttpServer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "close", at = @At("HEAD"))
	private void minecraftMcp$stopServer(CallbackInfo ci) {
		McpHttpServer.stop();
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void minecraftMcp$runVirtualInput(CallbackInfo ci) {
		VirtualInput.onTick();
	}

	/** The game is meant to run in its own instance for the agent, out of focus, so it never opens the pause menu by itself. */
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void minecraftMcp$neverPauseUnfocused(CallbackInfo ci) {
		ci.cancel();
	}

	/**
	 * No menu pauses it either. A paused world stands still, yet still runs commands without
	 * sending the client what they changed, so the agent would read stale blocks.
	 */
	@ModifyExpressionValue(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;isPausing()Z"))
	private boolean minecraftMcp$neverPauseForMenus(boolean pausing) {
		PausingMenu.setOpen(pausing);
		return false;
	}

	/** Holding attack only keeps mining while the mouse is grabbed, which never happens unfocused. */
	@ModifyExpressionValue(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
	private boolean minecraftMcp$treatMouseAsGrabbedForAgent(boolean grabbed) {
		return grabbed || AgentActivity.isActive();
	}
}
