"use strict";

// The error every distribution channel throws, in a module of its own so that a channel
// implemented outside channels.js can throw it without requiring channels.js back.
class ChannelError extends Error {}

module.exports = { ChannelError };
