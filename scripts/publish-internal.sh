#!/usr/bin/env bash

set -euo pipefail

usage() {
    echo "Usage: $0 <version-code> <version-name> [draft|completed]" >&2
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
    usage
    exit 2
fi

release_version_code="$1"
release_version_name="$2"
release_status="${3:-completed}"
config_root="${XDG_CONFIG_HOME:-${HOME}/.config}"
publisher_credentials="${GOOGLE_PLAY_SERVICE_ACCOUNT_JSON:-${config_root}/chattermore/play-publisher.json}"

if [[ ! "$release_version_code" =~ ^[1-9][0-9]*$ ]]; then
    echo "Version code must be a positive integer." >&2
    exit 2
fi

if [[ -z "$release_version_name" ]]; then
    echo "Version name must not be empty." >&2
    exit 2
fi

if [[ "$release_status" != "draft" && "$release_status" != "completed" ]]; then
    echo "Release status must be either 'draft' or 'completed'." >&2
    exit 2
fi

if [[ ! -r "$publisher_credentials" ]]; then
    echo "Google Play credentials are not readable at: $publisher_credentials" >&2
    echo "Set GOOGLE_PLAY_SERVICE_ACCOUNT_JSON to the service-account JSON path." >&2
    exit 1
fi

credential_permissions="$(stat -f '%Lp' "$publisher_credentials")"
if [[ "$credential_permissions" != "600" ]]; then
    echo "Google Play credentials must have 0600 permissions; found ${credential_permissions}." >&2
    exit 1
fi

repository_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repository_root"

git diff --check

GOOGLE_PLAY_SERVICE_ACCOUNT_JSON="$publisher_credentials" \
    ./gradlew \
        testDebugUnitTest \
        lintDebug \
        :app:bundleRelease \
        -PchattermoreVersionCode="$release_version_code" \
        -PchattermoreVersionName="$release_version_name" \
        --console=plain

release_archive="$repository_root/.release-artifacts/${release_version_name}-${release_version_code}"
mkdir -p "$release_archive"
cp app/build/outputs/bundle/release/app-release.aab "$release_archive/"
cp app/build/outputs/mapping/release/mapping.txt "$release_archive/"
shasum -a 256 "$release_archive/app-release.aab" > "$release_archive/app-release.aab.sha256"

GOOGLE_PLAY_SERVICE_ACCOUNT_JSON="$publisher_credentials" \
    ./gradlew \
        :app:publishReleaseBundle \
        -PchattermoreVersionCode="$release_version_code" \
        -PchattermoreVersionName="$release_version_name" \
        --track internal \
        --release-status "$release_status" \
        --console=plain
