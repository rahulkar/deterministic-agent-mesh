#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

SERVER_PORT="${SERVER_PORT:-8000}"

echo "Starting Deterministic Agent Mesh stack..."
echo "ADK Dev UI: http://localhost:${SERVER_PORT}"
echo
echo "Remote A2A Agent Cards:"
echo "  clinical_retriever:              http://localhost:9001/.well-known/agent-card.json"
echo "  pharmacovigilance_watchdog:      http://localhost:9002/.well-known/agent-card.json"
echo "  drug_interaction_agent:          http://localhost:9003/.well-known/agent-card.json"
echo "  compliance_guard_agent:          http://localhost:9004/.well-known/agent-card.json"
echo "  dosage_policy_agent:             http://localhost:9005/.well-known/agent-card.json"
echo "  greeting_agent:                  http://localhost:9006/.well-known/agent-card.json"
echo
echo "WireMock starts on a random local port behind the remote agents."

mvn compile exec:java \
  "-Dagentmesh.adk.start-runtime-on-load=true" \
  "-Dagentmesh.adk.fixed-agent-ports=true" \
  "-Dexec.mainClass=com.google.adk.web.AdkWebServer" \
  "-Dexec.args=--adk.agents.source-dir=. --server.port=${SERVER_PORT}"
