#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
target="$root/.env"
if [[ -e "$target" ]]; then
  echo 'Local configuration already exists; preserved.'
  exit 0
fi

command -v openssl >/dev/null || { echo 'openssl is required.' >&2; exit 1; }
umask 077
set -o noclobber
{
  for name in MYSQL_ROOT_PASSWORD M1_DB_PASSWORD M2_DB_PASSWORD M3_DB_PASSWORD SERVICE_KEY; do
    printf '%s=%s\n' "$name" "$(openssl rand -hex 24)"
  done
  printf '%s\n' 'DEMO_PASSWORD=CampusDemo2026!'
} > "$target"
echo 'Created ignored .env with private database/service secrets and the documented demo password.'
