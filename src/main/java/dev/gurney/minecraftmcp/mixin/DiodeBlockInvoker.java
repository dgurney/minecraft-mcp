package dev.gurney.minecraftmcp.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DiodeBlock.class)
public interface DiodeBlockInvoker {
	@Invoker("getInputSignal")
	int minecraftMcp$getInputSignal(Level level, BlockPos pos, BlockState state);

	@Invoker("getAlternateSignal")
	int minecraftMcp$getAlternateSignal(SignalGetter level, BlockPos pos, BlockState state);
}
