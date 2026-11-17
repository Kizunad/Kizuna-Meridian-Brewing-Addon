package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.QiAccountChange;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 服下丹露得的那份本行真元怎么进身体：记进本行（带「这一行的经」标签）、已经开拓的经脉内腔，按经脉 ID 先后装，
 * 一条装不下顺延下一条，都装不下的浪费。游炁得游离态，真炁及以上得气态。
 * 这份真元是外来的，写进活动的「外来真元」，身体的守恒校验把它算进去。
 */
public final class BrewElixirIntake {
    /** Human 给五行经脉打的标签前缀，后面接属性 ID 冒号后的那一段（fire、water……）。 */
    private static final String MERIDIAN_TAG = "human:meridian_";

    private BrewElixirIntake() {
        // 只做计算，入账由宿主活动原子提交。
    }

    /** 这一次的入账计划和真正装进去的量。 */
    public record Intake(BodyActivity activity, double credited) { }

    public static Intake plan(BodySnapshot message, String attribute, double amount) {
        // 一点都装不进（这一行的经一条没开、都满了、这一次药效为零）时活动是空的，量是 0。
        var body = BodyProtobuf.fromMessage(message);
        double density;
        if (body.realm() == Realm.WANDERING_QI) {
            density = QiDensity.FREE;
        } else {
            density = QiDensity.baseline(QiPhase.GAS);
        }
        return fill(body, attribute, amount, density);
    }

    private static Intake fill(dev.kizuna.meridian.core.BodySnapshot body, String attribute,
                               double amount, double density) {
        // 按经脉身份依次装入本行真元，容量不足的剩余药力直接浪费。
        var tag = new DefinitionId(MERIDIAN_TAG + attribute.substring(attribute.indexOf(':') + 1));
        var changes = new ArrayList<QiAccountChange>();
        var credits = new ArrayList<QiInventory>();
        double left = amount;
        List<Anatomy.MeridianDefinition> targets = body.structure().meridians().stream()
                .filter(meridian -> meridian.tags().contains(tag))
                .sorted(Comparator.comparing(meridian -> meridian.id().value())).toList();
        for (Anatomy.MeridianDefinition meridian : targets) {
            double fits = Math.min(left, room(body, meridian) * density);
            if (fits < QiAccounting.MIN_TRANSFER_QI) {
                continue;
            }
            MeridianState state = state(body, meridian);
            QiInventory lumen = state.lumenQi();
            var credit = new QiInventory(List.of(new Qi(new Qi.QiAttribute(new DefinitionId(attribute)), fits,
                    density)));
            changes.add(QiAccountChange.newBuilder()
                    .setLumenId(meridian.id().value())
                    .setExpected(BodyProtobuf.toInventory(lumen))
                    .setReplacement(BodyProtobuf.toInventory(QiAccounting.credit(lumen, credit)))
                    .build());
            credits.add(credit);
            left -= fits;
        }
        var activity = BodyActivity.newBuilder()
                .addAllAccounts(changes);
        if (!credits.isEmpty()) {
            activity.setIngestedQi(BodyProtobuf.toInventory(QiInventory.combine(credits)));
        }
        return new Intake(activity.build(), amount - left);
    }

    private static MeridianState state(dev.kizuna.meridian.core.BodySnapshot body,
                                       Anatomy.MeridianDefinition meridian) {
        // 定义与状态按经脉身份对应，不依赖列表顺序。
        return body.meridianStates().stream().filter(item -> item.id().equals(meridian.id())).findFirst()
                .orElseThrow();
    }

    private static double room(dev.kizuna.meridian.core.BodySnapshot body, Anatomy.MeridianDefinition meridian) {
        // 没开拓的经脉不收；开着的按内腔体积减去已有的和正往里送的算。
        MeridianState state = state(body, meridian);
        if (state.opening().stage() != MeridianOpening.Stage.OPENED) {
            return 0;
        }
        List<Anatomy.PortId> ports = meridian.ports().stream().map(Anatomy.Port::id).toList();
        List<Anatomy.ConnectionId> inbound = body.structure().connections().stream()
                .filter(connection -> ports.contains(connection.to())).map(Anatomy.Connection::id).toList();
        double reserved = body.transits().stream().filter(transit -> inbound.contains(transit.connectionId()))
                .mapToDouble(transit -> transit.cargo().volume()).sum();
        return Math.max(0, meridian.lumenVolume() - state.lumenQi().volume() - reserved);
    }
}
