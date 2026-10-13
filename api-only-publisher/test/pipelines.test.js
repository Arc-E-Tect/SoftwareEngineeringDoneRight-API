"use strict";

// The GitLab CI reference pipelines: what an enterprise copies into .gitlab-ci.yml.
// One job on the sealed image, which publishes itself; and the two-step variant, whose
// packaging job stays sealed, for an organisation that keeps credentials out of it.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const PIPELINES = path.join(__dirname, "..", "pipelines");
const read = (name) => fs.readFileSync(path.join(PIPELINES, name), "utf8");

test("the GitLab pipeline is one job on the image, pinned by digest, publishing on a tag", () => {
    const pipeline = YAML.parse(read("gitlab-ci.yml"));
    assert.match(pipeline.variables.PUBLISHER_IMAGE, /@sha256:/);
    assert.deepStrictEqual(pipeline.workflow.rules, [{ if: "$CI_COMMIT_TAG =~ /^v/" }]);
    const jobs = Object.keys(pipeline).filter((key) => pipeline[key] && pipeline[key].script);
    assert.deepStrictEqual(jobs, ["publish"]);
    assert.strictEqual(pipeline.publish.image, "$PUBLISHER_IMAGE");
});

test("only the publishing job lets the sealed image contact anything", () => {
    const pipeline = YAML.parse(read("gitlab-ci.yml"));
    assert.deepStrictEqual(pipeline.publish.variables, { API_ONLY_PUBLISHER_REMOTE: "allow" });
    assert.strictEqual(pipeline.variables.API_ONLY_PUBLISHER_REMOTE, undefined, "not for every job");
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
    const rule = YAML.parse(read("gitlab-ci.yml")).workflow.rules[0].if;
    const pattern = new RegExp(/=~ \/(.*)\/$/.exec(rule)[1]);
    for (const tag of ["v1.2.0", "v0.1.0-rc.1", "v2026.10"]) assert.match(tag, pattern, tag);
    for (const tag of ["orders-v1.2.0", "apionly-gitlab-ci-v0.1.1", "release-1"]) assert.doesNotMatch(tag, pattern, tag);
});
