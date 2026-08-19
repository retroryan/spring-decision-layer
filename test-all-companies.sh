#!/usr/bin/env bash
# Usage: ./test-all-companies.sh [--tier quick|boundary|full] [--no-reset] [--skip-errors] [--yes]
#
# Runs ./run.sh once per case below, across all four companies, to exercise every policy's
# above/below-the-line branch and the Repeat Denial Escalation history mechanic. Each case is a
# real Spring Boot start, a real Anthropic call, and a real write to the Neo4j graph configured
# in .env, so this costs model credits and takes real wall-clock time (mvnw start included).
# Every run's console output streams live as it happens (and is also saved), with the headings
# DecisionConsole already prints -- On duty for this run, Policies, as measured, and so on --
# picked out in color so a demo audience can follow which section is on screen. Colors turn
# themselves off automatically when stdout isn't a terminal (e.g. redirected to a file).
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
		sed -n '2,26p' "$0"
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

# Only a terminal gets escape codes; a redirected run (or one piped into `tee full.log`) gets
# plain text, and the per-case log files under test-results/ are always plain either way.
if [ -t 1 ]; then
	C_RESET=$'\033[0m'
	C_BOLD=$'\033[1m'
	C_BLUE=$'\033[34m'
	C_CYAN=$'\033[36m'
	C_GREEN=$'\033[32m'
	C_RED=$'\033[31m'
	C_YELLOW=$'\033[33m'
else
	C_RESET=""
	C_BOLD=""
	C_BLUE=""
	C_CYAN=""
	C_GREEN=""
	C_RED=""
	C_YELLOW=""
fi

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

company_name() {
	case "$1" in
	C-1042) echo "Ridgeline Builders" ;;
	C-1077) echo "Cornerstone Concrete" ;;
	C-1096) echo "Northgate Framing" ;;
	C-1123) echo "Summit Ironworks" ;;
	*) echo "$1" ;;
	esac
}

# 250000 -> 250,000. Left alone (no error) if it isn't all digits, which covers err-bad-amount's
# deliberately unparseable amount.
with_commas() {
	case "$1" in
	*[!0-9]*)
		echo "$1"
		return
		;;
	esac
	echo "$1" | rev | sed -E 's/([0-9]{3})/\1,/g; s/,$//' | rev
}

# One heading per line printed, styled by matching it against DecisionConsole's own format
# strings. If those change, this stops highlighting rather than mismatching, so a highlight is a
# hint and the underlying text -- captured unstyled in the per-case log -- is still the record.
hdr() { printf '%s▸ %s%s\n' "${C_BOLD}${C_BLUE}" "$1" "$C_RESET"; }
approved_line() { printf '%s%s%s\n' "${C_BOLD}${C_GREEN}" "$1" "$C_RESET"; }
denied_line() { printf '%s%s%s\n' "${C_BOLD}${C_RED}" "$1" "$C_RESET"; }
flagged_line() { printf '%s%s%s\n' "${C_BOLD}${C_YELLOW}" "$1" "$C_RESET"; }

format_sections() {
	local line
	while IFS= read -r line; do
		case "$line" in
		"Decision traces for "*) hdr "$line" ;;
		"On duty for this run") hdr "$line" ;;
		"Policies, as measured") hdr "$line" ;;
		"Precedent trail, now that this decision is on file") hdr "$line" ;;
		"Which underwriter approves past which line") hdr "$line" ;;
		"Who has set aside whose denial") hdr "$line" ;;
		"Transcript for this run, from Spring AI chat memory") hdr "$line" ;;
		"Follow-up on the same conversation, nothing about the file repeated") hdr "$line" ;;
		"APPROVED ("*) approved_line "$line" ;;
		"DENIED ("*) denied_line "$line" ;;
		"No company with id "*) flagged_line "$line" ;;
		"No companies in the graph"*) flagged_line "$line" ;;
		*"is not an amount"*) flagged_line "$line" ;;
		*"has to be more than zero"*) flagged_line "$line" ;;
		*) printf '%s\n' "$line" ;;
		esac
	done
}

# Extracts the one line this whole example exists to print (DecisionConsole.print), plus the
# error-path messages, so the summary row means something without opening the log. Reads the
# plain per-case log file, never the colored stream, so the regexes never have to skip escape
# codes.
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

print_case_header() {
	local num="$1" total="$2" label="$3" company="$4" amount="$5" desc rule
	if [ -n "$company" ]; then
		desc="$(company_name "$company") ($company) requesting \$$(with_commas "$amount")"
	else
		desc="no arguments -- Application's own defaults (C-1042, \$250,000)"
	fi
	rule="========================================================================"
	echo
	echo "${C_BOLD}${C_CYAN}${rule}${C_RESET}"
	echo "${C_BOLD}${C_CYAN}Case $num/$total: $label -- $desc${C_RESET}"
	echo "${C_BOLD}${C_CYAN}${rule}${C_RESET}"
}

print_case_footer() {
	local label="$1" status="$2" outcome="$3" dur="$4"
	echo "${C_BOLD}${C_CYAN}-- $label done: $status  $outcome  (${dur}s) --${C_RESET}"
}

# Streams live (so a demo audience watching the terminal sees each section as DecisionConsole
# prints it) and tees the unstyled copy to disk at the same time; format_sections only touches
# what reaches the terminal. pipefail (set at the top of this file) makes the if below see
# ./run.sh's own exit status even though it isn't the last command in the pipe.
run_case() {
	local num="$1" total="$2" label="$3" company="${4:-}" amount="${5:-}"
	local logfile status start dur outcome
	shift 3
	logfile="$RESULTS_DIR/$label.log"

	print_case_header "$num" "$total" "$label" "$company" "$amount"

	start=$SECONDS
	if ./run.sh "$@" 2>&1 | tee "$logfile" | format_sections; then
		status="ok"
	else
		status="nonzero-exit"
	fi
	dur=$((SECONDS - start))
	outcome=$(outcome_of "$logfile")

	printf '%s\t%s\t%s\t%ss\t%s\n' "$label" "$status" "$outcome" "$dur" "$logfile" >>"$SUMMARY"
	print_case_footer "$label" "$status" "$outcome" "$dur"
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

SELECTED=()
for entry in "${CASES[@]}"; do
	IFS='|' read -r label company amount min_tier <<<"$entry"
	if [ "$(tier_level "$min_tier")" -le "$SELECTED_LEVEL" ]; then
		SELECTED+=("$label|$company|$amount")
	fi
done

TOTAL=${#SELECTED[@]}
if [ "$DO_ERRORS" -eq 1 ]; then
	TOTAL=$((TOTAL + 3))
fi

CASE_NUM=0
for entry in "${SELECTED[@]}"; do
	IFS='|' read -r label company amount <<<"$entry"
	CASE_NUM=$((CASE_NUM + 1))
	run_case "$CASE_NUM" "$TOTAL" "$label" "$company" "$amount"
done

if [ "$DO_ERRORS" -eq 1 ]; then
	CASE_NUM=$((CASE_NUM + 1))
	run_case "$CASE_NUM" "$TOTAL" "err-unknown-company" C-9999 250000
	CASE_NUM=$((CASE_NUM + 1))
	run_case "$CASE_NUM" "$TOTAL" "err-bad-amount" C-1042 not-a-number
	# No arguments falls back to Application's own defaults (C-1042, $250,000), so unlike the
	# other two error cases this one does start the model and write a decision.
	CASE_NUM=$((CASE_NUM + 1))
	run_case "$CASE_NUM" "$TOTAL" "err-no-args"
fi

echo
echo "${C_BOLD}Results in $RESULTS_DIR${C_RESET}"
if command -v column >/dev/null 2>&1; then
	{
		printf 'case\tstatus\toutcome\ttime\tlog\n'
		cat "$SUMMARY"
	} | column -t -s $'\t'
else
	cat "$SUMMARY"
fi
