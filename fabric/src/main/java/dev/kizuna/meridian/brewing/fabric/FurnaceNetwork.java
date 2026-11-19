package dev.kizuna.meridian.brewing.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceRequest;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceView;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewSteps;
import dev.kizuna.meridian.brewing.server.VesselKind;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/** 有长度上限的菜单请求、个性化状态和一次性演示。 */
public final class FurnaceNetwork {
    private static final int REQUEST_BYTES = 256;
    private static final int STATE_BYTES = 8192;
    public static final ProtobufChannel REQUEST = channel("furnace_request", REQUEST_BYTES);
    public static final ProtobufChannel VIEW = channel("furnace_view", STATE_BYTES);
    public static final ProtobufChannel STEPS = channel("brew_steps", STATE_BYTES);

    private FurnaceNetwork() {}

    private static ProtobufChannel channel(String name, int limit) {
        return new ProtobufChannel(Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, name), limit);
    }

    static void register() {
        REQUEST.registerServerbound();
        VIEW.registerClientbound();
        STEPS.registerClientbound();
        ServerPlayNetworking.registerGlobalReceiver(REQUEST.type(), (payload, context) -> {
            try {
                handle(context.player(), FurnaceRequest.parseFrom(payload.bytes()));
            } catch (InvalidProtocolBufferException malformed) {
                // 无法解码的请求没有任何世界操作。
            }
        });
    }

    static void handle(ServerPlayer viewer, FurnaceRequest request) {
        if (request.hasBook()) {
            var steps = book(viewer, request.getBook());
            if (steps != null) {
                sendSteps(viewer, steps);
            }
        } else if (viewer.containerMenu instanceof FurnaceMenu menu) {
            menu.handle(request);
        }
    }

    static BrewTimeline book(ServerPlayer player, String document) {
        // 书页仅声明篇名，服务端核对两只手实际持有的卷轴；暂停身体也可以读手里的书。
        for (var hand : InteractionHand.values()) {
            var stack = player.getItemInHand(hand);
            if (!stack.is(BrewingItems.item("brew_recipe"))) {
                continue;
            }
            var held = RecipeScrollItem.kind(stack);
            if (held.isPresent() && held.orElseThrow().path().equals(document)) {
                var kind = held.orElseThrow();
                var timeline = BrewSteps.timeline(BrewRecipes.require(kind), VesselKind.WITCH_CAULDRON,
                        BrewSteps.defaultForm(kind));
                return timeline.toBuilder().setManual(true /* manual */).build();
            }
        }
        return null;
    }

    static void sendView(ServerPlayer player, FurnaceView view) {
        if (ServerPlayNetworking.canSend(player, VIEW.type())) {
            ServerPlayNetworking.send(player, VIEW.payload(view.toByteArray()));
        }
    }

    static void sendSteps(ServerPlayer player, BrewTimeline steps) {
        if (ServerPlayNetworking.canSend(player, STEPS.type())) {
            ServerPlayNetworking.send(player, STEPS.payload(steps.toByteArray()));
        }
    }
}
