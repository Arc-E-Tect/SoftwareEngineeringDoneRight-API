"use strict";

// The environment the Publisher runs git in.
//
// The Publisher finds a repository from a directory: the library's, or the one a command
// names. Git runs hooks -- the pre-push hook `init` writes runs `check` -- with GIT_DIR
// set, and passes it on to every git the hook starts. With GIT_DIR set and no
// GIT_WORK_TREE, git takes its current directory for the top of the working tree, so a
// library below the top of its repository was read from the wrong place, or, with a
// relative GIT_DIR, not found at all (#125).
//
// So git runs without the variables that tell it where the repository is: those
// `git rev-parse --local-env-vars` lists. The rest of the environment -- credentials,
// HOME, PATH -- is passed on as it is.

const LOCAL_ENV_VARS = [
    "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_CONFIG", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT",
    "GIT_OBJECT_DIRECTORY", "GIT_DIR", "GIT_WORK_TREE", "GIT_IMPLICIT_WORK_TREE", "GIT_GRAFT_FILE",
    "GIT_INDEX_FILE", "GIT_NO_REPLACE_OBJECTS", "GIT_REPLACE_REF_BASE", "GIT_PREFIX", "GIT_SHALLOW_FILE",
    "GIT_COMMON_DIR",
];

/** `env` without git's repository-local variables. */
function gitEnv(env = process.env) {
    const clean = { ...env };
    for (const name of LOCAL_ENV_VARS) delete clean[name];
    return clean;
}

module.exports = { gitEnv, LOCAL_ENV_VARS };
