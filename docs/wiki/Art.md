# 美术素材

本页两部分：

- 七个状态效果图标；
- 炼药卷「熬药」的卷轴物品图、阅读背景和熬药式战斗图标。

## 状态效果图标

七个状态效果各一张图标，放在 `fabric/src/main/resources/assets/meridian_brewing/textures/mob_effect/<id>.png`，
文件名和 `BrewingEffects` 登记的 id 一致：`depletion`、`armor_rot`、`insect_lure`、`qi_gather`、`bitter_yellow`、
`charm_stagger`、`charm_control`。规格是 18 × 18、RGBA 透明底，和原版状态效果图标、五脏诀附属的图标相同；
原版自己按这个 id 找图，屏幕右上和背包旁边都用它。

**出图**：

- 经用户自己的 CPA（`cliproxy` 后端），模型 `gpt-image-2.5-sunburst`，`images` 接口，1024 × 1024；
- 提示词在 [`art-prompts/`](art-prompts/)，每个效果一份 `<id>_effect_icon_prompt.txt`。共同要求：原版状态效果图标的画法，
  16 个逻辑像素放大到 1024、最近邻，品红 `#FF00FF` 纯色底，不超过 7 种颜色，不写字；
- 主色取 `BrewingEffects.register` 里登记的效果颜色（枯竭 `0xA08A4A` 等）。两个魅惑同一颗心：僵直是裂开的心带三颗星，
  受惑是挂在两根提线上的心；
- 原稿放仓库根目录的 `.local-art/effects/<id>_v<n>.png`，已写进 `.gitignore`，不进仓库、不进 JAR。

**后处理**（不手修）：

1. 去底：红、蓝都高于 200 且绿低于 60 的像素算品红底。原稿的底色几乎是纯 `#FF00FF`，粉色的心不会被当成底；
2. 按不透明部分裁边，补成正方形；
3. 盒式缩到 16 × 16，透明处先按透明度预乘，免得品红渗进边缘；
4. 透明度以 128 为界，只留全透明和不透明；
5. 无抖动中值切分到最多 12 色；
6. 居中放进 18 × 18，四周各留 1 像素。

物品图标（[器具外观与客户端模块](Client-Models.md)）是按原稿自己的像素格逐格取色、不缩放；状态图标的原稿像素格不齐，
例如枯竭的原稿按格取样是 20 × 19，放不进 18 × 18，所以改成整体缩小。

用户 2026-11-11 选了第一版，现在用的就是它。每个效果另有一版备选原稿；换图只换 PNG，代码不动。

## 卷轴、阅读背景与熬药式图标

2026-11-11 补齐。此前启动日志报「卷轴 meridian_brewing:brewing_basics_scroll 缺少贴图，使用功法库通用卷轴图标」，
战斗 UI 也因目录没有 `presentation.icon_id` 而不显示图标。做法和五脏诀、行药诀那一轮相同。

**来源**：

- 全部主体由用户 srv 上的 CPA 生成：模型 `gpt-image-2.5-sunburst`，图片接口，`quality=high`、PNG、`style=none`；
- 调用脚本 `handoff/technique-art-20261028-work/cpa_generate.py` 读 `~/.config/kizuna/cpa-image.env`，日志不打印 key；
- 方形请求 1024×1024，CPA 实际返回 1254×1254 RGB；背景请求和返回都是 1536×1024；
- 原稿放仓内 `.local-art/{scroll,background,icon}/<名字>_v<版本>.png`，不进仓库、不进 JAR；每样出了 v1、v2 两版；
- 提示词在 [art-prompts/](art-prompts/)，由 `handoff/art-20261111/art/write_prompts_brewing.py` 生成，共用句与五脏诀、行药诀相同。

**成品**：资源命名空间 `meridian_brewing`，路径由功法库约定。卷轴物品 ID 是 `<卷>_scroll`，阅读背景按卷路径取，
战斗图标按 `icon_id` 取 `gui/technique/<路径>.png`。

| 内容 | 文件 | 尺寸 | 提示词 |
|---|---|---|---|
| 卷轴物品 | [brewing_basics_scroll.png](../../fabric/src/main/resources/assets/meridian_brewing/textures/item/brewing_basics_scroll.png) | 32×32 透明底 | [卷轴](art-prompts/brewing_basics_scroll_icon_prompt.txt) |
| 阅读背景 | [brewing_basics.png](../../fabric/src/main/resources/assets/meridian_brewing/textures/gui/scroll/brewing_basics.png) | 1536×1024 | [背景](art-prompts/brewing_basics_background_prompt.txt) |
| 熬药式图标，`icon_id` 为 `meridian_brewing:brew` | [brew.png](../../fabric/src/main/resources/assets/meridian_brewing/textures/gui/technique/brew.png)、[brew_64.png](../../fabric/src/main/resources/assets/meridian_brewing/textures/gui/technique/brew_64.png) | 32×32、64×64 | [图标](art-prompts/brew_technique_icon_prompt.txt) |

**风格**：本书偏草药和炉火，用铜色、炉火橙、草药绿、烟熏褐，和五脏诀的象牙玉色五行、行药诀的紫绿分开。

- 卷轴：纸边烟熏焦黄，两端带铜绿的铜轴头，中间一道草茎绳，绳下插一枝鲜草叶和一片干叶。
- 背景：两侧带铜绿的铜卷轴杆。四边留白处是吊挂的草药束、木勺、沿口刻细槽的铜釜，下沿一排灶火压着黑、白、红三层柴灰，
  上沿是蒸汽，取自熬药式描述里的大锅、木勺和三层灰。中间 x 18%–82%、y 12%–88% 是浅色纸面，给正文用。
- 熬药式图标：炭黑外圈、铜橙内圈、烟熏褐底，这是本书战斗图标的共用边框。主体是火上的铜釜，木勺斜插进去，一片草叶落入，
  釜口冒三缕蒸汽。

**后处理**：脚本 [scripts/process_art.py](../../scripts/process_art.py)，从五脏诀的同名脚本复制，只换命名空间、卷和式的名单、边框色。
成品全由原稿和脚本决定，不手修。

- 卷轴：去品红底，判据是 R、B ≥ 130，G ≤ 140，且 R−G、B−G ≥ 60。然后依次：
  - 裁去透明边，最近邻缩到 30×30 以内；
  - 透明像素 RGB 归零后无抖动量化为 32 色；
  - 居中放进 32×32 RGBA，alpha 二值化为 0 或 255。
- 阅读背景：保持 1536×1024 RGB。
- 战斗图标：先最近邻缩成 64×64。与主要暗底 RGB 距离不超过 20 的颜色统一成本书底色 `#24110E`，再无抖动量化为 32 色。
  最后重画两圈边框：
  - 外圈 `#101012`，内圈 `#E26D24`，颜色取自 v1 原稿实际画出的边框；
  - 64×64 每圈宽 2 像素；
  - 最近邻缩成 32×32 后每圈宽 1 像素。

重出某一张：

1. 改对应提示词；
2. 用 `handoff/art-20261111/art/run_cpa.sh <新版本> brewing:<名字>:<icon|scroll|background>` 出原稿；
3. 再跑 `python3 scripts/process_art.py v1 <种类>/<名字>=<新版本>`，只换这一张，例如 `icon/brew=v2`。

当前成品全部来自 v1 原稿，复现命令是 `python3 scripts/process_art.py v1`；换过某张后把新参数更新在这里。

**校验**：`verification` 的 `BrewingArtTest.scrollsBackgroundsAndTechniqueIconsCoverTheCatalog` 按目录逐项检查：

- 每卷有 32×32 卷轴物品图和 1536×1024 阅读背景；
- 每式的 `icon_id` 与式 ID 同名，32×32 和 64×64 图标都能解码。
