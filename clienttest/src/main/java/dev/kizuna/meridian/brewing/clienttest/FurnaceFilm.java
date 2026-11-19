package dev.kizuna.meridian.brewing.clienttest;

import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** 每个客户端刻的最后拍一帧。GPU 读回与写盘按采样时的序号落盘，播放速度为每秒 20 帧。 */
final class FurnaceFilm implements AutoCloseable {
    private static FurnaceFilm active;
    private final ClientGameTestContext context;
    private final Path directory;
    private int captured;
    private int saved;

    static {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (active != null) {
                active.capture(client);
            }
        });
    }

    FurnaceFilm(ClientGameTestContext context) {
        this.context = context;
        directory = context.computeOnClient(client -> client.gameDirectory.toPath().resolve("screenshots"));
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        context.runOnClient(client -> active = this);
    }

    private void capture(Minecraft client) {
        // 与 Fabric 客户端截图相同，先渲染完整世界和 HUD，再读颜色缓冲。等待磁盘会漏掉下一刻。
        Stage.clearScreenshotMessages(client);
        var renderer = client.gameRenderer;
        renderer.update(DeltaTracker.ONE);
        renderer.extract(DeltaTracker.ONE, true /* renderLevel */);
        renderer.render(DeltaTracker.ONE, true /* renderLevel */);
        RenderSystem.getDevice().createCommandEncoder().submit();
        Path file = directory.resolve(String.format(Locale.ROOT, "furnaces_%05d.png", ++captured));
        Screenshot.takeScreenshot(renderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
                saved++;
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    int frame() {
        return context.computeOnClient(client -> captured);
    }

    @Override
    public void close() {
        context.runOnClient(client -> active = null);
        context.waitFor(client -> saved == captured);
    }
}
