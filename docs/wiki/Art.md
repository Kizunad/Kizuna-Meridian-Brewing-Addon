# 美术素材

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

现在用的是第一版。每个效果另有一版备选，原稿和 18 像素成品并排拼在一张总览里，等用户挑；换图只换 PNG，代码不动。
