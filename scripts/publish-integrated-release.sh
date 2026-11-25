#!/usr/bin/env bash
set -Eeuo pipefail

: "\${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "\${GH_TOKEN:?GH_TOKEN is required}"
: "\${RELEASE_TAG:?RELEASE_TAG is required}"
: "\${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "\${TARGET_SHA:?TARGET_SHA is required}"

API="https://api.github.com/repos/\${GITHUB_REPOSITORY}"
UPLOAD_API="https://uploads.github.com/repos/\${GITHUB_REPOSITORY}"
API_VERSION="2022-11-28"
BACKOFF=(5 10 20 40 60)
ASSETS=(
  "\${APK_PATH:?APK_PATH is required}"
  "release-output/moving-out-2-runtime-arm64-v8a.zip"
  "release-output/moving-out-2-runtime-arm64-v8a.zip.sha256"
  "release-output/SHA256SUMS.txt"
)
NOTES="Install the APK, connect to the internet, and launch. The app downloads and validates the matching runtime from this same release. Import your own legally obtained game files. Alpha APK uses an ephemeral CI debug signing key; uninstall an older build if Android rejects an update. No game files are included. Physical-device launch and graphics compatibility still require testing."

api() {
  gh api -H "X-GitHub-Api-Version: $API_VERSION" "$@"
}

list_releases() {
  api --paginate --slurp "repos/\${GITHUB_REPOSITORY}/releases?per_page=100" | jq -s 'add // []'
}

list_assets() {
  api --paginate --slurp "repos/\${GITHUB_REPOSITORY}/releases/$RELEASE_ID/assets?per_page=100" | jq -s 'add // []'
}

delete_asset() {
  local asset_id="$1"
  echo "Deleting invalid asset ID $asset_id"
  api --method DELETE "repos/\${GITHUB_REPOSITORY}/releases/assets/$asset_id" >/dev/null
}

find_asset() {
  local name="$1"
  list_assets | jq -c --arg name "$name" '[.[] | select(.name == $name)] | first // empty'
}

asset_is_complete() {
  local asset_json="$1" local_size="$2" local_file="$3"
  local local_digest remote_digest
  [[ -n "$asset_json" ]] || return 1
  [[ "$(jq -r '.state // ""' <<<"$asset_json")" == "uploaded" ]] || return 1
  [[ "$(jq -r '.size // -1' <<<"$asset_json")" == "$local_size" ]] || return 1
  (( local_size > 0 )) || return 1
  local_digest="sha256:$(sha256sum "$local_file" | awk '{print $1}')"
  remote_digest="$(jq -r '.digest // ""' <<<"$asset_json")"
  [[ "$remote_digest" == "$local_digest" ]]
}

cleanup_old_releases_and_tags() {
  local releases release_id tag
  echo "Listing all releases, including drafts; preserving target tag $RELEASE_TAG."
  releases="$(list_releases)"
  while IFS=$'\t' read -r release_id tag; do
    [[ -n "$release_id" && -n "$tag" ]] || continue
    [[ "$tag" == "$RELEASE_TAG" ]] && continue
    echo "Removing obsolete release $tag (ID $release_id)"
    api --method DELETE "repos/\${GITHUB_REPOSITORY}/releases/$release_id" >/dev/null
  done < <(jq -r --arg target "$RELEASE_TAG" '.[] | select(.tag_name != $target) | [.id, .tag_name] | @tsv' <<<"$releases")

  local refs
  refs="$(api --paginate --slurp "repos/\${GITHUB_REPOSITORY}/git/matching-refs/tags/" | jq -s 'add // []')"
  while IFS= read -r tag; do
    [[ -n "$tag" && "$tag" != "$RELEASE_TAG" ]] || continue
    echo "Removing obsolete Git tag $tag"
    api --method DELETE "repos/\${GITHUB_REPOSITORY}/git/refs/tags/$tag" >/dev/null
  done < <(jq -r '.[].ref | sub("^refs/tags/"; "")' <<<"$refs")
}

consolidate_target_release() {
  local releases matches count
  releases="$(list_releases)"
  matches="$(jq -c --arg tag "$RELEASE_TAG" '[.[] | select(.tag_name == $tag)] | sort_by(.created_at) | reverse' <<<"$releases")"
  count="$(jq 'length' <<<"$matches")"

  if (( count > 1 )); then
    echo "Found $count releases for $RELEASE_TAG; retaining newest and deleting duplicates."
    while IFS= read -r duplicate_id; do
      [[ -n "$duplicate_id" ]] || continue
      echo "Deleting duplicate target release ID $duplicate_id"
      api --method DELETE "repos/\${GITHUB_REPOSITORY}/releases/$duplicate_id" >/dev/null
    done < <(jq -r '.[1:][] | .id' <<<"$matches")
  fi

  if (( count >= 1 )); then
    RELEASE_ID="$(jq -r '.[0].id' <<<"$matches")"
    echo "Using the single existing release ID $RELEASE_ID for $RELEASE_TAG"
  else
    echo "No release exists for $RELEASE_TAG; creating exactly one draft."
    local payload response
    payload="$(jq -n \
      --arg tag "$RELEASE_TAG" \
      --arg target "$TARGET_SHA" \
      --arg title "Moving Out 2 Android $RELEASE_VERSION (Integrated Pre-release)" \
      --arg body "$NOTES" \
      '{tag_name:$tag,target_commitish:$target,name:$title,body:$body,draft:true,prerelease:true}')"
    response="$(api --method POST "repos/\${GITHUB_REPOSITORY}/releases" --input - <<<"$payload")"
    RELEASE_ID="$(jq -r '.id // empty' <<<"$response")"
    [[ "$RELEASE_ID" =~ ^[0-9]+$ ]] || { echo "Release creation returned no valid ID: $response" >&2; exit 1; }
    echo "Created draft release ID $RELEASE_ID"
  fi
}

upload_one_asset() {
  local file="$1" name local_size asset_json asset_id attempt status body response url encoded_name
  name="$(basename "$file")"
  local_size="$(stat -c%s "$file")"
  (( local_size > 0 )) || { echo "Refusing to upload empty asset: $file" >&2; return 1; }

  asset_json="$(find_asset "$name")"
  if asset_is_complete "$asset_json" "$local_size" "$file"; then
    echo "SKIP $name: already uploaded, size=$local_size"
    return 0
  fi
  if [[ -n "$asset_json" ]]; then
    asset_id="$(jq -r '.id' <<<"$asset_json")"
    echo "Existing asset $name is incomplete/invalid; deleting before retry."
    delete_asset "$asset_id"
  fi

  encoded_name="$(jq -rn --arg value "$name" '$value|@uri')"
  url="$UPLOAD_API/releases/$RELEASE_ID/assets?name=$encoded_name"
  for attempt in 1 2 3 4 5 6; do
    body="$(mktemp)"
    status="$(curl --silent --show-error --location \
      --output "$body" --write-out '%{http_code}' \
      --request POST \
      --header "Authorization: Bearer $GH_TOKEN" \
      --header "Accept: application/vnd.github+json" \
      --header "X-GitHub-Api-Version: $API_VERSION" \
      --header "Content-Type: application/octet-stream" \
      --data-binary "@$file" "$url" || printf '000')"

    if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      rm -f "$body"
      asset_json="$(find_asset "$name")"
      if asset_is_complete "$asset_json" "$local_size"; then
        echo "Uploaded and verified $name ($local_size bytes)"
        return 0
      fi
      echo "Upload returned HTTP $status but asset verification failed for $name." >&2
    elif [[ "$status" == "422" ]]; then
      echo "HTTP 422 for $name; re-querying release assets before deciding." >&2
      cat "$body" >&2
      asset_json="$(find_asset "$name")"
      if asset_is_complete "$asset_json" "$local_size"; then
        rm -f "$body"
        echo "Asset $name was accepted despite HTTP 422; skipping duplicate upload."
        return 0
      fi
      if [[ -n "$asset_json" ]]; then
        asset_id="$(jq -r '.id' <<<"$asset_json")"
        delete_asset "$asset_id"
      fi
    elif [[ "$status" == "000" || "$status" == "408" || "$status" == "429" || "$status" == "500" || "$status" == "502" || "$status" == "503" || "$status" == "504" ]]; then
      echo "Transient upload failure for $name: HTTP $status (attempt $attempt/6)" >&2
      cat "$body" >&2
    else
      echo "Non-retryable upload failure for $name: HTTP $status" >&2
      cat "$body" >&2
      rm -f "$body"
      return 1
    fi
    rm -f "$body"
    if (( attempt < 6 )); then
      sleep "\${BACKOFF[$((attempt - 1))]}"
    fi
  done
  echo "Exhausted six upload attempts for $name." >&2
  return 1
}

verify_and_publish() {
  local assets name file local_size asset_json
  assets="$(list_assets)"
  for file in "\${ASSETS[@]}"; do
    name="$(basename "$file")"
    local_size="$(stat -c%s "$file")"
    asset_json="$(jq -c --arg name "$name" '[.[] | select(.name == $name)] | first // empty' <<<"$assets")"
    if ! asset_is_complete "$asset_json" "$local_size"; then
      echo "Release verification failed: $name missing, empty, incomplete, or size mismatch." >&2
      echo "Expected local size: $local_size; remote asset: \${asset_json:-<missing>}" >&2
      return 1
    fi
    echo "VERIFIED $name size=$local_size state=uploaded"
  done

  local count
  count="$(jq 'length' <<<"$assets")"
  (( count >= 4 )) || { echo "Expected at least 4 assets, found $count." >&2; return 1; }

  echo "All four required assets verified. Publishing release ID $RELEASE_ID."
  api --method PATCH "repos/\${GITHUB_REPOSITORY}/releases/$RELEASE_ID" \
    -f draft=false -F prerelease=true >/dev/null

  local final
  final="$(api "repos/\${GITHUB_REPOSITORY}/releases/$RELEASE_ID")"
  [[ "$(jq -r '.draft' <<<"$final")" == "false" ]]
  [[ "$(jq -r '.prerelease' <<<"$final")" == "true" ]]
  [[ "$(jq -r '.tag_name' <<<"$final")" == "$RELEASE_TAG" ]]
  echo "$final" | jq '{id,tag_name,draft,prerelease,published_at,assets:[.assets[]|{name,state,size,digest,browser_download_url}]}'
}

main() {
  for file in "\${ASSETS[@]}"; do
    test -s "$file"
  done
  cleanup_old_releases_and_tags
  consolidate_target_release
  for file in "\${ASSETS[@]}"; do
    upload_one_asset "$file"
  done
  verify_and_publish
  echo "Release publication completed successfully: $RELEASE_TAG (ID $RELEASE_ID)"
}

main "$@"
