# 熬药结构的来源

三座熬药结构都由用户 2026-11-11 在自己的 26.2 存档里用原版结构方块搭好、存成 NBT，再随 Brewing 的 JAR 发：

| 结构 | 随包文件 | 大小（东西×高×南北） | 用户存的 NBT 的 SHA-256 | 随包 NBT 的 SHA-256 |
|---|---|---|---|---|
| 一阶 | `data/meridian_brewing/structure/potion-lv1.nbt` | 5×5×5 | `cc22b60565d95d60e484b77804d82164fe637d020839e5d197dd04dfc2082825` | `2b268788608065cdb03362097b2477f970ba84f7a4198dcdcd1bafac9c8f6bd5` |
| 二阶 | `data/meridian_brewing/structure/potion-lv2.nbt` | 11×7×11 | `802fdaff843d4ddaa2a8cf22c08fc793db3e78bd0ffc4045bb66b4c23fdf0230` | `be131dc4f734c42a6f10dcbe8679f8c1a468c7d15e2480ab58fd8a73fef39008` |
| 三阶 | `data/meridian_brewing/structure/potion-lv3-auto.nbt` | 15×15×14 | `961f58386094db5b73eb7e751f08b6a140c1419b2b79c882409bb74aa23b3448` | `7c3ca8983619b68d329f7dc8070ef781f347f5b0fa16b0d3d384a0fe06221936` |

一阶用了本模组的铜釜（营火是原版的），二阶用了本模组的巫釜和铁炉座，三阶全是原版方块。结构和本仓其余内容一样按
PolyForm Noncommercial 1.0.0 发布，没有第三方来源。

## 打包时改过的地方

用户存的 NBT 只改了下面几处，其余每格、每个方块状态原样保留：

- 一阶：铜釜 (2,2,2) 去掉方块实体数据。用户存结构时釜里带着氧化计时、火候相位和温度，摆出来应当是一口新釜。
- 二阶：巫釜 (5,1,5) 同上，去掉方块实体数据。
- 三阶：第 8 层东边 (10,8,6)、(10,8,7) 补两块下半磨制深板岩台阶，和西边 x=3 那两块对称（用户定的）。

营火、铁炉座、木桶、铜箱子存的都是空的或默认值，没有动。改法是一段只读用户 NBT、按上面三条写出新文件的脚本，
不经过游戏；脚本和用户的原文件留在主会话的交接目录（`brewing-structures-20261111/work/patch_nbt.py`、`nbt/`），不随仓库发。

## NBT 以外的规则

每座结构另有一份规则 `data/meridian_brewing/brew_structure/<名字>.json`：

| 字段 | 意思 |
|---|---|
| `template` | 对应的 NBT |
| `tier` | 几阶 |
| `anchor` | 锚点格（釜，三阶是水池西北那格水），比对从这里反推结构原点 |
| `required_air` | 必须空着的格，NBT 里这些格也必须是空气 |
| `fuel` | 三阶的燃料格：空着或放 `#meridian_brewing:tower_fuel` 里的方块 |
| `automation` | 能换成漏斗、投掷器、发射器、比较器（`#meridian_brewing:structure_automation`）的外框格 |

读定义时先查一遍，不对就打错误日志、跳过这一份（别的结构照常能用）：`required_air`、`fuel` 点名的格必须在 NBT 里有一格
（带结构空位的 NBT 会漏掉它们），`required_air` 那格在 NBT 里得是空气，`automation` 点名的格得是外框；三阶还要有铜箱子、
木桶，燃料格正好 4 格。数据包覆盖三阶定义时照这几条写，写坏了服务端日志会说是哪一条。

外框按材料类比，材料类的标签在 `tags/block/frame/`。不在 JSON 里点名、又不属于任何外框类的方块按核心严格比；
外框格不算核心，`structure.frame_tolerance` 只放宽外框格。哪几格算核心、核心认哪些方块、怎么提示不对的那一格，
见 [怎么熬药](How-To-Brew.md) 的「搭结构」和「三阶药塔」。

（`fabric/.../BrewStructureDefinitions.java`、`BrewStructureMatcher.java`）
