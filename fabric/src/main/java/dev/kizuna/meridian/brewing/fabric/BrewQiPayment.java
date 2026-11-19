package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewQiSettings;
import dev.kizuna.meridian.brewing.server.BrewQiTally;
import dev.kizuna.meridian.brewing.server.BrewSelfQi;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.VesselKind;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionView;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.QiAccountChange;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 熬药耗真元：起熬前估一次够不够，熬制时每推进一秒付总数的 1/30，先取釜所在区域的环境真元，
 * 不够的部分从施法者已开经脉里扣；两边都付不满就记欠付，欠付超过宽限才断锅。取用和扣下的真元都散回环境（取用的由 Human 放回原区域，
 * 自身扣的作为活动释放进环境账）。以后的密度、来源门槛都接在这里。
 */
final class BrewQiPayment {
    /** 浮点尾差；比这小的缺口按付清算。 */
    private static final double TOLERANCE = 1e-9;
    private static BrewQiSettings settings;

    private BrewQiPayment() {
        // 只持有配置，账随釜，真元随区域和身体。
    }

    static void initialize(Path configPath) {
        // 配置文件已由档位配置装好；旧文件缺耗元的键时用默认值并提示。
        settings = BrewQiSettings.load(configPath);
        BrewEnvironment.initialize();
    }

    static BrewQiTally tally(VesselKind vessel) {
        // 起熬时开一本新账，带上宽限；关掉耗元时总数为零，每秒什么都不取。
        return BrewQiTally.start(settings.brewCost(vessel), settings.graceSeconds());
    }

    static Optional<BrewText> startProblem(ServerPlayer caster, Level level, BlockPos pos, VesselKind vessel) {
        // 估环境这三十秒最多能给多少（剩余速率乘熬制秒数，不超过区域库存），加上自身已开经脉的余额；
        // 加起来不够这锅就不起熬。估算不算之后才来吐纳的身体，所以中途仍可能不够。
        double total = settings.brewCost(vessel);
        if (total <= 0) {
            return Optional.empty();
        }
        double ambient = ambientEstimate(level, pos);
        double self = selfBalance(caster);
        if (ambient + self + TOLERANCE >= total) {
            return Optional.empty();
        }
        return Optional.of(BrewText.message("qi.short_at_start", amount(total), amount(ambient), amount(self),
                amount(total - ambient - self)));
    }

    static BrewQiTally paySecond(ServerPlayer caster, Level level, BlockPos pos, String drawId, BrewQiTally tally) {
        // 先向环境取这一秒该付的（含前面欠的），差额再从施法者身上扣，身上有多少扣多少；
        // 还差的记成欠付，是否断锅由调用方看宽限。
        double payment = tally.nextPayment();
        if (payment <= TOLERANCE) {
            return tally.pay(0, 0);
        }
        double ambient = BrewEnvironment.draw(drawId, level, pos, payment).totalQi();
        double need = payment - ambient;
        if (need <= TOLERANCE) {
            return tally.pay(ambient, 0);
        }
        return tally.pay(ambient, paySelf(caster, need));
    }

    static BigDecimal amount(double qi) {
        // 动作栏和提示里统一保留一位小数；提示参数只收数字，用定点小数免得印出浮点尾巴。
        return BigDecimal.valueOf(qi).setScale(1, RoundingMode.HALF_UP);
    }

    private static double paySelf(ServerPlayer caster, double need) {
        // 没身体或暂停的身体付不了；余额不足一份时把已开经脉里的扣光，扣下的作为一次活动释放进环境。
        BrewBodyHost.Observation observed = BrewBodyHost.observe(caster);
        if (observed.presence() != BrewBodyHost.Presence.BOUND) {
            return 0;
        }
        BrewBodyHost.Binding binding = observed.found().orElseThrow();
        Map<String, QiInventory> lumens = BrewSelfQi.openedLumens(binding.body());
        double amount = Math.min(need, BrewSelfQi.balance(lumens));
        if (amount <= TOLERANCE) {
            return 0;
        }
        Map<String, QiInventory> debits = BrewSelfQi.debits(lumens, amount);
        BrewBodyHost.activity(binding, activity(lumens, debits));
        return QiInventory.combine(List.copyOf(debits.values())).totalQi();
    }

    private static double ambientEstimate(Level level, BlockPos pos) {
        // 没有区域（非主世界）就是零。
        Optional<RegionView> region = BrewEnvironment.region(level, pos);
        if (region.isEmpty()) {
            return 0;
        }
        return ambientEstimate(region.orElseThrow());
    }

    private static double ambientEstimate(RegionView region) {
        // 剩余速率乘熬制秒数，不超过区域库存。
        double stock = BodyProtobuf.fromInventory(region.getInventory()).totalQi();
        double rate = region.getSpareAbsorptionPerSecond();
        return Math.min(stock, rate * BrewCauldron.BREW_SECONDS);
    }

    private static double selfBalance(ServerPlayer caster) {
        // 没身体或暂停的身体付不了；暂停在药方检查那一步已经拦下。
        BrewBodyHost.Observation observed = BrewBodyHost.observe(caster);
        if (observed.presence() != BrewBodyHost.Presence.BOUND) {
            return 0;
        }
        return BrewSelfQi.balance(BrewSelfQi.openedLumens(observed.found().orElseThrow().body()));
    }

    private static BodyActivity activity(Map<String, QiInventory> lumens, Map<String, QiInventory> debits) {
        // 每条脉按原值做预期、扣后做替换，扣下的合起来作为释放，身体减少的量和释放的量对得上。
        var changes = new ArrayList<QiAccountChange>();
        for (Map.Entry<String, QiInventory> debit : debits.entrySet()) {
            QiInventory previous = lumens.get(debit.getKey());
            changes.add(QiAccountChange.newBuilder()
                    .setLumenId(debit.getKey())
                    .setExpected(BodyProtobuf.toInventory(previous))
                    .setReplacement(BodyProtobuf.toInventory(QiAccounting.debit(previous, debit.getValue())))
                    .build());
        }
        return BodyActivity.newBuilder()
                .addAllAccounts(changes)
                .setReleasedQi(BodyProtobuf.toInventory(QiInventory.combine(List.copyOf(debits.values()))))
                .build();
    }
}
