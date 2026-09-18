// This file lives at the repository root's release/ directory, shared across every module's
// own release.config.js, like first-release-patch-analyzer.js beside it.
//
// semantic-release reads every commit since a module's last tag, whichever module the commit
// changed, because it knows nothing of the other modules in this repository. So a feat that
// changed only the TranscriberJ made the Publisher's next release a minor one, and put the
// TranscriberJ's commit in the Publisher's changelog. The version and the release notes are
// therefore worked out from the commits that change something inside the module's own
// directory, and only those.
//
// It needs nothing but git, which semantic-release needs anyway, so it can be tested and
// required from anywhere.
const { execFileSync } = require('node:child_process');
const path = require('node:path');

/**
 * The module a semantic-release run is for: the directory it runs in, relative to the
 * repository's root, with forward slashes. Empty at the root itself.
 */
function componentDirectory(cwd) {
    const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd }).toString().trim();
    return path.relative(fs(root), fs(cwd)).split(path.sep).join('/');
}

/** A path as the file system spells it, so a symlinked temporary directory compares equal. */
function fs(p) {
    return require('node:fs').realpathSync(p);
}

/** The files a commit changes, relative to the repository's root. */
function changedFiles(hash, cwd) {
    return execFileSync('git', ['diff-tree', '--no-commit-id', '--name-only', '-r', '--root', hash], { cwd })
        .toString().split('\n').filter(Boolean);
}

/**
 * The commits of a semantic-release context that change something inside the module's own
 * directory. At the repository's root, every commit.
 */
function ownCommits(context) {
    const cwd = context.cwd || process.cwd();
    const directory = componentDirectory(cwd);
    if (!directory) return context.commits;
    const prefix = `${directory}/`;
    return context.commits.filter((commit) => changedFiles(commit.hash, cwd).some((file) => file.startsWith(prefix)));
}

/**
 * The context with only the module's own commits, for a plugin to work out a version or
 * release notes from. The context itself is left as it is.
 */
function forComponent(context) {
    const commits = ownCommits(context);
    if (context.logger && commits !== context.commits) {
        context.logger.log(`${commits.length} of ${context.commits.length} commits change `
            + `${componentDirectory(context.cwd || process.cwd())}/; only those count for its release`);
    }
    return { ...context, commits };
}

module.exports = { componentDirectory, ownCommits, forComponent };
