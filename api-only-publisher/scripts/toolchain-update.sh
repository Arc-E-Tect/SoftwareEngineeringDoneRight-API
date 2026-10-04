#!/bin/sh
# Moves the Publisher's own toolchain pins, from any directory and without npm's "--":
#
#   api-only-publisher/scripts/toolchain-update.sh --redocly 2.57.0
#   api-only-publisher/scripts/toolchain-update.sh --audit-fix --no-push
#   api-only-publisher/scripts/toolchain-update.sh --help
#
# It runs scripts/toolchain-update.js, which does the work and explains every option
# with --help. README.adoc#toolchain-update describes it.
set -eu

command -v node >/dev/null 2>&1 || { echo "toolchain-update: node is not on the PATH" >&2; exit 1; }

cd "$(dirname "$0")/.."
exec node scripts/toolchain-update.js "$@"
