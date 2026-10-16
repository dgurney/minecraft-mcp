package dev.gurney.minecraftmcp.mixin;

import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AvoidEntityGoal.class)
public interface AvoidEntityGoalAccessor {
	@Accessor("avoidClass")
	Class<?> minecraftMcp$avoidClass();
}
