#!/usr/bin/env python3
"""从 Bong 指定提交导入炼药器具模型，或校验随客户端 JAR 分发的副本。

用法：
  python3 scripts/import_bong_models.py --bong ~/Code/Bong   按 sources.json 记的提交重新导入
  python3 scripts/import_bong_models.py --check              只校验已分发的模型，不需要 Bong 仓

来源只用 sources.json 记的提交（git show 读取），不读 Bong 工作副本，也不改 Bong 仓；导入前核对源文件的
Git blob SHA-1 和 SHA-256。
BS3D 按组 UUID 建骨骼；脚本生成、组没有 UUID 的文件按来源路径和组在 outliner 里的位置补上固定 UUID。
收刃药刀由同一源文件生成：补 UUID 后把 sources.json 的 fold 记的组绕给定枢轴转给定角度，立方体不动。
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
CONVERSIONS = ('none', 'group-uuids', 'group-uuids+fold')


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def git(bong, *arguments):
    return subprocess.run(['git', '-C', str(bong), *arguments], capture_output=True, check=True).stdout


def add_group_uuids(nodes, seed, position):
    # UUID 由来源和组位置决定，重新导入得到同一文件。
    for index, node in enumerate(nodes):
        if isinstance(node, str):
            continue
        node_position = f'{position}/{index}'
        node.setdefault('uuid', str(uuid.uuid5(uuid.NAMESPACE_URL, seed + node_position)))
        add_group_uuids(node['children'], seed, node_position)


def fold(document, spec):
    # 收刃：刀背、刀刃两组的枢轴移到转轴上并转过固定角度，BS3D 按组枢轴和旋转画骨骼。
    groups = {group['name']: group for group in outliner_groups(document['outliner'])}
    for name in spec['groups']:
        groups[name]['origin'] = spec['pivot']
        groups[name]['rotation'] = spec['rotation']


def convert(source, model, origin):
    if model['conversion'] == 'none':
        return source
    document = json.loads(source)
    add_group_uuids(document['outliner'], origin, '')
    if model['conversion'] == 'group-uuids+fold':
        fold(document, model['fold'])
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


def check_fold(model, document):
    spec = model.get('fold')
    if (model['conversion'] == 'group-uuids+fold') != (spec is not None):
        return [f'{model["file"]}：fold 只随 group-uuids+fold 一起登记']
    if spec is None:
        return []
    groups = {group['name']: group for group in outliner_groups(document['outliner'])}
    if any(groups.get(name, {}).get('origin') != spec['pivot'] or groups[name].get('rotation') != spec['rotation']
           for name in spec['groups']):
        return [f'{model["file"]}：收刃的组枢轴或旋转与 sources.json 不符']
    return []


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
        errors.extend(check_fold(model, json.loads(data)))
    return errors


def import_models(sources, bong):
    for model in sources['models']:
        revision = f'{sources["commit"]}:{model["path"]}'
        blob = git(bong, 'rev-parse', revision).decode().strip()
        source = git(bong, 'show', revision)
        if blob != model['source_blob_sha1'] or sha256(source) != model['sha256']:
            raise SystemExit(f'{model["path"]}：提交 {sources["commit"]} 里的文件与登记的 blob SHA-1 或 SHA-256 不符')
        origin = f'{sources["repository"]}/blob/{sources["commit"]}/{model["path"]}#outliner'
        shipped = convert(source, model, origin)
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
