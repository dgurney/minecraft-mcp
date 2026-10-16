package dev.gurney.minecraftmcp.mixin;

import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LookAtPlayerGoal.class)
public interface LookAtPlayerGoalAccessor {
	@Accessor("lookAtType")
	Class<?> minecraftMcp$lookAtType();
}
