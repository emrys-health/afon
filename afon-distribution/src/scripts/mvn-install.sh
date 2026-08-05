#!/usr/bin/env bash
set -euo pipefail

# Until the afon artifacts are published in repository manager
# Installs afon artifacts in local maven repository
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
echo "Installing afon artifacts in local maven repository"
for file in afon-*-$VERSION.jar; do
  echo "Installing $file"
  mvn -q install:install-file -Dfile=$file
done
