#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }

for required in FIREBASE_JSON ENV_FILE CLOUDFLARE_TOKEN GITHUB_SHA GITHUB_RUN_ID GITHUB_RUN_ATTEMPT; do
  [[ -n "${!required:-}" ]] || fail "Missing required value: $required"
done
[[ "$GITHUB_SHA" =~ ^[a-f0-9]{40}$ && "$GITHUB_RUN_ID" =~ ^[0-9]+$ && "$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+$ ]] ||
  fail 'Invalid release identifier.'
[[ "$(id -u)" != 0 ]] || fail 'The deployment runner must use a non-root account.'

release_root="$HOME/.local/share/ptitgo-backend/releases"
install -d -m 700 "$release_root"
release_dir=$(mktemp -d "$release_root/$GITHUB_SHA-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT.XXXXXX")
export DATXE_DEPLOY_ENV_FILE="$release_dir/backend.env"
export DATXE_FIREBASE_FILE="$release_dir/firebase.json"
export DATXE_BACKEND_IMAGE="ptitgo-backend:$GITHUB_SHA"
export DATXE_DEPLOY_UID=$(id -u)
export DATXE_DEPLOY_GID=$(id -g)

printf '%s\n' "$ENV_FILE" > "$DATXE_DEPLOY_ENV_FILE"
printf '%s\n' "$FIREBASE_JSON" > "$DATXE_FIREBASE_FILE"
unset ENV_FILE FIREBASE_JSON

python3 - "$DATXE_FIREBASE_FILE" <<'PY' || fail 'Invalid Firebase service-account JSON.'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as file:
        account = json.load(file)
    if not isinstance(account, dict) or account.get("type") != "service_account":
        sys.exit(1)
    for key in ("project_id", "client_email", "private_key"):
        if not isinstance(account.get(key), str) or not account[key].strip():
            sys.exit(1)
    private_key = account["private_key"].strip()
    if not private_key.startswith("-----BEGIN PRIVATE KEY-----") or not private_key.endswith(
        "-----END PRIVATE KEY-----"
    ):
        sys.exit(1)
except (OSError, ValueError):
    sys.exit(1)
PY

if grep -Eq '^[[:space:]]*(export[[:space:]]+)?CLOUDFLARE_TOKEN[[:space:]]*=' "$DATXE_DEPLOY_ENV_FILE"; then
  printf '%s\n' 'ENV_FILE also defines CLOUDFLARE_TOKEN. Compose uses the dedicated secret.'
fi

project=''
for container in datxe_postgres datxe_redis spring_boot_backend cloudflare-tunnel; do
  if docker container inspect "$container" >/dev/null 2>&1; then
    owner=$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$container")
    [[ -n "$owner" && "$owner" != '<no value>' ]] || fail 'Existing container is outside the Compose project.'
    [[ -z "$project" || "$owner" == "$project" ]] || fail 'Existing containers belong to different Compose projects.'
    project=$owner
  fi
done
if [[ -z "$project" ]]; then
  grep -Eq '^[[:space:]]*(export[[:space:]]+)?COMPOSE_PROJECT_NAME[[:space:]]*=' "$DATXE_DEPLOY_ENV_FILE" ||
    fail 'Set the verified COMPOSE_PROJECT_NAME explicitly when no existing containers identify the project.'
  project=$(docker compose --env-file "$DATXE_DEPLOY_ENV_FILE" -f docker-compose.yml \
    -f docker-compose.production.yml config --format json 2>/dev/null |
    python3 -c 'import json, sys; print(json.load(sys.stdin)["name"])' 2>/dev/null) ||
    fail 'Unable to resolve the configured Compose project.'
fi
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail 'Invalid Compose project.'

compose=(docker compose --project-name "$project" --env-file "$DATXE_DEPLOY_ENV_FILE"
  -f docker-compose.yml -f docker-compose.production.yml)
if ! "${compose[@]}" config --quiet 2>/dev/null; then
  fail 'Compose validation failed. Verify release configuration without printing secrets.'
fi

capture_root=$("${compose[@]}" config --format json 2>/dev/null | python3 -c '
import json
import sys
backend = json.load(sys.stdin)["services"]["backend"]
if not str(backend["environment"].get("DATXE_VERIFICATION_ENCRYPTION_KEY") or "").strip():
    sys.exit(1)
for volume in backend["volumes"]:
    if volume["target"] == "/var/lib/ptitgo/verification":
        print(volume["source"])
        break
else:
    sys.exit(1)
' 2>/dev/null) || fail 'Production verification requires a capture mount and an encryption key.'
[[ "$capture_root" == /* && -d "$capture_root" ]] || fail 'Provision the capture directory before deploying.'
capture_root=$(realpath -e "$capture_root")
source_root=$(realpath -e "${GITHUB_WORKSPACE:-$PWD}")
[[ "$capture_root" != / && "$capture_root" != "$source_root" && "$capture_root" != "$source_root/"* ]] ||
  fail 'Verification captures must live outside the source tree.'
[[ "$(stat -c '%u:%a' "$capture_root")" == "$DATXE_DEPLOY_UID:700" ]] ||
  fail 'The capture directory must be owned by the deployment user with mode 0700.'

python3 - "$capture_root" <<'PY' || fail 'The capture directory contains an unreadable or legacy object. Complete the approved storage cutover first.'
import os
import sys

try:
    with os.scandir(sys.argv[1]) as entries:
        for entry in entries:
            if not entry.is_file(follow_symlinks=False):
                sys.exit(1)
            with open(entry.path, "rb") as image:
                if image.read(4) != b"DXE1":
                    sys.exit(1)
except OSError:
    sys.exit(1)
PY

previous_image=''
if docker container inspect spring_boot_backend >/dev/null 2>&1; then
  previous_mount=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/ptitgo/verification"}}{{.Source}}{{end}}{{end}}' spring_boot_backend)
  [[ "$previous_mount" == "$capture_root" ]] ||
    fail 'Existing backend uses a different capture location. Complete the approved storage cutover first.'
  previous_image=$(docker inspect --format '{{.Image}}' spring_boot_backend)
  docker image tag "$previous_image" "ptitgo-backend:rollback-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
fi

"${compose[@]}" build backend
"${compose[@]}" run --rm --no-deps --entrypoint sh backend -ec \
  'test -r /run/secrets/firebase.json && test -w /var/lib/ptitgo/verification' ||
  fail 'The runtime user cannot access the Firebase file or capture directory.'

printf '%s\n' "$project" "$DATXE_BACKEND_IMAGE" "$previous_image" > "$release_dir/release"
"${compose[@]}" up -d --wait --wait-timeout 180 --no-recreate postgres redis
"${compose[@]}" up -d --no-deps --no-build --wait --wait-timeout 180 backend
curl --fail --silent --show-error --max-time 10 --output /dev/null http://127.0.0.1:8080/actuator/health
"${compose[@]}" up -d --no-deps cloudflared
