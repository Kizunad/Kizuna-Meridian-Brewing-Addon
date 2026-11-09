package dev.kizuna.meridian.brewing.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewBodyState;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

/** 药效来源、部位与恢复游标存入唯一身体进度，实体状态图标负责可见生命周期。 */
public final class BrewBodyProgress {
    public static final String KEY = "meridian_brewing:effects";
    public static final int SCHEMA_VERSION = 1;

    private BrewBodyProgress() {
        // 不维护进程内第二份药效存档。
    }

    public static BrewBodyState read(BodySnapshot body) {
        // 初次用药为空状态；已有但无法解码的进度必须报错。
        Any stored = body.getAddonProgressMap().get(KEY);
        if (stored == null) {
            return BrewBodyState.newBuilder()
                    .setSchemaVersion(SCHEMA_VERSION)
                    .build();
        }
        try {
            BrewBodyState state = stored.unpack(BrewBodyState.class);
            if (state.getSchemaVersion() != SCHEMA_VERSION) {
                throw new IllegalArgumentException("不支持的药效进度版本");
            }
            return state;
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException("药效进度损坏", invalid);
        }
    }

    public static void write(BodyActivity.Builder activity, BrewBodyState state) {
        // 与同笔能力增撤一起提交，不能让显示来源和身体修正分别成功。
        activity.putProgress(KEY, Any.pack(state));
    }
}
