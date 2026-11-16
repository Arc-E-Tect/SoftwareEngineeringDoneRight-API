## Git conventions
- Commit messages should follow the Conventional Commits specification (https://www.conventionalcommits.org/en/v1.0.0/).
- Commit messages are signed.
- Semantic versioning is used for releases (https://semver.org/).

### Commit messages
Changes to scripts, updates of versions of the dependencies, etc. are using the commit type `maintenance`.
This is also the default commit type for the commits done in the branch named 'maintenance' tool.

When the text is updated to reflect changes in the tools, scripts, or dependencies, the commit type `maintenance` is used.
When the text is updated to fix typos, grammatical errors, or formatting issues, the commit type `fix` is used.
When the text is updated to improve clarity, readability, or flow, the commit type `review` is used.
When content is removed from the book, such as outdated information or redundant sections, the commit type `review` is used.

### Co-author trailer
When a commit contains code that Claude changed, the commit message ends with this trailer:

```
Co-authored-by: Claude <noreply@anthropic.com>
```

A commit without code changed by Claude carries no trailer.

### Commit scope and type for specific files

**Copilot-related files:**
- Files: `copilot-instructions.md`, `git-commit-instructions.md`, and other copilot configuration files
- Type: `chore`
- Scope: `copilot`
- Example: `chore(copilot): update commit message guidelines`

**Workflow-related files:**
- Files: `.github/workflows/*.yml`, `.github/workflows/*.yaml`
- Type: `fix` (when fixing bugs) or `chore` (for other changes)
- Scope: `workflow`
- Examples:
  - `fix(workflow): correct sed regex in changelog extraction`
  - `chore(workflow): add annotation for book content changes`

### Critical: Never use `---` in commit messages

**NEVER use `---` (three dashes) in commit messages.**

The sequence `---` is a Git scissors line convention that causes all content below it to be automatically removed from the commit message.
This is a standard Git feature that cannot be disabled without breaking convention.

When generating commit messages:
- ✅ Use single dash `-` for lists
- ✅ Use `##` for section headers
- ✅ Use blank lines to separate sections
- ❌ **NEVER** use `---` (three dashes on a line) anywhere in the commit message
- ❌ Avoid horizontal rules or dividers with three or more dashes

Example of problematic commit message:
```
feat: add new chapter

Changes made:
- Added content
- Updated references

---

Additional notes that will be LOST
```

The "Additional notes" section will be automatically removed by Git.
