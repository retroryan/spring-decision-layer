#!/usr/bin/env bash
# Usage: ./run.sh [--no-seed] [companyId] [amount]      for example: ./run.sh C-1042 250000
#
# --no-seed skips GraphSeeder for one run, so the graph is read exactly as you left it. Use it
# after editing the graph by hand; any ordinary run puts the seeded nodes and relationships back.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
	echo "No .env found. Copy .env.example to .env, then add your ANTHROPIC_API_KEY and"
	echo "the connection details for a Neo4j instance."
	exit 1
fi

set -a && source .env && set +a

# Checked here rather than in Java, because an unset NEO4J_URI fails during Spring's own
# startup with a stack trace that says nothing about the missing setting.
for required in ANTHROPIC_API_KEY NEO4J_URI NEO4J_PASSWORD; do
	if [ -z "${!required:-}" ]; then
		echo "$required is not set in .env. See .env.example."
		exit 1
	fi
done

# A JVM system property rather than an application argument: Application reads its arguments
# positionally, so an extra --loan.seed.enabled=false would be taken for the company id.
jvm=""
if [ "${1:-}" = "--no-seed" ]; then
	shift
	jvm="-Dspring-boot.run.jvmArguments=-Dloan.seed.enabled=false"
fi

if [ "$#" -gt 0 ]; then
	./mvnw -q ${jvm:+"$jvm"} spring-boot:run -Dspring-boot.run.arguments="$*"
else
	./mvnw -q ${jvm:+"$jvm"} spring-boot:run
fi
