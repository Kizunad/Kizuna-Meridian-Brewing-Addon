package dev.kizuna.meridian.brewing.server;

/** 可信服务端的控制器；输入 CharmCommand，输出 CharmReply，不开放网络写入口。 */
public interface CharmController {
    String ENTRYPOINT = "meridian_brewing_charm_controller";
    String CALLER = "meridian_brewing:charm";

    byte[] exchange(String caller, byte[] payload);
}
