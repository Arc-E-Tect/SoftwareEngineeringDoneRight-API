"use strict";

// Distribution channels.
//
// Every channel ships the bytes that `pack` produced, and never rebuilds them.
// Building per channel invites the copies of one version to differ -- by a line
// ending, by a timestamp -- which surfaces much later as an unexplainable verify
// failure in a consumer's build.

const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");

const { parse, compare, isPrerelease, npmDistTag } = require("./version-policy");
const { ChannelError } = require("./channel-error");
const { publishNuget } = require("./nuget");
const { publishGitlabPackage } = require("./gitlab");
const { refuseRemote } = require("./remote");

/** What every package and release says it is, unless `description` says otherwise. */
const DEFAULT_DESCRIPTION = "API description documents for {target}.";

/** `template` with {target} and {version} filled in, as tagFormat has always been. */
function format(template, manifest) {
    return String(template).replace(/\{target\}/g, manifest.target).replace(/\{version\}/g, manifest.version);
}


/**
 * `file` -- publish to a local directory.
 *
 * Not a real distribution mechanism. It exists so the pipeline is testable end
 * to end before any remote exists, and as a local-iteration escape hatch
 * afterwards.
 */
function publishFile(archive, manifest, options, log) {
    // Relative to the library root, not to wherever the CLI happened to be run.
    const dir = path.resolve(options.baseDir || ".", options.directory || "publish");
    const targetDir = path.join(dir, manifest.target, manifest.version);
    // `clean: true` keeps only the version being published. Used where the
    // directory is a build output rather than a record of releases: an older
    // version is published again from the commit that declared it.
    if (options.clean === true) {
        fs.rmSync(path.join(dir, manifest.target), { recursive: true, force: true });
    }
    fs.mkdirSync(targetDir, { recursive: true });

    const archiveDest = path.join(targetDir, path.basename(archive));
    fs.copyFileSync(archive, archiveDest);
    fs.writeFileSync(path.join(targetDir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");

    log(`-- Published ${manifest.target} ${manifest.version} to ${archiveDest}`);
    return { location: archiveDest, files: [archiveDest, path.join(targetDir, "manifest.json")] };
}

function pom(groupId, artifactId, version, packaging, description = `API description documents for ${artifactId}.`) {
    return `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${groupId}</groupId>
  <artifactId>${artifactId}</artifactId>
  <version>${version}</version>
  <packaging>${packaging}</packaging>
  <description>${description.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")}</description>
</project>
`;
}

/** The POM's description: configured, or the POM's own default. */
function pomDescription(options, manifest) {
    return options.description ? format(options.description, manifest) : undefined;
}

/**
 * `maven` -- publish into a Maven repository layout.
 *
 * Writing the layout directly rather than shelling out to `mvn` keeps Maven off
 * the list of things a specification repository has to have installed. The
 * layout is all that a resolver reads.
 *
 * This is what makes the Subscriber cheap: a Gradle consumer declares the
 * artifact in a configuration and lets Gradle's own dependency resolution do the
 * fetching, caching and up-to-date checking, instead of the plugin carrying a
 * hand-rolled HTTP client, cache and retry policy.
 */
function publishMaven(archive, manifest, options, log) {
    const groupId = options.groupId;
    if (!groupId) throw new ChannelError("the maven channel requires a groupId");

    const configured = options.repository || path.join(os.homedir(), ".m2", "repository");
    if (/^https?:\/\//.test(configured)) {
        return publishMavenRemote(archive, manifest, { ...options, repository: configured }, log);
    }

    const repository = path.resolve(options.baseDir || ".", configured);
    const artifactId = options.artifactId || manifest.target;
    const version = manifest.version;
    const extension = options.extension || "tgz";

    const dir = path.join(repository, ...groupId.split("."), artifactId, version);
    fs.mkdirSync(dir, { recursive: true });

    const base = `${artifactId}-${version}`;
    const artifactDest = path.join(dir, `${base}.${extension}`);
    fs.copyFileSync(archive, artifactDest);
    const pomDest = path.join(dir, `${base}.pom`);
    fs.writeFileSync(pomDest, pom(groupId, artifactId, version, extension, pomDescription(options, manifest)));

    // The manifest travels beside the artifact as well as inside it, so a
    // consumer can read provenance without unpacking anything.
    const manifestDest = path.join(dir, `${base}-manifest.json`);
    fs.writeFileSync(manifestDest, JSON.stringify(manifest, null, 2) + "\n");

    log(`-- Published ${groupId}:${artifactId}:${version} to ${repository}`);
    return {
        location: artifactDest,
        coordinates: `${groupId}:${artifactId}:${version}@${extension}`,
        files: [artifactDest, pomDest, manifestDest],
    };
}

/**
 * `npm` -- publish as an npm package.
 *
 * Real semver, integrity hashes for free, private scopes available, and the
 * toolchain here is already Node. Its other advantage is social rather than
 * technical: Renovate and Dependabot understand npm natively, so a contract bump
 * arrives in an implementation repository as a pull request, without any bespoke
 * machinery. Publishing is otherwise entirely passive -- the producer releases
 * and nothing happens downstream until somebody looks.
 */
function publishNpm(archive, manifest, options, log) {
    const scope = options.scope;
    const name = scope ? `${scope}/${manifest.target}` : (options.namePrefix || "") + manifest.target;
    const version = manifest.version;
    const distTag = npmDistTag(version, options);

    const workDir = fs.mkdtempSync(path.join(os.tmpdir(), "api-only-npm-"));
    try {
        // The package contains exactly what was packed, extracted -- never a
        // rebuild. Every channel ships the same bytes.
        execFileSync("tar", ["-xzf", archive, "-C", workDir], { encoding: "utf8" });

        fs.writeFileSync(path.join(workDir, "package.json"), JSON.stringify({
            name,
            version,
            description: format(options.description || DEFAULT_DESCRIPTION, manifest),
            license: options.license || "UNLICENSED",
            files: fs.readdirSync(workDir).filter((f) => f !== "package.json").sort(),
            // npm defaults a scoped package to restricted. A contract exists to be
            // read by the people implementing against it, so the default here is
            // the opposite; set access: restricted deliberately to narrow it.
            publishConfig: scope ? { access: options.access || "public" } : undefined,
            repository: manifest.source && manifest.source.repository
                ? { type: "git", url: manifest.source.repository }
                : undefined,
        }, null, 2) + "\n");
        // npm packs each file with its mode, and extracting and writing them applied this
        // process's umask: fixed modes keep the package the same bytes on every machine.
        for (const file of fs.readdirSync(workDir)) fs.chmodSync(path.join(workDir, file), 0o644);

        const outDir = path.resolve(options.baseDir || ".", options.directory || "build/packages/npm");
        fs.mkdirSync(outDir, { recursive: true });

        if (options.publish === false) {
            // `npm pack` alone is what makes this channel testable with no
            // registry: the tarball can be installed from a path.
            const packed = execFileSync("npm", ["pack", "--silent", "--pack-destination", outDir],
                { cwd: workDir, encoding: "utf8" }).trim().split("\n").pop();
            const location = path.join(outDir, packed);
            log(`-- Packed npm ${name}@${version} (${distTag}) to ${location}`);
            return { location, distTag, name, files: [location] };
        }

        // Idempotent: a version the registry already has is left alone when it is the
        // same package, and refused when it is not -- npm never replaces a version.
        const registry = options.registry ? ["--registry", options.registry] : [];
        const [local] = JSON.parse(execFileSync("npm", ["pack", "--dry-run", "--json"],
            { cwd: workDir, encoding: "utf8", stdio: "pipe" }));
        const published = npmPublishedDist(name, version, registry);
        if (published) {
            // The SHA-512 integrity when the registry records one; the tarball's SHA-1
            // otherwise, as GitLab's npm registry records only that.
            const [held, mine] = published.integrity ? [published.integrity, local.integrity]
                : published.shasum ? [published.shasum, local.shasum] : [null, null];
            if (!held) {
                throw new ChannelError(
                    `npm already has ${name}@${version}, and it cannot be compared: the registry records neither ` +
                    "an integrity nor a shasum for it. A published version is never replaced; publish the change as a new version.");
            }
            if (held === mine) {
                log(`-- npm ${name}@${version} is already published, identical; nothing to do`);
                return { location: name + "@" + version, distTag, name, alreadyPublished: true };
            }
            throw new ChannelError(
                `npm already has ${name}@${version}, and it is not this package (${held}, not ${mine}). ` +
                "A published version is never replaced; publish the change as a new version.");
        }

        const args = ["publish", "--tag", distTag, ...registry];
        // A signed attestation tying this tarball to the commit and workflow run
        // that produced it. A tool whose whole purpose is making provenance
        // auditable should not ask to be taken on trust itself. Requires a public
        // package and an OIDC-capable CI job; off by default because it fails
        // outright anywhere else.
        if (options.provenance) args.push("--provenance");
        execFileSync("npm", args, { cwd: workDir, encoding: "utf8", stdio: "pipe" });
        log(`-- Published npm ${name}@${version} under dist-tag '${distTag}'`);
        return { location: name + "@" + version, distTag, name };
    } finally {
        fs.rmSync(workDir, { recursive: true, force: true });
    }
}

/** The `dist` npm records for `name@version` -- integrity, shasum, tarball -- or null when it has no such version. */
function npmPublishedDist(name, version, registry) {
    try {
        const out = execFileSync("npm", ["view", `${name}@${version}`, "dist", "--json", ...registry],
            { encoding: "utf8", stdio: "pipe" }).trim();
        return out ? JSON.parse(out) : null;
    } catch (error) {
        const output = `${error.stdout || ""}${error.stderr || ""}`;
        if (/E404/.test(output)) return null;
        throw new ChannelError(`could not ask npm whether ${name}@${version} is published: ${output.trim() || error.message}`);
    }
}

/**
 * `github-release` -- attach the archive to a GitHub release.
 *
 * The language-neutral floor: immutable per tag, works for private repositories
 * with a token, and readable by a consumer with no JVM and no Node. It has no
 * dependency-resolution semantics and no update notification, which is why it is
 * the fallback rather than the default.
 *
 * Shells out to `gh` rather than carrying an HTTP client and a token-handling
 * policy, because `gh` already solves authentication better than this tool would.
 */
function publishGithubRelease(archive, manifest, options, log) {
    const repository = options.repository;
    if (!repository) throw new ChannelError("the github-release channel requires a repository");

    const tag = format(options.tagFormat || "{target}-v{version}", manifest);

    const gh = (args) => execFileSync("gh", args, { encoding: "utf8", stdio: "pipe" });

    let exists = true;
    try {
        gh(["release", "view", tag, "--repo", repository]);
    } catch {
        exists = false;
    }

    if (!exists) {
        const args = ["release", "create", tag, "--repo", repository,
                      "--title", format(options.title || "{target} {version}", manifest),
                      "--notes", format(options.notes || options.description || DEFAULT_DESCRIPTION, manifest)];
        // A pre-release is marked as one, so that "latest release" never resolves
        // to a contract that is not finished.
        if (isPrerelease(manifest.version)) args.push("--prerelease");
        gh(args);
    }

    // Idempotent, as the registries are: an asset the release already has is left alone
    // when it is the same bytes, and refused when it is not. Never --clobber: that would
    // replace a published contract under the same version.
    const asset = path.basename(archive);
    const assets = exists
        ? gh(["release", "view", tag, "--repo", repository, "--json", "assets", "--jq", ".assets[].name"]).split("\n").map((a) => a.trim())
        : [];
    if (assets.includes(asset)) {
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), "api-only-gh-"));
        try {
            gh(["release", "download", tag, "--repo", repository, "--pattern", asset, "--dir", dir]);
            if (fs.readFileSync(path.join(dir, asset)).equals(fs.readFileSync(archive))) {
                log(`-- ${repository} release ${tag} already has ${asset}, identical; nothing to do`);
                return { location: `${repository}@${tag}`, tag, alreadyPublished: true };
            }
        } finally {
            fs.rmSync(dir, { recursive: true, force: true });
        }
        throw new ChannelError(
            `${repository} release ${tag} already has ${asset}, and it is not this archive. ` +
            "A published version is never replaced; publish the change as a new version.");
    }
    gh(["release", "upload", tag, archive, "--repo", repository]);
    log(`-- Published ${manifest.target} ${manifest.version} to ${repository} release ${tag}`);
    return { location: `${repository}@${tag}`, tag };
}


/**
 * Deploying to a remote Maven repository.
 *
 * Uploading the two files a resolver needs is the whole protocol, so this does
 * it directly rather than requiring Maven to be installed in a specification
 * repository that otherwise has no use for it.
 *
 * The token is read from the environment, never from configuration: a
 * configuration file gets committed, and a credential in a committed file is a
 * credential that has leaked.
 */
async function putFile(url, body, auth, contentType) {
    const response = await fetch(url, {
        method: "PUT",
        headers: {
            ...auth,
            "Content-Type": contentType,
            "Content-Length": String(body.length),
        },
        body,
    });
    if (!response.ok) {
        throw new ChannelError(
            `PUT ${url} failed: ${response.status} ${response.statusText}. ` +
            (response.status === 401 || response.status === 403
                ? "Check the token in the environment variable named by channels.maven.tokenEnv."
                : await response.text().catch(() => ""))
        );
    }
}

/** What a Maven repository holds at `url`, or null when it holds nothing there. */
async function getFile(url, auth) {
    const response = await fetch(url, { headers: auth });
    if (response.status === 404) return null;
    if (!response.ok) {
        throw new ChannelError(`GET ${url} failed: ${response.status} ${response.statusText}. ` +
            (response.status === 401 || response.status === 403
                ? "Check the token in the environment variable named by channels.maven.tokenEnv." : ""));
    }
    return Buffer.from(await response.arrayBuffer());
}

/** An HTTP header name: RFC 9110's token. */
const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;

/** The highest of `versions` that parse as semver and pass `keep`, or null. */
function highest(versions, keep = () => true) {
    const valid = versions.filter((v) => { try { parse(v); return keep(v); } catch { return false; } });
    return valid.length ? valid.reduce((a, b) => (compare(a, b) >= 0 ? a : b)) : null;
}

/** The artifact's maven-metadata.xml, as `mvn deploy` keeps it. */
function metadataXml(groupId, artifactId, versions, lastUpdated) {
    const latest = highest(versions);
    const release = highest(versions, (v) => !isPrerelease(v));
    return '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n' +
        `  <groupId>${groupId}</groupId>\n  <artifactId>${artifactId}</artifactId>\n  <versioning>\n` +
        (latest ? `    <latest>${latest}</latest>\n` : "") + (release ? `    <release>${release}</release>\n` : "") +
        `    <versions>\n${versions.map((v) => `      <version>${v}</version>\n`).join("")}    </versions>\n` +
        `    <lastUpdated>${lastUpdated}</lastUpdated>\n  </versioning>\n</metadata>\n`;
}

/**
 * Adds the version to the artifact's maven-metadata.xml, which a repository that does not
 * generate it -- GitLab's -- otherwise never has, so that update bots never see a new
 * version. Written only when it lacks the version, or its latest or release would change.
 */
async function updateMetadata(url, groupId, artifactId, manifest, auth, log) {
    const held = await getFile(url, auth);
    const xml = held ? held.toString("utf8") : "";
    const block = /<versions>([\s\S]*?)<\/versions>/.exec(xml);
    const versions = block ? [...block[1].matchAll(/<version>\s*([^<\s]+)\s*<\/version>/g)].map((m) => m[1]) : [];
    const tag = (name) => ((new RegExp(`<${name}>\\s*([^<\\s]+)\\s*</${name}>`).exec(xml)) || [])[1] || null;
    const listed = versions.includes(manifest.version) ? versions : [...versions, manifest.version];
    if (listed === versions && tag("latest") === highest(listed)
        && tag("release") === highest(listed, (v) => !isPrerelease(v))) {
        return;
    }
    // The commit's time, as the archive's producedAt, so the same commit writes the same file.
    const lastUpdated = String(manifest.producedAt || new Date().toISOString()).replace(/\.\d+Z$/, "Z").replace(/[-:TZ]/g, "");
    try {
        await putFile(url, Buffer.from(metadataXml(groupId, artifactId, listed, lastUpdated), "utf8"), auth, "application/xml");
    } catch (error) {
        throw new ChannelError(`the repository refused maven-metadata.xml for ${groupId}:${artifactId}: ${error.message.trim()} ` +
            "The version's files are published; a re-run retries only the metadata. For a repository that generates " +
            "its own maven-metadata.xml, set channels.maven.metadata: false.");
    }
    log(`-- Listed ${manifest.version} in ${groupId}:${artifactId}'s maven-metadata.xml`);
}

function publishMavenRemote(archive, manifest, options, log) {
    const tokenEnv = options.tokenEnv || "MAVEN_TOKEN";
    const token = process.env[tokenEnv];
    if (!token) {
        throw new ChannelError(
            `no credential in $${tokenEnv}, which channels.maven.tokenEnv names. ` +
            `Remote publication needs one; set it in the release job's environment.`
        );
    }
    // A bearer token unless tokenHeader names another header, which then carries the
    // token as it is: GitLab takes a job, personal or deploy token as Job-Token,
    // Private-Token or Deploy-Token, and Bearer only for OAuth.
    if (options.tokenHeader !== undefined && !HEADER_NAME.test(options.tokenHeader)) {
        throw new ChannelError(`channels.maven.tokenHeader must be an HTTP header name, such as Job-Token, ` +
            `not ${JSON.stringify(options.tokenHeader)}`);
    }
    const auth = options.tokenHeader === undefined ? { "Authorization": `Bearer ${token}` } : { [options.tokenHeader]: token };

    const artifactId = options.artifactId || manifest.target;
    const version = manifest.version;
    const extension = options.extension || "tgz";
    const artifactDir = `${options.repository.replace(/\/+$/, "")}/${options.groupId.split(".").join("/")}/${artifactId}`;
    const base = `${artifactDir}/${version}/${artifactId}-${version}`;

    const work = (async () => {
        // Idempotent, file by file: each file the repository already has is left alone when
        // it is the same bytes, and refuses the whole version when it is not. Every file is
        // read back before any is uploaded, so a run interrupted after the artifact is
        // completed by the next one, and a version that differs anywhere is not touched.
        const files = [
            { url: `${base}.${extension}`, bytes: fs.readFileSync(archive), type: "application/octet-stream" },
            { url: `${base}.pom`, type: "application/xml",
              bytes: Buffer.from(pom(options.groupId, artifactId, version, extension, pomDescription(options, manifest)), "utf8") },
            { url: `${base}-manifest.json`, type: "application/json",
              bytes: Buffer.from(JSON.stringify(manifest, null, 2) + "\n", "utf8") },
        ];
        const missing = [];
        for (const file of files) {
            const existing = await getFile(file.url, auth);
            if (!existing) {
                missing.push(file);
            } else if (!existing.equals(file.bytes)) {
                throw new ChannelError(
                    `${options.repository} already has ${options.groupId}:${artifactId}:${version}, ` +
                    `and its ${file.url.split("/").pop()} is not this one. ` +
                    "A published version is never replaced; publish the change as a new version.");
            }
        }
        for (const file of missing) await putFile(file.url, file.bytes, auth, file.type);
        if (options.metadata !== false) {
            await updateMetadata(`${artifactDir}/maven-metadata.xml`, options.groupId, artifactId, manifest, auth, log);
        }
        return missing.length === 0;
    })();

    // The CLI is synchronous throughout; surfacing the promise here would make
    // every caller async for one channel's benefit.
    return work.then((alreadyPublished) => {
        log(alreadyPublished
            ? `-- ${options.groupId}:${artifactId}:${version} is already in ${options.repository}, identical; nothing to do`
            : `-- Deployed ${options.groupId}:${artifactId}:${version} to ${options.repository}`);
        return {
            location: `${base}.${extension}`, coordinates: `${options.groupId}:${artifactId}:${version}@${extension}`,
            ...(alreadyPublished ? { alreadyPublished } : {}),
        };
    });
}

const CHANNELS = {
    file: publishFile, maven: publishMaven, npm: publishNpm, "github-release": publishGithubRelease, nuget: publishNuget,
    "gitlab-package": publishGitlabPackage,
};

/**
 * Publishes to the channel named `channel`, of the type its settings' `type` names, or
 * of the type it is named after -- `maven` is a Maven channel, as it has always been.
 */
function publish(archive, manifest, channel, options, log = () => {}) {
    const { type = channel, ...settings } = options || {};
    const handler = CHANNELS[type];
    if (!handler) {
        throw new ChannelError(
            `unknown channel '${channel}'; available channels are ${Object.keys(CHANNELS).join(", ")}`
        );
    }
    // Here as well as in the CLI, so the Publisher used as a library keeps it too.
    refuseRemote([channel], { [channel]: options || {} });
    return handler(archive, manifest, settings, log);
}

module.exports = { publish, CHANNELS, ChannelError, pom, format, DEFAULT_DESCRIPTION };
