package dev.gurney.minecraftmcp.game;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Plain-text summary of what the player could see on screen and in the F3 overlay. */
public final class StatusReport {
	private static final int BRIEF_CHAT_LINES = 5;
	private static final int DETAILED_CHAT_LINES = 30;

	private StatusReport() {
	}

	public static String brief() {
		return GameThreads.onClient(() -> build(false));
	}

	public static String detailed() {
		return GameThreads.onClient(() -> build(true));
	}

	private static String build(boolean detailed) {
		Minecraft minecraft = Minecraft.getInstance();
		StringBuilder out = new StringBuilder();
		if (minecraft.getWindow().isIconified()) {
			out.append("screenshot/cursor coordinates: none, the game window is minimized\n");
		} else {
			ScreenCapture.Size image = ScreenCapture.imageSize(minecraft.getWindow());
			out.append("screenshot/cursor coordinates: ").append(image.width()).append('x').append(image.height()).append('\n');
		}

		Screen screen = minecraft.gui.screen();
		out.append("screen: ").append(screen == null ? "none (playing)" : describe(screen)).append('\n');
		List<String> heldKeys = VirtualInput.heldKeyNames();
		if (!heldKeys.isEmpty()) {
			out.append("keys held down: ").append(String.join(", ", heldKeys)).append('\n');
		}

		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (player == null || level == null) {
			out.append("not in a world\n");
			return out.toString();
		}
		boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		if (server != null) {
			out.append("world: \"").append(server.getWorldData().getLevelName()).append("\"\n");
			out.append("commands: ").append(operator ? "available (cheats enabled)" : "only " + commandNames(player) + " (cheats disabled)").append('\n');
		} else {
			ServerData data = minecraft.getCurrentServer();
			out.append("world: multiplayer server").append(data == null ? "" : " \"" + data.name + "\" at " + data.ip).append('\n');
			out.append("commands: ").append(operator ? "available (operator)" : "only " + commandNames(player) + " (not an operator)").append('\n');
		}
		out.append("game mode: ").append(minecraft.gameMode.getPlayerMode().getName()).append('\n');

		BlockPos pos = player.blockPosition();
		out.append(String.format(Locale.ROOT, "position: %.2f %.2f %.2f (block %d %d %d) in %s, biome %s%n",
			player.getX(), player.getY(), player.getZ(), pos.getX(), pos.getY(), pos.getZ(),
			level.dimension().identifier(),
			level.getBiome(pos).unwrapKey().map(key -> key.identifier().toString()).orElse("unknown")));
		out.append("facing: ").append(facing(player)).append('\n');
		out.append("looking at: ").append(describeTarget(minecraft.hitResult, level)).append('\n');
		out.append(String.format(Locale.ROOT, "health %.1f/%.1f, food %d/20, xp level %d%n",
			player.getHealth(), player.getMaxHealth(), player.getFoodData().getFoodLevel(), player.experienceLevel));
		long time = level.getOverworldClockTime();
		out.append(String.format(Locale.ROOT, "time: day %d, %d ticks into the day (0 = sunrise, 6000 = noon, 13000 = night)%n", time / 24000, time % 24000));

		Inventory inventory = player.getInventory();
		out.append("hotbar (selected = hotbar.").append(inventory.getSelectedSlot()).append("):");
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			appendSlot(out, "hotbar." + slot, inventory.getItem(slot));
		}
		out.append('\n');

		if (detailed) {
			out.append("inventory:");
			for (int slot = Inventory.getSelectionSize(); slot < inventory.getNonEquipmentItems().size(); slot++) {
				appendSlot(out, "inventory." + (slot - Inventory.getSelectionSize()), inventory.getItem(slot));
			}
			out.append('\n');
			out.append("equipment:");
			for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
				appendSlot(out, slot.getName(), player.getItemBySlot(slot));
			}
			out.append('\n');
		}

		List<String> chat = ChatLog.latest(detailed ? DETAILED_CHAT_LINES : BRIEF_CHAT_LINES);
		if (!chat.isEmpty()) {
			out.append("recent chat (oldest first):\n");
			chat.forEach(line -> out.append("  ").append(line).append('\n'));
		}
		return out.toString();
	}

	/** The commands the server lets the player run, e.g. "/help, /list, /me", as it sends only those. */
	private static String commandNames(LocalPlayer player) {
		List<String> names = player.connection.getCommands().getRoot().getChildren().stream().map(node -> "/" + node.getName()).sorted().toList();
		return names.isEmpty() ? "no commands" : String.join(", ", names);
	}

	/** E.g. "east (yaw -53.1, pitch 17.2)". */
	static String facing(LocalPlayer player) {
		// Adding 0 turns -0.0 into 0.0 so it doesn't print as "-0.0".
		return String.format(Locale.ROOT, "%s (yaw %.1f, pitch %.1f)",
			player.getDirection().getName(), Mth.wrapDegrees(player.getYRot()) + 0f, player.getXRot() + 0f);
	}

	public static String describe(Screen screen) {
		return screen.getClass().getSimpleName() + " \"" + screen.getTitle().getString() + "\"";
	}

	private static String describeTarget(HitResult hit, ClientLevel level) {
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = block.getBlockPos();
			return String.format(Locale.ROOT, "block %s at %d %d %d (%s face)",
				WorldQuery.describe(level.getBlockState(pos)),
				pos.getX(), pos.getY(), pos.getZ(), block.getDirection().getName());
		}
		if (hit instanceof EntityHitResult entity) {
			return String.format(Locale.ROOT, "entity %s \"%s\" at %.1f %.1f %.1f",
				BuiltInRegistries.ENTITY_TYPE.getKey(entity.getEntity().getType()),
				entity.getEntity().getName().getString(),
				entity.getEntity().getX(), entity.getEntity().getY(), entity.getEntity().getZ());
		}
		return "nothing within reach";
	}

	private static void appendSlot(StringBuilder out, String label, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		out.append(' ').append(label).append('=').append(BuiltInRegistries.ITEM.getKey(stack.getItem()));
		if (stack.getCount() > 1) {
			out.append(" x").append(stack.getCount());
		}
		if (stack.has(DataComponents.CUSTOM_NAME)) {
			out.append(" \"").append(stack.getHoverName().getString()).append('"');
		}
		out.append(';');
	}
}
