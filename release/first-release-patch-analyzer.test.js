// Run with: node --test release/*.test.js
const { test } = require('node:test');
const assert = require('node:assert/strict');

const { analyzeCommits, releaseType } = require('./first-release-patch-analyzer');

test('before 1.0.0 a breaking change releases a minor version', () => {
    assert.equal(releaseType('major', '0.12.0'), 'minor');
    assert.equal(releaseType('major', '0.0.1'), 'minor');
});

test('from 1.0.0 on a breaking change releases a major version', () => {
    assert.equal(releaseType('major', '1.0.0'), 'major');
    assert.equal(releaseType('major', '2.3.4'), 'major');
    assert.equal(releaseType('major', '10.0.0'), 'major');
});

test('anything short of a breaking change releases as the commits say, before 1.0.0 and after', () => {
    for (const version of ['0.12.0', '1.0.0']) {
        assert.equal(releaseType('minor', version), 'minor');
        assert.equal(releaseType('patch', version), 'patch');
        assert.equal(releaseType(null, version), null);
    }
});

test('the first release is a patch, whatever its commits, without analysing them', async () => {
    assert.equal(await analyzeCommits({}, {}), 'patch');
    assert.equal(await analyzeCommits({}, { lastRelease: {} }), 'patch');
    assert.equal(await analyzeCommits({}, { lastRelease: { version: '0.0.0' } }), 'patch');
});
