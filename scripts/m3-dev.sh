#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
tools_dir="$project_root/.work/tools"
export JAVA_HOME="$tools_dir/jdk-21.0.13+9"
export PATH="$JAVA_HOME/bin:$tools_dir/apache-maven-3.9.9/bin:$tools_dir/node-v24.22.2-linux-x64/bin:$PATH"
export npm_config_cache="$project_root/.work/cache/npm"
for executable in "$JAVA_HOME/bin/java" "$tools_dir/apache-maven-3.9.9/bin/mvn" "$tools_dir/node-v24.22.2-linux-x64/bin/node"; do
  if [[ ! -x "$executable" ]]; then
    echo "Missing local tool: $executable; see docs/M3_DEVELOPMENT.md" >&2
    exit 1
  fi
done
if [[ "${1:-}" == mvn ]]; then
  shift
  exec mvn "-Dmaven.repo.local=$project_root/.work/cache/maven" "$@"
fi
if [[ $# == 0 ]]; then
  java -version
  mvn -version
  node --version
  npm --version
else
  exec "$@"
fi
