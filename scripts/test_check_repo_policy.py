#!/usr/bin/env python3
"""Offline self-tests for check_repo_policy.py using temporary Git repositories."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_repo_policy as policy  # noqa: E402

GOOD = '4gknw59gyj@privaterelay.appleid.com'
# Built from code points so this file contains no Cyrillic literals.
RUSSIAN = ''.join(map(chr, (0x41f, 0x440, 0x438, 0x432, 0x435, 0x442)))


class Repo:
    def __init__(self, directory):
        self.root = Path(directory)
        self.git('init', '-q', '-b', 'main')

    def git(self, *args, email=GOOD, committer=None):
        env = dict(os.environ, GIT_AUTHOR_NAME='A', GIT_AUTHOR_EMAIL=email,
                   GIT_COMMITTER_NAME='C', GIT_COMMITTER_EMAIL=committer or email,
                   GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
        return subprocess.run(['git', *args], cwd=self.root, env=env, check=True,
                              stdout=subprocess.PIPE).stdout.decode().strip()

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, str):
            target.write_text(content, encoding='utf-8')
        else:
            target.write_bytes(content)

    def commit(self, message='c', **kwargs):
        self.git('add', '-A')
        self.git('commit', '-q', '--allow-empty', '-m', message, **kwargs)
        return self.git('rev-parse', 'HEAD')


class FileChecks(unittest.TestCase):
    def errors(self, files):
        with tempfile.TemporaryDirectory() as directory:
            repo = Repo(directory)
            for path, content in files.items():
                repo.write(path, content)
            return policy.check_files(repo.root)

    def test_clean_tree(self):
        self.assertEqual(self.errors({'README.md': 'English only\n'}), [])

    def test_cyrillic_outside_allow_list(self):
        errors = self.errors({'docs/A.md': f'ok\n{RUSSIAN}\n',
                              'app/src/main/res/values/strings.xml': RUSSIAN,
                              'app/src/test/T.kt': RUSSIAN})
        self.assertEqual(errors, [
            'app/src/main/res/values/strings.xml:1: Cyrillic text outside localization resources',
            'app/src/test/T.kt:1: Cyrillic text outside localization resources',
            'docs/A.md:2: Cyrillic text outside localization resources',
        ])

    def test_russian_resources_allowed(self):
        self.assertEqual(self.errors({'app/src/main/res/values-ru/strings.xml': RUSSIAN,
                                      'app/src/main/res/values-ru-rRU/strings.xml': RUSSIAN}), [])
        # Allow-list is exact: other locales or nested lookalikes are not exempt.
        self.assertEqual(len(self.errors({'app/src/main/res/values-rus/s.xml': RUSSIAN,
                                          'lib/app/src/main/res/values-ru/s.xml': RUSSIAN})), 2)

    def test_binary_files_skipped_for_text(self):
        self.assertEqual(self.errors({'a.bin': b'\0' + RUSSIAN.encode()}), [])

    def test_forbidden_paths(self):
        errors = self.errors({'x/Index.HTML': 'a', 'b.htm': 'a', 'c.ZIP': b'PK',
                              'design/notes.txt': 'a', 'docs/design.md': 'fine'})
        self.assertEqual(sorted(e.split(':')[0] for e in errors),
                         ['b.htm', 'c.ZIP', 'design/notes.txt', 'x/Index.HTML'])

    def test_size_limit(self):
        big = b'\0' * (policy.MAX_FILE_BYTES + 1)
        errors = self.errors({'app/libs/bimeron-core.aar': big, 'app/libs/other.aar': big,
                              'exact.bin': b'\0' * policy.MAX_FILE_BYTES})
        self.assertEqual(len(errors), 1)
        self.assertTrue(errors[0].startswith('app/libs/other.aar:'))

    def test_ignored_and_deleted_files_skipped(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Repo(directory)
            repo.write('.gitignore', 'build/\n')
            repo.write('build/out.html', 'a')
            repo.write('gone.zip', b'PK')
            repo.commit()
            (repo.root / 'gone.zip').unlink()
            self.assertEqual(policy.check_files(repo.root), [])


class CommitChecks(unittest.TestCase):
    def test_range_and_emails(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Repo(directory)
            base = repo.commit()
            repo.commit(committer='noreply@github.com')
            count, errors = policy.check_commits([f'{base}..HEAD'], repo.root)
            self.assertEqual((count, errors), (1, []))
            bad = repo.commit(email='someone@example.com')
            repo.commit(committer='Other@Example.com')
            count, errors = policy.check_commits([f'{base}..HEAD'], repo.root)
            self.assertEqual(count, 3)
            self.assertEqual(len(errors), 3)
            self.assertIn(f"{bad}: author email 'someone@example.com' is not allowed", errors)

    def test_push_event_ranges(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Repo(directory)
            first = repo.commit(email='old@example.com')
            second = repo.commit()
            revisions = policy.event_revisions('push', {'before': first, 'after': second},
                                               'refs/heads/main', repo.root)
            self.assertEqual(policy.check_commits(revisions, repo.root), (1, []))
            # Initial push: before is all zeros or missing, so the whole history is checked.
            for event in ({'before': policy.ZERO_SHA, 'after': second}, {'after': second}, {}):
                revisions = policy.event_revisions('push', event, 'refs/heads/main', repo.root)
                count, errors = policy.check_commits(revisions, repo.root)
                self.assertEqual(count, 2)
                self.assertEqual(len(errors), 2)
            # Unknown before (force push): fall back instead of failing open.
            revisions = policy.event_revisions('push', {'before': 'f' * 40, 'after': second},
                                               'refs/heads/main', repo.root)
            self.assertEqual(policy.check_commits(revisions, repo.root)[0], 2)

    def test_new_branch_excludes_other_remote_refs(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Repo(directory)
            main = repo.commit(email='old@example.com')
            repo.git('update-ref', 'refs/remotes/origin/main', main)
            head = repo.commit()
            repo.git('update-ref', 'refs/remotes/origin/feature', head)
            revisions = policy.event_revisions('push', {'before': policy.ZERO_SHA, 'after': head},
                                               'refs/heads/feature', repo.root)
            self.assertEqual(policy.check_commits(revisions, repo.root), (1, []))

    def test_pull_request_range(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Repo(directory)
            base = repo.commit(email='old@example.com')
            head = repo.commit(email='new@example.com')
            event = {'pull_request': {'base': {'sha': base}, 'head': {'sha': head}}}
            revisions = policy.event_revisions('pull_request', event, 'refs/pull/1/merge', repo.root)
            count, errors = policy.check_commits(revisions, repo.root)
            self.assertEqual(count, 1)
            self.assertEqual(len(errors), 2)


class SelfCheck(unittest.TestCase):
    def test_policy_scripts_have_no_cyrillic(self):
        for name in ('check_repo_policy.py', 'test_check_repo_policy.py'):
            text = (Path(__file__).resolve().parent / name).read_text(encoding='utf-8')
            self.assertIsNone(policy.CYRILLIC.search(text), name)


if __name__ == '__main__':
    unittest.main()
