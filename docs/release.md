# Afon Release

## Maven release 

The Maven release is performed by executing the script:
```shell
./release.sh
```
It uses the [maven-release-plugin](https://maven.apache.org/maven-release/maven-release-plugin/) to 
perform create a git tag with a fixed release version and build the released components off of the tag.

It requires three inputs:
```shell
Version:    release version (semver)
Qualifier:  stable|preview
Next:       next snapshot version
```

For example, to release a preview version but remain on the same SNAPSHOT version: 
```shell
./release 1.0-beta-1 preview 1.0-SNAPSHOT
```

To release a stable version and move to the next SNAPSHOT version:
```shell
./release 1.0 stable 1.1-SNAPSHOT
```

**NOTE:** For the moment, the deploy stage of the release to a Repository Manager is disabled,
until we sort out where it will be deployed to and with which credentials.