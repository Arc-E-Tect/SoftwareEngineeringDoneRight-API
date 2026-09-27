---
description: Move a build's API-Only TranscriberJ emitters from emitterOptions and unnamed emitters to emitter('<id>') blocks, ready for 1.0.0.
---

# Migrate the API-Only TranscriberJ to per-emitter configuration (Codex)

Help me migrate this repository's `apiOnlyTranscriberJ` configuration from the settings 1.0.0 removes to per-emitter configuration.

## How to work

- Investigate first, then present a plan listing every file you will change, and wait for my approval before editing.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Do not commit, push, or change anything outside this repository.
- Do not invent DSL properties, tasks or options. Everything that exists is listed under the facts below. If you need something that is not listed, stop and ask me.
- Change nothing but what the migration needs: do not restrict an emitter's source sets, turn on `schemaClasses = 'shared'` or add emitters unless I ask.

## Facts

- The TranscriberJ is 0.11.0 or newer. Each subscription configures an emitter with `emitter('<id>') { sourceSets = [...]; options = [...] }`, and can set `intoJava`, `intoResources` and `intoFiles` there.
- `emitterOptions = [<id>: [...]]` is deprecated and removed in 1.0.0. Setting both it and `emitter('<id>') { options }` for one emitter fails the build.
- An emitter on the `transcriberjEmitters` configuration that no `emitter(...)` block names still runs, with the subscription's source sets and no options, and is warned about. From 1.0.0 it does not run.
- An emitter block's `sourceSets` defaults to the subscription's `sourceSets`, so naming an emitter with an empty block changes nothing about where its output compiles.
- The core's output is in `build/generated/{sources,resources}/transcriberj/<contract>/core`, available as the subscription's `into` and `intoResources`. Each emitter's is in `.../<contract>/<emitterId>`, available as its block's `intoJava` and `intoResources`.
- `generateContractSources<Contract>` generates the core only; `generateContractSources<Contract><Emitter>` generates one emitter; `reportContractSources<Contract>` writes the subscription's `reportFile`.

## Steps

1. Run the build and collect every warning that says `emitterOptions, which is deprecated` or `not configured with emitter(`. List them for me.
2. For each emitter named in a warning, add `emitter('<id>') { }` to the subscription.
3. Move each `emitterOptions` entry into its emitter block as `options = [...]`, and delete `emitterOptions`.
4. Search the build scripts for `generated/sources/transcriberj`, `generated/resources/transcriberj` and `generateContractSources`. For each hit, show me what it refers to, and propose the property or task that replaces it. Where a tool read emitter output from the subscription's `into`, add the emitter's `intoJava`.
5. Run the build again. Show me that no deprecation warning is left, and that it passes.

Stop after step 5 and summarise what changed, and which files to commit.
