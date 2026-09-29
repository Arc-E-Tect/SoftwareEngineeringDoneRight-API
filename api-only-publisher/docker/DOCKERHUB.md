# API-Only Publisher

A sealed, vettable [API-Only Publisher](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/tree/main/api-only-publisher) for CI pipelines.
It builds, lints and packages API description bundles -- OpenAPI and AsyncAPI documents assembled from a library of reusable fragments -- and downloads nothing when it runs.

One image, with one digest, holds one Publisher version *and* the Redocly CLI and AsyncAPI CLI it runs.
Pull it by digest, verify its signature, read its SBOM, scan it, mirror it into your own registry, and your pipeline knows exactly what it runs.

The full documentation is [on GitHub](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/api-only-publisher/README.adoc#docker).
[Publishing from CI](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/guides/publishing-from-ci/overview.adoc) is the guide, with prompts for Claude Code and Codex that set the pipeline up in your own repository.

## Two steps

1. **In the image**, the Publisher packages bundles into files: an archive per target, a Maven layout, `.nupkg` files and npm tarballs. It needs no network and no credential.
2. **In your pipeline**, a following job publishes those files with the registry's own tools and credentials: `npm publish`, `mvn deploy:deploy-file`, `dotnet nuget push`, a release upload.

Every packaging run writes `build/packages/packages.sha256`, listing each file it produced; the publishing job runs `sha256sum -c` on it and publishes exactly those bytes.
Credentials never enter the image.

The image does not publish to remote registries itself: that is not supported yet.
Reference pipelines for [GitHub Actions](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/api-only-publisher/pipelines/github-actions.yml) and [GitLab CI](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/api-only-publisher/pipelines/gitlab-ci.yml) show the second step; the GitLab one is not exercised yet.

## Tags

- `1.4.2`: that version, for good. An exact-version tag never points at another digest once pushed.
- `1.4`, `1`, `latest`: the highest released version.
- `1.5.0-rc.1`: a pre-release gets only its exact tag.

Pin by digest: `arcetect/api-only-publisher@sha256:<digest>`.
A digest names the bytes you vetted, in any registry you mirror them to.

```console
docker buildx imagetools inspect arcetect/api-only-publisher:latest --format '{{ .Manifest.Digest }}'
```

## Use

Run it from your library's root, mounted at `/work`, as yourself:

```console
docker run --rm --network none --user "$(id -u):$(id -g)" -v "$PWD:/work" \
  arcetect/api-only-publisher@sha256:<digest> init --yes --openapi --asyncapi .
docker run --rm --network none --user "$(id -u):$(id -g)" -v "$PWD:/work" \
  arcetect/api-only-publisher@sha256:<digest> build
docker run --rm --network none --user "$(id -u):$(id -g)" -v "$PWD:/work" \
  arcetect/api-only-publisher@sha256:<digest> publish --target orders --target payments
```

It runs as any UID, on a read-only root filesystem with a writable `/tmp` (`--read-only --tmpfs /tmp`), and as a CI job image on GitHub Actions and GitLab CI: its arguments are the Publisher's, and anything else, such as `sh -c '...'`, runs as given.

## Sealed

The image carries its own toolchain, and `apionly.yaml` must pin the versions it carries; its labels name them:

- `com.arc-e-tect.api-only-publisher.toolchain.redocly`
- `com.arc-e-tect.api-only-publisher.toolchain.asyncapi`

A library pinning other versions fails at once, before anything is built, saying which versions the image carries.
`API_ONLY_PUBLISHER_TOOLCHAIN_DOWNLOAD=allow` lets it download them instead, for someone who knowingly accepts that; it is off by default.

Nothing phones home: npm's update notifier, Redocly CLI's telemetry and AsyncAPI CLI's analytics and update checks are switched off in the image's environment.
The smoke test was run on a network whose every packet was captured, and the image sent none.

## Verify

The image is signed with Sigstore cosign, keyless, by the release workflow's GitHub identity:

```console
cosign verify \
  --certificate-identity https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/.github/workflows/api-only-publisher-image.yml@refs/heads/main \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  arcetect/api-only-publisher@sha256:<digest>
```

It carries an SPDX SBOM, listing every Debian and npm package in it, and SLSA provenance naming the repository, the workflow and the commit:

```console
docker buildx imagetools inspect arcetect/api-only-publisher@sha256:<digest> --format '{{ json .SBOM }}'
docker buildx imagetools inspect arcetect/api-only-publisher@sha256:<digest> --format '{{ json .Provenance }}'
```

Each release's GitHub Release has the SBOMs and the Trivy scan reports attached.
The same image, with the same digest, is at `ghcr.io/arc-e-tect/api-only-publisher`.

## Platforms

`linux/amd64` and `linux/arm64`, as one multi-platform image.

## Licence

MIT, as the [API-Only Publisher](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/api-only-publisher/LICENSE) is.
The image also contains Node.js, Debian packages, Redocly CLI and AsyncAPI CLI, each under its own licence; the SBOM lists them.
