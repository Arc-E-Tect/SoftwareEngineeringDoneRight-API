// This file lives at the repository root's release/ directory, shared across every plugin
// module's own release.config.js (each requires it via a relative "../release/..." path). A
// plain `require('@semantic-release/commit-analyzer')` would resolve relative to *this file's
// own location* (release/), which has no node_modules of its own - the real dependency is
// installed in the calling module's own node_modules (e.g. api-only-subscriber/node_modules),
// since that's where semantic-release's `npm ci` runs. semantic-release itself is always invoked
// with process.cwd() set to that module's directory, so resolving from there - rather than from
// this file's location - is what actually finds it. It is resolved when commits are analysed,
// not when this file is required, so the rules below can be tested from anywhere.
const commitAnalyzer = () => require(require.resolve('@semantic-release/commit-analyzer', { paths: [process.cwd()] }));
// Only the commits that change this module count for its version: see component-commits.js.
const { forComponent } = require('./component-commits');

// New plugins are seeded with a baseline tag (e.g. api-only-subscriber-v0.0.0)
// specifically so semantic-release finds a lastRelease and increments from it instead
// of falling back to its own hardcoded "no prior tag at all -> 1.0.0" behavior. But
// that seed tag is a placeholder, not a real release: once it's present, context.lastRelease
// is truthy, so without this check the real commit-analyzer would run and a feat commit
// would compute a minor bump (0.1.0) instead of the intended first-release 0.0.1. So we
// force 'patch' both when there's no lastRelease at all AND when the only lastRelease is
// the 0.0.0 seed - after the real 0.0.1 release exists, normal analysis takes over again.
const SEED_VERSION = '0.0.0';

// A 0.x version is still in development, and every component's README says so: until
// 1.0.0, a release may break compatibility without a major version bump. semantic-release
// knows nothing of that, and a breaking change would make 0.12.0 into 1.0.0. So before
// 1.0.0 a breaking change releases a minor version; from 1.0.0 on, when compatibility is
// what a version promises, it releases a major one. Reaching 1.0.0 is a decision, never a
// side effect of a commit.
function releaseType(type, lastVersion) {
  return type === 'major' && Number.parseInt(lastVersion, 10) === 0 ? 'minor' : type;
}

module.exports = {
  analyzeCommits: async (pluginConfig, context) => {
    if (!context.lastRelease || !context.lastRelease.version || context.lastRelease.version === SEED_VERSION) {
      return 'patch';
    }
    const type = await commitAnalyzer().analyzeCommits(pluginConfig, forComponent(context));
    return releaseType(type, context.lastRelease.version);
  },
  releaseType
};
