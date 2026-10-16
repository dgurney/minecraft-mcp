package dev.gurney.minecraftmcp.mixin;

import dev.gurney.minecraftmcp.game.ScreenCapture;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
	private void minecraftMcp$captureBeforeGui(CallbackInfo ci) {
		ScreenCapture.beforeGui();
	}
}
