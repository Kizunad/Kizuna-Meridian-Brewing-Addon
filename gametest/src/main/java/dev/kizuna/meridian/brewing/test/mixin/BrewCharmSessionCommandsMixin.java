package dev.kizuna.meridian.brewing.test.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewCharmSessions;
import dev.kizuna.meridian.brewing.fabric.BrewingCharmProjectileGameTest;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmCommand;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmEndReason;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 只在 GameTest 装配中观察正式控制器已确认的会话命令。 */
@Mixin(BrewCharmSessions.class)
public abstract class BrewCharmSessionCommandsMixin {
    @Inject(method = "send", at = @At("RETURN"))
    private static void meridianBrewingTest$command(CharmCommand.Action action, CharmSession session,
                                                   CharmEndReason reason, CallbackInfo callback) {
        // 不改命令或响应，只把实际动作与原因交给对应测试场景。
        var command = CharmCommand.newBuilder()
                .setAction(action)
                .setSession(session)
                .setReason(reason)
                .build();
        BrewingCharmProjectileGameTest.recordCommand(command);
    }
}
