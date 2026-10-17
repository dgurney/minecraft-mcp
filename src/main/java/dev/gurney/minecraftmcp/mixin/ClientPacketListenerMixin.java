package dev.gurney.minecraftmcp.mixin;

import dev.gurney.minecraftmcp.game.RemoteCommands;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	/** After the thread check, so only on the client thread, in order with the packets before it. */
	@Inject(
		method = "handleSystemChat",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER),
		cancellable = true)
	private void minecraftMcp$collectCommandOutput(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		if (!packet.overlay() && RemoteCommands.onSystemMessage(packet.content())) {
			ci.cancel();
		}
	}
}
