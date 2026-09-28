#!/bin/sh
# Packs the working tree into api-only-publisher.tgz, the npm tarball the
# Dockerfile installs, for a local or pull-request build of the image:
#
#   docker/build-context.sh [version]
#   docker build -t api-only-publisher:local .
#
# The release workflow does not use this: it builds from the tarball it published.
set -eu

cd "$(dirname "$0")/.."
version=${1:-0.0.0-local}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The same files npm publishes, with the version stamped as the release stamps it.
cp package.json README.adoc LICENSE "$work/"
cp -R src "$work/src"
(cd "$work" && npm version "$version" --no-git-tag-version --allow-same-version >/dev/null)
sed -i.bak -E "s/^:api-only-publisher-version: .*/:api-only-publisher-version: $version/" "$work/README.adoc"
rm -f "$work/README.adoc.bak"
tarball=$(cd "$work" && npm pack --silent)
mv "$work/$tarball" api-only-publisher.tgz
echo "api-only-publisher.tgz: @arc-e-tect/api-only-publisher@$version"
