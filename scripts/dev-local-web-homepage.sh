#!/usr/bin/env bash

set -Eeuo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_ENV_FILE="$REPOSITORY_ROOT/.env"
WEB_PORT="${WEB_PORT:-3000}"
HOMEPAGE_PORT="${HOMEPAGE_PORT:-4321}"
PUBLIC_WORKSPACE_URL="${PUBLIC_WORKSPACE_URL:-http://127.0.0.1:${WEB_PORT}}"
web_pid=""
homepage_pid=""

if [[ ! -f "$COMPOSE_ENV_FILE" ]]; then
  COMPOSE_ENV_FILE="$REPOSITORY_ROOT/.env.example"
fi

compose() {
  docker compose --env-file "$COMPOSE_ENV_FILE" -f "$REPOSITORY_ROOT/infra/docker-compose.yml" "$@"
}

# shellcheck disable=SC2329 # Invoked through the EXIT trap.
cleanup() {
  local exit_status=$?
  trap - EXIT INT TERM

  for pid in "$web_pid" "$homepage_pid"; do
    if [[ -n "$pid" ]]; then
      kill "$pid" 2>/dev/null || true
    fi
  done

  for pid in "$web_pid" "$homepage_pid"; do
    if [[ -n "$pid" ]]; then
      wait "$pid" 2>/dev/null || true
    fi
  done

  exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for command_name in docker make mise curl; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'Required command not found: %s\n' "$command_name" >&2
    exit 1
  fi
done

if [[ ! -x "$REPOSITORY_ROOT/web/node_modules/.bin/next" || ! -x "$REPOSITORY_ROOT/homepage/node_modules/.bin/astro" ]]; then
  printf 'Install both frontend dependencies first:\n  mise exec -- pnpm --dir web install --frozen-lockfile\n  mise exec -- pnpm --dir homepage install --frozen-lockfile\n' >&2
  exit 1
fi

for port in "$WEB_PORT" "$HOMEPAGE_PORT"; do
  if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
    printf 'Ports must be integers from 1 to 65535. Received: %s\n' "$port" >&2
    exit 2
  fi
done

cd "$REPOSITORY_ROOT"
printf 'Starting the API and worker in Compose; the web and homepage services will run on this host.\n'
make dev api worker

running_frontends="$(compose ps --status running -q web homepage)"
if [[ -n "$running_frontends" ]]; then
  printf 'Stopping Compose web and homepage containers to free their local ports.\n'
  compose stop web homepage
fi

api_binding="$(compose port api 8080)"
if [[ -z "$api_binding" ]]; then
  printf 'Could not find the Compose API host port.\n' >&2
  exit 1
fi

export PAPER_T_RAIL_API_ORIGIN="${PAPER_T_RAIL_API_ORIGIN:-http://${api_binding}}"
export PUBLIC_WORKSPACE_URL

printf 'Waiting for the API at %s/api/v1/health\n' "$PAPER_T_RAIL_API_ORIGIN"
api_ready=false
for ((attempt = 1; attempt <= 60; attempt += 1)); do
  if curl --fail --silent --show-error --max-time 5 "$PAPER_T_RAIL_API_ORIGIN/api/v1/health" >/dev/null 2>&1; then
    api_ready=true
    break
  fi
  sleep 2
done

if [[ "$api_ready" != true ]]; then
  printf 'The API did not become ready. Check the Compose logs for the api service.\n' >&2
  exit 1
fi

printf 'Web:      http://127.0.0.1:%s\n' "$WEB_PORT"
printf 'Homepage: http://127.0.0.1:%s\n' "$HOMEPAGE_PORT"
printf 'API:      %s\n' "$PAPER_T_RAIL_API_ORIGIN"

mise exec -- pnpm --dir web exec next dev --hostname 127.0.0.1 --port "$WEB_PORT" &
web_pid=$!
PUBLIC_WORKSPACE_URL="$PUBLIC_WORKSPACE_URL" mise exec -- pnpm --dir homepage exec astro dev --host 127.0.0.1 --port "$HOMEPAGE_PORT" &
homepage_pid=$!

exit_status=0
while true; do
  if ! kill -0 "$web_pid" 2>/dev/null; then
    if wait "$web_pid"; then exit_status=0; else exit_status=$?; fi
    printf 'The web dev server exited; stopping the homepage dev server.\n' >&2
    break
  fi

  if ! kill -0 "$homepage_pid" 2>/dev/null; then
    if wait "$homepage_pid"; then exit_status=0; else exit_status=$?; fi
    printf 'The homepage dev server exited; stopping the web dev server.\n' >&2
    break
  fi

  sleep 1
done

exit "$exit_status"
