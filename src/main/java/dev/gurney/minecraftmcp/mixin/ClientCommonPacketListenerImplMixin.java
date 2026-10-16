package dev.gurney.minecraftmcp.mixin;

import dev.gurney.minecraftmcp.game.ClientCatchUp;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ClientCommonPacketListenerImplMixin {
	/** At the end, so only once the ping is handled in order with the packets before it, not when it first arrives. */
	@Inject(method = "handlePing", at = @At("TAIL"))
	private void minecraftMcp$notePing(ClientboundPingPacket packet, CallbackInfo ci) {
		ClientCatchUp.onClientPing(packet.getId());
	}
}
