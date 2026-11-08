package dev.kizuna.meridian.brewing.server;

/** 铜釜可同步给方块实体和客户端的氧化与上蜡状态。 */
public record CopperCauldronState(CopperOxidation oxidation, boolean waxed) {
    public CopperCauldronState {
        if (oxidation == null) {
            throw new IllegalArgumentException("铜釜氧化状态不能为空");
        }
    }

    public CopperCauldronState wax() {
        return new CopperCauldronState(oxidation, true);
    }

    public CopperCauldronState scrape() {
        return new CopperCauldronState(oxidation.previous(), waxed);
    }

    public CopperCauldronState age(CopperExposure exposure) {
        if (waxed || exposure == CopperExposure.SHELTERED) {
            return this;
        }
        return new CopperCauldronState(oxidation.next(), false);
    }
}
