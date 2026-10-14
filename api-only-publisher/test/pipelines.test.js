"use strict";

// The reference pipelines: what a team copies into .gitlab-ci.yml or .github/workflows/.
// One job on the sealed image, which publishes itself; and the two-step variants, whose
// packaging job stays sealed, for an organisation that keeps credentials out of it.
// Each checks versions in a pull or merge request, and publishes with the history the
// check needs; a channel problem is a warning, never the end of the job.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const PIPELINES = path.join(__dirname, "..", "pipelines");
const read = (name) => fs.readFileSync(path.join(PIPELINES, name), "utf8");

const TAG_RULE = { if: "$CI_COMMIT_TAG =~ /^v/" };
const MR_RULE = { if: "$CI_PIPELINE_SOURCE == \"merge_request_event\"" };

test("the GitLab pipeline publishes in one job on the image, pinned by digest, on a tag, and checks merge requests", () => {
    const pipeline = YAML.parse(read("gitlab-ci.yml"));
    assert.match(pipeline.variables.PUBLISHER_IMAGE, /@sha256:/);
    assert.deepStrictEqual(pipeline.workflow.rules, [TAG_RULE, MR_RULE]);
    const jobs = Object.keys(pipeline).filter((key) => pipeline[key] && pipeline[key].script);
    assert.deepStrictEqual(jobs, ["check", "publish"]);
    assert.strictEqual(pipeline.publish.image, "$PUBLISHER_IMAGE");
    assert.deepStrictEqual(pipeline.publish.rules, [{ if: "$CI_COMMIT_TAG" }]);
});

test("only the publishing job lets the sealed image contact anything", () => {
    const pipeline = YAML.parse(read("gitlab-ci.yml"));
    assert.deepStrictEqual(pipeline.publish.variables, { API_ONLY_PUBLISHER_REMOTE: "allow", GIT_DEPTH: "0" });
    assert.strictEqual(pipeline.variables.API_ONLY_PUBLISHER_REMOTE, undefined, "not for every job");
    assert.strictEqual((pipeline.check.variables || {}).API_ONLY_PUBLISHER_REMOTE, undefined);
});

test("the job builds, lints and publishes the targets with the Publisher in the image, and nothing else", () => {
    const pipeline = YAML.parse(read("gitlab-ci.yml"));
    const script = pipeline.publish.script.join("\n");
    for (const command of ["api-only-publisher build", "api-only-publisher lint", "api-only-publisher publish"]) {
        assert.ok(script.includes(command), command);
    }
    assert.match(script, /--target/);
    // Everything the job runs comes from the one vetted image: nothing fetched while it runs.
    for (const forbidden of [/\bnpm\b/, /\bnpx\b/, /\bcurl\b/, /\bwget\b/, /\bapk\b/, /\bapt/, /\bpip\b/]) {
        assert.doesNotMatch(script, forbidden);
    }
});

test("the GitLab pipeline says what apionly.yaml needs: gitlab-package with no settings", () => {
    const text = read("gitlab-ci.yml");
    assert.match(text, /^#\s+gitlab-package: \{\}$/m);
    assert.match(text, /tokenHeader: Job-Token/);
});

test("the two-step GitLab pipeline remains, its packaging job sealed", () => {
    const pipeline = YAML.parse(read("gitlab-ci-two-step.yml"));
    assert.strictEqual(pipeline.package.image, "$PUBLISHER_IMAGE");
    assert.strictEqual((pipeline.package.variables || {}).API_ONLY_PUBLISHER_REMOTE, undefined);
    assert.ok(pipeline["publish:generic"], "the generic registry by the platform's own tools");
});

test("the GitLab pipeline runs on a v* tag, never on the tag a GitLab release it creates puts on the commit", () => {
    // release: true creates <target>-v<version>; a pipeline on that tag would run again.
    const rule = YAML.parse(read("gitlab-ci.yml")).workflow.rules.find((r) => r.if.includes("CI_COMMIT_TAG")).if;
    const pattern = new RegExp(/=~ \/(.*)\/$/.exec(rule)[1]);
    for (const tag of ["v1.2.0", "v0.1.0-rc.1", "v2026.10"]) assert.match(tag, pattern, tag);
    for (const tag of ["orders-v1.2.0", "apionly-gitlab-ci-v0.1.1", "release-1"]) assert.doesNotMatch(tag, pattern, tag);
});

// ------------------------------------------------------------------ versions: check, history, tags

/** Every script line of a GitLab job, or every run of a GitHub job's steps, as one text. */
function scriptOf(job) {
    return job.script ? [].concat(job.before_script || [], job.script).join("\n")
        : job.steps.map((step) => step.run || "").join("\n");
}

test("a merge request's pipeline runs check in the image against the merge-base with its target branch", () => {
    for (const name of ["gitlab-ci.yml", "gitlab-ci-two-step.yml"]) {
        const pipeline = YAML.parse(read(name));
        assert.deepStrictEqual(pipeline.check.rules, [MR_RULE], name);
        assert.strictEqual(pipeline.check.image, "$PUBLISHER_IMAGE", name);
        assert.strictEqual(pipeline.check.variables.GIT_DEPTH, "0", name);
        const script = scriptOf(pipeline.check);
        assert.match(script, /git fetch .*\$CI_MERGE_REQUEST_TARGET_BRANCH_NAME/, name);
        assert.match(script, /api-only-publisher check --since "\$\(git merge-base HEAD "origin\/\$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"\)"/, name);
    }
});

test("a GitHub pull request runs check in the sealed image, with no network, against the merge-base with its base branch", () => {
    const pipeline = YAML.parse(read("github-actions.yml"));
    assert.ok("pull_request" in pipeline.on);
    const { check } = pipeline.jobs;
    assert.strictEqual(check.if, "github.event_name == 'pull_request'");
    assert.strictEqual(check.steps[0].with["fetch-depth"], 0);
    const script = scriptOf(check);
    assert.match(script, /docker run --rm --network none .*"\$PUBLISHER_IMAGE" check --since "\$\(git merge-base HEAD "origin\/\$BASE_REF"\)"/s);
    assert.strictEqual(pipeline.jobs.package.if, "github.event_name != 'pull_request'");
});

test("every job that publishes or packages checks out the full history, so publish can decide every version", () => {
    const github = YAML.parse(read("github-actions.yml"));
    assert.strictEqual(github.jobs.package.steps[0].with["fetch-depth"], 0);
    const container = YAML.parse(`jobs:\n${read("github-actions-container-job.yml")}`).jobs;
    assert.strictEqual(container.package.steps[0].with["fetch-depth"], 0);
    assert.strictEqual(YAML.parse(read("gitlab-ci.yml")).publish.variables.GIT_DEPTH, "0");
    assert.strictEqual(YAML.parse(read("gitlab-ci-two-step.yml")).package.variables.GIT_DEPTH, "0");
});

test("a publishing step compares documents, warns about a version it cannot publish, and never fails the job on one", () => {
    const github = YAML.parse(read("github-actions.yml")).jobs.publish;
    const twoStep = YAML.parse(read("gitlab-ci-two-step.yml"));
    const scripts = [
        ...github.steps.filter((step) => /^Publish|^Attach/.test(step.name)).map((step) => [step.name, step.run]),
        ...["publish:npm", "publish:maven", "publish:nuget", "publish:generic"].map((job) => [job, scriptOf(twoStep[job])]),
    ];
    assert.strictEqual(scripts.length, 8);
    for (const [name, script] of scripts) {
        assert.doesNotMatch(script, /exit 1/, name);
        assert.doesNotMatch(script, /cmp -s/, `${name}: bytes are not what a version is`);
        assert.match(script, /documents\(\) \{ sed -n '\/\^  "files": \\\[\/,\/\^  \\\]\/p'; \}/, name);
        assert.match(script, /with the same documents/, name);
        assert.match(script, /other documents/, name);
    }
});

test("the documents a publishing step compares are the manifest's files, whichever commit it was packed from", () => {
    // The helper every publishing step defines, run on two manifests of one version.
    const { execFileSync } = require("node:child_process");
    const documents = (manifest) => execFileSync("sh", ["-c", `documents() { sed -n '/^  "files": \\[/,/^  \\]/p'; }; documents`],
        { input: JSON.stringify(manifest, null, 2) + "\n", encoding: "utf8" });
    const manifest = (commit, sha) => ({ schemaVersion: 1, target: "orders", version: "1.0.0", producedAt: commit, closureSha256: commit,
        source: { repository: "r", commit }, files: [{ path: "openapi.yaml", sha256: sha }] });
    assert.match(documents(manifest("a", "1")), /"sha256": "1"/);
    assert.strictEqual(documents(manifest("a", "1")), documents(manifest("b", "1")));
    assert.notStrictEqual(documents(manifest("a", "1")), documents(manifest("a", "2")));
});

test("after publishing, the pipeline creates each target's release tag, which check and publish read", () => {
    const github = YAML.parse(read("github-actions.yml")).jobs.publish;
    assert.strictEqual(github.env.TAG_FORMAT, "{target}-v{version}");
    const tagging = github.steps[github.steps.length - 1];
    assert.strictEqual(tagging.if, "env.TAG_FORMAT != ''");
    assert.match(tagging.run, /git\/refs" -f ref="refs\/tags\/\$tag" -f sha="\$\{\{ github\.sha \}\}"/);
    assert.match(tagging.run, /::warning::/);

    const twoStep = YAML.parse(read("gitlab-ci-two-step.yml"));
    const release = twoStep["release:tags"];
    assert.strictEqual(release.stage, "release");
    assert.match(scriptOf(release), /\/releases"/);
    assert.match(scriptOf(release), /JOB-TOKEN: \$CI_JOB_TOKEN/);
    assert.strictEqual(twoStep.variables.TAG_FORMAT, "{target}-v{version}");
    // The one-job pipeline has the gitlab-package channel create the tag with the release.
    assert.match(read("gitlab-ci.yml"), /release: true .*tag/);
});
