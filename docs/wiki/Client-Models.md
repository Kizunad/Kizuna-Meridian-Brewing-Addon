# 器具外观与客户端模块

炼药的外观分两处：方块状态、方块模型、贴图和语言随 `fabric/` 进服务端和客户端；
放置模型与枯木染色只在 `client/`（模组 ID `meridian_brewing_client`，`environment: client`），不进服务端路径。
客户端模块硬依赖 `meridian_brewing`、BS3D 客户端 `backpack_storage_3d_client` 和 Players Animation
`kizuna_players_animation`（药刀物品定义用它的数值属性）。

## 三件 Bong 模型

铁火盆、铁炉座和药刀用 Bong 仓固定提交里的 Blockbench 模型，随客户端 JAR 放在
`client/src/main/resources/bs3d/meridian_brewing/`。仓库地址、提交、源路径、Git blob SHA-1、对应 PR、
原始与分发后的 SHA-256 和转换方式记在同目录 `sources.json`；提交号以该文件为准。

- 重新导入：`python3 scripts/import_bong_models.py --bong ~/Code/Bong`。只按 `sources.json` 记的提交 `git show`
  读文件，核对 blob SHA-1 和 SHA-256，不读 Bong 工作副本，也不改 Bong 仓。
- 校验：`python3 scripts/import_bong_models.py --check`，`check` 任务经 `checkModels` 一并执行。检查未登记文件、
  SHA-256、Blockbench 4.x、内嵌 PNG、只有逐面 UV 的 cube、outliner 与元素一一对应、outliner 里每个组都有 UUID，
  以及刀刃开合剪辑与 `sources.json` 记的参数一致。
- 铁火盆和铁炉座逐字节原样分发（`conversion: none`）。
- 药刀原文件由脚本生成，outliner 里的组没有 `uuid`，BS3D 按组 UUID 建骨骼，缺了会整件导入失败。导入时按来源地址
  和组在 outliner 里的位置补固定的 UUID（`uuid5`），几何和贴图不变（`conversion: group-uuids`）。
- 药刀补 UUID 后再加刀刃开合剪辑（`conversion: group-uuids+open`）：`open.groups` 列的刀背、刀刃两组枢轴移到
  转轴 `open.pivot`（组本身不转，立方体和贴图不变，静止几何与来源相同），再写一段名为 `open` 的线性剪辑，
  长 `open.seconds`，0 s 两组转 `open.closed_rotation`（转进刀柄槽，合刃），末帧回到 0（展开）。
  参数只在 `sources.json` 里改，不手改分发文件。第二轮单独生成的收刃模型 `herb_knife_folded` 已删掉，
  合刃就是这段剪辑的进度 0。

## 放置模型接线

`BrewingClient` 按 BS3D 的放置模型入口（见 BS3D `docs/wiki/Model-Resources.md`「放置模型」）登记三份模型，
登记 ID 与方块、物品 ID 相同：

| 模型 | 原点（模型像素） | 绘制 |
|---|---|---|
| `iron_brazier`、`iron_stove` | (0, 0, 0)：模型以中轴为原点 | `brew_fire` 方块实体，按方块 ID 取模型 |
| `herb_knife` | (8, 0, 8)：模型按方块格画 | 只作物品，按 `progress` 画刀刃开合 |

- 方块侧：火盆和炉座的方块状态按 `facing` 列四个变体，都指向只有粒子贴图的方块模型，几何全由方块实体绘制；
  碰撞和轮廓用 `BrewingBlocks` 里按模型外框量出的形状。`FacingApparatusBlock` 让正面朝向放置的玩家，
  结构旋转和镜像时朝向一起转。
- 物品侧：客户端 `assets/meridian_brewing/items/<id>.json` 用 `minecraft:special` 引用 `bs3d:placed`；
  `base` 模型给各视角的显示变换。服务端 JAR 不带这三个物品定义。
- 药刀物品按 `minecraft:display_context` 选进度，三支都指同一份 `herb_knife`：物品栏（`gui`）固定 `progress: 1`
  展开，掉落物（`ground`）固定 `progress: 0` 收刃；其余视角（手持、展示框、头上）走 `minecraft:range_dispatch`，
  属性 `kizuna_players_animation:item_state`（`channel: open`、`idle: 1`），八档阈值 0.125、0.25 … 1.0
  各画同值进度，低于 0.125 画进度 0。手里的值来自持有者此刻 PA 剪辑的 `open` 轨道（见下节）；
  展示框、头上和没在播剪辑时取 `idle`，是展开的。
- BS3D 按模型在该进度下的外形报物品包围盒，掉落物按刀身真实最低点贴地。
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

## 药刀动作

药刀的手臂动作和刀刃开合都经 Players Animation 播放，PA 已有的下发和客户端补帧负责同步，不另开网络包。
切药由服务端播（`ServerPlayerAnimations.play`）；开刃由本人客户端在换刀那一 tick 自己播，服务端只发给追踪者。两段动作的关键帧、规格和门禁结果在 PA `docs/animation-specs/`
（`herb_knife_draw.md`、`herb_knife_cut.md`、`herb_knife_blade.md`、`PROGRESS-herb-knife.md`）。

- **甩腕开刃** `herb_knife_draw`（0.60 s）：主手从别的物品换成药刀时播一次；上线、换维度、重生时只记下当前主手不播，
  副手拿药刀不播，换走药刀不停（播满再淡出）。本人客户端 `HerbKnifeDrawClient` 每个客户端 tick 末尾看本机主手，
  换刀当 tick 用 PA 客户端入口自己播，换刀后画的第一帧刀就是合着的，不等服务端往返；服务端 `HerbKnifeDraw`
  每 tick 看在线玩家的主手，用 `ServerPlayerAnimations.playForTrackers` 只发给追踪这名玩家的其他人，
  到期停止也只发他们。数字键换刀在客户端 tick 里处理，当 tick 就开播；滚轮换刀发生在两 tick 之间，
  到下一 tick 末尾才开播，中间不到 1 tick 的帧会按原版持握姿势画一下开着的刀（已接受）。
  剪辑的 `open` 轨道 0.20 → 0.26 s 由 0 到 1，物品定义按它画刀刃转开。开刃不出声。
- **切药** `herb_knife_cut`（1.00 s）：只认主手药刀、副手毒马铃薯。右键开始使用（`startUsingItem`，不挥手），
  服务端同时播切药；使用时长 20 tick，开始使用后的第 11 tick（0.55 s，刃口压过药材）出一片毒薯片、
  扣一份药材、药刀耗损 1，并在持刀者处放 `meridian_brewing:knife.cut`（原版剪羊毛声，音量 0.5、音高 1.2）。
  按住右键时原版在用满后的下一 tick 再次开始使用，一秒一刀；第 11 tick 前松手不出片，松手时停止切药剪辑。
  开刃没播完就切，不拦，切药接管手臂和刀刃。一刀之间换走副手药材就不出片。
- 收刀合刃不做动作：换走药刀时刀直接从手里消失。
- 第一人称由 PA 画全身。切药在第一人称里不取胯、腰、胸、头的轨道（PA 索引 `fpv_torso: false`），上身不跟着前倾，两手、刀和药材照剪辑；第三人称和别的玩家看到的不变。
  原版视野 70° 时低头 30° 药材上沿进画面，40° 露上半截，slim 45°、classic 50° 起整块在两拳之间，一直低到 90° 都看得到（2026-11-11 真实客户端截图，身体和头同向）。

## 晒药匾

晒药匾由 `scripts/draw_herb_crate.py` 生成原版方块模型和两张 16 像素贴图，生成结果随仓提交：

- 外形是矮木框敞口浅底：四角 2 像素短足，匾底下两根横撑，竹编匾底四周围一圈 3 像素高的木框，
  总高 5 像素、外沿 14 × 14 像素；前后左右对称，没有朝向。
- 木框贴图用原版橡木木板的色板画顺纹木条，匾底用原版白桦木板的色板画压一挑一的竹篾编纹，均按固定种子生成。
- 碰撞和轮廓是木框外框 `Block.box(1, 0, 1, 15, 5, 15)`。
- 物品用方块模型。匾里的药材不显示。
- 旧存档的晒药匾方块状态带 `facing`，读入时丢掉该属性，按无朝向的默认状态显示；方块实体里的库存不受影响。

## 物品图标

药方、枯草灰、干苔、干虞美人、河豚干、毒薯片、浆果泥、药渣各有本仓 32×32 图标
（`fabric/.../textures/item/<id>.png`），物品模型 `item/generated` 单层，和其他 Kizuna 附属的物品图标同尺寸。
图标由 `gpt-image-2.5-sunburst` 出像素画原稿，按原稿自己的像素格逐格取色、去掉底色后居中放进 32×32，不缩放、不手修；
原稿和转换脚本留在会话交接目录，不进 JAR。以后改图标直接改 PNG。

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

截图写在运行目录的 `screenshots/`。场景和文件名见 `BrewingModelsClientTest`、`KnifeScenes` 和 `BrewPlayScenes`。
`BrewPlayScenes` 拍生存里能看到的熬药流程：晒药匾界面、注水下料点火、熬药式起手后动作栏的几个时刻、副手下辅料和引药、
舀成药、焦锅舀药渣、成药提示、创造栏里的巫药，以及七个状态效果图标；每拍一张在日志里记一行 `[炼药截图]` 釜的状态。
`BrewLoreClientTest`（场景 `lore`，`-PclienttestOnly=lore`）手持聚气汤药方和《熬药总纲》右键，各拍原版书本界面的首页、
第二页和末页，再把语言换成英文拍一遍总纲，看中英文排页。
离线玩家名决定原版默认皮肤：缺省用 `Player991`（classic 宽臂），加 `-PclienttestUsername=Player507` 拍 slim 的 Alex。

## 许可

铁火盆、铁炉座和药刀模型来自 [Bong](https://github.com/Kizunad/Bong)：铁火盆、铁炉座对应
[PR #2422](https://github.com/Kizunad/Bong/pull/2422)，药刀对应 [PR #2148](https://github.com/Kizunad/Bong/pull/2148)。
照 BS3D 引入 Bong 装备的做法，只记来源（`sources.json`），不另写许可。
巫釜、铜釜、晒药匾的模型和贴图，8 个物品图标和枯木染色是本仓原创；枯木引用的是原版贴图路径，JAR 不带原版贴图。
