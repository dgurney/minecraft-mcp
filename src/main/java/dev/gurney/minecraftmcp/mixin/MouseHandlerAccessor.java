package dev.gurney.minecraftmcp.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("xpos")
	void minecraftMcp$setXpos(double xpos);

	@Accessor("ypos")
	void minecraftMcp$setYpos(double ypos);
}
