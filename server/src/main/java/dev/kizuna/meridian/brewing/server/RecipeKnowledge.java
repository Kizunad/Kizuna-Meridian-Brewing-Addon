package dev.kizuna.meridian.brewing.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.brewing.protocol.pb.BrewRecipeMessages.BrewRecipeBook;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** 读过的药方存在施法者唯一身体的 addon_progress 里，不另建玩家账本。 */
public final class RecipeKnowledge {
    public static final String PROGRESS_KEY = "meridian_brewing:recipes";
    public static final int SCHEMA_VERSION = 1;

    private RecipeKnowledge() {
        // 只做身体进度的编解码，不在进程里另存一份。
    }

    public static Set<BrewKind> read(BodySnapshot body) {
        // 没读过任何药方时进度为空；已有但解不开、版本不对或认不出的药方都是存档损坏，必须报错。
        Any stored = body.getAddonProgressMap().get(PROGRESS_KEY);
        EnumSet<BrewKind> known = EnumSet.noneOf(BrewKind.class);
        if (stored == null) {
            return known;
        }
        BrewRecipeBook book;
        try {
            book = stored.unpack(BrewRecipeBook.class);
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException("药方进度损坏", invalid);
        }
        if (book.getSchemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的药方进度版本：" + book.getSchemaVersion());
        }
        for (String id : book.getRecipeIdsList()) {
            BrewKind kind = kind(id).orElseThrow(() -> new IllegalArgumentException("药方进度里有未知药方：" + id));
            if (!known.add(kind)) {
                throw new IllegalArgumentException("药方进度里药方重复：" + id);
            }
        }
        return known;
    }

    public static void write(BodyActivity.Builder activity, Collection<BrewKind> known) {
        // 整份覆盖写入，按枚举顺序排好，同一组药方总是同一份字节。
        var book = BrewRecipeBook.newBuilder().setSchemaVersion(SCHEMA_VERSION);
        EnumSet<BrewKind> sorted = known.isEmpty() ? EnumSet.noneOf(BrewKind.class) : EnumSet.copyOf(known);
        for (BrewKind kind : sorted) {
            book.addRecipeIds(kind.path());
        }
        activity.putProgress(PROGRESS_KEY, Any.pack(book.build()));
    }

    public static Optional<BrewKind> kind(String path) {
        // 药方羊皮纸组件、指令参数和进度都用同一个路径名找药方。
        for (BrewKind kind : BrewKind.values()) {
            if (kind.path().equals(path)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
