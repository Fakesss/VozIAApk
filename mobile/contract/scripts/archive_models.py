#!/usr/bin/env python3
"""Pin sherpa-onnx release-archive models into mobile/contract/models.json.

Reads models.archive.source.json, downloads (or reuses) each archive, records
its SHA-256/size and the size/SHA-256 of every selected member, and replaces
the matching entries in models.json. Hugging Face entries are left untouched.

    python3 mobile/contract/scripts/archive_models.py [--cache DIR]
"""
import argparse
import fnmatch
import hashlib
import json
import pathlib
import shutil
import tarfile
import urllib.request

CONTRACT = pathlib.Path(__file__).resolve().parents[1]
ALLOWED_PREFIX = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/'
SHERPA_COMMIT = json.loads((CONTRACT.parent / 'native' / 'pins.json').read_text())['sherpa-onnx']['source']['commit']


def digest(stream):
    value = hashlib.sha256()
    size = 0
    for block in iter(lambda: stream.read(1 << 20), b''):
        value.update(block)
        size += len(block)
    return value.hexdigest(), size


def fetch(url, cache):
    if not url.startswith(ALLOWED_PREFIX):
        raise ValueError('Archive models must come from sherpa-onnx GitHub releases: ' + url)
    target = cache / url.rsplit('/', 1)[1]
    if not target.exists():
        partial = target.with_suffix(target.suffix + '.partial')
        with urllib.request.urlopen(url, timeout=300) as response, partial.open('wb') as output:
            shutil.copyfileobj(response, output)
        partial.replace(target)
    return target


def terms_fingerprint(terms):
    canonical = json.dumps(terms, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    return hashlib.sha256(canonical.encode('utf-8')).hexdigest()


def entry(spec, cache):
    archive = fetch(spec['archive']['url'], cache)
    with archive.open('rb') as source:
        archive_sha, archive_size = digest(source)
    root = spec['archive']['root'].rstrip('/') + '/'
    members = {}
    with tarfile.open(archive, 'r:bz2') as tar:
        for member in tar:
            if not member.isfile() or not member.name.startswith(root):
                continue
            relative = member.name[len(root):]
            role = None
            for rule in spec['include']:
                if rule.get('path') == relative or ('glob' in rule and fnmatch.fnmatchcase(relative, rule['glob'])):
                    role = rule['role']
                    break
            if role is None:
                continue
            sha, size = digest(tar.extractfile(member))
            members[relative] = {'path': relative, 'size': size, 'sha256': sha, 'role': role}
    missing = [rule['path'] for rule in spec['include'] if 'path' in rule and rule['path'] not in members]
    if missing:
        raise ValueError(spec['id'] + ' archive lacks ' + ', '.join(missing))
    files = sorted(members.values(), key=lambda f: (f['role'] != 'model', f['path']))
    licence = dict(spec['licence'])
    licence['terms_fingerprint'] = terms_fingerprint(licence['terms'])
    result = {key: spec[key] for key in ('id', 'name', 'capability', 'runtime')}
    result.update({
        # Provenance: the sherpa-onnx source release whose runtime reads these archives.
        'repo': 'k2-fsa/sherpa-onnx', 'revision': SHERPA_COMMIT,
        'archive': {'url': spec['archive']['url'], 'sha256': archive_sha, 'size': archive_size, 'root': spec['archive']['root']},
        'files': files,
        'languages': spec['languages'],
        'min_ram_gb': spec['min_ram_gb'],
        'config': spec['config'],
        'licence': licence,
        'total_bytes': sum(f['size'] for f in files),
    })
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=pathlib.Path, default=CONTRACT.parents[1] / 'build' / 'model-archives')
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    source = json.loads((CONTRACT / 'models.archive.source.json').read_text())
    catalogue_path = CONTRACT / 'models.json'
    catalogue = json.loads(catalogue_path.read_text())
    ids = {spec['id'] for spec in source['models']}
    models = [m for m in catalogue['models'] if m['id'] not in ids]
    for spec in source['models']:
        models.append(entry(spec, args.cache))
        print('pinned', spec['id'], models[-1]['archive']['sha256'], len(models[-1]['files']), 'files')
    catalogue['models'] = models
    catalogue_path.write_text(json.dumps(catalogue, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
