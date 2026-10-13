"use strict";

// Whether `publish` may contact anything.
//
// A channel's local mode writes files; its remote mode sends them somewhere: a
// repository or feed at a URL, a registry, a release. API_ONLY_PUBLISHER_REMOTE=never
// refuses every remote mode, so the Publisher contacts nothing. The Docker image sets
// it, and is sealed unless a pipeline sets allow; outside the image it is unset, and
// the Publisher behaves as it always has.
//
// It is a promise the Publisher keeps, not an enforcement: whoever sets the variable
// can change it. A guarantee needs the platform to isolate the job's network.

const { ChannelError } = require("./channel-error");

const REMOTE_ENV = "API_ONLY_PUBLISHER_REMOTE";

/** `allow`, the default when unset or empty, or `never`; anything else is refused. */
function remoteMode(env = process.env) {
    const value = env[REMOTE_ENV];
    if (value === undefined || value === "") return "allow";
    if (value !== "allow" && value !== "never") {
        throw new ChannelError(`${REMOTE_ENV} must be allow or never, not ${JSON.stringify(value)}`);
    }
    return value;
}

const URL = /^https?:\/\//;

/** Whether `channel`, with these settings, would contact anything. */
function isRemote(channel, settings = {}) {
    switch (channel) {
        case "maven":
        case "nuget":
            return URL.test(settings.repository || "");
        case "npm":
            return settings.publish !== false;
        case "github-release":
        case "gitlab-package":
            return true;
        default:
            return false;
    }
}

/**
 * Refuses, under `never`, a run that would use any remote channel, naming each of them,
 * before anything is built, packed or sent. Also refuses an invalid value, whatever the
 * channels: a typo in a switch that keeps the Publisher sealed is not ignored.
 */
function refuseRemote(names, configured = {}, env = process.env) {
    if (remoteMode(env) === "allow") return;
    // Judged by type: a channel named partner-a may be a Maven repository at a URL.
    const remote = names.filter((name) => {
        const settings = configured[name] || {};
        return isRemote(settings.type || name, settings);
    });
    if (remote.length === 0) return;
    throw new ChannelError(
        `${REMOTE_ENV}=never refuses the remote channels ${remote.join(", ")}, so nothing was built, packed or sent. ` +
        `Publish with their local modes, or set ${REMOTE_ENV}=allow where this run may contact them.`);
}

module.exports = { REMOTE_ENV, remoteMode, isRemote, refuseRemote };
