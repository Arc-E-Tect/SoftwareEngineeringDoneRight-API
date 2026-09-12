/**
 * @type {import('semantic-release').GlobalConfig}
 */

module.exports = {
    branches: ["main"],
    // Scoped to this plugin's own tag namespace so its version history is independent of the
    // other plugins in this monorepo (which share the unscoped "v${version}" tag format).
    // An "api-only-publisher-v0.0.0" tag is seeded as the baseline, and the shared
    // release/first-release-patch-analyzer.js below forces the release type to "patch" whenever
    // no prior release exists for this tag namespace, so the first real release computes as
    // exactly 0.0.1 regardless of the triggering commit's type - Shadow's history shows a seeded
    // baseline alone is not enough: a feat-typed first commit still computes 0.1.0 without this.
    tagFormat: "api-only-publisher-v${version}",
    plugins: [
        ["../release/first-release-patch-analyzer", {
            preset: 'angular',
            releaseRules: [
                { type: 'feat', release: 'minor' },
                { type: 'fix', release: 'patch' },
                // Documentation is part of the product - inaccurate or unclear docs are a bug, not
                // mere housekeeping, so a docs commit releases exactly like a fix.
                { type: 'docs', release: 'patch' },
                { type: 'refactor', release: 'patch' },
                { type: 'improvement', release: 'minor' },
                { type: 'scenario', release: 'patch' },
                { type: 'maintenance', release: 'patch' },
                { breaking: true, release: 'major' }
            ],
            parserOpts: {
                noteKeywords: ['BREAKING CHANGE', 'BREAKING CHANGES', 'BREAKING'],
                breakingHeaderPattern: /^(\w*)(?:\((.*)\))?!: (.*)$/
            }
        }],
        ["@semantic-release/npm", {
            // semantic-release versions, tags and writes the changelog; it does not
            // publish. The publish happens in its own workflow job afterwards, for
            // two reasons.
            //
            // Trusted publishing exchanges a short-lived OIDC credential whose claim
            // names the workflow that requested it. Publishing from inside the shared
            // semantic-version-apply.yml would mean registering *that* workflow as the
            // trusted publisher -- and it is shared, so every project using it could
            // then publish this package.
            //
            // And verifyConditions demands an npm token whenever npmPublish is not
            // false, which made computing a version require publish rights it has no
            // use for.
            "npmPublish": false,
        }],
        ["@semantic-release/release-notes-generator", {
            preset: 'angular',
            parserOpts: {
                noteKeywords: ['BREAKING CHANGE', 'BREAKING CHANGES', 'BREAKING'],
                breakingHeaderPattern: /^(\w*)(?:\((.*)\))?!: (.*)$/
            },
            writerOpts: {
                commitsSort: ['subject', 'scope'],
                transform: (commit, context) => {
                    const typeMapping = {
                        'feat': '✨ New and updated features',
                        'fix': '🐛 Bug Fixes',
                        'refactor': '♻️ Refactorings',
                        'improvement': '⚡ Improvements',
                        'scenario': '📋 Scenarios',
                        'maintenance': '🔧 Misc',
                        'docs': '📝 Documentation',
                        'style': '💄 Styling',
                        'perf': '⚡ Performance',
                        'test': '✅ Tests',
                        'build': '🔨 Build System',
                        'ci': '👷 CI/CD',
                        'chore': '🔧 Misc',
                        'revert': '⏪ Reverts'
                    };

                    // Exclude release commits from CHANGELOG
                    if (commit.scope === 'release' ||
                        (commit.subject && commit.subject.match(/^release \d+\.\d+\.\d+/))) {
                        return null;
                    }

                    if (!commit.type) {
                        return null;
                    }

                    const newCommit = { ...commit };

                    if (typeMapping[newCommit.type]) {
                        newCommit.type = typeMapping[newCommit.type];
                    }

                    if (newCommit.scope === '*') {
                        newCommit.scope = '';
                    }

                    if (typeof newCommit.hash === 'string') {
                        newCommit.shortHash = newCommit.hash.substring(0, 7);
                    }

                    if (typeof newCommit.subject === 'string') {
                        let url = context.repository
                            ? `${context.host}/${context.owner}/${context.repository}`
                            : context.repoUrl;
                        if (url) {
                            url = `${url}/issues/`;
                            newCommit.subject = newCommit.subject.replace(/#([0-9]+)/g, (_, issue) => {
                                return `[#${issue}](${url}${issue})`;
                            });
                        }
                        if (context.host) {
                            newCommit.subject = newCommit.subject.replace(
                                /\B@([a-z0-9](?:-?[a-z0-9/]){0,38})/g,
                                (_, username) => {
                                    if (username.includes('/')) {
                                        return `@${username}`;
                                    }
                                    return `[@${username}](${context.host}/${username})`;
                                }
                            );
                        }
                    }

                    return newCommit;
                }
            },
            presetConfig: {
                types: [
                    { type: 'feat', section: '✨ New and updated features', hidden: false },
                    { type: 'fix', section: '🐛 Bug Fixes', hidden: false },
                    { type: 'refactor', section: '♻️ Refactorings', hidden: false },
                    { type: 'improvement', section: '⚡ Improvements', hidden: false },
                    { type: 'scenario', section: '📋 Scenarios', hidden: false },
                    { type: 'maintenance', section: '🔧 Misc', hidden: false },
                    { type: 'docs', section: '📝 Documentation', hidden: false },
                    { type: 'style', section: '💄 Styling', hidden: false },
                    { type: 'perf', section: '⚡ Performance', hidden: false },
                    { type: 'test', section: '✅ Tests', hidden: false },
                    { type: 'build', section: '🔨 Build System', hidden: true },
                    { type: 'ci', section: '👷 CI/CD', hidden: true },
                    { type: 'chore', section: '🔧 Misc', hidden: true },
                    { type: 'revert', section: '⏪ Reverts', hidden: false }
                ]
            }
        }],
        ["@semantic-release/changelog", {
            "changelogFile": "CHANGELOG.md",
            preset: 'angular',
            presetConfig: {
                types: [
                    { type: 'feat', section: '✨ Features', hidden: false },
                    { type: 'fix', section: '🐛 Bug Fixes', hidden: false },
                    { type: 'refactor', section: '♻️  Refactoring', hidden: false },
                    { type: 'improvement', section: '⬆️  Improvements', hidden: false },
                    { type: 'scenario', section: '📋 Scenarios', hidden: false },
                    { type: 'maintenance', section: '🔧 Misc', hidden: false },
                    { type: 'docs', section: '📝 Documentation', hidden: false },
                    { type: 'style', section: '💄 Styling', hidden: false },
                    { type: 'perf', section: '⚡ Performance', hidden: false },
                    { type: 'test', section: '✅ Tests', hidden: false },
                    { type: 'build', section: '🔨 Build System', hidden: true },
                    { type: 'ci', section: '👷 CI/CD', hidden: true },
                    { type: 'chore', section: '🔧 Misc', hidden: true },
                    { type: 'revert', section: '⏪ Reverts', hidden: false }
                ]
            }
        }],
        ["@semantic-release/git", {
            "assets": ["!**/node_modules/**", "!**/build/**", "!**/bin/**", "./CHANGELOG.md"],
            "message": "chore(release): release <%= nextRelease.version %> - <%= new Date().toLocaleDateString('en-US', {year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: 'numeric' }) %> [skip ci]\n\n<%= nextRelease.notes %>"
        }],
        "@semantic-release/github",
    ],
};
