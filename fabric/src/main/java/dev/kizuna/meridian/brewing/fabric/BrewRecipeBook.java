package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

/** 药方知识只记在玩家唯一身体的进度里，经活动宿主读写；旧玩家存档的记录在第一次读写前并入。 */
final class BrewRecipeBook {
    private BrewRecipeBook() {
        // 没有进程内的药方表，每次都读身体。
    }

    static Lookup lookup(ServerPlayer player) {
        // 主动读：身体暂停如实报告，不当成没学过；读到身体时先把旧存档里的记录并进来。
        var observed = BrewBodyHost.observe(player);
        if (observed.presence() != BrewBodyHost.Presence.BOUND) {
            return new Lookup(observed.presence(), Optional.empty(), Set.of());
        }
        BrewBodyHost.Binding binding = mergeLegacy(player, observed.found().orElseThrow());
        return new Lookup(BrewBodyHost.Presence.BOUND, Optional.of(binding), RecipeKnowledge.read(binding.body()));
    }

    static Lookup replace(ServerPlayer player, Set<BrewKind> known) {
        // 读羊皮纸和调试指令都整份写回；和现有记录一样时不发空活动。
        Lookup current = lookup(player);
        if (current.presence() != BrewBodyHost.Presence.BOUND || current.known().equals(known)) {
            return current;
        }
        var activity = BodyActivity.newBuilder();
        RecipeKnowledge.write(activity, known);
        BrewBodyHost.Binding updated = BrewBodyHost.activity(current.binding().orElseThrow(), activity.build());
        return new Lookup(BrewBodyHost.Presence.BOUND, Optional.of(updated), RecipeKnowledge.read(updated.body()));
    }

    static BrewBodyHost.Binding mergeLegacy(ServerPlayer player, BrewBodyHost.Binding binding) {
        // 登录恢复和每次读写药方都先走这里：旧记录和身体上的取并集，提交成功才清掉实体上的旧记录；
        // 死亡的身体留到下次。
        LegacyRecipeHolder holder = (LegacyRecipeHolder) player;
        Set<BrewKind> legacy = holder.meridianBrewing$legacyRecipes();
        if (legacy.isEmpty() || binding.body().getVitality().getDead()) {
            return binding;
        }
        EnumSet<BrewKind> merged = EnumSet.copyOf(legacy);
        Set<BrewKind> known = RecipeKnowledge.read(binding.body());
        merged.addAll(known);
        BrewBodyHost.Binding updated = binding;
        if (!merged.equals(known)) {
            var activity = BodyActivity.newBuilder();
            RecipeKnowledge.write(activity, merged);
            updated = BrewBodyHost.activity(binding, activity.build());
        }
        holder.meridianBrewing$setLegacyRecipes(Set.of());
        return updated;
    }

    /** 一次读到的药方知识；只有身体在、没暂停时才有绑定和药方。 */
    record Lookup(BrewBodyHost.Presence presence, Optional<BrewBodyHost.Binding> binding, Set<BrewKind> known) {
        boolean knows(BrewKind kind) {
            // 暂停或没有身体时一律不算学过；调用方要先看 presence 决定提示。
            return known.contains(kind);
        }
    }
}
