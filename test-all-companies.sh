#!/usr/bin/env bash
# Usage: ./test-all-companies.sh [--tier quick|boundary|full] [--no-reset] [--skip-errors] [--yes]
#
# Runs ./run.sh once per case below, across all four companies, to exercise every policy's
# above/below-the-line branch and the Repeat Denial Escalation history mechanic. Each case is a
# real Spring Boot start, a real Anthropic call, and a real write to the Neo4j graph configured
# in .env, so this costs model credits and takes real wall-clock time (mvnw start included).
#
#   --tier quick     the four documented defaults only (one run per company)
#   --tier boundary  quick + one amount that clears every policy and one that breaks debt-to-income
#                     per company (default is this plus escalation repeats; see --tier full)
#   --tier full       boundary + immediate repeats on C-1042 and C-1123 so a denial just written
#                     becomes precedent for the very next run, the mechanic the README's headline
#                     sequence demonstrates                                        [default]
#   --no-reset        skip resetting the graph to the seeded baseline first
#   --skip-errors     skip the three non-loan error-path checks (unknown company, bad amount,
#                      no arguments)
#   --yes             don't prompt before resetting the graph
#
# Case order within a company is deliberate: a "clears everything" run before the documented
# default, then (on full) an immediate repeat of a denial so escalation has something to build on,
# then a deep denial and its own repeat. Reordering these changes what precedent later cases read.
set -euo pipefail
cd "$(dirname "$0")"

TIER="full"
DO_RESET=1
DO_ERRORS=1
ASSUME_YES=0

while [ "$#" -gt 0 ]; do
	case "$1" in
	--tier)
		TIER="${2:-}"
		shift 2
		;;
	--no-reset)
		DO_RESET=0
		shift
		;;
	--skip-errors)
		DO_ERRORS=0
		shift
		;;
	--yes | -y)
		ASSUME_YES=1
		shift
		;;
	-h | --help)
		sed -n '2,24p' "$0"
		exit 0
		;;
	*)
		echo "Unknown option: $1" >&2
		exit 1
		;;
	esac
done

tier_level() {
	case "$1" in
	quick) echo 1 ;;
	boundary) echo 2 ;;
	full) echo 3 ;;
	*)
		echo "Unknown tier '$1'. Use quick, boundary, or full." >&2
		exit 1
		;;
	esac
}
SELECTED_LEVEL=$(tier_level "$TIER")

if [ ! -f .env ]; then
	echo "No .env found. Copy .env.example to .env, then add your ANTHROPIC_API_KEY and"
	echo "the connection details for a Neo4j instance."
	exit 1
fi

set -a && source .env && set +a

for required in ANTHROPIC_API_KEY NEO4J_URI NEO4J_PASSWORD; do
	if [ -z "${!required:-}" ]; then
		echo "$required is not set in .env. See .env.example."
		exit 1
	fi
done

RESULTS_DIR="test-results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"
SUMMARY="$RESULTS_DIR/summary.tsv"
: >"$SUMMARY"

reset_graph() {
	if ! command -v cypher-shell >/dev/null 2>&1; then
		echo "cypher-shell not found; skipping reset. Run the query in"
		echo "docs/reference.md#reset by hand, then re-run with --no-reset."
		return
	fi

	echo "About to delete this demo's own nodes (Company, Policy, Underwriter, LoanApplication,"
	echo "Decision, Exception) so every case below starts from the documented seeded baseline."
	echo "GraphSeeder rebuilds Company/Policy/Underwriter on the next run. Chat memory (Session,"
	echo "Message, Metadata) is untouched."
	if [ "$ASSUME_YES" -ne 1 ]; then
		read -r -p "Reset the graph now? [y/N] " ans
		case "$ans" in
		[Yy]*) ;;
		*)
			echo "Skipping reset; running against whatever is already in the graph."
			return
			;;
		esac
	fi

	local db_args=()
	if [ -n "${NEO4J_DATABASE:-}" ]; then
		db_args=(-d "$NEO4J_DATABASE")
	fi
	cypher-shell -a "$NEO4J_URI" -u "${NEO4J_USERNAME:-neo4j}" -p "$NEO4J_PASSWORD" "${db_args[@]}" \
		"MATCH (n) WHERE n:Company OR n:Policy OR n:Underwriter OR n:LoanApplication OR n:Decision OR n:Exception DETACH DELETE n"
	echo "Reset done."
}

# Extracts the one line this whole example exists to print (DecisionConsole.print), plus the
# error-path messages, so the summary row means something without opening the log.
outcome_of() {
	local logfile="$1" line
	line=$(grep -E '^(APPROVED|DENIED) \((clear|borderline)\)\.' "$logfile" | head -1)
	if [ -n "$line" ]; then
		echo "${line%%. *}"
		return
	fi
	line=$(grep -E '^No company with id|^No companies in the graph' "$logfile" | head -1)
	if [ -n "$line" ]; then
		echo "UNKNOWN_COMPANY"
		return
	fi
	line=$(grep -E "is not an amount|has to be more than zero" "$logfile" | head -1)
	if [ -n "$line" ]; then
		echo "BAD_AMOUNT"
		return
	fi
	echo "NO_VERDICT (check log)"
}

run_case() {
	local label="$1" logfile status start dur outcome
	shift
	logfile="$RESULTS_DIR/$label.log"
	start=$SECONDS
	echo "==> $label: ./run.sh $*"
	if ./run.sh "$@" >"$logfile" 2>&1; then
		status="ok"
	else
		status="nonzero-exit"
	fi
	dur=$((SECONDS - start))
	outcome=$(outcome_of "$logfile")
	printf '%s\t%s\t%s\t%ss\t%s\n' "$label" "$status" "$outcome" "$dur" "$logfile" >>"$SUMMARY"
	echo "    $status  $outcome  (${dur}s)"
}

if [ "$DO_RESET" -eq 1 ]; then
	reset_graph
fi

# label|companyId|amount|minimum tier that includes this case
CASES=(
	"1042-clears|C-1042|50000|boundary"
	"1042-default|C-1042|250000|quick"
	"1042-repeat-escalation|C-1042|250000|full"
	"1042-deep-denial|C-1042|700000|boundary"
	"1042-deep-repeat|C-1042|700000|full"
	"1077-clears|C-1077|50000|boundary"
	"1077-default|C-1077|250000|quick"
	"1077-borderline|C-1077|1352000|boundary"
	"1096-credit-only|C-1096|50000|boundary"
	"1096-default|C-1096|250000|quick"
	"1096-compounded|C-1096|900000|boundary"
	"1123-clears|C-1123|50000|boundary"
	"1123-default|C-1123|250000|quick"
	"1123-repeat|C-1123|250000|full"
	"1123-compounded|C-1123|1000000|boundary"
)

for entry in "${CASES[@]}"; do
	IFS='|' read -r label company amount min_tier <<<"$entry"
	if [ "$(tier_level "$min_tier")" -le "$SELECTED_LEVEL" ]; then
		run_case "$label" "$company" "$amount"
	fi
done

if [ "$DO_ERRORS" -eq 1 ]; then
	run_case "err-unknown-company" C-9999 250000
	run_case "err-bad-amount" C-1042 not-a-number
	# No arguments falls back to Application's own defaults (C-1042, $250,000), so unlike the
	# other two error cases this one does start the model and write a decision.
	run_case "err-no-args"
fi

echo
echo "Results in $RESULTS_DIR"
if command -v column >/dev/null 2>&1; then
	{
		printf 'case\tstatus\toutcome\ttime\tlog\n'
		cat "$SUMMARY"
	} | column -t -s $'\t'
else
	cat "$SUMMARY"
fi
