"use strict";

// The programmatic surface.
//
// The CLI is the usual way in, but a release job that wants to decide something
// for itself -- which targets changed, what a closure hashes to, which version a
// target is at, whether a version is a pre-release -- should not have to parse
// console output to find out. Everything the CLI does is available here directly.

module.exports = {
    ...require("./config"),
    ...require("./placeholders"),
    ...require("./version"),
    ...require("./version-policy"),
    ...require("./bundle-version"),
    ...require("./closure"),
    ...require("./unreferenced"),
    ...require("./aggregate"),
    ...require("./fragment-paths"),
    ...require("./pipeline"),
    ...require("./pack"),
    ...require("./changed"),
    ...require("./split"),
    ...require("./channels"),
};
