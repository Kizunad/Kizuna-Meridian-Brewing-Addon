"""晒药匾的方块模型和 16 像素贴图。

矮木框敞口浅底：四角短足把匾底架离地面，底下两根横撑，竹编匾底四周围一圈木框，前后左右对称，没有朝向。
贴图按固定种子程序生成：木框用原版橡木木板的色板画顺纹木条，匾底用原版白桦木板的色板画压一挑一的竹篾编纹。
输出原版方块模型 JSON（parent minecraft:block/block）和不带朝向的方块状态。

用法：python3 scripts/draw_herb_crate.py（覆盖写入 fabric 资源；生成结果随仓提交）
"""

import random

from PIL import Image

from draw_cauldrons import ASSETS, NAMESPACE, SIZE, hex_colour, jitter, write_json

# ---------------------------------------------------------------- 几何（单位：方块内像素）

FRAME_LOW, FRAME_HIGH = 1, 15
LEG_TOP = 2
RIM_TOP = 5
MAT_TOP = 3
LEG_WIDTH = 2
BATTENS = ((5, 6), (10, 11))
BATTEN_LOW = 1

# 横向木条的纹理沿 x 走；沿 z 走的木条把同一段纹理转四分之一圈贴上去，木纹顺着木条。
ALONG_X, ALONG_Z = 'x', 'z'


def face(texture, uv, rotation=0, cullface=None):
    entry = {'texture': texture, 'uv': uv}
    if rotation:
        entry['rotation'] = rotation
    if cullface:
        entry['cullface'] = cullface
    return entry


def bar(start, end, along, faces):
    # 木条：给出要露出来的面，UV 按元素坐标取，沿 z 的木条顶底面转 90°。
    (x0, y0, z0), (x1, y1, z1) = start, end
    uvs = {
        'north': [SIZE - x1, SIZE - y1, SIZE - x0, SIZE - y0],
        'south': [x0, SIZE - y1, x1, SIZE - y0],
        'west': [z0, SIZE - y1, z1, SIZE - y0],
        'east': [SIZE - z1, SIZE - y1, SIZE - z0, SIZE - y0],
    }
    if along == ALONG_X:
        uvs['up'] = [x0, z0, x1, z1]
        uvs['down'] = [x0, SIZE - z1, x1, SIZE - z0]
        turns = {}
    else:
        # 竖着的一条 UV 换成横着的一条，再转 90° 贴回去。
        uvs['up'] = [z0, x0, z1, x1]
        uvs['down'] = [z0, SIZE - x1, z1, SIZE - x0]
        turns = {'up': 90, 'down': 90}
    result = {}
    for name in faces:
        cull = 'down' if name == 'down' and y0 == 0 else None
        result[name] = face('#frame', uvs[name], turns.get(name, 0), cull)
    return {'from': list(start), 'to': list(end), 'faces': result}


def elements():
    sides = ('north', 'south', 'west', 'east')
    low, high = FRAME_LOW, FRAME_HIGH
    inner_low, inner_high = low + 1, high - 1
    parts = []
    for x in (low, high - LEG_WIDTH):
        for z in (low, high - LEG_WIDTH):
            # 四角短足；顶面被木框和匾底盖住。
            parts.append(bar((x, 0, z), (x + LEG_WIDTH, LEG_TOP, z + LEG_WIDTH), ALONG_X, sides + ('down',)))
    for z0, z1 in BATTENS:
        # 匾底下的两根横撑，两端顶在木框下沿之内。
        parts.append(bar((inner_low, BATTEN_LOW, z0), (inner_high, LEG_TOP, z1), ALONG_X, sides + ('down',)))
    # 前后两条木框通长，左右两条夹在中间。
    parts.append(bar((low, LEG_TOP, low), (high, RIM_TOP, low + 1), ALONG_X, sides + ('up', 'down')))
    parts.append(bar((low, LEG_TOP, high - 1), (high, RIM_TOP, high), ALONG_X, sides + ('up', 'down')))
    parts.append(bar((low, LEG_TOP, inner_low), (low + 1, RIM_TOP, inner_high), ALONG_Z, ('west', 'east', 'up', 'down')))
    parts.append(bar((high - 1, LEG_TOP, inner_low), (high, RIM_TOP, inner_high), ALONG_Z, ('west', 'east', 'up', 'down')))
    # 竹编匾底：四边被木框夹住，只露顶面和底面。
    parts.append({
        'from': [inner_low, LEG_TOP, inner_low], 'to': [inner_high, MAT_TOP, inner_high],
        'faces': {
            'up': face('#mat', [inner_low, inner_low, inner_high, inner_high]),
            'down': face('#mat', [inner_low, SIZE - inner_high, inner_high, SIZE - inner_low]),
        },
    })
    return parts


def model():
    textures = {name: f'{NAMESPACE}:block/herb_crate_{name}' for name in ('frame', 'mat')}
    textures['particle'] = textures['frame']
    return {
        'parent': 'minecraft:block/block',
        'ambientocclusion': False,
        'textures': textures,
        'elements': elements(),
    }


# ---------------------------------------------------------------- 贴图

# 原版橡木木板（oak_planks）的色板；白桦木板（birch_planks）的色板往暗处补两档画篾缝。均由深到浅。
OAK = [hex_colour(c) for c in ('67502c', '7e6237', '967441', '9f844d', 'af8f55', 'b8945f', 'c29d62')]
BIRCH = [hex_colour(c) for c in ('6f6043', '8a7a52', '9e8b61', 'ae9f76', 'b8a875', 'c8b77a', 'd7c185')]
# 木条每行的基准色阶：上沿受光最亮，下沿最暗，中间两道深色木纹。
FRAME_ROWS = (5, 4, 4, 2, 4, 3, 4, 5, 4, 3, 2, 4, 4, 3, 4, 1)
GRAIN_RUNS = 9
WEAVE = 4


def frame_texture():
    rng = random.Random(1110)
    levels = [[jitter(FRAME_ROWS[row], rng, high=len(OAK) - 1) for _ in range(SIZE)] for row in range(SIZE)]
    for _ in range(GRAIN_RUNS):
        # 顺纹的深色短线：横向两到五格，随机落在某一行。
        row, start, length = rng.randrange(SIZE), rng.randrange(SIZE), rng.randint(2, 5)
        for x in range(start, min(SIZE, start + length)):
            levels[row][x] = max(0, levels[row][x] - 2)
    return paint(levels, OAK)


def mat_texture():
    # 压一挑一：4×4 一格，横篾格与竖篾格相间；每根篾两像素宽，受光的一侧亮，钻到邻格下面的一端压暗。
    rng = random.Random(1111)
    levels = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            lx, ly = x % WEAVE, y % WEAVE
            horizontal = (x // WEAVE + y // WEAVE) % 2 == 0
            across, along = (ly, lx) if horizontal else (lx, ly)
            level = 5 if across % 2 == 0 else 4
            if along == WEAVE - 1:
                level = 2
            row.append(jitter(level, rng, low=1, high=len(BIRCH) - 1))
        levels.append(row)
    return paint(levels, BIRCH)


def paint(levels, ramp):
    image = Image.new('RGBA', (SIZE, SIZE))
    for y, row in enumerate(levels):
        for x, level in enumerate(row):
            image.putpixel((x, y), ramp[level])
    return image


# ---------------------------------------------------------------- 输出

def main():
    write_json(ASSETS / 'models/block/herb_crate.json', model())
    write_json(ASSETS / 'blockstates/herb_crate.json', {'variants': {'': {'model': f'{NAMESPACE}:block/herb_crate'}}})
    for name, image in (('frame', frame_texture()), ('mat', mat_texture())):
        image.save(ASSETS / f'textures/block/herb_crate_{name}.png')
    print('晒药匾元素', len(model()['elements']))


if __name__ == '__main__':
    main()
