#!/usr/bin/env bash
set -uo pipefail

check_engine=false
case "${1:-}" in
  '') ;;
  --docker) check_engine=true ;;
  *) echo 'Usage: bash scripts/check-environment.sh [--docker]' >&2; exit 2 ;;
esac
failed=0

check() {
  local label="$1" pattern="$2"
  shift 2
  local output
  if output="$("$@" 2>&1)" && printf '%s\n' "$output" | grep -Eq "$pattern"; then
    printf 'OK   %s: %s\n' "$label" "$(printf '%s\n' "$output" | head -n 1)"
  else
    printf 'FAIL %s: missing, unavailable, or unexpected version\n' "$label"
    failed=1
  fi
}

check 'JDK 21' 'version "21\.' java -version
check 'javac 21' '^javac 21\.' javac -version
check 'Node.js 24' '^v24\.' node --version
check 'npm' '^[0-9]+\.' npm --version
check 'Maven' 'Apache Maven 3\.' mvn --version
check 'Git' '^git version ' git --version
check 'Docker CLI' '^Docker version ' docker --version
check 'Docker Compose' 'Docker Compose version' docker compose version
check 'Docker Buildx' 'github.com/docker/buildx' docker buildx version
if "$check_engine"; then
  check 'Docker Engine' '^[0-9]+\.' docker version --format '{{.Server.Version}}'
fi
if [[ "$failed" -eq 0 ]]; then
  echo 'PASS: required development tools are available.'
else
  echo 'Some requirements are not ready.' >&2
fi
exit "$failed"
