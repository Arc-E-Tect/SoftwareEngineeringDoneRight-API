// The release notes of a module, from the commits that change it: see component-commits.js.
// Each module's release.config.js names this file where it named
// @semantic-release/release-notes-generator, with the same options; this hands those options,
// and a context holding only the module's own commits, to the real generator.
//
// Resolved from process.cwd() rather than from this file, for the reason
// first-release-patch-analyzer.js gives: the dependency is installed in the module's own
// node_modules, not beside this file.
const releaseNotesGenerator = require(require.resolve('@semantic-release/release-notes-generator', { paths: [process.cwd()] }));
const { forComponent } = require('./component-commits');

module.exports = {
  generateNotes: async (pluginConfig, context) => releaseNotesGenerator.generateNotes(pluginConfig, forComponent(context))
};
