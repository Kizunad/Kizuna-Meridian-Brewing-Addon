package dev.kizuna.meridian.brewing.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import dev.kizuna.meridian.fabric.MeridianCommands;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * /meridian progression recipes [&lt;targets&gt;]：列出读过和没读过的药方，看自己不要权限；
 * recipes learn|forget [&lt;targets&gt;] &lt;药方|all&gt; 是 2 级调试指令。
 */
final class BrewRecipeCommands {
    private static final String NODE = "recipes";
    private static final String ALL = "all";
    private static final String KEY = "commands." + BrewingFabric.MOD_ID + ".recipes.";

    private BrewRecipeCommands() {
    }

    static void register() {
        // 三级节点对所有玩家可见；学、忘两个字面量自带 2 级权限，和目标参数的权限一致。
        MeridianCommands.register(MeridianCommands.Branch.PROGRESSION, NODE, MeridianCommands.PLAYER,
                (node, context) -> {
                    MeridianCommands.optionalPlayers(node, (parent, targets) ->
                            parent.executes(command -> list(command.getSource(), targets.resolve(command))));
                    node.then(change("learn", true /* learn */));
                    node.then(change("forget", false /* learn */));
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> change(String literal, boolean learn) {
        // learn [<targets>] <药方|all>：目标紧跟动作字面量，药方名和 all 都能补全。
        LiteralArgumentBuilder<CommandSourceStack> action = Commands.literal(literal)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        MeridianCommands.optionalPlayers(action, (parent, targets) -> parent.then(recipeArgument()
                .executes(command -> change(command, targets.resolve(command), learn))));
        return action;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> recipeArgument() {
        // 补全按枚举顺序列出全部药方，最后是 all。
        return Commands.argument("recipe", StringArgumentType.word()).suggests((context, builder) -> {
            for (BrewKind kind : BrewKind.values()) {
                builder.suggest(kind.path());
            }
            builder.suggest(ALL);
            return builder.buildFuture();
        });
    }

    private static int list(CommandSourceStack source, Collection<ServerPlayer> players) {
        // 每个目标一行：读过的、没读过的；读不到身体的单独报失败。
        int listed = 0;
        for (ServerPlayer player : players) {
            BrewRecipeBook.Lookup lookup = BrewRecipeBook.lookup(player);
            if (refused(source, player, lookup)) {
                continue;
            }
            EnumSet<BrewKind> unknown = EnumSet.allOf(BrewKind.class);
            unknown.removeAll(lookup.known());
            source.sendSuccess(() -> Component.translatable(KEY + "list", player.getDisplayName(),
                    names(lookup.known()), names(unknown)), false);
            listed++;
        }
        return listed;
    }

    private static int change(CommandContext<CommandSourceStack> command, Collection<ServerPlayer> players,
                              boolean learn) throws CommandSyntaxException {
        // 整份写回身体进度；all 一次学会或忘掉全部药方。
        CommandSourceStack source = command.getSource();
        String recipe = StringArgumentType.getString(command, "recipe");
        Optional<Set<BrewKind>> selected = selection(recipe);
        if (selected.isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "unknown", recipe));
            return 0;
        }
        int changed = 0;
        for (ServerPlayer player : players) {
            BrewRecipeBook.Lookup lookup = BrewRecipeBook.lookup(player);
            if (refused(source, player, lookup)) {
                continue;
            }
            EnumSet<BrewKind> known = EnumSet.noneOf(BrewKind.class);
            known.addAll(lookup.known());
            if (learn) {
                known.addAll(selected.orElseThrow());
            } else {
                known.removeAll(selected.orElseThrow());
            }
            BrewRecipeBook.replace(player, known);
            source.sendSuccess(() -> Component.translatable(KEY + (learn ? "learned" : "forgotten"),
                    player.getDisplayName(), names(selected.orElseThrow())), true);
            changed++;
        }
        return changed;
    }

    private static Optional<Set<BrewKind>> selection(String recipe) {
        // all 选中全部药方；其余按药方路径找。
        if (ALL.equals(recipe)) {
            return Optional.of(EnumSet.allOf(BrewKind.class));
        }
        return RecipeKnowledge.kind(recipe).map(EnumSet::of);
    }

    private static boolean refused(CommandSourceStack source, ServerPlayer player, BrewRecipeBook.Lookup lookup) {
        // 身体暂停或没有身体时这个目标跳过，提示原因。
        if (lookup.presence() == BrewBodyHost.Presence.BOUND) {
            return false;
        }
        String reason = lookup.presence() == BrewBodyHost.Presence.PAUSED ? "body_paused" : "no_body";
        source.sendFailure(Component.translatable(KEY + reason, player.getDisplayName()));
        return true;
    }

    private static Component names(Set<BrewKind> kinds) {
        // 药方名按枚举顺序用顿号连起来；一个都没有时写「无」。
        if (kinds.isEmpty()) {
            return Component.translatable(KEY + "none");
        }
        MutableComponent joined = Component.empty();
        boolean first = true;
        for (BrewKind kind : EnumSet.copyOf(kinds)) {
            if (!first) {
                joined.append(Component.translatable(KEY + "separator"));
            }
            joined.append(BrewTexts.component(BrewCauldron.recipeName(kind)));
            first = false;
        }
        return joined;
    }
}
