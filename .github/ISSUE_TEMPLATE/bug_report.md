---
name: Bug report
about: Something does not work as documented, or fails
title: ''
labels: bug
assignees: ''

---

<!-- Search existing issues first. Never include secrets or real data: redact them from logs and config. -->

## Summary
A clear and concise description of the bug.

## Steps to reproduce
1. Configuration used (properties changed from the defaults, with any secrets redacted):
2. Command run, eg `./afon.sh -a manage-stack deploy aws-full`:
3. ...

## Expected behaviour
What you expected to happen.

## Actual behaviour
What actually happened, including the full error message.

## Logs
Relevant extract of `$AFON_HOME/logs/afon-core.log`, or of the console output, with secrets redacted.

<details><summary>Log</summary>

```
paste log here
```

</details>

## Environment
- Afon version or commit: (eg `afon-cli` version, or `git rev-parse --short HEAD`)
- How Afon is run: (source build with `afon.sh` / `afon-cli` distribution / Docker image)
- OS: (eg macOS 15, Ubuntu 24.04)
- Java version: (`java -version`)
- Cloud(s): (aws / azure / gcp)
- Stack(s): (eg `aws-full`, `aws-polaris`)
- Component(s): (eg Polaris, Keycloak, Spark Connect, Trino, Jupyter, CLI)

## Workaround
Any workaround you found, if any.

## Additional context
Anything else that may help, eg whether it worked in a previous version.
