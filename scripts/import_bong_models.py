#!/usr/bin/env python3
"""从 Bong 指定提交导入炼药器具模型，或校验随客户端 JAR 分发的副本。

用法：
  python3 scripts/import_bong_models.py --bong ~/Code/Bong   按 sources.json 记的提交重新导入
  python3 scripts/import_bong_models.py --check              只校验已分发的模型，不需要 Bong 仓

来源只用 sources.json 记的提交（git show 读取），不读 Bong 工作副本，也不改 Bong 仓。
BS3D 只读 Blockbench 4.x；5.0 文件把 groups 里的组属性并回 outliner 后按 4.10 分发，几何与贴图不变。
BS3D 按组 UUID 建骨骼；脚本生成、组没有 UUID 的文件按来源路径和组在 outliner 里的位置补上固定 UUID。
"""

import argparse
import hashlib
import json
import subprocess
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / 'client/src/main/resources/bs3d/meridian_brewing'
SOURCES = MODELS / 'sources.json'
EMBEDDED_PNG = 'data:image/png;base64,'
CONVERSIONS = ('none', 'blockbench-5.0-to-4.10', 'group-uuids')


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def bong_file(bong, commit, path):
    result = subprocess.run(['git', '-C', str(bong), 'show', f'{commit}:{path}'], capture_output=True, check=True)
    return result.stdout


def outliner_4x(nodes, groups):
    # 5.0 的 outliner 只剩 uuid 和子节点，组名、枢轴、旋转和可见性都在 groups 里；4.x 把它们写在节点上。
    converted = []
    for node in nodes:
        if isinstance(node, str):
            converted.append(node)
            continue
        group = {key: value for key, value in groups[node['uuid']].items() if not key.startswith('_')}
        group['isOpen'] = node.get('isOpen', False)
        group['children'] = outliner_4x(node['children'], groups)
        converted.append(group)
    return converted


def add_group_uuids(nodes, seed, position):
    # UUID 由来源和组位置决定，重新导入得到同一文件。
    for index, node in enumerate(nodes):
        if isinstance(node, str):
            continue
        node_position = f'{position}/{index}'
        node.setdefault('uuid', str(uuid.uuid5(uuid.NAMESPACE_URL, seed + node_position)))
        add_group_uuids(node['children'], seed, node_position)


def convert(source, conversion, origin):
    if conversion == 'none':
        return source
    document = json.loads(source)
    if conversion == 'group-uuids':
        add_group_uuids(document['outliner'], origin, '')
    else:
        groups = {group['uuid']: group for group in document.pop('groups')}
        document['outliner'] = outliner_4x(document['outliner'], groups)
        document['meta']['format_version'] = '4.10'
    return json.dumps(document, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def outliner_elements(nodes):
    for node in nodes:
        if isinstance(node, str):
            yield node
        else:
            yield from outliner_elements(node['children'])


def outliner_groups(nodes):
    for node in nodes:
        if not isinstance(node, str):
            yield node
            yield from outliner_groups(node['children'])


def check_document(name, data):
    # 与 BS3D 导入同口径的静态检查：4.x、内嵌贴图、只有逐面 UV 的 cube、每个元素恰好挂在一个层级位置、组有 UUID。
    document = json.loads(data)
    errors = []
    if not document['meta']['format_version'].startswith('4.'):
        errors.append(f'{name}：不是 Blockbench 4.x')
    if not all(texture.get('source', '').startswith(EMBEDDED_PNG) for texture in document['textures']):
        errors.append(f'{name}：贴图没有内嵌')
    for element in document['elements']:
        if element.get('type', 'cube') != 'cube' or element.get('box_uv') or element.get('inflate', 0):
            errors.append(f'{name}：{element["name"]} 不是逐面 UV 的 cube')
    listed = list(outliner_elements(document['outliner']))
    if sorted(listed) != sorted(element['uuid'] for element in document['elements']):
        errors.append(f'{name}：outliner 与元素不一一对应')
    if not all(isinstance(group.get('uuid'), str) for group in outliner_groups(document['outliner'])):
        errors.append(f'{name}：outliner 里有组没有 UUID')
    return errors


def check(sources):
    errors = []
    shipped = {model['file'] for model in sources['models']}
    extra = sorted(path.name for path in MODELS.glob('*.bbmodel') if path.name not in shipped)
    if extra:
        errors.append(f'sources.json 没有登记：{", ".join(extra)}')
    for model in sources['models']:
        path = MODELS / model['file']
        if not path.is_file():
            errors.append(f'{model["file"]}：文件缺失')
            continue
        data = path.read_bytes()
        if sha256(data) != model['shipped_sha256']:
            errors.append(f'{model["file"]}：与导入结果的 SHA-256 不符')
        if model['conversion'] == 'none' and model['shipped_sha256'] != model['sha256']:
            errors.append(f'{model["file"]}：未转换的模型必须与来源逐字节相同')
        if model['conversion'] not in CONVERSIONS:
            errors.append(f'{model["file"]}：未知转换 {model["conversion"]}')
        errors.extend(check_document(model['file'], data))
    return errors


def import_models(sources, bong):
    for model in sources['models']:
        source = bong_file(bong, sources['commit'], model['path'])
        if sha256(source) != model['sha256']:
            raise SystemExit(f'{model["path"]}：提交 {sources["commit"]} 里的文件与登记的 SHA-256 不符')
        origin = f'https://github.com/{sources["repository"]}/blob/{sources["commit"]}/{model["path"]}#outliner'
        shipped = convert(source, model['conversion'], origin)
        (MODELS / model['file']).write_bytes(shipped)
        model['shipped_sha256'] = sha256(shipped)
    SOURCES.write_text(json.dumps(sources, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--bong', type=Path, help='Bong 仓目录')
    mode.add_argument('--check', action='store_true')
    arguments = parser.parse_args()
    sources = json.loads(SOURCES.read_text(encoding='utf-8'))
    if arguments.bong:
        import_models(sources, arguments.bong)
    errors = check(sources)
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        return 1
    print(f'炼药器具模型：{len(sources["models"])} 个与 sources.json 一致')
    return 0


if __name__ == '__main__':
    sys.exit(main())
