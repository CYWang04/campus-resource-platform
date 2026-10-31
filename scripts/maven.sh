#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if command -v mvn >/dev/null 2>&1; then
  exec mvn -f "$root/backend/pom.xml" "$@"
fi

tools="$root/.work/tools"
version='3.9.9'
maven="$tools/apache-maven-$version/bin/mvn"
if [[ ! -x "$maven" ]]; then
  mkdir -p "$tools"
  archive="$tools/apache-maven-$version-bin.tar.gz"
  url="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.tar.gz"
  curl --fail --location --retry 2 "$url" --output "$archive"
  expected="$(curl --fail --silent --show-error --location "$url.sha512" | awk '{print $1}')"
  actual="$(shasum -a 512 "$archive" | awk '{print $1}')"
  [[ "$expected" == "$actual" ]] || { echo 'Maven checksum mismatch.' >&2; exit 1; }
  tar -xzf "$archive" -C "$tools"
fi
exec "$maven" -f "$root/backend/pom.xml" "$@"
