# Contributing to sinew-kotlin

Thanks for helping improve the Kotlin implementation of Sinew! This repository uses **trunk-based development**, like the rest of the Sinew repositories: `main` is the only long-lived branch, every change reaches it through a squash-merged pull request, and releases are tags on `main`. The shared rules are on the [Git workflow](https://sinew-dev.srctool.com/contributing/git-workflow) page.

## Getting started
- JDK 17 and the Gradle wrapper in this repository (`./gradlew`).
- IntelliJ IDEA or Android Studio; Xcode on macOS for the iOS targets.

## Development workflow
1. Branch from the latest `main`, named `<type>/<short-description>` (for example `feat/pager-cursor-strategy`, `fix/auth-refresh-race`).
   - Maintainers push branches to `srctool/sinew-kotlin`; everyone else forks it.
2. Make small, focused commits.
3. Before opening the PR, run:
- Build and tests: `./gradlew check checkSinewGraph` (on macOS, `check` also runs the iOS simulator tests)
- Format with the project's formatter (ktfmt/ktlint when configured) or the IDE's default Kotlin style.
4. Update the README or docs if behavior changes.
5. Open a pull request **into `main` of this repository** and fill out the template. If `main` moves while it's open, update your branch (`git pull --rebase origin main`, or the PR's **Update branch** button).

### PR title format (Conventional Commits)
Format your PR title as:

```
<type>(<scope>): <short description>
```

- type: one of `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`
- scope: the affected module or package (for example `sinew-network`, `sinew-paging`, `bom`, or `ci`)
- short description: a concise summary

GitKraken tip: GitKraken uses the first line of the commit message as the PR title, so you can use this format when committing.

## Merging policy
- Squash and merge only, into the protected `main`. Merge commits and rebase merges aren't used, and nobody pushes or force-pushes to `main` directly.
- The squashed commit's title is the PR title and its body is the PR description, so keep both clear: what changed and why. Maintainers may edit the final message.
- Branches are deleted automatically after the merge.

## Releases
A release is a tag on `main`: `vX.Y.Z` (for example `v0.3.0`).

```bash
git switch main && git pull
git tag v0.3.0 && git push origin v0.3.0
```

The publish workflow checks the tag is on `main`, publishes `com.srctool.sinew:*` to Maven Central from a single macOS job (the iOS artifacts need Xcode), then creates the GitHub Release. The tag decides the version.

Before tagging, merge a PR that updates the version and `CHANGELOG.md`. A fix to a released version is an ordinary PR into `main` followed by a patch tag. After a release, the umbrella repository (`srctool/sinew`) picks up the new commit through its Dependabot submodule PR.

## Testing
- Add or adjust tests for new or changed behavior, and make sure `./gradlew check checkSinewGraph` passes.
- Prefer deterministic tests; avoid relying on clock/time or networking unless mocked.

## Commit & PR guidelines
- Write clear commit messages and PR descriptions; link issues (for example "Fixes #123").
- Keep PRs focused and reasonably small.
- Include screenshots or logs for visible or behavioral changes when helpful.

## Code of Conduct
This project adheres to the Contributor Covenant.
See `CODE_OF_CONDUCT.md`. For sensitive reports, email contact@srctool.com.

## License
Contributions to `sinew-kotlin` are made under the Apache 2.0 license found in `LICENSE`.

Thank you for contributing!
