"use strict";

// The channel types, in a module of their own so that the configuration can check a
// channel's type without requiring the channels themselves.

const CHANNEL_TYPES = Object.freeze(["file", "maven", "npm", "github-release", "nuget", "gitlab-package"]);

/** The types as a sentence names them: "file, maven, ... or gitlab-package". */
function describeTypes() {
    return `${CHANNEL_TYPES.slice(0, -1).join(", ")} or ${CHANNEL_TYPES[CHANNEL_TYPES.length - 1]}`;
}

module.exports = { CHANNEL_TYPES, describeTypes };
