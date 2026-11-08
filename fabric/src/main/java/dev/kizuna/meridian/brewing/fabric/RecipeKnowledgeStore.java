package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 药方学习的本仓内存宿主；跨仓 addon_progress 持久化由上游宿主接入。 */
public final class RecipeKnowledgeStore {
    private static final Map<UUID, RecipeKnowledge> KNOWLEDGE = new ConcurrentHashMap<>();

    private RecipeKnowledgeStore() {
        // 学习状态按玩家 UUID 隔离。
    }

    static void learn(Player player, BrewKind kind) {
        KNOWLEDGE.computeIfAbsent(player.getUUID(), ignored -> new RecipeKnowledge()).learn(kind);
    }

    static boolean knows(Player player, BrewKind kind) {
        RecipeKnowledge knowledge = KNOWLEDGE.get(player.getUUID());
        return knowledge != null && knowledge.knows(kind);
    }

    public static void load(Player player, ValueInput input) {
        ValueInput progress = input.childOrEmpty("addon_progress");
        ValueInput recipes = progress.childOrEmpty(RecipeKnowledge.PROGRESS_KEY);
        RecipeKnowledge knowledge = new RecipeKnowledge();
        for (BrewKind kind : BrewKind.values()) {
            if (recipes.getBooleanOr(kind.path(), false /* defaultKnown */)) {
                knowledge.learn(kind);
            }
        }
        KNOWLEDGE.put(player.getUUID(), knowledge);
    }

    public static void save(Player player, ValueOutput output) {
        RecipeKnowledge knowledge = KNOWLEDGE.get(player.getUUID());
        if (knowledge == null) {
            return;
        }
        ValueOutput progress = output.child("addon_progress");
        ValueOutput recipes = progress.child(RecipeKnowledge.PROGRESS_KEY);
        for (BrewKind kind : BrewKind.values()) {
            recipes.putBoolean(kind.path(), knowledge.knows(kind));
        }
    }
}
