# Contributing

## Code of Conduct

All members of the project community must abide by the [SAP Open Source Code of Conduct](https://github.com/SAP/.github/blob/main/CODE_OF_CONDUCT.md).
Only by respecting each other we can develop a productive, collaborative community.
Instances of abusive, harassing, or otherwise unacceptable behavior may be reported by contacting [a project maintainer](REUSE.toml).

## Engaging in Our Project

We use GitHub to manage reviews of pull requests.

* If you are a new contributor, see: [Steps to Contribute](#steps-to-contribute)

* Before implementing your change, create an issue that describes the problem you would like to solve or the code that should be enhanced. Please note that you are willing to work on that issue.

* The team will review the issue and decide whether it should be implemented as a pull request. In that case, they will assign the issue to you. If the team decides against picking up the issue, the team will post a comment with an explanation.

## Steps to Contribute

Should you wish to work on an issue, please claim it first by commenting on the GitHub issue that you want to work on. This is to prevent duplicated efforts from other contributors on the same issue.

If you have questions about one of the issues, please comment on them, and one of the maintainers will clarify.

## Contributing Code or Documentation

You are welcome to contribute code in order to fix a bug or to implement a new feature that is logged as an issue.

The following rule governs code contributions:

* Contributions must be licensed under the [Apache 2.0 License](./LICENSE).
* Due to legal reasons, contributors will be asked to accept a Developer Certificate of Origin (DCO) when they create the first pull request to this project. This happens in an automated fashion during the submission process. SAP uses [the standard DCO text of the Linux Foundation](https://developercertificate.org/).
* Contributions must follow our [guidelines on AI-generated code](https://github.com/SAP/.github/blob/main/CONTRIBUTING_USING_GENAI.md) in case you are using such tools.

## Local verification

Use Java 17 or later and run `./gradlew test --rerun-tasks` for fresh module results.
Published classes must target Java 17 and compile against its API. Build publications and Javadoc
locally with `stageReadinessArtifacts verifyReadinessJavadoc`; artifact verification uses an explicit
non-SNAPSHOT test version as described in [artifact versioning](docs/artifact-versioning.md).
`verifyReadinessDocumentation` checks the extracted configuration surface, Javadoc coverage and
Markdown links. New public options need default, meaning and schema entries in the reference.
Run `reuse lint` and `git diff --check` before requesting review. Do not include local coverage maps,
credentials, signing keys or machine-specific evidence in product changes.

## Issue reports

* We use GitHub issues to track bugs and enhancement requests.

* Please provide as much context as possible when you open an issue. The information you provide must be comprehensive enough to reproduce that issue for the assignee.
