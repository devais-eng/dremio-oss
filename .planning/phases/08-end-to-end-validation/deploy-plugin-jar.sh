#!/usr/bin/env bash
# Phase 8 Task 1: Deploy the Phase 7 icebergcatalog plugin JAR to the distribution directory.
#
# The Maven target/ directory is .gitignore'd, so this script documents the deployment
# command for reproducibility. Run this whenever the distribution JAR is stale relative
# to plugins/icebergcatalog/target/.
#
# Prerequisites:
#   - Maven build has been run: mvn package -pl plugins/icebergcatalog -DskipTests
#   - Distribution exists at DIST (unpacked via prior full Maven build or download)

set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
VERSION="26.0.5-202509091642240013-f5051a07"
DIST_NAME="dremio-community-${VERSION}"

SRC_JAR="${REPO_ROOT}/plugins/icebergcatalog/target/dremio-icebergcatalog-plugin-${VERSION}.jar"
DIST_JARS="${REPO_ROOT}/distribution/server/target/${DIST_NAME}/${DIST_NAME}/jars"
DEST_JAR="${DIST_JARS}/dremio-icebergcatalog-plugin-${VERSION}.jar"

echo "Source JAR: ${SRC_JAR}"
echo "Destination: ${DEST_JAR}"

if [ ! -f "${SRC_JAR}" ]; then
  echo "ERROR: Source JAR not found. Run: mvn package -pl plugins/icebergcatalog -DskipTests"
  exit 1
fi

if [ ! -d "${DIST_JARS}" ]; then
  echo "ERROR: Distribution jars/ directory not found at ${DIST_JARS}"
  echo "The Dremio distribution must be built or extracted first."
  exit 1
fi

cp "${SRC_JAR}" "${DEST_JAR}"
echo "Deployed. Verifying Phase 7 artifacts..."
jar tf "${DEST_JAR}" | grep -E 'restcatalog-layout\.json|RESTCATALOG\.svg'
echo "OK — Phase 7 artifacts confirmed in distribution JAR."
