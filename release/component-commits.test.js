// Run with: node --test release/*.test.js
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { componentDirectory, ownCommits, forComponent } = require('./component-commits');

/** A repository with two components, and a commit for each kind of change. */
function repository() {
    const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'component-commits-')));
    const git = (...args) => execFileSync('git', args, { cwd: root }).toString().trim();
    git('init', '-q');
    git('config', 'user.email', 'test@example.com');
    git('config', 'user.name', 'Test');
    git('config', 'commit.gpgsign', 'false');
    const commit = (message, ...files) => {
        for (const file of files) {
            fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
            fs.appendFileSync(path.join(root, file), `${message}\n`);
        }
        git('add', '-A');
        git('commit', '-q', '-m', message);
        return { hash: git('rev-parse', 'HEAD'), message };
    };
    const commits = {
        first: commit('feat: the first commit', 'publisher/a.js'),
        publisher: commit('fix: the publisher', 'publisher/src/b.js'),
        subscriber: commit('feat: the subscriber', 'subscriber/c.java'),
        both: commit('fix: both', 'publisher/d.js', 'subscriber/d.java'),
        outside: commit('ci: the workflows', '.github/workflows/e.yml'),
        lookalike: commit('feat: a directory whose name starts the same', 'publisher-docs/f.md'),
    };
    return { root, commits };
}

test('a component is the directory semantic-release runs in, relative to the repository', () => {
    const { root } = repository();

    assert.equal(componentDirectory(path.join(root, 'publisher')), 'publisher');
    assert.equal(componentDirectory(root), '');
});

test('a component keeps only the commits that change something inside its directory', () => {
    const { root, commits } = repository();
    const all = Object.values(commits);

    const publisher = ownCommits({ cwd: path.join(root, 'publisher'), commits: all });
    const subscriber = ownCommits({ cwd: path.join(root, 'subscriber'), commits: all });

    assert.deepEqual(publisher.map((c) => c.message),
        ['feat: the first commit', 'fix: the publisher', 'fix: both']);
    assert.deepEqual(subscriber.map((c) => c.message), ['feat: the subscriber', 'fix: both']);
});

test('run at the repository root, every commit counts, as before', () => {
    const { root, commits } = repository();
    const all = Object.values(commits);

    assert.equal(ownCommits({ cwd: root, commits: all }).length, all.length);
});

test('the context is copied with the commits filtered, and says what it dropped', () => {
    const { root, commits } = repository();
    const logged = [];
    const context = {
        cwd: path.join(root, 'subscriber'),
        commits: Object.values(commits),
        logger: { log: (...args) => logged.push(args.join(' ')) },
        lastRelease: { version: '1.0.0' },
    };

    const filtered = forComponent(context);

    assert.equal(filtered.commits.length, 2);
    assert.equal(filtered.lastRelease, context.lastRelease);
    assert.equal(context.commits.length, 6, 'the original context is left alone');
    assert.deepEqual(logged, ['2 of 6 commits change subscriber/; only those count for its release']);
});
