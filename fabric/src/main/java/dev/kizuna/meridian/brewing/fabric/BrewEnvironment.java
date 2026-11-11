package dev.kizuna.meridian.brewing.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostRequest;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostResponse;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionDraw;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionQuery;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionView;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.server.EnvironmentHostEntrypoint;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 釜所在区域的环境真元：经 Human 环境宿主的字节入口查询和取用，不依赖 Human 的 Fabric 实现类。
 * Human 是硬依赖，入口缺失、尚未绑定世界或答复失败都直接报错。
 */
final class BrewEnvironment {
    private static final String ENTRYPOINT = "meridian_environment_host";
    private static final int PROTOCOL_VERSION = 1;
    private static EnvironmentHostEntrypoint endpoint;

    private BrewEnvironment() {
        // 只转发请求，区域规则和取用速率都由 Human 决定。
    }

    static void initialize() {
        // 启动时就取到入口，缺了 Human 时在装配阶段失败，而不是第一次熬药时。
        List<EnvironmentHostEntrypoint> entrypoints = FabricLoader.getInstance()
                .getEntrypoints(ENTRYPOINT, EnvironmentHostEntrypoint.class);
        if (entrypoints.size() != 1) {
            throw new IllegalStateException("需要恰好一个 Human 环境宿主入口，实际 " + entrypoints.size());
        }
        endpoint = entrypoints.get(0);
    }

    static Optional<RegionView> region(Level level, BlockPos pos) {
        // 非主世界没有环境区域，答复里没有区域视图。
        EnvironmentHostResponse response = exchange(EnvironmentHostRequest.newBuilder()
                .setQuery(position(level, pos)));
        if (response.hasRegion()) {
            return Optional.of(response.getRegion());
        }
        return Optional.empty();
    }

    static QiInventory draw(String drawId, Level level, BlockPos pos, double amount) {
        // 想取多少就请求多少，实际取到的可以更少或为空；同一 ID 重试只回首次结果。
        EnvironmentHostResponse response = exchange(EnvironmentHostRequest.newBuilder()
                .setDraw(RegionDraw.newBuilder()
                        .setDrawId(drawId)
                        .setPosition(position(level, pos))
                        .setAmount(amount)));
        if (!response.hasDrawn()) {
            throw new IllegalStateException("环境宿主没有回取用结果：" + response.getResultCase());
        }
        return BodyProtobuf.fromInventory(response.getDrawn().getDrawnQi());
    }

    private static RegionQuery position(Level level, BlockPos pos) {
        // 区域按方块坐标查，区块和维度的换算归 Human。
        return RegionQuery.newBuilder()
                .setDimensionId(level.dimension().identifier().toString())
                .setBlockX(pos.getX())
                .setBlockZ(pos.getZ())
                .build();
    }

    private static EnvironmentHostResponse exchange(EnvironmentHostRequest.Builder operation) {
        // 每个请求新 ID，答复必须对得上；未绑定和失败答复都是宿主故障。
        EnvironmentHostRequest request = operation
                .setProtocolVersion(PROTOCOL_VERSION)
                .setRequestId(UUID.randomUUID().toString())
                .build();
        EnvironmentHostResponse response;
        try {
            response = EnvironmentHostResponse.parseFrom(endpoint.exchange(request.toByteArray()));
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("环境宿主答复无法解析", invalid);
        }
        if (!response.getRequestId().equals(request.getRequestId())) {
            throw new IllegalStateException("环境宿主答复的请求 ID 不一致");
        }
        if (response.getUnbound()) {
            throw new IllegalStateException("环境宿主尚未绑定世界");
        }
        if (response.hasFailure()) {
            throw new IllegalStateException("环境宿主拒绝请求：" + response.getFailure().getCode());
        }
        return response;
    }
}
