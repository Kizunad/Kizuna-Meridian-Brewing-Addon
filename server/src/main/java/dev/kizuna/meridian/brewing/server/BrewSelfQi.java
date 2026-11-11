package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 施法者自身那份真元：只从已开经脉的内腔里扣，按各脉余额比例分摊，每条脉里再按各属性所占比例分摊。
 * 任何属性、任何相态都能用（占位规则）。
 */
public final class BrewSelfQi {
    private BrewSelfQi() {
        // 只做计算，扣账由宿主活动原子提交。
    }

    public static Map<String, QiInventory> openedLumens(BodySnapshot body) {
        // 按经脉 ID 排序，同一份身体每次算出的分摊顺序相同。
        var lumens = new LinkedHashMap<String, QiInventory>();
        BodyProtobuf.fromMessage(body).meridianStates().stream()
                .filter(state -> state.opening().stage() == MeridianOpening.Stage.OPENED)
                .sorted(Comparator.comparing(state -> state.id().value()))
                .forEach(state -> lumens.put(state.id().value(), state.lumenQi()));
        return lumens;
    }

    public static double balance(Map<String, QiInventory> lumens) {
        return lumens.values().stream().mapToDouble(QiInventory::totalQi).sum();
    }

    public static Map<String, QiInventory> debits(Map<String, QiInventory> lumens, double amount) {
        // 调用方先确认余额够；每条脉扣它在总余额里所占的比例，太小扣不动的零头留在脉里。
        double total = balance(lumens);
        if (!(amount > 0) || total < amount) {
            throw new IllegalArgumentException("自身真元不够这一份：" + total + " < " + amount);
        }
        var debits = new LinkedHashMap<String, QiInventory>();
        for (Map.Entry<String, QiInventory> lumen : lumens.entrySet()) {
            double share = amount * lumen.getValue().totalQi() / total;
            QiInventory debit = proportional(lumen.getValue(), share);
            if (!debit.entries().isEmpty()) {
                debits.put(lumen.getKey(), debit);
            }
        }
        return debits;
    }

    private static QiInventory proportional(QiInventory stock, double wanted) {
        // 按各属性所占比例取，不先把某一种取空；低于最小可流转量、或大余额减不动的那份不取。
        double stockQi = stock.totalQi();
        if (wanted <= 0 || stockQi <= 0) {
            return QiInventory.empty();
        }
        double fraction = Math.min(1, wanted / stockQi);
        var shares = new ArrayList<Qi>();
        for (Qi entry : stock.entries()) {
            double share = entry.amount() * fraction;
            if (share < QiAccounting.MIN_TRANSFER_QI || entry.amount() - share == entry.amount()) {
                continue;
            }
            shares.add(entry.withAmount(share));
        }
        return new QiInventory(shares);
    }
}
