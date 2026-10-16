package dev.gurney.minecraftmcp.mixin;

import java.util.Map;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("ALL")
	static Map<String, KeyMapping> minecraftMcp$all() {
		throw new AssertionError("replaced by mixin");
	}

	@Invoker("shouldSetOnIngameFocus")
	boolean minecraftMcp$shouldSetOnIngameFocus();
}
