package dev.kizuna.meridian.brewing.test;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** GameTest 玩家通过真实字节宿主和 Framework 事务参与正式功法服务。 */
public final class BrewingTechniqueTestHost implements TechniqueHostEntrypoint {
    private static final long MILLIS_PER_TICK = 50;
    private static final double HP = 100;
    private static final double LIFESPAN = 100_000;
    private static final Map<UUID, Actor> ACTORS = new HashMap<>();

    static void bind(ServerPlayer player) {
        // 只认领测试玩家，生产玩家与其他测试宿主保持独立。
        ACTORS.put(player.getUUID(), new Actor(player));
    }

    static BodySnapshot body(ServerPlayer player) {
        // 读取已提交的权威身体，断言不会暗中推进时钟。
        return ACTORS.get(player.getUUID()).body();
    }

    static void unbind(ServerPlayer player) {
        // 生命周期取消完成后释放测试宿主，避免跨用例残留。
        ACTORS.remove(player.getUUID());
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 与正式附属相同，使用有界 Protobuf 解析和响应回显。
        TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
        var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
        Actor actor = ACTORS.get(UUID.fromString(request.getEntityUuid()));
        if (actor == null) {
            response.setUnbound(true /* unbound */);
        } else {
            actor.route(request, response);
        }
        return TechniqueProtocol.encodeHostResponse(response.build());
    }

    private static final class Actor {
        private final ServerPlayer player;
        private final long startedTick;
        private final BodyId bodyId;
        private final InMemoryBodyStore store;
        private final BodyCommandHandler<String> commands;

        private Actor(ServerPlayer player) {
            // 身体时钟随真实服务端 tick 推进，学会、起手、推进和结束均走 Framework。
            this.player = player;
            startedTick = player.level().getGameTime();
            BodySnapshot initial = initialBody(player);
            bodyId = initial.id();
            store = new InMemoryBodyStore(List.of(initial), List.of());
            commands = new BodyCommandHandler<>(
                    (context, command) -> Optional.of(TechniqueHost.CALLER_ID),
                    (context, command, body) -> true,
                    (context, body) -> timePlan(body), store, (context, receipt, before, after) -> { });
        }

        private BodySnapshot body() {
            // 测试断言与宿主响应读取同一份事务存储。
            return store.find(bodyId).orElseThrow();
        }

        private TimeAdvancePlan timePlan(BodySnapshot body) {
            // 客户端请求不能指定时间，时间只来自世界 tick。
            long elapsedMillis = (player.level().getGameTime() - startedTick) * MILLIS_PER_TICK;
            BodyMessages.TimeCursor time = BodyProtobuf.toMessage(body).getSettledAt().toBuilder()
                    .setElapsedMillis(elapsedMillis)
                    .build();
            return TimeAdvancePlan.newBuilder()
                    .setTarget(time)
                    .setBiologicalSecondsPerSimulationSecond(1)
                    .setMode(TimeAdvanceMode.ACTIVE)
                    .build();
        }

        private void route(TechniqueHostRequest request, TechniqueHostResponse.Builder response) {
            // 世界效果由真实 Brewing 处理器执行；测试宿主接收其余移动意图。
            switch (request.getOperationCase()) {
                case INSPECT -> inspect(response);
                case ACTIVITY -> submit(request, response);
                case EFFECT -> response.setEffectAccepted(true /* effectAccepted */);
                default -> throw new IllegalArgumentException("未知测试宿主操作");
            }
        }

        private void inspect(TechniqueHostResponse.Builder response) {
            // 先按旧动作结算时间，再采集玩家的实时双手物品。
            BodySnapshot before = body();
            BodyCommand command = BodyCommand.newBuilder()
                    .setBodyId(bodyId.value())
                    .setExpectedRevision(before.revision())
                    .setAdvanceTime(AdvanceTime.getDefaultInstance())
                    .build();
            ResponseEnvelope result = execute("settle_" + UUID.randomUUID(), command, Route.NORMAL);
            if (result.hasFailure() && result.getFailure().getCode() != FailureCode.TIME_NOT_ADVANCED) {
                response.setFailure(result.getFailure());
                return;
            }
            TechniqueHostFacts facts = TechniqueHostFacts.newBuilder()
                    .setGrounded(true /* grounded */)
                    .setInWater(false /* inWater */)
                    .setClimbing(false /* climbing */)
                    .setRiding(false /* riding */)
                    .setGliding(false /* gliding */)
                    .build();
            response.setBody(BodyProtobuf.toMessage(body()));
            response.setHeldItems(TechniqueWorldItems.inspect(player));
            response.setInspectFacts(facts);
        }

        private void submit(TechniqueHostRequest request, TechniqueHostResponse.Builder response) {
            // 学会和施放都通过 handleActivity 授权、校验、原子保存和回执去重。
            BodyCommand command = BodyCommand.newBuilder()
                    .setBodyId(bodyId.value())
                    .setExpectedRevision(request.getExpectedRevision())
                    .setExecuteActivity(request.getActivity())
                    .build();
            ResponseEnvelope result = execute(request.getRequestId(), command, Route.ACTIVITY);
            if (result.hasFailure()) {
                response.setFailure(result.getFailure());
                return;
            }
            TechniqueActivityCommit commit = TechniqueActivityCommit.newBuilder()
                    .setRevision(result.getCommandCommitted().getRevision())
                    .setActivity(request.getActivity())
                    .build();
            response.setActivity(commit);
        }

        private ResponseEnvelope execute(String id, BodyCommand command, Route route) {
            // 使用框架公开的二进制事务入口，不在测试中伪造进度或提交版本。
            RequestEnvelope envelope = RequestEnvelope.newBuilder()
                    .setProtocolVersion(MeridianProtocol.VERSION)
                    .setRequestId(id)
                    .setBodyCommand(command)
                    .build();
            byte[] payload = MeridianProtocol.encodeRequest(envelope);
            byte[] response;
            if (route == Route.ACTIVITY) {
                response = commands.handleActivity("test", payload);
            } else {
                response = commands.handle("test", payload);
            }
            return MeridianProtocol.decodeResponse(response);
        }
    }

    private static BodySnapshot initialBody(ServerPlayer player) {
        // 身体是测试内容，生产目录仍从 Brewing 自己的资源加载。
        BodyMessages.PartDefinition part = BodyMessages.PartDefinition.newBuilder()
                .setId("body")
                .setDisplayName("测试主体")
                .setMaxHp(HP)
                .build();
        BodyMessages.BodyStructure structure = BodyMessages.BodyStructure.newBuilder().addParts(part).build();
        BodyMessages.PartState state = BodyMessages.PartState.newBuilder().setId("body").setHp(HP).build();
        BodyMessages.TemplateReference template = BodyMessages.TemplateReference.newBuilder()
                .setId("brewing_test:body")
                .setVersion(1)
                .build();
        BodyMessages.VitalityState vitality = BodyMessages.VitalityState.newBuilder()
                .setAgeSeconds(0)
                .setLifespanLimitSeconds(LIFESPAN)
                .setSpentLifespanSeconds(0)
                .setDead(false /* dead */)
                .build();
        BodyMessages.TimeCursor time = BodyMessages.TimeCursor.newBuilder()
                .setDomain("brewing_test:world")
                .setEpoch("test")
                .setElapsedMillis(0)
                .build();
        BodyMessages.BodySnapshot body = BodyMessages.BodySnapshot.newBuilder()
                .setSchemaVersion(BodySnapshot.CURRENT_SCHEMA_VERSION)
                .setId(player.getUUID().toString())
                .setTemplate(template)
                .setStructure(structure)
                .setRealm(BodyMessages.Realm.WANDERING_QI)
                .setVitality(vitality)
                .setSettledAt(time)
                .setRevision(0)
                .addPartStates(state)
                .build();
        return BodyProtobuf.fromMessage(body);
    }

    private enum Route { NORMAL, ACTIVITY }
}
