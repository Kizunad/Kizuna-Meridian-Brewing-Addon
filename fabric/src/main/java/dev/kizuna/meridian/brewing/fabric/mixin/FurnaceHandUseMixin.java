package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.FurnaceHandUse;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class FurnaceHandUseMixin implements FurnaceHandUse {
    @Shadow public ServerPlayer player;
    @Unique private InteractionHand meridianBrewing$handledHand;
    @Unique private int meridianBrewing$nextSequence;
    @Unique private static final String ACK = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
            + "ackBlockChangesUpTo(I)V";

    @Inject(method = "handleUseItemOn", at = @At(value = "INVOKE", target = ACK))
    private void meridianBrewing$beginClick(ServerboundUseItemOnPacket packet, CallbackInfo callback) {
        // 原版先验线程和加载状态，再确认预测序号；只有此包里的炉口回调能认领这次点击。
        meridianBrewing$handledHand = null;
        meridianBrewing$nextSequence = packet.getSequence() + 1;
    }

    @Override
    public void meridianBrewing$claimHand(InteractionHand hand) {
        meridianBrewing$handledHand = hand;
    }

    @Inject(method = "handleUseItem", at = @At(value = "INVOKE", target = ACK, shift = At.Shift.AFTER),
            cancellable = true)
    private void meridianBrewing$skipFallback(ServerboundUseItemPacket packet, CallbackInfo callback) {
        var hand = meridianBrewing$handledHand;
        meridianBrewing$handledHand = null;
        if (hand == packet.getHand() && packet.getSequence() == meridianBrewing$nextSequence) {
            // 已确认原版方块预测，再纠正本地预测出的饮用动作和空瓶换物。
            var flags = BrewUseFlagsAccessor.meridianBrewing$useFlags();
            var value = SynchedEntityData.DataValue.create(flags, player.getEntityData().get(flags));
            player.connection.send(new ClientboundSetEntityDataPacket(player.getId(), List.of(value)));
            player.containerMenu.sendAllDataToRemote();
            callback.cancel();
        }
    }

    @Inject(method = "handleClientTickEnd", at = @At("TAIL"))
    private void meridianBrewing$endClick(ServerboundClientTickEndPacket packet, CallbackInfo callback) {
        // 以客户端输入帧为界，两个相继到达的包可以跨服务器 tick。
        meridianBrewing$handledHand = null;
    }
}
