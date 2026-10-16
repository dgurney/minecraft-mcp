package dev.gurney.minecraftmcp.mixin;

import dev.gurney.minecraftmcp.game.BlockWatch;
import dev.gurney.minecraftmcp.game.ClientCatchUp;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
	/** Sends the pings ClientCatchUp queued, now that this tick has sent its changes, then finishes block watches. */
	@Inject(method = "tickServer", at = @At("RETURN"))
	private void minecraftMcp$afterTick(BooleanSupplier haveTime, CallbackInfo ci) {
		ClientCatchUp.onServerTickEnd();
		BlockWatch.onServerTickEnd((MinecraftServer) (Object) this);
	}

	@Inject(method = "stopServer", at = @At("HEAD"))
	private void minecraftMcp$onStop(CallbackInfo ci) {
		BlockWatch.onServerStop();
		ClientCatchUp.onServerStop();
	}
}
