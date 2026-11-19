package dev.kizuna.meridian.brewing.client;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.brewing.fabric.FurnaceNetwork;
import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceRequest;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceView;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** 画面在客户端线程订阅已解码的不可变对象；步骤播放的位置由画面自行保管。 */
public final class FurnaceClientData {
    @FunctionalInterface
    public interface ViewListener {
        void changed(FurnaceView view);
    }

    @FunctionalInterface
    public interface StepListener {
        void received(BrewTimeline steps);
    }

    public static final Event<ViewListener> VIEW = EventFactory.createArrayBacked(ViewListener.class,
            listeners -> view -> {
                for (var listener : listeners) {
                    listener.changed(view);
                }
            });
    public static final Event<StepListener> STEPS = EventFactory.createArrayBacked(StepListener.class,
            listeners -> steps -> {
                for (var listener : listeners) {
                    listener.received(steps);
                }
            });
    private static FurnaceView current;

    private FurnaceClientData() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(FurnaceNetwork.VIEW.type(), (packet, context) -> {
            try {
                accept(FurnaceView.parseFrom(packet.bytes()));
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalArgumentException("药炉状态无法解码", invalid);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(FurnaceNetwork.STEPS.type(), (packet, context) -> {
            try {
                STEPS.invoker().received(BrewTimeline.parseFrom(packet.bytes()));
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalArgumentException("药方步骤无法解码", invalid);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> current = null);
    }

    private static void accept(FurnaceView update) {
        if (update.getFull()) {
            current = update;
        } else {
            if (current == null || current.getMenu() != update.getMenu()) {
                return;
            }
            var result = current.toBuilder();
            if (update.hasCatalog()) {
                result.setCatalog(update.getCatalog());
            }
            if (update.hasOrders()) {
                result.setOrders(update.getOrders());
            }
            if (update.hasClock()) {
                result.setClock(update.getClock());
            }
            current = result.build();
        }
        VIEW.invoker().changed(current);
    }

    public static Optional<FurnaceView> view(int menu) {
        return Optional.ofNullable(current).filter(view -> view.getMenu() == menu);
    }

    public static void request(FurnaceRequest action) {
        ClientPlayNetworking.send(FurnaceNetwork.REQUEST.payload(action.toByteArray()));
    }
}
