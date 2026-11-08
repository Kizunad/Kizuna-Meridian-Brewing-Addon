# 炼药

本仓提供 `meridian_brewing` 命名空间下的巫药、巫釜与炼药规则，面向 Minecraft 26.2 Fabric。
服务端规则位于 `server/`，Fabric 世界接线与资源位于 `fabric/`，目录解析和真实玩法验证分别位于
`verification/` 与 `gametest/`。

本仓依赖相邻工作区的 Framework 与 Technique 协议；Human、Threatening、Damage 和 BS3D 的跨仓入口
只在接口边界处声明，由对应仓库实现。`shared/src/main/resources/data/meridian_brewing/`
保存唯一的功法目录源文件。

本地检查：

```sh
flock "/tmp/kizuna-gradle.lock" -c '"./gradlew" test build --no-daemon'
flock "/tmp/kizuna-gradle.lock" -c '"./gradlew" :gametest:runGametest --no-daemon'
python3 "scripts/check_docs.py"
python3 "scripts/check_data.py"
git diff --check
```

本仓当前保留工作区改动，不在实现窗口创建远端或提交代码。
