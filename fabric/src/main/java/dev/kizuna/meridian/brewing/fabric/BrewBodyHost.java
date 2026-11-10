package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.protocol.ActivityHostProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.ActivityHostMessages.ActivityHostRequest;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HealBody;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyActivityHost;
import dev.kizuna.meridian.server.BodyHealthHost;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 药效经唯一健康宿主读取、治疗，经活动宿主原子更新能力与进度。 */
final class BrewBodyHost {
    static final String SOURCE = "meridian_brewing:effects";
    private static final String REJECTED = "身体宿主拒绝药效：";
    private static List<BodyHealthHost> healthHosts;
    private static List<BodyActivityHost> activityHosts;

    private BrewBodyHost() {
        // 只保留服务注册点，不缓存身体状态。
    }

    static void initialize() {
        // 必装附属必须提供接线，不能在缺包时退回原版身体账本。
        healthHosts = FabricLoader.getInstance().getEntrypoints("meridian_health_host", BodyHealthHost.class);
        activityHosts = FabricLoader.getInstance().getEntrypoints("meridian_activity_host", BodyActivityHost.class);
        if (healthHosts.isEmpty() || activityHosts.isEmpty()) {
            throw new IllegalStateException("炼药缺少必需的身体宿主");
        }
    }

    static Optional<Binding> inspect(LivingEntity entity) {
        // 用药、施放等主动操作：未绑定是物种事实；宿主暂停和其他失败都向上传递，不能换另一具身体。
        Observation observed = observe(entity);
        if (observed.presence() == Presence.PAUSED) {
            throw new IllegalStateException(REJECTED + FailureCode.BODY_PAUSED);
        }
        return observed.found();
    }

    static Observation observe(LivingEntity entity) {
        // 登录恢复与周期结算：宿主暂停的身体保留现场，这一次跳过；其他宿主失败照旧向上传递。
        String requestId = UUID.randomUUID().toString();
        var request = HealthHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId)
                .setSubjectId(entity.getStringUUID())
                .setInspect(true /* inspect */)
                .build();
        byte[] payload = HealthHostProtobuf.encodeRequest(request);
        Binding result = null;
        for (BodyHealthHost host : healthHosts) {
            var response = HealthHostProtobuf.decodeResponse(host.exchange(payload));
            if (paused(response, requestId)) {
                return new Observation(Presence.PAUSED, Optional.empty());
            }
            validateResponse(response, requestId);
            if (response.hasBody()) {
                if (result != null) {
                    throw new IllegalStateException("同一实体被多个身体宿主认领");
                }
                result = new Binding(entity.getStringUUID(), host, response.getBody());
            }
        }
        if (result == null) {
            return new Observation(Presence.UNBOUND, Optional.empty());
        }
        return new Observation(Presence.BOUND, Optional.of(result));
    }

    private static boolean paused(HealthHostResponse response, String requestId) {
        // 只认本次请求的明确暂停答复；身份不符仍由 validateResponse 报错。
        return response.getRequestId().equals(requestId)
                && response.hasFailure()
                && response.getFailure().getCode() == FailureCode.BODY_PAUSED;
    }

    static Binding activity(Binding binding, BodyActivity plan) {
        // 请求固定预期修订，宿主不能刷新版本掩盖读写冲突。
        var command = BodyCommand.newBuilder()
                .setBodyId(binding.body().getId())
                .setExpectedRevision(binding.body().getRevision())
                .setExecuteActivity(plan)
                .build();
        RequestEnvelope envelope = envelope(command);
        var request = ActivityHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(envelope.getRequestId())
                .setSubjectId(binding.subjectId())
                .setActivity(envelope)
                .build();
        byte[] payload = ActivityHostProtobuf.encodeRequest(request);
        for (BodyActivityHost host : activityHosts) {
            var response = HealthHostProtobuf.decodeResponse(host.exchange(SOURCE, payload));
            validateResponse(response, request.getRequestId());
            if (response.hasBody()) {
                return binding.updated(response.getBody());
            }
        }
        throw new IllegalStateException("已绑定身体没有对应的活动宿主");
    }

    static Binding heal(Binding binding, HealBody healing) {
        // 表里治疗用一条健康命令提交，指定部位与内部账户不能转成原版平均回血。
        var command = BodyCommand.newBuilder()
                .setBodyId(binding.body().getId())
                .setExpectedRevision(binding.body().getRevision())
                .setHealBody(healing)
                .build();
        RequestEnvelope envelope = envelope(command);
        var request = HealthHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(envelope.getRequestId())
                .setSubjectId(binding.subjectId())
                .setEffect(envelope)
                .build();
        byte[] payload = HealthHostProtobuf.encodeRequest(request);
        var response = HealthHostProtobuf.decodeResponse(binding.host().exchange(payload));
        validateResponse(response, request.getRequestId());
        if (!response.hasBody()) {
            throw new IllegalStateException("治疗期间身体宿主失去绑定");
        }
        return binding.updated(response.getBody());
    }

    private static RequestEnvelope envelope(BodyCommand command) {
        // 每个新的世界事件分配身份，核心成功回执负责去重。
        return RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
    }

    private static void validateResponse(HealthHostResponse response, String requestId) {
        // 错误身份和业务拒绝都不能被视为本次药效成功。
        if (!response.getRequestId().equals(requestId)) {
            throw new IllegalStateException("身体宿主响应身份不匹配");
        }
        if (response.hasFailure()) {
            throw new IllegalStateException(REJECTED + response.getFailure().getCode());
        }
    }

    record Binding(String subjectId, BodyHealthHost host, BodySnapshot body) {
        Binding updated(BodySnapshot updated) {
            // 成功响应必须仍是原身体，禁止借响应切换账本。
            if (!updated.getId().equals(body.getId())) {
                throw new IllegalStateException("身体宿主返回了另一具身体");
            }
            return new Binding(subjectId, host, updated);
        }
    }

    /** 被动读身体的三种情形。 */
    enum Presence {
        BOUND,
        UNBOUND,
        PAUSED
    }

    /** 被动读到的身体；只有宿主认领且没有暂停时带绑定。 */
    record Observation(Presence presence, Optional<Binding> found) { }
}
