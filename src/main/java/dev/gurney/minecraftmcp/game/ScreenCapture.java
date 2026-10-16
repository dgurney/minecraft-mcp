package dev.gurney.minecraftmcp.game;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Captures the game window as a JPEG scaled to fit {@link #MAX_WIDTH}x{@link #MAX_HEIGHT}. */
public final class ScreenCapture {
	private static final int MAX_WIDTH = 1280;
	private static final int MAX_HEIGHT = 800;
	private static final float JPEG_QUALITY = 0.85f;
	private static final long TIMEOUT_SECONDS = 10;

	public record Size(int width, int height) {
	}

	/** A capture waiting for the next frame's world to be drawn; only touched on the render thread. */
	private static Consumer<NativeImage> beforeGuiReceiver;

	private ScreenCapture() {
	}

	/** The size of captured images for the window's current size; input coordinates use the same space. */
	public static Size imageSize(Window window) {
		return fit(window.getWidth(), window.getHeight());
	}

	private static Size fit(int width, int height) {
		double scale = Math.min(1.0, Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height));
		return new Size((int) Math.round(width * scale), (int) Math.round(height * scale));
	}

	/**
	 * Captures the window. Without the HUD, the next frame is captured before its GUI is drawn,
	 * unless a screen (menu, inventory, chat) is open: then the GUI is what matters, so it's kept.
	 */
	public static byte[] captureJpeg(boolean hud) {
		CompletableFuture<BufferedImage> frame = new CompletableFuture<>();
		Consumer<NativeImage> receiver = image -> {
			try (image) {
				frame.complete(toBufferedImage(image));
			}
		};
		GameThreads.onClient(() -> {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.getWindow().isIconified()) {
				throw new ToolException("the game window is minimized, so nothing is being rendered; ask the user to restore it");
			}
			if (hud || minecraft.gui.screen() != null) {
				Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), receiver);
			} else {
				beforeGuiReceiver = receiver;
			}
			return null;
		});
		BufferedImage full = GameThreads.await(frame, TIMEOUT_SECONDS, "the game did not render a frame within " + TIMEOUT_SECONDS + "s");
		return encodeJpeg(scale(full));
	}

	/** Called on the render thread each frame after the world is drawn and before the GUI is. */
	public static void beforeGui() {
		if (beforeGuiReceiver != null) {
			Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), beforeGuiReceiver);
			beforeGuiReceiver = null;
		}
	}

	private static BufferedImage toBufferedImage(NativeImage image) {
		BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		result.setRGB(0, 0, image.getWidth(), image.getHeight(), image.getPixels(), 0, image.getWidth());
		return result;
	}

	private static BufferedImage scale(BufferedImage full) {
		Size size = fit(full.getWidth(), full.getHeight());
		if (size.width() == full.getWidth() && size.height() == full.getHeight()) {
			return full;
		}
		BufferedImage scaled = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_RGB);
		var graphics = scaled.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.drawImage(full, 0, 0, size.width(), size.height(), null);
		graphics.dispose();
		return scaled;
	}

	private static byte[] encodeJpeg(BufferedImage image) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ImageWriteParam params = writer.getDefaultWriteParam();
		params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		params.setCompressionQuality(JPEG_QUALITY);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(output);
			writer.write(null, new IIOImage(image, null, null), params);
		} catch (IOException e) {
			throw new IllegalStateException("encoding a JPEG in memory failed", e);
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}
}
