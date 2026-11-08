package dev.kizuna.meridian.brewing.server;

/** 一次枯萎任务的世界改方块权限快照。 */
public record WitheringPermissions(
        WitheringSource source,
        boolean worldWithering,
        boolean mobGriefing,
        boolean protectedSpawn) {
    public WitheringPermissions {
        if (source == null) {
            throw new IllegalArgumentException("枯萎来源不能为空");
        }
    }

    public boolean allowsBlockChange() {
        if (!worldWithering || protectedSpawn) {
            return false;
        }
        return source == WitheringSource.PLAYER || mobGriefing;
    }
}
