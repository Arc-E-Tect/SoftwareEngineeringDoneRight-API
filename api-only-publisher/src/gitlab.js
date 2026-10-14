"use strict";

// `gitlab-package` -- publish to a GitLab project's Generic Package Registry.
//
// One generic package per target, at the contract's version, holding the archive and
// manifest.json: the same two files the `file` channel writes. In a GitLab CI job it
// needs no settings at all: the API, the project and the job token come from GitLab's
// own CI variables. `release: true` also creates the tag's GitLab release, linking both
// files as packages.
//
// GitLab allows duplicate versions by default: a different file under a version it has
// is accepted, and served from then on. So every file is read back before it is
// uploaded, and the read-back, not GitLab, keeps a published version immutable.

const path = require("path");
const { execFileSync } = require("child_process");

const { isPrerelease } = require("./version-policy");
const { ChannelError } = require("./channel-error");
const { judgeHeld } = require("./held-documents");

const GITLAB_COM = "https://gitlab.com/api/v4";
const TOKEN_HEADERS = { private: "PRIVATE-TOKEN", deploy: "DEPLOY-TOKEN", job: "JOB-TOKEN" };

/** The API, the project and the credential, from configuration first and GitLab CI variables second. */
function channelSettings(options, env = process.env) {
    const tokenEnv = options.tokenEnv || "GITLAB_TOKEN";
    let token = env[tokenEnv];
    let tokenType = options.tokenType || "private";
    if (!TOKEN_HEADERS[tokenType]) {
        throw new ChannelError(`channels.gitlab-package.tokenType must be private, deploy or job, not ${JSON.stringify(tokenType)}`);
    }
    if (!token && env.CI_JOB_TOKEN) {
        token = env.CI_JOB_TOKEN;
        tokenType = "job";
    }
    if (!token) {
        throw new ChannelError(
            `no token in $${tokenEnv}, which channels.gitlab-package.tokenEnv names, and no $CI_JOB_TOKEN. ` +
            "Set one in the release job's environment, or run in a GitLab CI job.");
    }
    const project = options.project === undefined ? env.CI_PROJECT_ID : String(options.project);
    if (!project) {
        throw new ChannelError("the gitlab-package channel needs a project: set channels.gitlab-package.project, " +
            "or run in a GitLab CI job ($CI_PROJECT_ID)");
    }
    const url = (options.url || env.CI_API_V4_URL || GITLAB_COM).replace(/\/+$/, "");
    return { url, project, tokenType, headers: { [TOKEN_HEADERS[tokenType]]: token } };
}

/** The commit a new release's tag points at: the CI job's, or the checkout's HEAD. */
function releaseRef(options, env = process.env) {
    if (env.CI_COMMIT_SHA) return env.CI_COMMIT_SHA;
    try {
        return execFileSync("git", ["-C", path.resolve(options.baseDir || "."), "rev-parse", "HEAD"],
            { encoding: "utf8", stdio: "pipe" }).trim();
    } catch {
        throw new ChannelError("release: true needs the commit to tag: $CI_COMMIT_SHA, or a git checkout to read HEAD from");
    }
}

/** The status and GitLab's message, without the status twice: GitLab's messages often start with it. */
async function answered(response) {
    const text = await response.text();
    let message = text;
    try {
        const body = JSON.parse(text);
        message = body.message || body.error || text;
    } catch {
        // Not JSON: a proxy's error page, say. Reported as it came.
    }
    return message.startsWith(String(response.status)) ? message : `${response.status} ${message}`;
}

const TOKEN_HINT = " Check the token, and that it may read and write this project's package registry.";

/** GitLab's copy of a package file: its bytes, or null when it has none. */
async function readBack(fileUrl, name, headers) {
    const response = await fetch(fileUrl, { headers });
    if (response.status === 404) return null;
    if (!response.ok) {
        throw new ChannelError(`could not read ${name} back from ${fileUrl}: ${await answered(response)}.` +
            (response.status === 401 || response.status === 403 ? TOKEN_HINT : ""));
    }
    return Buffer.from(await response.arrayBuffer());
}

function refused(name, manifest, why) {
    return new ChannelError(`GitLab already has ${name} in ${manifest.target} ${manifest.version}, and it ${why}; ` +
        "a published version is never replaced, so publish the change as a new version");
}

/** The manifest in GitLab's copy of a file, which must describe the same documents as `manifest`. */
function sameAs(held, file, manifest) {
    const judged = judgeHeld(held, file.kind, manifest);
    if (judged.verdict === "unreadable") throw refused(file.name, manifest, `cannot be compared: ${judged.reason}`);
    if (judged.verdict === "different") throw refused(file.name, manifest, "is not this one");
    return judged.held;
}

/**
 * Uploads one file unless GitLab has it: GitLab's copy when it was already there with the
 * same documents, or null when it was uploaded now.
 */
async function upload(file, manifest, headers) {
    const held = await readBack(file.url, file.name, headers);
    if (held) return { held, found: sameAs(held, file, manifest) };
    const response = await fetch(file.url, { method: "PUT", headers, body: file.bytes });
    if (response.ok) return null;
    const answer = `${await answered(response)}`;
    // Refused, perhaps as a duplicate another run published meanwhile: compare that one.
    const now = await readBack(file.url, file.name, headers).catch(() => null);
    if (now) return { held: now, found: sameAs(now, file, manifest) };
    throw new ChannelError(`GitLab refused ${file.name}: ${answer}.` +
        (response.status === 401 || response.status === 403 ? TOKEN_HINT : ""));
}

async function api(url, headers, method = "GET", body) {
    return fetch(url, {
        method,
        headers: body === undefined ? headers : { ...headers, "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
}

/** Creates the tag's release, or adds the package links an existing one lacks; never changes anything else. */
async function release(settings, manifest, options, links, ref, log) {
    const { format, DEFAULT_DESCRIPTION } = require("./channels");
    const tag = format(options.tagFormat || "{target}-v{version}", manifest);
    const releases = `${settings.url}/projects/${encodeURIComponent(settings.project)}/releases`;
    const existing = await api(`${releases}/${encodeURIComponent(tag)}`, settings.headers);
    if (existing.status === 404) {
        const created = await api(releases, settings.headers, "POST", {
            tag_name: tag,
            ref,
            name: format(options.title || "{target} {version}", manifest),
            description: format(options.notes || options.description || DEFAULT_DESCRIPTION, manifest),
            assets: { links },
        });
        if (!created.ok) throw new ChannelError(`GitLab refused the release ${tag}: ${await answered(created)}`);
        log(`-- Created the GitLab release ${tag}`);
        return;
    }
    if (!existing.ok) {
        throw new ChannelError(`could not read the GitLab release ${tag}: ${await answered(existing)}`);
    }
    // A release's link names are unique: one already named, or already pointing here, is left as it is.
    const held = ((await existing.json()).assets || {}).links || [];
    for (const link of links.filter((l) => !held.some((h) => h.url === l.url || h.name === l.name))) {
        const added = await api(`${releases}/${encodeURIComponent(tag)}/assets/links`, settings.headers, "POST", link);
        if (!added.ok) throw new ChannelError(`GitLab refused a link on the release ${tag}: ${await answered(added)}`);
        log(`-- Linked ${link.name} from the GitLab release ${tag}`);
    }
}

function publishGitlabPackage(archive, manifest, options, log) {
    const { format } = require("./channels");
    const settings = channelSettings(options);
    const wantsRelease = options.release === true && !isPrerelease(manifest.version);
    if (wantsRelease && settings.tokenType === "deploy") {
        throw new ChannelError("a deploy token cannot create a GitLab release: GitLab gives it the package registry only. " +
            "Publish with $CI_JOB_TOKEN, or a personal or project access token with the api scope, or set release: false.");
    }
    const ref = wantsRelease ? releaseRef(options) : null;

    const packageName = format(options.packageName || "{target}", manifest);
    const base = `${settings.url}/projects/${encodeURIComponent(settings.project)}/packages/generic/` +
        `${encodeURIComponent(packageName)}/${encodeURIComponent(manifest.version)}`;
    const files = [
        { name: path.basename(archive), bytes: require("fs").readFileSync(archive), kind: "tgz" },
        { name: "manifest.json", bytes: Buffer.from(JSON.stringify(manifest, null, 2) + "\n"), kind: "json" },
    ].map((file) => ({ ...file, url: `${base}/${encodeURIComponent(file.name)}` }));

    return (async () => {
        // Idempotent, by documents: a file GitLab has is left alone when it holds the same
        // documents, and refused when it does not.
        let held = 0;
        let identical = true;
        for (const file of files) {
            const copy = await upload(file, manifest, settings.headers);
            if (!copy) continue;
            held += 1;
            identical = identical && copy.held.equals(file.bytes);
            // A version completed beside an archive from another commit gets that archive's
            // manifest, so the two agree on where the version came from.
            if (file.kind === "tgz") files[1].bytes = copy.found.bytes;
        }
        const alreadyPublished = held === files.length;
        log(alreadyPublished
            ? `-- ${packageName} ${manifest.version} is already in GitLab project ${settings.project}, ` +
              `${identical ? "identical" : "with the same documents"}; nothing to do`
            : `-- Published ${packageName} ${manifest.version} to GitLab project ${settings.project}`);

        if (options.release === true && !wantsRelease) {
            log(`-- no GitLab release for a pre-release: ${manifest.version} would become the latest release`);
        }
        if (wantsRelease) {
            await release(settings, manifest, options,
                files.map((file) => ({ name: file.name, url: file.url, link_type: "package" })), ref, log);
        }
        return { location: files[0].url, ...(alreadyPublished ? { alreadyPublished } : {}) };
    })();
}

module.exports = { publishGitlabPackage, channelSettings };
