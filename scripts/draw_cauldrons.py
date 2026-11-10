"""巫釜与铜釜的方块模型和 16 像素贴图。

几何按层定义：每层给外半径和内半径（釜口以下的空腔），在 16×16 网格上按格心到中轴的距离取格，
相同的层合成一段，每段再拆成矩形元素；只保留露在外面的面，朝空腔的面用内壁贴图。
贴图按固定种子程序生成：色板取原版炼药锅与四档铜块的颜色，纹样是本仓自己画的（横向分带、锤痕、烟熏）。
输出原版方块模型 JSON（parent minecraft:block/block，物品栏里直接显示立体模型）。

用法：python3 scripts/draw_cauldrons.py（覆盖写入 fabric 资源；生成结果随仓提交）
"""

import json
import math
from pathlib import Path
import random

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'fabric/src/main/resources/assets/meridian_brewing'
SIZE = 16
CENTER = 8
NAMESPACE = 'meridian_brewing'


# ---------------------------------------------------------------- 几何

WITCH = {
    # y: (外半径, 内半径；None 表示实心)。三足圆腹，最宽 12 像素，比原版炼药锅小一圈；釜口外翻。
    'layers': {
        3: (3.2, None), 4: (4.7, None), 5: (5.6, 4.1), 6: (6.2, 4.8), 7: (6.2, 4.8), 8: (6.2, 4.8),
        9: (5.8, 4.4), 10: (5.0, 3.6), 11: (4.3, 3.0), 12: (5.6, 3.0),
    },
    # 三足各 2×2，从地面到釜底下一层，位置在半径约 4.5 的圆上，后一前二。
    'legs': [((7, 11), (9, 13)), ((3, 5), (5, 7)), ((11, 5), (13, 7))],
    'leg_height': 4,
    'lugs': [],
}

COPPER = {
    # 圆腹大铜锅，最宽 14 像素，比巫釜大一圈，无足，直接坐在火上；两侧各一个小耳。
    'layers': {
        0: (4.6, None), 1: (5.9, None), 2: (6.7, 5.3), 3: (7.2, 5.9), 4: (7.5, 6.2), 5: (7.5, 6.2),
        6: (7.5, 6.2), 7: (7.2, 5.9), 8: (6.7, 5.4), 9: (6.0, 4.8), 10: (7.0, 4.8),
    },
    'legs': [],
    'leg_height': 0,
    # 釜耳：(x 范围, z 范围, y 范围)，贴在最宽处两侧。
    'lugs': [((0, 1), (7, 9), (5, 8)), ((15, 16), (7, 9), (5, 8))],
}


def ring(outer, inner):
    # 格心到中轴的距离不超过外半径即为实心；在内半径以内的格是空腔。
    cells, hollow = set(), set()
    for x in range(SIZE):
        for z in range(SIZE):
            distance = math.hypot(x + 0.5 - CENTER, z + 0.5 - CENTER)
            if inner is not None and distance <= inner:
                hollow.add((x, z))
            elif distance <= outer:
                cells.add((x, z))
    return cells, hollow


def voxels(spec):
    # 返回实心格 {(x, y, z)} 与空腔格；空腔一直通到釜口，用来给内壁面挑贴图。
    solid, hollow = set(), set()
    for y, (outer, inner) in spec['layers'].items():
        cells, inside = ring(outer, inner)
        solid |= {(x, y, z) for x, z in cells}
        hollow |= {(x, y, z) for x, z in inside}
    for (x0, z0), (x1, z1) in spec['legs']:
        solid |= {(x, y, z) for x in range(x0, x1) for z in range(z0, z1) for y in range(spec['leg_height'])}
    for (x0, x1), (z0, z1), (y0, y1) in spec['lugs']:
        solid |= {(x, y, z) for x in range(x0, x1) for z in range(z0, z1) for y in range(y0, y1)}
    validate_closed(solid, hollow)
    return solid, hollow


def validate_closed(solid, hollow):
    # 空腔格四邻只能是实心或空腔（上方釜口除外），否则釜壁有缝，侧面能看穿。
    for x, y, z in hollow:
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            neighbour = (x + dx, y, z + dz)
            if neighbour not in solid and neighbour not in hollow:
                raise SystemExit(f'釜壁在 {(x, y, z)} 旁有缝')
        if (x, y - 1, z) not in solid and (x, y - 1, z) not in hollow:
            raise SystemExit(f'釜底在 {(x, y, z)} 下有缝')


def slabs(solid):
    # 相邻几层的格完全相同就合成一段，减少元素数量。
    heights = sorted({y for _, y, _ in solid})
    groups = []
    for y in heights:
        layer = frozenset((x, z) for x, yy, z in solid if yy == y)
        if groups and groups[-1][2] == layer and groups[-1][1] == y:
            groups[-1] = (groups[-1][0], y + 1, layer)
        else:
            groups.append((y, y + 1, layer))
    return groups


def rectangles(cells):
    # 贪心拆矩形：从左上角起先向 x 伸到头，再整行向 z 伸。
    remaining = set(cells)
    result = []
    for z in range(SIZE):
        for x in range(SIZE):
            if (x, z) not in remaining:
                continue
            x1 = x
            while (x1 + 1, z) in remaining:
                x1 += 1
            z1 = z
            while all((xx, z1 + 1) in remaining for xx in range(x, x1 + 1)):
                z1 += 1
            for xx in range(x, x1 + 1):
                for zz in range(z, z1 + 1):
                    remaining.discard((xx, zz))
            result.append(((x, z), (x1 + 1, z1 + 1)))
    return result


FACES = {
    'north': (0, 0, -1), 'south': (0, 0, 1), 'west': (-1, 0, 0),
    'east': (1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0),
}


def boundary(face, x0, y0, z0, x1, y1, z1):
    # 元素某一面外侧紧挨的那一层格。
    dx, dy, dz = FACES[face]
    xs = [x0 - 1] if dx < 0 else [x1] if dx > 0 else range(x0, x1)
    ys = [y0 - 1] if dy < 0 else [y1] if dy > 0 else range(y0, y1)
    zs = [z0 - 1] if dz < 0 else [z1] if dz > 0 else range(z0, z1)
    return [(x, y, z) for x in xs for y in ys for z in zs]


def face_texture(face, outside, hollow):
    # 朝空腔的侧面和釜底内面是内壁；其余向上的面是口沿和肩，向下的面是底。
    inner = sum(cell in hollow for cell in outside)
    if inner * 2 >= len(outside):
        return '#inside'
    if face == 'up':
        return '#top'
    if face == 'down':
        return '#bottom'
    return '#side'


def elements(solid, hollow):
    result = []
    for y0, y1, layer in slabs(solid):
        for (x0, z0), (x1, z1) in rectangles(layer):
            faces = {}
            for face in FACES:
                outside = [cell for cell in boundary(face, x0, y0, z0, x1, y1, z1) if cell not in solid]
                if not outside:
                    continue
                entry = {'texture': face_texture(face, outside, hollow)}
                if face == 'down' and y0 == 0:
                    entry['cullface'] = 'down'
                faces[face] = entry
            if faces:
                result.append({'from': [x0, y0, z0], 'to': [x1, y1, z1], 'faces': faces})
    return result


def model(spec, texture_prefix):
    solid, hollow = voxels(spec)
    textures = {name: f'{NAMESPACE}:block/{texture_prefix}_{name}' for name in ('side', 'top', 'bottom', 'inside')}
    textures['particle'] = textures['side']
    return {
        'parent': 'minecraft:block/block',
        'ambientocclusion': False,
        'textures': textures,
        'elements': elements(solid, hollow),
    }, solid, hollow


# ---------------------------------------------------------------- 贴图

def hex_colour(value):
    return tuple(int(value[index:index + 2], 16) for index in (0, 2, 4)) + (255,)


def mix(colour, other, amount):
    return tuple(round(a + (b - a) * amount) for a, b in zip(colour[:3], other[:3])) + (255,)


# 巫釜：比原版炼药锅更黑、略偏紫的生铁。
WITCH_RAMP = [hex_colour(c) for c in ('1c1a20', '242128', '2c2930', '35313a', '3f3a44', '4a4450', '57505d', '665e6b')]
# 四档铜色从深到浅，取自原版铜块对应档位的色板。
COPPER_RAMPS = {
    'copper': ('904931', '9a5038', 'a75a40', 'b26247', 'c26b4c', 'c87456', 'd67b5b', 'e3826c'),
    'exposed_copper': ('796454', '7f7257', '947661', '988c69', 'a87762', 'ba8277', 'ce8d83', 'd79a8e'),
    'weathered_copper': ('497164', '6a7147', '748d57', '6c975c', '64a077', '66a977', '7ab799', '8cc4a5'),
    'oxidized_copper': ('3b6655', '396e59', '3e816b', '4c9484', '53a178', '4fab90', '59b292', '6ec59f'),
}
# 铜锈斑：已有锈色的三档在锤痕之间点一些更绿的斑。
COPPER_PATINA = {
    'exposed_copper': ('7a9869', 0.10),
    'weathered_copper': ('5fae86', 0.18),
    'oxidized_copper': ('7fd0ad', 0.08),
}
SOOT = hex_colour('231d1a')


def draw(shade):
    # shade(x, y, rng) 返回色阶序号；固定种子，重跑得到同一张图。
    rng = random.Random(1110)
    return [[shade(x, y, rng) for x in range(SIZE)] for y in range(SIZE)]


# 巫釜外壁各层的基准色阶：口沿最亮，颈下一圈阴影，腹部上亮下暗，底与足最暗。
WITCH_SIDE_LEVELS = {12: 6, 11: 2, 10: 4, 9: 5, 8: 4, 7: 4, 6: 3, 5: 3, 4: 2, 3: 1, 2: 1, 1: 1, 0: 0}


def jitter(level, rng, low=0, high=7):
    # 少量像素上下浮一档，保留原版那种细碎的斑驳，又不至于满屏噪点。
    roll = rng.random()
    if roll < 0.15:
        level -= 1
    elif roll > 0.85:
        level += 1
    return max(low, min(high, level))


def witch_side(x, row, rng):
    # 行号 = 15 - 模型 y；釜口以上没有几何的行沿用口沿色。
    return jitter(WITCH_SIDE_LEVELS.get(15 - row, 6), rng)


def witch_top(x, row, rng):
    return jitter(6, rng, high=7)


def witch_inside(x, row, rng):
    return jitter(1, rng)


def witch_bottom(x, row, rng):
    return jitter(1, rng)


# 铜釜外壁各层的基准色阶：口沿最亮，颈暗，腹部中间亮、往下渐暗。
COPPER_SIDE_LEVELS = {10: 7, 9: 2, 8: 4, 7: 5, 6: 5, 5: 5, 4: 4, 3: 4, 2: 3, 1: 2, 0: 1}


def copper_side(x, row, rng):
    # 腹部零星点出锤痕：亮一档的像素下面紧跟暗一档的像素。
    y = 15 - row
    level = jitter(COPPER_SIDE_LEVELS.get(y, 7), rng, low=1)
    if 3 <= y <= 7 and (x * 7 + y * 3) % 11 == 0:
        return min(7, level + 1)
    if 2 <= y <= 6 and (x * 7 + (y + 1) * 3) % 11 == 0:
        return max(1, level - 1)
    return level


def copper_top(x, row, rng):
    return jitter(6, rng, high=7)


def copper_inside(x, row, rng):
    return jitter(2, rng)


def copper_bottom(x, row, rng):
    return jitter(1, rng)


def colourise(indices, ramp, extra=None):
    image = Image.new('RGBA', (SIZE, SIZE))
    for row, values in enumerate(indices):
        for x, value in enumerate(values):
            colour = ramp[value]
            if extra:
                colour = extra(x, row, colour)
            image.putpixel((x, row), colour)
    return image


def copper_textures(stage):
    ramp = [hex_colour(c) for c in COPPER_RAMPS[stage]]
    patina = COPPER_PATINA.get(stage)
    spots = random.Random(2410)
    spot_map = [[spots.random() for _ in range(SIZE)] for _ in range(SIZE)]

    def weather(x, row, colour):
        # 锈斑在各档用同一张随机图，档位越高斑越多，四档放在一起看是同一口锅在变。
        if patina and spot_map[row][x] < patina[1]:
            return mix(colour, hex_colour(patina[0]), 0.7)
        return colour

    def sooted(x, row, colour):
        # 锅底两行和底面熏黑：铜锅直接坐在火上。
        colour = weather(x, row, colour)
        if 15 - row <= 1:
            return mix(colour, SOOT, 0.45)
        return colour

    return {
        'side': colourise(draw(copper_side), ramp, sooted),
        'top': colourise(draw(copper_top), ramp, weather),
        'inside': colourise(draw(copper_inside), ramp, weather),
        'bottom': colourise(draw(copper_bottom), ramp, lambda x, row, colour: mix(colour, SOOT, 0.6)),
    }


def witch_textures():
    return {
        'side': colourise(draw(witch_side), WITCH_RAMP),
        'top': colourise(draw(witch_top), WITCH_RAMP),
        'inside': colourise(draw(witch_inside), WITCH_RAMP),
        'bottom': colourise(draw(witch_bottom), WITCH_RAMP),
    }


# ---------------------------------------------------------------- 输出

def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def write_textures(prefix, images):
    for name, image in images.items():
        path = ASSETS / 'textures/block' / f'{prefix}_{name}.png'
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path)


COPPER_STAGES = ('copper', 'exposed_copper', 'weathered_copper', 'oxidized_copper')
COPPER_OXIDATION = ('unaffected', 'exposed', 'weathered', 'oxidized')


def main():
    witch, _, _ = model(WITCH, 'witch_cauldron')
    write_json(ASSETS / 'models/block/witch_cauldron.json', witch)
    write_textures('witch_cauldron', witch_textures())
    copper, _, _ = model(COPPER, 'copper_cauldron')
    write_json(ASSETS / 'models/block/copper_cauldron.json', copper)
    variants = {}
    for stage, oxidation in zip(COPPER_STAGES, COPPER_OXIDATION):
        prefix = f'{stage}_cauldron'
        write_textures(prefix, copper_textures(stage))
        name = 'copper_cauldron' if stage == 'copper' else prefix
        if stage != 'copper':
            # 其余三档沿用同一几何，只换贴图。
            textures = {key: f'{NAMESPACE}:block/{prefix}_{key}' for key in ('side', 'top', 'bottom', 'inside')}
            textures['particle'] = textures['side']
            write_json(ASSETS / f'models/block/{name}.json',
                       {'parent': f'{NAMESPACE}:block/copper_cauldron', 'textures': textures})
        variants[f'oxidation={oxidation}'] = {'model': f'{NAMESPACE}:block/{name}'}
    write_json(ASSETS / 'blockstates/copper_cauldron.json', {'variants': variants})
    print('巫釜元素', len(witch['elements']), '铜釜元素', len(copper['elements']))


if __name__ == '__main__':
    main()
