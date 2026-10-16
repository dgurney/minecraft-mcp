package dev.gurney.minecraftmcp.mixin;

import dev.gurney.minecraftmcp.game.VirtualInput;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class GuiMixin {
	@Inject(method = "setScreen", at = @At("TAIL"))
	private void minecraftMcp$repressHeldKeys(Screen screen, CallbackInfo ci) {
		if (screen == null) {
			VirtualInput.onScreenClosed();
		}
	}
}
