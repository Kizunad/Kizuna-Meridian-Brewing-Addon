package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** 《熬药总纲》：拿着用一下就翻开总纲那一篇，书不消耗，也不学任何东西。 */
final class BrewManualItem extends Item {
    BrewManualItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // 正文在客户端按玩家的语言取，服务端只说翻开哪一篇。
        if (!level.isClientSide() && player instanceof ServerPlayer reader) {
            BrewLore.open(reader, BrewLore.OVERVIEW);
        }
        return InteractionResult.SUCCESS;
    }
}
