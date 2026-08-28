#!/usr/bin/env bash
# Usage: ./run.sh [--no-seed] [companyId] [amount]      for example: ./run.sh C-1042 250000
#
# --no-seed skips GraphSeeder for one run, so the graph is read exactly as you left it. Use it
# after editing the graph by hand; any ordinary run puts the seeded nodes and relationships back.
set -euo pipefail
cd "$(dirname "$0")"

# The environment is the source of truth. A .env is sourced if present, purely as a convenience,
# but nothing here requires the file. spring-boot:test-run starts its own throwaway Neo4j via
# Testcontainers (Docker must be running), so the only thing that has to be set is the Bedrock
# token for the model call.
if [ -f .env ]; then
	set -a && source .env && set +a
fi

if [ -z "${AWS_BEARER_TOKEN_BEDROCK:-}" ]; then
	echo "AWS_BEARER_TOKEN_BEDROCK is not set. Export it in your shell, or put it in a .env file."
	exit 1
fi

# run.sh always runs a single command and exits, so it forces the shell non-interactive. (Bare
# `./mvnw spring-boot:test-run` leaves it interactive and opens a REPL instead.) These go through
# the environment using Spring's relaxed binding, so they reach the app whether or not the
# spring-boot plugin forks a JVM, and are not read as part of the decide command built below.
export SPRING_SHELL_INTERACTIVE_ENABLED=false
if [ "${1:-}" = "--no-seed" ]; then
	shift
	export LOAN_SEED_ENABLED=false
fi

# The decision is a Spring Shell command, invoked with positional arguments: "decide C-1042 250000".
# Both default, so ./run.sh with no arguments still decides C-1042 at $250,000.
cmd="decide"
if [ "$#" -gt 0 ]; then cmd="$cmd $1"; fi
if [ "$#" -gt 1 ]; then cmd="$cmd $2"; fi

./mvnw -q spring-boot:test-run -Dspring-boot.run.arguments="$cmd"
