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

const { isPrerelease, npmDistTag } = require("./version-policy");

class ChannelError extends Error {}

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
    fs.mkdirSync(targetDir, { recursive: true });

    const archiveDest = path.join(targetDir, path.basename(archive));
    fs.copyFileSync(archive, archiveDest);
    fs.writeFileSync(path.join(targetDir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");

    log(`-- Published ${manifest.target} ${manifest.version} to ${archiveDest}`);
    return { location: archiveDest };
}

function pom(groupId, artifactId, version, packaging) {
    return `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${groupId}</groupId>
  <artifactId>${artifactId}</artifactId>
  <version>${version}</version>
  <packaging>${packaging}</packaging>
  <description>API description documents for ${artifactId}.</description>
</project>
`;
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
    fs.writeFileSync(path.join(dir, `${base}.pom`), pom(groupId, artifactId, version, extension));

    // The manifest travels beside the artifact as well as inside it, so a
    // consumer can read provenance without unpacking anything.
    fs.writeFileSync(path.join(dir, `${base}-manifest.json`), JSON.stringify(manifest, null, 2) + "\n");

    log(`-- Published ${groupId}:${artifactId}:${version} to ${repository}`);
    return { location: artifactDest, coordinates: `${groupId}:${artifactId}:${version}@${extension}` };
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
    const distTag = npmDistTag(version);

    const workDir = fs.mkdtempSync(path.join(os.tmpdir(), "api-only-npm-"));
    try {
        // The package contains exactly what was packed, extracted -- never a
        // rebuild. Every channel ships the same bytes.
        execFileSync("tar", ["-xzf", archive, "-C", workDir], { encoding: "utf8" });

        fs.writeFileSync(path.join(workDir, "package.json"), JSON.stringify({
            name,
            version,
            description: `API description documents for ${manifest.target}.`,
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

        const outDir = path.resolve(options.baseDir || ".", options.directory || "build/packages/npm");
        fs.mkdirSync(outDir, { recursive: true });

        if (options.publish === false) {
            // `npm pack` alone is what makes this channel testable with no
            // registry: the tarball can be installed from a path.
            const packed = execFileSync("npm", ["pack", "--silent", "--pack-destination", outDir],
                { cwd: workDir, encoding: "utf8" }).trim().split("\n").pop();
            const location = path.join(outDir, packed);
            log(`-- Packed npm ${name}@${version} (${distTag}) to ${location}`);
            return { location, distTag, name };
        }

        const args = ["publish", "--tag", distTag];
        if (options.registry) args.push("--registry", options.registry);
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

    const tag = (options.tagFormat || "{target}-v{version}")
        .replace(/\{target\}/g, manifest.target)
        .replace(/\{version\}/g, manifest.version);

    const gh = (args) => execFileSync("gh", args, { encoding: "utf8", stdio: "pipe" });

    let exists = true;
    try {
        gh(["release", "view", tag, "--repo", repository]);
    } catch {
        exists = false;
    }

    if (!exists) {
        const args = ["release", "create", tag, "--repo", repository,
                      "--title", `${manifest.target} ${manifest.version}`,
                      "--notes", `API description documents for ${manifest.target}.`];
        // A pre-release is marked as one, so that "latest release" never resolves
        // to a contract that is not finished.
        if (isPrerelease(manifest.version)) args.push("--prerelease");
        gh(args);
    }

    gh(["release", "upload", tag, archive, "--repo", repository, "--clobber"]);
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
async function putFile(url, body, token, contentType) {
    const response = await fetch(url, {
        method: "PUT",
        headers: {
            "Authorization": `Bearer ${token}`,
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

function publishMavenRemote(archive, manifest, options, log) {
    const tokenEnv = options.tokenEnv || "MAVEN_TOKEN";
    const token = process.env[tokenEnv];
    if (!token) {
        throw new ChannelError(
            `no credential in $${tokenEnv}, which channels.maven.tokenEnv names. ` +
            `Remote publication needs one; set it in the release job's environment.`
        );
    }

    const artifactId = options.artifactId || manifest.target;
    const version = manifest.version;
    const extension = options.extension || "tgz";
    const base = `${options.repository.replace(/\/+$/, "")}/` +
        `${options.groupId.split(".").join("/")}/${artifactId}/${version}/${artifactId}-${version}`;

    const work = (async () => {
        await putFile(`${base}.${extension}`, fs.readFileSync(archive), token, "application/octet-stream");
        await putFile(`${base}.pom`,
            Buffer.from(pom(options.groupId, artifactId, version, extension), "utf8"),
            token, "application/xml");
        await putFile(`${base}-manifest.json`,
            Buffer.from(JSON.stringify(manifest, null, 2) + "\n", "utf8"),
            token, "application/json");
    })();

    // The CLI is synchronous throughout; surfacing the promise here would make
    // every caller async for one channel's benefit.
    return work.then(() => {
        log(`-- Deployed ${options.groupId}:${artifactId}:${version} to ${options.repository}`);
        return { location: `${base}.${extension}`, coordinates: `${options.groupId}:${artifactId}:${version}@${extension}` };
    });
}

const CHANNELS = { file: publishFile, maven: publishMaven, npm: publishNpm, "github-release": publishGithubRelease };

function publish(archive, manifest, channel, options, log = () => {}) {
    const handler = CHANNELS[channel];
    if (!handler) {
        throw new ChannelError(
            `unknown channel '${channel}'; available channels are ${Object.keys(CHANNELS).join(", ")}`
        );
    }
    return handler(archive, manifest, options || {}, log);
}

module.exports = { publish, CHANNELS, ChannelError, pom };
