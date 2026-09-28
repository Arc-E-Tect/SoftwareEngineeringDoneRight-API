#!/bin/sh
# The image's entrypoint, in the manner of the official images: the Publisher's
# own commands and flags run the Publisher, and anything else runs as given.
#
#   docker run <image> build --target a      -> api-only-publisher build --target a
#   docker run <image> --version             -> api-only-publisher --version
#   docker run <image>                       -> api-only-publisher (its help)
#   docker run <image> sh -c '...'           -> sh -c '...'
#
# The last form is what a GitLab runner does with a job image: it passes a shell
# as the command, so a job needs no `entrypoint: [""]` override. GitHub Actions
# replaces the entrypoint of a `container:` job altogether, so it never runs.
set -e

case "${1-}" in
    "" | -* | init | config | targets | build | lint | closure | changed | pack | publish | split)
        exec api-only-publisher "$@"
        ;;
esac
exec "$@"
