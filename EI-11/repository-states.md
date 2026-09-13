# Repository states

## Initial

- STP: branch research/asm-codex, HEAD 15ab0a8728bcbd8c57fb63a807a05a3df7910e73, only preserved EI-9/ untracked.
- Jenkins plugin: branch main, HEAD a1319cd0041099452a371e55d943d34687799b19, clean.
- SonarJava: branch master, HEAD 9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c, clean.

Both expected EI-10 commits existed. SonarJava was a valid Maven multimodule checkout. R0 was fixed at its initial clean commit.

## Production commits

STP:

- 8ddc73d Fix Maven skipped-test execution accounting
- d954211 Fix Maven schema-v3 evidence metadata
- 11e1cde Preserve Maven ownership in explicit selection

Jenkins plugin:

- 48d8954 Fix Maven schema-v3 adapter coordinate
- 5e86477 Install SonarJava Java 26 toolchain on Docker agents
- d34a08e Preserve Maven schema-v3 execution evidence
- b446df5 Package EI-11 Maven ownership artifacts

SonarJava disposable branch:

- eed6922c776e5d029266459d04ed5a69e9d6fc8f Add EI-11 diagnostic output

## Pre-cleanup

- STP HEAD: 11e1cdeaae7951137c56f1ffc3008c066e0adc64; EI-11/ and preserved EI-9/ untracked before the evidence commit.
- Jenkins plugin HEAD: b446df5a355b92a1a893de66aae7e598ee0c8bcd; clean.
- SonarJava HEAD: eed6922c776e5d029266459d04ed5a69e9d6fc8f on ei-11-r1; clean.
- No push command was performed.

Final post-cleanup state is recorded in docker-cleanup.md and machine-summary.json.
