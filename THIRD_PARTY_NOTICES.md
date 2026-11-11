# 第三方说明

PolyForm Noncommercial 1.0.0 仅适用于本项目有权许可的原创部分。

| 组件 | 来源 | 许可与使用 |
|---|---|---|
| Gradle Wrapper | 9.8.0，来自相邻 Framework | Apache License 2.0；保留版权头和分发校验和 |
| Meridian Framework、Technique Framework、Human Addon | 相邻工作区 | PolyForm Noncommercial 1.0.0；仅验证工具通过 composite build 引用，组合方式与版本由 Framework 的 `scripts/workspace.gradle`、`scripts/libs.versions.toml` 提供 |
| Protocol Buffers | 4.36.2 | BSD 3-Clause；仅验证工具使用 |
| JUnit / Hamcrest Core | 4.13.2 / 1.3 | EPL 1.0 / BSD 3-Clause；仅测试使用 |
| Fabric Loom、Fabric API、Fabric Loader | 1.19.3 / 0.161.0+26.2 / 0.20.1 | Apache License 2.0；仅构建与 GameTest 使用，客户端截图验收用其中的客户端 GameTest |
| 铁火盆、铁炉座、药刀 Blockbench 模型 | [Bong](https://github.com/Kizunad/Bong)，铁火盆、铁炉座来自 PR #2422，药刀来自 PR #2148；提交号、源路径和 Git blob SHA-1 见 `client/src/main/resources/bs3d/meridian_brewing/sources.json` | 照 BS3D 引入 Bong 装备的做法记来源，不另写许可；药刀补组 UUID，收刃药刀由同一源文件转出，转换方式记在 `sources.json` |
| 物品图标、状态效果图标 | 本项目经 CPA（`cliproxy` 后端）用 `gpt-image-2.5-sunburst` 生成，物品图标按原稿像素格取色放进 32 × 32，状态效果图标缩到 18 × 18 | 本项目占位素材，不含第三方图像；出图和后处理见 `docs/wiki/Art.md` |
| BS3D 客户端 | 相邻工作区 `backpack-and-storage-3D` | 客户端模块经 composite build 编译引用其放置模型入口，不内嵌 |

正式 Fabric JAR 只包含本仓数据，不内嵌 Minecraft、Fabric、上游类或测试工具。
