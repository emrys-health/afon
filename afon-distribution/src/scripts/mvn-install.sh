#!/usr/bin/env bash
set -euo pipefail

# Installs afon artifacts in local mvn repository
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
echo "Installing in local mvn repository afon artifacts for version $VERSION"
for file in afon-*-$VERSION.jar; do
  echo "Installing $file"
  mvn -q install:install-file -Dfile=$file
done
