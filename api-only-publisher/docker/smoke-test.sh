#!/bin/sh
# Smoke-tests an api-only-publisher image, exactly as the release workflow does:
#
#   docker/smoke-test.sh <image-reference> [expected-version]
#
# Needs nothing but Docker and a POSIX shell. Every step runs the image with no
# network, a read-only root filesystem with a tmpfs at /tmp, and the invoking
# user's UID and GID, against a temporary directory mounted at /work.
# README.adoc#docker-maintainers describes it.
set -eu

IMAGE=${1:?usage: smoke-test.sh <image-reference> [expected-version]}
EXPECTED_VERSION=${2:-}
USER_SPEC="$(id -u):$(id -g)"
# Any image version carries this pin, and no image ever will: a toolchain pin the
# image cannot run without downloading.
FOREIGN_PIN="@redocly/cli@0.0.1"
TARGETS="example-service second-service"

WORK=$(mktemp -d "${TMPDIR:-/tmp}/api-only-publisher-smoke.XXXXXX")
trap 'rm -rf "$WORK" 2>/dev/null || docker run --rm -v "$WORK:/w" --entrypoint rm "$IMAGE" -rf /w/lib /w/sealed' EXIT

step() { printf '\n== %s\n' "$*"; }
pass() { printf '   ok: %s\n' "$*"; }
fail() { printf '   FAILED: %s\n' "$*" >&2; exit 1; }

# The image, sealed, as the invoking user, on one directory under $WORK.
sealed() {
    dir=$1
    shift
    docker run --rm --network none --read-only --tmpfs /tmp --user "$USER_SPEC" \
        -e SOURCE_DATE_EPOCH=1700000000 -v "$WORK/$dir:/work" "$IMAGE" "$@"
}

label() {
    docker image inspect --format "{{ index .Config.Labels \"$1\" }}" "$IMAGE"
}

mkdir -p "$WORK/lib" "$WORK/sealed" "$WORK/empty"

step "0. The default user is not root"
uid=$(docker run --rm --network none --entrypoint id "$IMAGE" -u)
[ "$uid" != 0 ] || fail "the image runs as root by default"
pass "default UID $uid"

step "1. Version"
version=$(sealed empty --version)
if [ -n "$EXPECTED_VERSION" ]; then
    [ "$version" = "$EXPECTED_VERSION" ] || fail "--version printed '$version', expected '$EXPECTED_VERSION'"
fi
[ "$version" = "$(label org.opencontainers.image.version)" ] ||
    fail "--version printed '$version', the version label says '$(label org.opencontainers.image.version)'"
pass "$version"

step "2. Help"
sealed empty >"$WORK/help.txt" || fail "no arguments did not exit successfully"
grep -q '^Usage:' "$WORK/help.txt" || fail "no arguments did not print the help"
pass "no arguments prints the help and exits 0"

step "3. Toolchain"
for key in redocly asyncapi; do
    labelled=$(label "com.arc-e-tect.api-only-publisher.toolchain.$key")
    kind=$([ "$key" = redocly ] && echo openapi || echo asyncapi)
    pinned=$(sealed empty node -p \
        "require('/usr/local/lib/node_modules/@arc-e-tect/api-only-publisher/src/init-config').TOOLCHAIN_PIN.$kind")
    name=${pinned%@*}
    installed="$name@$(sealed empty node -p \
        "require('/opt/api-only-publisher/toolchain/node_modules/$name/package.json').version")"
    [ "$labelled" = "$pinned" ] && [ "$installed" = "$pinned" ] ||
        fail "$key: label '$labelled', installed '$installed', TOOLCHAIN_PIN '$pinned'"
    pass "$key: $pinned (label, installed and TOOLCHAIN_PIN agree)"
done

step "4. Scaffold two targets, commit, build and lint"
sealed lib init --yes --openapi --asyncapi . >/dev/null
# init scaffolds one target; the second is a copy under another name.
sealed lib sh -c '
    set -e
    cd specs
    cp openapi/bundles/example-service_openapi_structure.yaml openapi/bundles/second-service_openapi_structure.yaml
    cp openapi/bundles/example-service.bundle.properties openapi/bundles/second-service.bundle.properties
    cp asyncapi/bundles/example-service_asyncapi_structure.yaml asyncapi/bundles/second-service_asyncapi_structure.yaml
    cd ..
    cat >>apionly.yaml <<EOF
  second-service:
    openapi:
      bundle: bundles/second-service_openapi_structure.yaml
    asyncapi:
      bundle: bundles/second-service_asyncapi_structure.yaml

channels:
  file:
    directory: build/publish
  maven:
    groupId: com.example.api
    repository: build/maven
  nuget:
    idPrefix: Example
    repository: build/nuget
  npm:
    scope: "@example"
    publish: false
    directory: build/npm
EOF
    printf "build/\ndist/\n" >.gitignore
    git init -q
    git add -A
    git -c user.name=smoke -c user.email=smoke@example.invalid commit -qm scaffold'
sealed lib build -q || fail "build failed"
sealed lib lint -q || fail "lint failed"
pass "build and lint succeed for $TARGETS"

step "5. Package two bundles to every local channel in one run"
sealed lib publish -q --target example-service --target second-service || fail "publish failed"
for target in $TARGETS; do
    # NuGet ids drop the hyphens: Example.ExampleService, lower-cased in the feed.
    lower=$(echo "example.$target" | tr -d '-')
    for file in \
        "build/publish/$target/0.1.0/$target-0.1.0.tgz" \
        "build/publish/$target/0.1.0/manifest.json" \
        "build/maven/com/example/api/$target/0.1.0/$target-0.1.0.tgz" \
        "build/maven/com/example/api/$target/0.1.0/$target-0.1.0.pom" \
        "build/nuget/$lower/0.1.0/$lower.0.1.0.nupkg" \
        "build/npm/example-$target-0.1.0.tgz"; do
        [ -f "$WORK/lib/$file" ] || fail "missing $file"
    done
    pass "$target: archive and manifest.json, Maven layout and POM, .nupkg, npm tarball"
done

step "6. Every file written belongs to the invoking user"
strangers=$(find "$WORK/lib" ! -user "$(id -u)" | head -5)
[ -z "$strangers" ] || fail "not owned by $(id -u): $strangers"
pass "all owned by $USER_SPEC"

step "7. Packaging twice gives byte-identical files"
cp "$WORK/lib/build/packages/packages.sha256" "$WORK/first.sha256"
sealed lib publish -q --target example-service --target second-service || fail "second publish failed"
cmp -s "$WORK/first.sha256" "$WORK/lib/build/packages/packages.sha256" ||
    fail "the second run's files differ: $(diff "$WORK/first.sha256" "$WORK/lib/build/packages/packages.sha256")"
pass "$(wc -l <"$WORK/first.sha256" | tr -d ' ') files, identical hashes"

step "7a. The hand-off lists every file, and verifies"
grep -q '"channel": "nuget"' "$WORK/lib/build/packages/packages.json" || fail "packages.json lists no nuget file"
sealed lib sh -c 'sha256sum -c --quiet build/packages/packages.sha256' || fail "sha256sum -c failed"
pass "sha256sum -c build/packages/packages.sha256 succeeds"

step "8. A toolchain the image does not carry fails at once, without the network"
sealed sealed init --yes --openapi . >/dev/null
sealed sealed sh -c "sed -i 's|redocly: .*|redocly: \"$FOREIGN_PIN\"|' apionly.yaml"
start=$(date +%s)
if sealed sealed sh -c 'timeout 60 api-only-publisher build' >"$WORK/sealed.txt" 2>&1; then
    fail "a build pinning $FOREIGN_PIN succeeded"
fi
elapsed=$(($(date +%s) - start))
[ "$elapsed" -lt 30 ] || fail "took ${elapsed}s to fail"
carried=$(label com.arc-e-tect.api-only-publisher.toolchain.redocly)
for expected in "pins $FOREIGN_PIN" "carries " "$carried" "API_ONLY_PUBLISHER_TOOLCHAIN_DOWNLOAD=allow"; do
    grep -qF "$expected" "$WORK/sealed.txt" || fail "the message does not say '$expected': $(cat "$WORK/sealed.txt")"
done
[ ! -e "$WORK/sealed/build" ] || fail "the build staged files before refusing"
pass "failed in ${elapsed}s, naming the image's toolchain and the opt-in"

step "9. Shell mode, as a CI job calls it"
sealed empty sh -c 'api-only-publisher --help' | grep -q '^Usage:' || fail "sh -c 'api-only-publisher --help' failed"
pass "sh -c 'api-only-publisher --help'"

step "10. An arbitrary UID with no passwd entry builds, on a read-only root"
docker run --rm --network none --read-only --tmpfs /tmp --user 54321:54321 "$IMAGE" sh -c '
    set -e
    mkdir /tmp/lib && cd /tmp/lib
    api-only-publisher init --yes --openapi --asyncapi . >/dev/null
    git init -q && git add -A && git -c user.name=smoke -c user.email=smoke@example.invalid commit -qm scaffold
    api-only-publisher build -q' || fail "build as UID 54321 failed"
pass "UID 54321 builds both kinds"

printf '\nSmoke test passed: %s\n' "$IMAGE"
