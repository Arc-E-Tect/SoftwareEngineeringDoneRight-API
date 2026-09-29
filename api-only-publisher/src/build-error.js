"use strict";

// The error a build throws, in a module of its own so that toolchain.js can throw it
// without requiring pipeline.js back.
class BuildError extends Error {}

module.exports = { BuildError };
