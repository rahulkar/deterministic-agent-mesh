#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

SERVER_PORT="${SERVER_PORT:-8000}"

mvn compile exec:java \
  "-Dexec.mainClass=com.agentmesh.deterministic.StackLauncher"
