"use strict";

// `nuget` -- publish as a NuGet package, for a .NET build.
//
// Written directly rather than with nuget.exe or `dotnet pack`, as the Maven channel is
// written without mvn: a specification repository needs no .NET SDK to reach a .NET
// audience. A .nupkg is a ZIP archive of a .nuspec, two Open Packaging Conventions parts
// and the content, and a push is one multipart HTTP PUT.
//
// The package carries what `pack` produced, extracted, never rebuilt, under `contracts/`:
// a folder NuGet gives no behaviour, so the path is the whole contract with a consumer
// that reads it from its global packages folder today, and the later .targets that
// places and verifies it is free to decide how it is delivered. That path does not change
// without a major version of the Publisher.
//
// Renovate and Dependabot understand NuGet, so a contract bump reaches a .NET consumer as
// a pull request, as it does an npm or Maven one.

const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");

const { parse, isPrerelease } = require("./version-policy");
const { zip } = require("./zip");
const { ChannelError } = require("./channel-error");

/** Where the documents and manifest.json sit inside the package. */
const CONTENT_FOLDER = "contracts";

// NuGet's own rule (NuGet.Client, PackageIdValidator), and nuget.org's length limit.
const ID = /^\w+([.-]\w+)*$/;
const ID_MAX_LENGTH = 100;

const PUBLISH_RESOURCE = "PackagePublish/2.0.0";
const CONTENT_RESOURCE = "PackageBaseAddress/3.0.0";

function pascalCase(target) {
    return target.split(/[-_.]/).filter(Boolean)
        .map((part) => part[0].toUpperCase() + part.slice(1)).join("");
}

/**
 * The package id for a target: `packageId` when configured, otherwise
 * `<idPrefix>.<target in PascalCase>`, as the Maven channel takes `groupId` and an
 * optional `artifactId`. Refused, before anything is written, when NuGet would refuse it.
 */
function packageId(target, options) {
    let id = options.packageId;
    if (!id) {
        if (!options.idPrefix) throw new ChannelError("the nuget channel requires an idPrefix, such as Contoso.Contracts");
        id = `${options.idPrefix}.${pascalCase(target)}`;
    }
    if (!ID.test(id) || id.length > ID_MAX_LENGTH) {
        throw new ChannelError(
            `'${id}' is not a valid NuGet package id: letters, digits and underscores, in parts ` +
            `separated by single dots or dashes, at most ${ID_MAX_LENGTH} characters. ` +
            "Set channels.nuget.idPrefix, or channels.nuget.packageId, to one that is.");
    }
    return id;
}

/** Refuses a version NuGet would not tell apart from another. */
function checkVersion(version) {
    if (parse(version).build) {
        throw new ChannelError(
            `version '${version}' carries build metadata, which NuGet ignores when it identifies a ` +
            "package: it would be the same package as every other build of " +
            `${version.split("+")[0]}, and a feed refuses the second. Publish it without the +metadata.`);
    }
}

function escape(text) {
    return String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** The .nuspec: what the npm channel writes into package.json, in NuGet's terms. */
function nuspec(id, manifest, options) {
    const source = manifest.source || {};
    const repository = source.repository
        ? `    <repository type="git" url="${escape(source.repository)}"` +
          (source.commit ? ` commit="${escape(source.commit)}"` : "") + " />\n"
        : "";
    return '<?xml version="1.0" encoding="utf-8"?>\n' +
        '<package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">\n' +
        "  <metadata>\n" +
        `    <id>${escape(id)}</id>\n` +
        `    <version>${escape(manifest.version)}</version>\n` +
        `    <authors>${escape(options.authors || options.idPrefix || id)}</authors>\n` +
        `    <description>${escape((options.description || "API description documents for {target}.")
            .replace(/\{target\}/g, manifest.target).replace(/\{version\}/g, manifest.version))}</description>\n` +
        `    <license type="expression">${escape(options.license || "UNLICENSED")}</license>\n` +
        repository +
        "  </metadata>\n" +
        "</package>\n";
}

function contentTypes(names) {
    // Not path.extname: it reads "_rels/.rels" as a dotfile with no extension, and OPC needs "rels".
    const extension = (name) => {
        const base = path.posix.basename(name);
        return base.includes(".") ? base.slice(base.lastIndexOf(".") + 1) : "";
    };
    const extensions = [...new Set(names.map(extension).filter(Boolean))].sort();
    const types = extensions.map((extension) =>
        `<Default Extension="${escape(extension)}" ContentType="${extension === "rels"
            ? "application/vnd.openxmlformats-package.relationships+xml" : "application/octet"}" />`);
    return '<?xml version="1.0" encoding="utf-8"?>' +
        `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">${types.join("")}</Types>`;
}

function relationships(nuspecName) {
    return '<?xml version="1.0" encoding="utf-8"?>' +
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
        `<Relationship Type="http://schemas.microsoft.com/packaging/2010/07/manifest" Target="/${escape(nuspecName)}" Id="Rnuspec" />` +
        "</Relationships>";
}

/**
 * The .nupkg bytes: the .nuspec, the packaging parts, then everything the packed archive
 * holds, extracted and sorted. The same archive always yields the same bytes.
 */
function nupkg(archive, id, manifest, options) {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), "api-only-nuget-"));
    try {
        execFileSync("tar", ["-xzf", archive, "-C", work], { encoding: "utf8" });
        const content = fs.readdirSync(work).sort().map((name) => ({
            name: `${CONTENT_FOLDER}/${name}`, data: fs.readFileSync(path.join(work, name)),
        }));
        const nuspecName = `${id}.nuspec`;
        const rels = "_rels/.rels";
        const names = [nuspecName, rels, ...content.map((entry) => entry.name)];
        return {
            bytes: zip([
                { name: nuspecName, data: Buffer.from(nuspec(id, manifest, options), "utf8") },
                { name: "[Content_Types].xml", data: Buffer.from(contentTypes(names), "utf8") },
                { name: rels, data: Buffer.from(relationships(nuspecName), "utf8") },
                ...content,
            ]),
            nuspec: nuspec(id, manifest, options),
        };
    } finally {
        fs.rmSync(work, { recursive: true, force: true });
    }
}

/**
 * Write the package into a local feed, in the hierarchical layout `nuget add` writes --
 * `<id>/<version>/<id>.<version>.nupkg`, lower-cased, with the .sha512 and .nuspec beside
 * it -- plus manifest.json, so provenance is readable without unpacking, as in Maven.
 */
function publishLocal(archive, manifest, options, id, log) {
    const { bytes, nuspec: spec } = nupkg(archive, id, manifest, options);
    const lowerId = id.toLowerCase();
    const lowerVersion = String(manifest.version).toLowerCase();
    const feed = path.resolve(options.baseDir || ".", options.repository);
    const dir = path.join(feed, lowerId, lowerVersion);
    fs.mkdirSync(dir, { recursive: true });

    const file = path.join(dir, `${lowerId}.${lowerVersion}.nupkg`);
    fs.writeFileSync(file, bytes);
    fs.writeFileSync(`${file}.sha512`, crypto.createHash("sha512").update(bytes).digest("base64"));
    fs.writeFileSync(path.join(dir, `${lowerId}.nuspec`), spec);
    fs.writeFileSync(path.join(dir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");

    log(`-- Published NuGet ${id} ${manifest.version} to ${feed}`);
    return { location: file, id, version: manifest.version, prerelease: isPrerelease(manifest.version) };
}

/** The resources a feed's service index lists. */
async function feedResources(index) {
    const response = await fetch(index);
    if (!response.ok) {
        throw new ChannelError(`could not read the NuGet service index at ${index}: ${response.status} ${response.statusText}`);
    }
    const resources = (await response.json()).resources || [];
    const find = (type) => (resources.find((r) => r["@type"] === type) || {})["@id"] || null;
    const publish = find(PUBLISH_RESOURCE);
    if (!publish) {
        throw new ChannelError(`the NuGet feed at ${index} has no ${PUBLISH_RESOURCE} resource, so it cannot be pushed to`);
    }
    return { publish, content: find(CONTENT_RESOURCE) };
}

/**
 * Whether the feed's copy of this version is these bytes: true or false, or null when the
 * feed offers no way to read it back.
 */
async function sameAsPublished(content, id, version, bytes, key) {
    if (!content) return null;
    const lowerId = id.toLowerCase();
    const lowerVersion = String(version).toLowerCase();
    const url = `${content.replace(/\/?$/, "/")}${lowerId}/${lowerVersion}/${lowerId}.${lowerVersion}.nupkg`;
    const response = await fetch(url, { headers: { "X-NuGet-ApiKey": key } });
    if (!response.ok) return null;
    return Buffer.from(await response.arrayBuffer()).equals(bytes);
}

/**
 * Push the package to a remote feed: one PUT to the endpoint the service index names,
 * the package as the first part of a multipart body, the key in X-NuGet-ApiKey.
 *
 * The key is read from the environment, never from configuration, as the Maven channel
 * reads its token.
 */
function publishRemote(archive, manifest, options, id, log) {
    const tokenEnv = options.tokenEnv || "NUGET_API_KEY";
    const key = process.env[tokenEnv];
    if (!key) {
        throw new ChannelError(
            `no API key in $${tokenEnv}, which channels.nuget.tokenEnv names. ` +
            "Remote publication needs one; set it in the release job's environment.");
    }
    const { bytes } = nupkg(archive, id, manifest, options);

    return (async () => {
        const { publish: endpoint, content } = await feedResources(options.repository);
        const boundary = `api-only-${crypto.createHash("sha256").update(bytes).digest("hex").slice(0, 32)}`;
        const body = Buffer.concat([
            Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="package"; filename="package.nupkg"\r\n` +
                "Content-Type: application/octet-stream\r\n\r\n", "utf8"),
            bytes,
            Buffer.from(`\r\n--${boundary}--\r\n`, "utf8"),
        ]);
        const response = await fetch(endpoint, {
            method: "PUT",
            headers: {
                "X-NuGet-ApiKey": key,
                "Content-Type": `multipart/form-data; boundary=${boundary}`,
                "Content-Length": String(body.length),
            },
            body,
        });
        if (response.status === 409) {
            // Idempotent: the same package again is success, a different one is not.
            const same = await sameAsPublished(content, id, manifest.version, bytes, key);
            if (same) {
                log(`-- NuGet ${id} ${manifest.version} is already published, identical; nothing to do`);
                return {
                    location: endpoint, id, version: manifest.version, prerelease: isPrerelease(manifest.version),
                    alreadyPublished: true,
                };
            }
            throw new ChannelError(`the NuGet feed already has ${id} ${manifest.version}` +
                (same === false ? ", and it is not this package" : ", and it cannot be read back to compare") +
                "; a published version is never replaced, so publish the change as a new version");
        }
        if (!response.ok) {
            throw new ChannelError(`the NuGet feed refused the package: ${response.status} ${response.statusText}. ` +
                (response.status === 401 || response.status === 403
                    ? "Check the key in the environment variable named by channels.nuget.tokenEnv." : ""));
        }
        log(`-- Pushed NuGet ${id} ${manifest.version} to ${endpoint}`);
        return { location: endpoint, id, version: manifest.version, prerelease: isPrerelease(manifest.version) };
    })();
}

/**
 * The channel: a path writes into a local feed, an http(s) URL -- the feed's service
 * index -- pushes to a remote one. Everything that can be refused is refused before
 * anything is written or sent.
 */
function publishNuget(archive, manifest, options, log) {
    const id = packageId(manifest.target, options);
    checkVersion(manifest.version);
    if (!options.repository) {
        throw new ChannelError("the nuget channel requires a repository: a directory for a local feed, or a feed's service index URL");
    }
    if (/^https?:\/\//.test(options.repository)) {
        return publishRemote(archive, manifest, options, id, log);
    }
    return publishLocal(archive, manifest, options, id, log);
}

module.exports = { publishNuget, packageId, nuspec, checkVersion, CONTENT_FOLDER };
