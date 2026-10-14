"use strict";

// The errors every distribution channel throws, in a module of its own so that a channel
// implemented outside channels.js can throw them without requiring channels.js back.
//
// A ChannelError is the library's to fix -- a channel's settings in apionly.yaml -- and
// fails the run. Its two subclasses are about the channel, an external party, and never
// fail the run on their own: `publish` reports them and goes on with the other channels.

class ChannelError extends Error {}

/** The channel holds this version with other documents, or with a copy that cannot be compared. */
class ChannelConflict extends ChannelError {}

/** The channel could not be reached, refused the request, or this run has no credential for it. */
class ChannelUnavailable extends ChannelError {}

/**
 * What became of a publication that threw `error`: `conflict` or `unavailable`, or null
 * when the error is the library's own and must fail the run. A request that never got an
 * answer -- fetch's "fetch failed" -- and a registry client that exited with an error,
 * such as npm or gh, are the channel being unavailable.
 */
function outcomeOf(error) {
    if (error instanceof ChannelConflict) return "conflict";
    if (error instanceof ChannelUnavailable) return "unavailable";
    if (error instanceof ChannelError) return null;
    if (error instanceof TypeError && error.message === "fetch failed") return "unavailable";
    if (error && typeof error.status === "number" && "stderr" in error) return "unavailable";
    return null;
}

module.exports = { ChannelError, ChannelConflict, ChannelUnavailable, outcomeOf };
