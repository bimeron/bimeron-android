#!/usr/bin/env python3
"""Repository policy checks run early in CI.

Checks (see docs/CI.md):

1. Text: no Cyrillic characters in repository text files, except in paths on
   CYRILLIC_ALLOWED (Android Russian localization resources).
2. Files: no *.html, *.htm or *.zip files, nothing under design/, and no file
   larger than MAX_FILE_BYTES except paths on LARGE_FILE_ALLOWED.
3. Commits: every commit in the pushed range or pull request must have an
   author and committer email from ALLOWED_EMAILS.

File checks use `git ls-files --cached --others --exclude-standard`, so tracked
files and new, non-ignored files are both covered; paths missing from the
working tree (deleted but not yet staged) are skipped.

Usage:
  python3 scripts/check_repo_policy.py                # files, plus commits on GitHub Actions
  python3 scripts/check_repo_policy.py --range A..B   # files plus an explicit commit range
  python3 scripts/check_repo_policy.py --no-commits   # file checks only
"""
import argparse
import fnmatch
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

# Cyrillic, Cyrillic Supplement, Extended-A/B/C. Written as escapes so this file
# passes its own check.
CYRILLIC = re.compile(r'[\u0400-\u052f\u1c80-\u1c8f\u2de0-\u2dff\ua640-\ua69f]')

# Keep this list minimal. Only Android Russian localization resources may
# contain Cyrillic; tests should reference those resources instead of literals.
CYRILLIC_ALLOWED = (
    'app/src/main/res/values-ru/*',
    'app/src/main/res/values-ru-*/*',
)

FORBIDDEN = ('*.html', '*.htm', '*.zip', 'design/*')

MAX_FILE_BYTES = 1024 * 1024
LARGE_FILE_ALLOWED = ('app/libs/bimeron-core.aar',)

ALLOWED_EMAILS = frozenset({
    '4gknw59gyj@privaterelay.appleid.com',
    'noreply@github.com',
})

ZERO_SHA = '0' * 40


def git(*args, cwd=ROOT):
    return subprocess.run(['git', *args], cwd=cwd, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def matches(path, patterns):
    # fnmatch's '*' also matches '/', so 'design/*' covers the whole tree.
    lowered = path.lower()
    return any(fnmatch.fnmatchcase(lowered, pattern.lower()) for pattern in patterns)


def list_files(root=ROOT):
    output = git('ls-files', '-z', '--cached', '--others', '--exclude-standard', cwd=root)
    paths = sorted({p for p in output.decode('utf-8', 'surrogateescape').split('\0') if p})
    return [p for p in paths if (root / p).is_file()]


def read_text(path):
    data = path.read_bytes()
    if b'\0' in data[:8192]:
        return None
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError:
        return None


def check_files(root=ROOT):
    errors = []
    for relative in list_files(root):
        path = root / relative
        if matches(relative, FORBIDDEN):
            errors.append(f'{relative}: forbidden file type or location')
        size = path.stat().st_size
        if size > MAX_FILE_BYTES and relative not in LARGE_FILE_ALLOWED:
            errors.append(f'{relative}: {size} bytes exceeds {MAX_FILE_BYTES}')
        if matches(relative, CYRILLIC_ALLOWED):
            continue
        text = read_text(path)
        if text is None:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            if CYRILLIC.search(line):
                errors.append(f'{relative}:{number}: Cyrillic text outside localization resources')
    return errors


def is_commit(sha, root):
    if not sha or sha == ZERO_SHA:
        return False
    result = subprocess.run(['git', 'cat-file', '-e', f'{sha}^{{commit}}'], cwd=root,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return result.returncode == 0


def new_ref_exclusions(ref, root):
    """Commits on other remote refs, which a newly created ref does not introduce."""
    own = set()
    if ref.startswith('refs/heads/'):
        own.add('refs/remotes/origin/' + ref[len('refs/heads/'):])
    own.add(ref)
    lines = git('for-each-ref', '--format=%(objectname) %(refname)',
                'refs/remotes', 'refs/tags', cwd=root).decode().splitlines()
    return sorted({sha for sha, name in (line.split(' ', 1) for line in lines)
                   if name not in own and not name.endswith('/HEAD')})


def event_revisions(event_name, event, ref, root=ROOT):
    """rev-list arguments for the commits introduced by a GitHub event."""
    if event_name in ('pull_request', 'pull_request_target'):
        pull = event['pull_request']
        return [pull['head']['sha'], '--not', pull['base']['sha']]
    after = event.get('after') or 'HEAD'
    if after == ZERO_SHA:
        return []  # ref deletion: no new commits
    before = event.get('before')
    if is_commit(before, root):
        return [after, '--not', before]
    # Initial push, new branch/tag, or a force push whose old head is unavailable:
    # check everything not already on another ref (the whole history if none).
    exclusions = new_ref_exclusions(ref, root)
    return [after, '--not', *exclusions] if exclusions else [after]


def check_commits(revisions, root=ROOT):
    if not revisions:
        return 0, []
    output = git('log', '--format=%H%x00%ae%x00%ce', *revisions, '--',
                 cwd=root).decode('utf-8', 'replace')
    errors = []
    count = 0
    for line in output.splitlines():
        if not line:
            continue
        sha, author, committer = line.split('\0')
        count += 1
        for role, email in (('author', author), ('committer', committer)):
            if email.strip().lower() not in ALLOWED_EMAILS:
                errors.append(f'{sha}: {role} email {email!r} is not allowed')
    return count, errors


def github_revisions(root=ROOT):
    path = os.environ.get('GITHUB_EVENT_PATH')
    if not path:
        return None
    if git('rev-parse', '--is-shallow-repository', cwd=root).strip() == b'true':
        raise SystemExit('Commit policy needs full history: use actions/checkout with fetch-depth: 0')
    event = json.loads(Path(path).read_text())
    return event_revisions(os.environ.get('GITHUB_EVENT_NAME', ''), event,
                           os.environ.get('GITHUB_REF', ''), root)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--range', help='explicit commit range, e.g. origin/main..HEAD')
    parser.add_argument('--no-commits', action='store_true', help='skip the commit email check')
    args = parser.parse_args(argv)

    errors = check_files()
    if not args.no_commits:
        revisions = [args.range] if args.range else github_revisions()
        if revisions is None:
            print('Commit check skipped: no --range and no GitHub event')
        else:
            count, commit_errors = check_commits(revisions)
            print(f'Checked {count} commit(s): {" ".join(revisions)}')
            errors += commit_errors
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        print(f'Repository policy: {len(errors)} violation(s)', file=sys.stderr)
        return 1
    print('Repository policy OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
