# 器具外观与客户端模块

炼药的外观分两处：方块状态、方块模型、贴图和语言随 `fabric/` 进服务端和客户端；
放置模型与枯木染色只在 `client/`（模组 ID `meridian_brewing_client`，`environment: client`），不进服务端路径。
客户端模块硬依赖 `meridian_brewing` 和 BS3D 客户端 `backpack_storage_3d_client`。

## 三件 Bong 模型

铁火盆、铁炉座和药刀用 Bong 仓固定提交里的 Blockbench 模型，随客户端 JAR 放在
`client/src/main/resources/bs3d/meridian_brewing/`。仓库地址、提交、源路径、Git blob SHA-1、对应 PR、
原始与分发后的 SHA-256 和转换方式记在同目录 `sources.json`；提交号以该文件为准。

- 重新导入：`python3 scripts/import_bong_models.py --bong ~/Code/Bong`。只按 `sources.json` 记的提交 `git show`
  读文件，核对 blob SHA-1 和 SHA-256，不读 Bong 工作副本，也不改 Bong 仓。
- 校验：`python3 scripts/import_bong_models.py --check`，`check` 任务经 `checkModels` 一并执行。检查未登记文件、
  SHA-256、Blockbench 4.x、内嵌 PNG、只有逐面 UV 的 cube、outliner 与元素一一对应、outliner 里每个组都有 UUID，
  以及收刃参数与分发文件一致。
- 铁火盆和铁炉座逐字节原样分发（`conversion: none`）。
- 药刀原文件由脚本生成，outliner 里的组没有 `uuid`，BS3D 按组 UUID 建骨骼，缺了会整件导入失败。导入时按来源地址
  和组在 outliner 里的位置补固定的 UUID（`uuid5`），几何和贴图不变（`conversion: group-uuids`）。
- 收刃药刀 `herb_knife_folded` 由导入脚本从同一源文件生成（`conversion: group-uuids+fold`）：补 UUID 后，把
  `fold.groups` 列的刀背、刀刃两组绕转轴 `fold.pivot` 按 `fold.rotation` 转进刀柄槽，立方体和贴图不变。
  参数只在 `sources.json` 里改，不手改分发文件。

## 放置模型接线

`BrewingClient` 按 BS3D 的放置模型入口（见 BS3D `docs/wiki/Model-Resources.md`「放置模型」）登记四份模型，
登记 ID 与方块、物品 ID 相同：

| 模型 | 原点（模型像素） | 绘制 |
|---|---|---|
| `iron_brazier`、`iron_stove` | (0, 0, 0)：模型以中轴为原点 | `brew_fire` 方块实体，按方块 ID 取模型 |
| `herb_knife`、`herb_knife_folded` | (8, 0, 8)：模型按方块格画 | 只作物品 |

- 方块侧：火盆和炉座的方块状态按 `facing` 列四个变体，都指向只有粒子贴图的方块模型，几何全由方块实体绘制；
  碰撞和轮廓用 `BrewingBlocks` 里按模型外框量出的形状。`FacingApparatusBlock` 让正面朝向放置的玩家，
  结构旋转和镜像时朝向一起转。
- 物品侧：客户端 `assets/meridian_brewing/items/<id>.json` 用 `minecraft:special` 引用 `bs3d:placed`；
  `base` 模型给各视角的显示变换。服务端 JAR 不带这三个物品定义。
- 药刀物品按 `minecraft:display_context` 选模型：只有掉落物（`ground`）画收刃，其余视角
  （物品栏、手持、展示框、头上）画展开。
- 所有方块物品取方块的翻译键（`useBlockDescriptionPrefix`），物品名与方块名相同。
- 第一人称由 Players Animation 画全身，手持物在身体两侧，第一人称也用第三人称视角的显示变换：
  火盆、炉座、晒药匾和药刀低头时都在右手边看得到，第三人称在手上。

### 药刀的显示变换

`client/.../models/item/herb_knife.json` 的 `display` 由本仓按模型外框算出，目标是各视角都露出刀刃侧面：

- `gui`：刀柄斜放，转轴在左上、尾绳在右下，展开的刀刃垂在刀柄左侧，和 Bong 的药刀图标同一方向；
- `fixed`（展示框）：同一侧面朝外；
- `thirdperson_*`：刀柄握在掌中，刀刃平面朝身体两侧，刃口朝前；
- `ground`（掉落物）：和 `gui` 同样斜放、缩到 0.6，像原版工具的掉落物一样竖着绕竖轴自转，
  转到正对时看得到收起的刀身；
- `head` 沿用 Bong 原值；
- `firstperson_*` 沿用 Bong 原值；装了 Players Animation 后不用。

## 晒药匾

晒药匾由 `scripts/draw_herb_crate.py` 生成原版方块模型和两张 16 像素贴图，生成结果随仓提交：

- 外形是矮木框敞口浅底：四角 2 像素短足，匾底下两根横撑，竹编匾底四周围一圈 3 像素高的木框，
  总高 5 像素、外沿 14 × 14 像素；前后左右对称，没有朝向。
- 木框贴图用原版橡木木板的色板画顺纹木条，匾底用原版白桦木板的色板画压一挑一的竹篾编纹，均按固定种子生成。
- 碰撞和轮廓是木框外框 `Block.box(1, 0, 1, 15, 5, 15)`。
- 物品用方块模型。匾里的药材不显示。
- 旧存档的晒药匾方块状态带 `facing`，读入时丢掉该属性，按无朝向的默认状态显示；方块实体里的库存不受影响。

## 物品图标

药方、枯草灰、干苔、干虞美人、河豚干、毒薯片、浆果泥、药渣各有本仓 16 像素图标
（`fabric/.../textures/item/<id>.png`），物品模型 `item/generated` 单层。图标逐像素手画，PNG 就是源文件，
直接改 PNG；风格贴近原版物品：本色加深勾边、左上受光、透明底。

## 巫釜与铜釜

两种釜由 `scripts/draw_cauldrons.py` 生成原版方块模型和 16 像素贴图（本仓自己画，色板取原版炼药锅和四档铜块），
生成结果随仓提交。巫釜三足圆腹、最宽 12 像素、口沿外翻；铜釜圆腹无足、最宽 14 像素、两侧有耳，四档氧化各一套贴图。
釜内液体和蒸汽不画。

铜釜用方块状态 `oxidation`（`unaffected`、`exposed`、`weathered`、`oxidized`）显示氧化级：

- 规则状态仍以方块实体里的 `BrewCauldron` 为准；方块实体每次 tick 后把氧化级写回方块状态（`UPDATE_CLIENTS`），
  上蜡、刮锈和自然老化都在下一次 tick 后显示。
- 用 `/setblock` 或结构方块放下的铜釜带着方块状态里的氧化级，方块实体从它起算，未上蜡；
  手写的方块实体数据里没有铜釜字段时也按方块状态取。旧存档的铜釜方块状态读作 `unaffected`，
  方块实体存着原来的氧化级，第一次 tick 后外观跟上。
- 选方块状态而不是方块实体绘制：四档贴图走区块网格，不需要额外的同步包，导出的结构 NBT 自带外观。
- 上蜡没有外观差别，不进方块状态。

## 枯木

九种原版树（橡、云杉、白桦、丛林、金合欢、深色橡、红树、樱花、苍白橡）各有 `dead_<树>_log` 与 `dead_<树>_wood`。
模型直接用原版原木贴图：`block/dead_log` 的侧面 `tintindex 0`、端面 `tintindex 1`，`block/dead_wood` 六面都是
`tintindex 0`。

- 方块：`DeadWoodTint` 经 Fabric `BlockColorRegistry` 给每种枯木登记常量染色（ARGB），侧面和端面各一色。
- 物品：`items/dead_*.json` 的 `tints` 写同样的 `minecraft:constant` 值。两处的数值由 `DeadWoodTintTest` 对照。
- 枯木与原版原木一样按 `axis` 横竖放置，旋转后端面仍用端面染色。

## 真实客户端截图

`clienttest/` 用 Fabric 客户端 GameTest 在单人世界里摆场景并截图，不比对像素，供人工检查朝向、贴图错位和缺面：

```sh
flock /tmp/kizuna-gradle.lock env ALSOFT_DRIVERS=null LIBGL_ALWAYS_SOFTWARE=1 \
  xvfb-run -a -s "-screen 0 1280x960x24" ./gradlew --no-daemon :clienttest:runClientGametest \
  -PclienttestRunDir=<新目录>
```

截图写在运行目录的 `screenshots/`。场景和文件名见 `BrewingModelsClientTest`。

## 许可

铁火盆、铁炉座和药刀模型来自 [Bong](https://github.com/Kizunad/Bong)：铁火盆、铁炉座对应
[PR #2422](https://github.com/Kizunad/Bong/pull/2422)，药刀对应 [PR #2148](https://github.com/Kizunad/Bong/pull/2148)。
照 BS3D 引入 Bong 装备的做法，只记来源（`sources.json`），不另写许可。
巫釜、铜釜、晒药匾的模型和贴图，8 个物品图标和枯木染色是本仓原创；枯木引用的是原版贴图路径，JAR 不带原版贴图。
