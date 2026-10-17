#!/usr/bin/env python3
"""검토한 코드/테스트의 변경을 감지합니다. 소스를 해석하거나 명세를 자동 수정하지 않습니다."""
import hashlib
import json
from pathlib import Path
import sys


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    index = root / 'integration/analyzer/evidence/noteu/chat-rooms/source-index.json'
    manifest = json.loads(index.read_text(encoding='utf-8'))
    changed = []
    for source in manifest['sources']:
        path = (root / source['path']).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            changed.append(source['path'])
        elif hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
            changed.append(source['path'])
    if changed:
        print('근거를 작성한 이후 변경되거나 사라진 출처가 있습니다:')
        for name in changed:
            print(f'- {name}')
        print('해당 구현/테스트와 명세·BDD 설명을 검토한 뒤 source-index.json을 갱신하세요.')
        return 1
    print(f"확인한 출처 {len(manifest['sources'])}개가 현재 파일과 일치합니다.")
    return 0


if __name__ == '__main__':
    sys.exit(main())
