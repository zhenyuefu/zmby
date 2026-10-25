#!/usr/bin/env python3
"""Export a reviewed source allowlist, without local research or VCS history.

This is a packaging check, not a guarantee that arbitrary new source is secret-free.
Review every new allowlist entry and the resulting manifest before publication.
"""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import sys


ROOT = Path(__file__).resolve().parents[1]
# Files are deliberately explicit: new files require an intentional publishing decision.
FILES = (
    '.gitignore', 'AGENTS.md', 'README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md',
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'mise.toml',
    'gradlew', 'gradlew.bat', 'gradle/libs.versions.toml',
    'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'app/build.gradle.kts', 'app/compose-stability.conf', 'app/proguard-rules.pro',
    'devtools/fake-zidoo-player/build.gradle.kts',
    'tools/mock_emby.py', 'tools/export_public.py',
    'docs/images/mark.svg', 'docs/images/home.png', 'docs/images/browse.png',
    'docs/images/detail.png', 'docs/images/search.png', 'docs/images/settings.png',
    'LICENSES/Apache-2.0.txt', 'LICENSES/LGPL-2.1.txt',
)
TREES = {
    'app/src/main': {'.kt', '.xml', '.txt'},
    'app/src/test': {'.kt'},
    'app/schemas': {'.json'},
    'devtools/fake-zidoo-player/src/main': {'.kt', '.xml'},
}
BINARY_FILES = {'gradle/wrapper/gradle-wrapper.jar'}
# Findings print paths and line numbers only. Never echo a matched credential.
CHECKS = {
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'GitHub token': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b'),
    'cloud access key': re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    'Slack token': re.compile(r'\bxox[baprs]-[A-Za-z0-9-]{20,}\b'),
    # /Users/{uid}/Items is an Emby route, not a macOS home directory.
    'host absolute path': re.compile(r'/(?:Users/(?!u[0-9]+/)[a-z][a-z0-9._-]+/|private/var/folders/|home/[a-z][a-z0-9._-]+/)[^\s`"<>]*'),
    'internal domain': re.compile(r'\b(?:[a-z0-9-]+\.)*(?:temu\.team|kuajing\.team)\b', re.I),
    'embedded URL credential': re.compile(r'https?://[^\s/<>"`]+:[^\s/<>"`]+@'),
}


def selected_files():
    paths = set(FILES)
    for directory, suffixes in TREES.items():
        base = ROOT / directory
        if not base.is_dir() or base.is_symlink():
            raise ValueError(f'missing or symlinked source directory: {directory}')
        for path in base.rglob('*'):
            if path.is_symlink():
                raise ValueError(f'symlink in source tree: {path.relative_to(ROOT)}')
            if path.is_file():
                if path.suffix not in suffixes:
                    raise ValueError(f'unreviewed file type: {path.relative_to(ROOT)}')
                paths.add(path.relative_to(ROOT).as_posix())
    return sorted(paths)


def audit(paths):
    errors = []
    for rel in paths:
        path = ROOT / rel
        if not path.is_file() or any(p.is_symlink() for p in (path, *path.parents)):
            errors.append(f'{rel}: missing file or symlink')
            continue
        # Enforce the same 1 MiB limit used by the local jj snapshot policy.
        if path.stat().st_size > 1024 * 1024:
            errors.append(f'{rel}: over 1 MiB, review separately')
        if rel in BINARY_FILES or path.suffix == '.png':
            continue
        try:
            body = path.read_text(encoding='utf-8')
        except UnicodeError:
            errors.append(f'{rel}: unexpected binary content')
            continue
        for line, text in enumerate(body.splitlines(), 1):
            for label, pattern in CHECKS.items():
                # This exact reserved-domain fixture tests rejecting URL credentials.
                candidate = text.replace('https://alice:secret@example.com', '') if rel in {'app/src/test/java/app/zmby/data/ServerImportTest.kt', 'tools/export_public.py'} else text
                if pattern.search(candidate):
                    errors.append(f'{rel}:{line}: {label}')
    if errors:
        raise ValueError('export refused:\n' + '\n'.join(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='new destination directory; never overwritten')
    args = parser.parse_args()
    output = args.output.expanduser().absolute()
    # An export is never created in one of its own input trees or in VCS metadata.
    resolved = output.resolve()
    protected = ('app', 'devtools', 'docs', 'tools', 'gradle', 'LICENSES', '.git', '.jj', '.agents')
    if resolved == ROOT or ROOT in resolved.parents and resolved.relative_to(ROOT).parts[0] in protected:
        parser.error('choose a destination outside source trees (for example build/public/zmby)')
    if output.exists() or output.is_symlink():
        parser.error('destination already exists; choose a new directory')
    paths = selected_files()
    audit(paths)
    output.mkdir(parents=True, exist_ok=False)
    manifest = []
    for rel in paths:
        source = ROOT / rel
        target = output / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(0o755 if rel == 'gradlew' else 0o644)
        manifest.append(f'{hashlib.sha256(target.read_bytes()).hexdigest()}  {rel}\n')
    (output / 'PUBLIC_MANIFEST.sha256').write_text(''.join(manifest), encoding='utf-8')
    print(f'Exported {len(paths)} files to {output}')
    print('Excluded: local notes/research, VCS history, build outputs, credentials and device backups.')
    print('Pattern scan passed. Manual source, image and dependency review is still required.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
