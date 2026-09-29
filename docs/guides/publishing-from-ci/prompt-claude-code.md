---
description: Set up a two-step CI pipeline that packages API bundles with the sealed API-Only Publisher image and publishes them with the platform's own tools.
---

# Publish this library's API bundles from CI, with the sealed Publisher image

Help me set up a CI pipeline for this API specification library -- the repository with `apionly.yaml` -- that packages its bundles with the sealed API-Only Publisher Docker image, and publishes the files it produces to our registries in a second job, with the CI platform's own tools and credentials.

When we are done:

- `apionly.yaml` configures the local channel modes the chosen registries need, and nothing else in it has changed;
- the toolchain pins in `apionly.yaml` are the ones the image carries;
- one pipeline file packages the chosen targets in the image, pinned by digest, and hands the files to a publishing job;
- the publishing job verifies the files against the hand-off, publishes exactly the files it lists, and leaves a version that is already published alone when it is identical and fails when it is not;
- no credential appears in any file: only the platform's job token, or a secret I name;
- the pipeline file is validated;
- I have the commands to try the packaging step locally, and a list of what to check after the first pipeline run.

## How to work

- Start in plan mode.
  Investigate first, then present a plan that lists every file you will create or change, and wait for my approval before you edit anything.
- Keep a todo list of the steps below, and update it as you finish each one.
- Ask before you assume.
  The questions in Step 1 are mine to answer; do not pick an answer for me.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Do not commit, push, or change anything outside this repository.
  At the end, tell me what to commit.
- Never ask me for a credential's value, and never write one into a file, a command or your output.
  Tell me which secret to create, with which scope, and I will create it.

## Principles

- **The image packages; the pipeline publishes.**
  The image runs with no network and no credential.
  Publishing happens in a separate job, with the registry's own client.
  Do not configure a channel's remote mode -- `maven` or `nuget` to a URL, `npm` with publishing, `github-release` -- for the image: it is not supported there yet.
- **Exactly the bytes the image produced.**
  The publishing job runs `sha256sum -c` on the hand-off before anything else, and publishes only files the hand-off lists.
  It never repackages, rebuilds or edits a file.
- **Pinned by digest.**
  The image is referenced as `<registry>/<name>@sha256:<digest>`, never by tag alone.
- **Copy the reference pipeline, do not reinvent it.**
  Start from the reference pipeline for the chosen platform, and change only what this library needs: the image reference, the targets, the trigger, and the registries.

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-publisher-version}` | `0.12.0` |

This is the Publisher version this prompt was verified with: the first release with a Docker image. Use it unless I name a newer one; a newer release works the same unless its changelog says otherwise.

## Facts: the API-Only Publisher image {api-only-publisher-version}

- The image is `docker.io/arcetect/api-only-publisher`, the same digest also at `ghcr.io/arc-e-tect/api-only-publisher`, for `linux/amd64` and `linux/arm64`.
  Get a version's digest with `docker buildx imagetools inspect arcetect/api-only-publisher:{api-only-publisher-version} --format '{{ .Manifest.Digest }}'`.
- Its arguments are the Publisher's commands: `docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/work" <image> build`.
  Anything else runs as given, so `sh -c '...'` works, and so does using it as a CI job image.
- It runs as a non-root user; any UID works.
  A GitHub Actions `container:` job needs `options: --user 1001`, the runner's user, to write the workspace.
  A GitLab job needs no `entrypoint` override.
- It downloads nothing.
  `apionly.yaml`'s `toolchain.redocly` and `toolchain.asyncapi` must be the versions its labels `com.arc-e-tect.api-only-publisher.toolchain.redocly` and `.asyncapi` name, or `build` and `lint` fail at once, naming them.
  Read them with `docker image inspect <image> --format '{{ json .Config.Labels }}'`.
- The local channel modes, in `apionly.yaml`'s `channels`:
  - `file: { directory: build/publish }` writes `build/publish/<target>/<version>/<target>-<version>.tgz` and `manifest.json`;
  - `maven: { groupId: <group>, repository: build/maven }` writes the artifact, its `.pom` and `-manifest.json` under `build/maven/<group path>/<target>/<version>/`, with no checksums and no `maven-metadata.xml`;
  - `nuget: { idPrefix: <Prefix>, repository: build/nuget }` writes `build/nuget/<id>/<version>/<id>.<version>.nupkg`, lower-cased, and the id is `<Prefix>.<Target in PascalCase>`;
  - `npm: { scope: "@<scope>", publish: false, directory: build/npm }` writes `build/npm/<scope>-<target>-<version>.tgz`.
- `publish --target <a> --target <b>` packages each target once and writes it to every configured channel.
  It also writes the hand-off: `build/packages/packages.json`, each file with its `path`, `sha256`, `target`, `version` and `channel`, and `build/packages/packages.sha256`, for `sha256sum -c` from the repository root.
- A target's version comes from its version file, `<target>.bundle.properties` beside its bundle root; it holds a release version.
- The reference pipelines, which you copy:
  - GitHub Actions: https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/api-only-publisher/pipelines/github-actions.yml, and its container-job variant, https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/api-only-publisher/pipelines/github-actions-container-job.yml.
    It is exercised end to end.
  - GitLab CI: https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/api-only-publisher/pipelines/gitlab-ci.yml.
    It is **not exercised yet**: validated against GitLab's CI schema only.
- GitHub Packages requires an npm package's scope to be the repository owner's name, lower-cased.

## Step 1: Ask, then investigate

Ask me, and wait for my answers:

1. The CI platform: GitHub Actions or GitLab CI.
2. Where the image comes from: the public image, or our organisation's mirror -- and then its full reference.
3. Which targets to package.
4. Which registries to publish to, for example GitHub Packages npm, Maven or NuGet, a GitHub Release; or GitLab's npm, Maven, NuGet or generic package registry.
5. What triggers a publication: a tag, a merge to `main`, or a manual run.

Then, without editing anything, read and report:

1. `apionly.yaml`: its targets, its `toolchain` pins, its `channels`, and `build.packages` if set.
2. Each chosen target's version file, and whether the target is published (`publish: false` is not).
3. The existing CI files: `.github/workflows/` or `.gitlab-ci.yml`, and what they already do with this library.
4. The image's toolchain labels, and whether they match the pins.
   If they do not, stop and tell me: aligning the pins changes what `build` produces, and that is my decision.

Then present the plan: the channel entries to add, the pipeline file and its jobs, the permissions or secrets the publishing job needs, and anything you found in the existing CI that conflicts.
Wait for my approval.

## Step 2: Configure the local channels

Add to `apionly.yaml`'s `channels` only the local modes the chosen registries need:

- npm: `npm` with `publish: false`, and the scope the registry requires;
- Maven: `maven` to a directory, with the `groupId` I confirm;
- NuGet: `nuget` to a directory, with the `idPrefix` I confirm;
- a GitHub Release or GitLab's generic registry: `file` to a directory.

Change nothing else in `apionly.yaml`.
Run the packaging step locally, as in Step 5, and show me the hand-off it wrote.

## Step 3: Write the pipeline

Copy the reference pipeline for the platform, and change only:

- the image reference, pinned by digest, from Step 1;
- the targets;
- the trigger;
- the publishing steps: keep those for the chosen registries, and remove the others.

Keep the hand-off check first in the publishing job, and keep each registry's "already published" check.
Use only the platform's job token -- `GITHUB_TOKEN` with the permissions the reference sets, or `CI_JOB_TOKEN` -- unless a registry needs another credential; then tell me the secret to create and its exact scope, and reference it by name.

## Step 4: Validate

1. GitHub Actions: run `actionlint` on the workflow, for example `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest`, and show me the output.
2. GitLab CI: validate `.gitlab-ci.yml` with the project's CI Lint (**Build > Pipeline editor** or the `/projects/:id/ci/lint` API) if I give you access, otherwise against GitLab's published CI schema.
   Say plainly that the GitLab reference pipeline this is based on is not exercised yet.

## Step 5: Wrap up

1. Give me the commands to try the packaging step locally, as the pipeline runs it, for example:

   ```console
   docker run --rm --network none --read-only --tmpfs /tmp --user "$(id -u):$(id -g)" -v "$PWD:/work" <image@digest> build
   docker run --rm --network none --read-only --tmpfs /tmp --user "$(id -u):$(id -g)" -v "$PWD:/work" <image@digest> publish --target <a> --target <b>
   sha256sum -c build/packages/packages.sha256
   ```

2. Tell me what to check after the first pipeline run: that the publishing job's hand-off check passed; that each package is in the registry at the target's version; that a consumer resolves it with its own client; and that re-running the publishing job finds every version already published, identical.
3. Tell me which files to commit, and which secrets, if any, to create.
4. Offer to add a section like this to `CLAUDE.md`, and write it only if I agree:

   ```markdown
   ## Publishing
   - CI packages the bundles in the sealed api-only-publisher image, pinned by digest, and publishes the files in a second job with the registry's own tools.
   - apionly.yaml's toolchain pins must match the image's toolchain labels; moving either is a deliberate change.
   - The publishing job publishes only files listed in build/packages/packages.sha256, after sha256sum -c.
   ```
