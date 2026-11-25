#!/usr/bin/env bash
# Minimal S3 client on curl (>= 7.75, --aws-sigv4): upload, download, list and delete objects on any
# S3-compatible store (Cloudflare R2, SeaweedFS, AWS) without extra tools. Sourced by backup-postgres.sh
# and restore-drill.sh; not meant to be run directly.
#
# Required variables: S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY. Optional: S3_REGION (default auto).
# Credentials are passed to curl through a private config file (never on the command line, so they do not
# show in `ps`), created lazily; call s3_cleanup from your EXIT trap.

S3_REGION="${S3_REGION:-auto}"
_S3_CURL_CONFIG=""

s3_require() {
  local name
  for name in S3_ENDPOINT S3_BUCKET S3_ACCESS_KEY S3_SECRET_KEY; do
    if [ -z "${!name:-}" ]; then
      echo "error: $name is not set" >&2
      return 2
    fi
  done
}

_s3_config() {
  if [ -z "$_S3_CURL_CONFIG" ]; then
    _S3_CURL_CONFIG="$(mktemp)"
    chmod 600 "$_S3_CURL_CONFIG"
    printf 'user = "%s:%s"\n' "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >"$_S3_CURL_CONFIG"
  fi
}

# Call from the caller's EXIT trap: removes the temporary curl credentials file.
s3_cleanup() {
  [ -n "$_S3_CURL_CONFIG" ] && rm -f "$_S3_CURL_CONFIG"
  _S3_CURL_CONFIG=""
}

_s3_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# s3_put <local file> <key>
s3_put() {
  _s3_config
  local hash
  hash="$(_s3_sha256 "$1")"
  curl --fail-with-body --silent --show-error --retry 3 --retry-delay 2 \
    --config "$_S3_CURL_CONFIG" --aws-sigv4 "aws:amz:${S3_REGION}:s3" \
    -H "x-amz-content-sha256: ${hash}" \
    -T "$1" "${S3_ENDPOINT%/}/${S3_BUCKET}/$2" >/dev/null
}

# s3_get <key> [output file]  (stdout when no file is given)
s3_get() {
  _s3_config
  local out=(--output -)
  [ -n "${2:-}" ] && out=(--output "$2")
  curl --fail-with-body --silent --show-error --retry 3 --retry-delay 2 \
    --config "$_S3_CURL_CONFIG" --aws-sigv4 "aws:amz:${S3_REGION}:s3" \
    "${out[@]}" "${S3_ENDPOINT%/}/${S3_BUCKET}/$1"
}

# s3_delete <key>
s3_delete() {
  _s3_config
  curl --fail-with-body --silent --show-error --retry 3 --retry-delay 2 -X DELETE \
    --config "$_S3_CURL_CONFIG" --aws-sigv4 "aws:amz:${S3_REGION}:s3" \
    "${S3_ENDPOINT%/}/${S3_BUCKET}/$1" >/dev/null
}

# s3_list <prefix>  -> one key per line, sorted (follows continuation tokens)
s3_list() {
  _s3_config
  local prefix="$1" token="" page keys
  while :; do
    local query="list-type=2&prefix=${prefix}"
    [ -n "$token" ] && query="${query}&continuation-token=${token}"
    page="$(curl --fail-with-body --silent --show-error --retry 3 --retry-delay 2 \
      --config "$_S3_CURL_CONFIG" --aws-sigv4 "aws:amz:${S3_REGION}:s3" \
      "${S3_ENDPOINT%/}/${S3_BUCKET}?${query}")" || return 1
    keys="$(printf '%s' "$page" | grep -o '<Key>[^<]*</Key>' | sed -e 's/<Key>//' -e 's/<\/Key>//' || true)"
    [ -n "$keys" ] && printf '%s\n' "$keys"
    if printf '%s' "$page" | grep -q '<IsTruncated>true</IsTruncated>'; then
      token="$(printf '%s' "$page" | grep -o '<NextContinuationToken>[^<]*</NextContinuationToken>' |
        sed -e 's/<NextContinuationToken>//' -e 's/<\/NextContinuationToken>//')"
      [ -z "$token" ] && break
    else
      break
    fi
  done | sort
}
