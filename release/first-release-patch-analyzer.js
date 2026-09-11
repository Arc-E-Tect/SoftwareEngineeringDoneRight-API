// This file lives at the repository root's release/ directory, shared across every plugin
// module's own release.config.js (each requires it via a relative "../release/..." path). A
// plain `require('@semantic-release/commit-analyzer')` would resolve relative to *this file's
// own location* (release/), which has no node_modules of its own - the real dependency is
// installed in the calling module's own node_modules (e.g. doppelganger-api-detector/node_modules),
// since that's where semantic-release's `npm ci` runs. semantic-release itself is always invoked
// with process.cwd() set to that module's directory, so resolving from there - rather than from
// this file's location - is what actually finds it.
const commitAnalyzer = require(require.resolve('@semantic-release/commit-analyzer', { paths: [process.cwd()] }));

// New plugins are seeded with a baseline tag (e.g. doppelganger-api-detector-v0.0.0)
// specifically so semantic-release finds a lastRelease and increments from it instead
// of falling back to its own hardcoded "no prior tag at all -> 1.0.0" behavior. But
// that seed tag is a placeholder, not a real release: once it's present, context.lastRelease
// is truthy, so without this check the real commit-analyzer would run and a feat commit
// would compute a minor bump (0.1.0) instead of the intended first-release 0.0.1. So we
// force 'patch' both when there's no lastRelease at all AND when the only lastRelease is
// the 0.0.0 seed - after the real 0.0.1 release exists, normal analysis takes over again.
const SEED_VERSION = '0.0.0';

module.exports = {
  analyzeCommits: async (pluginConfig, context) => {
    if (!context.lastRelease || !context.lastRelease.version || context.lastRelease.version === SEED_VERSION) {
      return 'patch';
    }
    return commitAnalyzer.analyzeCommits(pluginConfig, context);
  }
};
