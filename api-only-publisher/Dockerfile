# syntax=docker/dockerfile:1
#
# The API-Only Publisher as a sealed image: one Publisher version and the toolchain
# it runs, downloading nothing when it runs. README.adoc#docker describes it.
#
# The build context is this directory, and .dockerignore lets in only what is
# copied below. api-only-publisher.tgz is the npm package itself: the release
# workflow fetches the published tarball from the registry, and
# docker/build-context.sh packs the working tree for a local or pull-request build.

# node:24-trixie-slim
ARG BASE=node@sha256:8ec5d7557396cfe32d21c3f9c13072355ceab22b584578ca4bb28af31120cffe

FROM ${BASE} AS toolchain

# The toolchain from its lockfile, so every transitive dependency is pinned, and
# the SBOM lists what is actually installed. No install scripts run: nothing in the
# toolchain needs one to bundle or validate, and one that downloads would break
# the seal before the image even exists.
WORKDIR /opt/api-only-publisher/toolchain
COPY docker/toolchain/package.json docker/toolchain/package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-fund --no-audit --cache /tmp/npm-cache \
    && rm -rf /tmp/npm-cache \
    # AsyncAPI CLI creates this directory on every run, beside its own code, and
    # never writes to it; created now, it needs no writable root filesystem.
    && mkdir node_modules/@asyncapi/cli/lib/utils/logs

FROM ${BASE}

ARG VERSION=0.0.0-local
ARG REVISION=unknown
ARG CREATED=1970-01-01T00:00:00Z

# git for the commit time and ref the Publisher reads, ca-certificates for any TLS
# a channel's opt-in remote mode would need. tar and npm come with the base.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    # A mounted repository belongs to whoever runs the container, which is rarely
    # the user git runs as; without this, git refuses it as "dubious ownership".
    && git config --system --add safe.directory '*'

COPY --from=toolchain /opt/api-only-publisher/toolchain /opt/api-only-publisher/toolchain
COPY docker/asyncapi-analytics.json /opt/api-only-publisher/asyncapi-analytics.json
COPY docker/asyncapi-config/production.json /opt/api-only-publisher/asyncapi-config/production.json
COPY --chmod=0755 docker/entrypoint.sh /usr/local/bin/docker-entrypoint.sh

# Bind-mounted rather than copied, so the tarball is in no layer.
RUN --mount=type=bind,source=api-only-publisher.tgz,target=/tmp/api-only-publisher.tgz \
    npm install --global --omit=dev --no-fund --no-audit --cache /tmp/npm-cache /tmp/api-only-publisher.tgz \
    && rm -rf /tmp/npm-cache \
    && useradd --uid 10001 --gid 0 --no-create-home --home-dir /tmp --shell /usr/sbin/nologin publisher \
    && mkdir -p /work \
    && chown 10001:0 /work \
    && chmod 0775 /work

ENV API_ONLY_PUBLISHER_TOOLCHAIN_DIR=/opt/api-only-publisher/toolchain \
    API_ONLY_PUBLISHER_TOOLCHAIN_DOWNLOAD=never \
    # Any UID, with or without a passwd entry, and a read-only root filesystem:
    # everything that writes to HOME writes to /tmp, which is writable or a tmpfs.
    HOME=/tmp \
    # AsyncAPI CLI's framework asks the passwd database for the user's shell when
    # SHELL is unset, and an arbitrary UID has no entry there.
    SHELL=/bin/sh \
    # Nothing contacts the internet on its own.
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    NPM_CONFIG_FUND=false \
    NPM_CONFIG_AUDIT=false \
    REDOCLY_TELEMETRY=off \
    REDOCLY_SUPPRESS_UPDATE_NOTICE=true \
    ASYNCAPI_METRICS_CONFIG_PATH=/opt/api-only-publisher/asyncapi-analytics.json \
    ASYNCAPI_SKIP_NEW_VERSION_CHECK=true \
    # AsyncAPI CLI reads its settings with node-config, from ./config by default:
    # the mounted library's own config/ directory, if it has one. This is its own.
    NODE_CONFIG_DIR=/opt/api-only-publisher/asyncapi-config

LABEL org.opencontainers.image.title="API-Only Publisher" \
      org.opencontainers.image.description="A sealed, vettable API-Only Publisher for CI pipelines: builds, lints and packages API description bundles, downloading nothing at run time." \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="Arc-E-Tect" \
      org.opencontainers.image.source="https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API" \
      org.opencontainers.image.url="https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/tree/main/api-only-publisher" \
      org.opencontainers.image.documentation="https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/api-only-publisher/README.adoc#docker" \
      com.arc-e-tect.api-only-publisher.toolchain.redocly="@redocly/cli@2.57.0" \
      com.arc-e-tect.api-only-publisher.toolchain.asyncapi="@asyncapi/cli@6.2.0"

USER 10001:0
WORKDIR /work
ENTRYPOINT ["docker-entrypoint.sh"]
