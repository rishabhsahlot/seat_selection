#!/usr/bin/env bash
# On-sale stampede: ./burst.sh <BASE_URL>
#
# Creates a fresh show, then fires one shuffled burst of reserve requests at it:
#   hot-seat storm  many users race for the same few seats  -> exactly one winner per seat
#   retries         the same request sent many times at once -> exactly one reservation
#   stampede        thousands of requests for random seats   -> only 201s and clean 409s
# While the burst runs it watches the show's seat counts; afterwards it checks the results
# against the API and /actuator/prometheus. Exits 1 if any check fails, 2 if setup fails.
#
# Needs bash, curl >= 7.84 and jq. Every setting below can be overridden from the
# environment, e.g.  STORM=100 STAMPEDE=2000 ./burst.sh http://localhost:8080

set -uo pipefail

# ---------------------------------------------------------------- settings

BASE=${1:?usage: ./burst.sh <BASE_URL>}
BASE=${BASE%/}
ADMIN_API_KEY=${ADMIN_API_KEY:-dev-admin-key}
HOT_SEATS=${HOT_SEATS:-5}              # seats everyone fights over
STORM=${STORM:-500}                    # users racing for each hot seat
RETRY_KEYS=${RETRY_KEYS:-50}           # requests that get retried ...
RETRY_FANOUT=${RETRY_FANOUT:-20}       # ... this many times, all at once
STAMPEDE=${STAMPEDE:-15000}            # other requests, each for 1-2 random seats
STAMPEDE_USERS=${STAMPEDE_USERS:-1500} # users sending those requests
ROWS=${ROWS:-20}                       # the hall: ROWS x SEATS_PER_ROW seats (A1 ... T50)
SEATS_PER_ROW=${SEATS_PER_ROW:-50}
CONCURRENCY=${CONCURRENCY:-300}        # requests in flight at once
TIMEOUT=${TIMEOUT:-120}                # seconds per request

WORK=$(mktemp -d "${TMPDIR:-/tmp}/burst.XXXXXX") # scratch files for this run, deleted on exit
trap 'touch "$WORK/done"; rm -rf "$WORK"' EXIT
RUN=$RANDOM$RANDOM # makes user ids and keys unique to this run

# Child processes started by xargs need these.
export BASE TIMEOUT RUN

# ---------------------------------------------------------------- helpers

# Stops the script with exit code 2 (setup failed, nothing was tested).
fail() { echo "burst.sh: $1" >&2; exit 2; }

# Number of lines in a file.
lines() { wc -l <"$1" | tr -d ' '; }

# Runs FUNCTION once per line of input, CONCURRENCY at a time; the line's words become
# its arguments. Output goes through a pipe, where short lines never get mixed together.
in_parallel() { xargs -P "$CONCURRENCY" -L 1 bash -c "$1 \"\$@\"" _ | cat; }

# How many result lines match a regex, e.g. count ' 201$'.
count() { grep -cE "$1" "$WORK/results"; }

# The value of one metric in a saved /actuator/prometheus page.
metric() { awk -v name="$1" '$1 == name { print int($2) }' "$2"; }

# How much a metric changed during the run.
delta() { echo $(($(metric "$1" "$WORK/metrics.after") - $(metric "$1" "$WORK/metrics.before"))); }

# Each check is a few calc lines followed by report:
#   calc FORMULA GOT WANT [WORKING]  records one comparison, e.g.
#        calc "seat_taken = HOT_SEATS x (STORM - 1)" 2495 2495 "5 x 499"
#        prints  seat_taken = HOT_SEATS x (STORM - 1)  ->  2495 = 5 x 499   (or != if it does not hold)
#   report NAME                      prints PASS (all comparisons held) or FAIL, then the calc lines.
# Any FAIL makes the script exit 1.
FAILED=0
CALCS=""
CHECK_OK=1
calc() {
	local sign="="
	[ "$2" = "$3" ] || { sign="!="; CHECK_OK=0; }
	CALCS+=$(printf '\n         %-54s ->  %s %s %s' "$1" "$2" "$sign" "${4:-$3}")
}
report() {
	if [ "$CHECK_OK" = 1 ]; then echo "  [PASS] $1$CALCS"; else echo "  [FAIL] $1$CALCS"; FAILED=1; fi
	CALCS=""
	CHECK_OK=1
}

# ---------------------------------------------------------------- requests (run in parallel)

# One reserve request. Prints one result line: SCENARIO TAG SEATS OUTCOME, where OUTCOME is
# 201, 200-replay, 409-seat_taken, 409-per_user_limit ..., or 000 if the connection failed.
reserve() { # SCENARIO TAG TOKEN SEATS KEY   (SEATS like A1 or A1,A2)
	local body="{\"seats\":[\"${4//,/\",\"}\"],\"idempotency_key\":\"$5\"}"
	local out
	out=$(curl -s --max-time "$TIMEOUT" -X POST "$BASE/shows/$SID/reserve" -d "$body" \
		-H "Authorization: Bearer $3" -H 'Content-Type: application/json' \
		-w '\n%{http_code} %header{idempotent-replayed}')

	local status=${out##*$'\n'} # the -w line: "201 ", "409 " or "200 true"
	local outcome=${status% *}
	[[ $status == *true ]] && outcome=200-replay
	[[ $out =~ \"reason\":\"([a-z_]+)\" ]] && outcome=$outcome-${BASH_REMATCH[1]}
	echo "$1 $2 $4 $outcome"
}

# Gets a token for user number N. Prints "N TOKEN". Retried, because this is setup, not the test.
mint() { # N
	local out
	out=$(curl -s --retry 5 --retry-all-errors --max-time "$TIMEOUT" -X POST "$BASE/auth/token" \
		-H 'Content-Type: application/json' -d "{\"user_id\":\"u$RUN-$1\"}")
	[[ $out =~ \"access_token\":\"([^\"]+)\" ]] && echo "$1 ${BASH_REMATCH[1]}"
}

export -f reserve mint

# ---------------------------------------------------------------- setup

# Fails early if the service is down, and saves the metrics to compare against later.
check_ready() {
	curl -sf --max-time 30 "$BASE/actuator/health/readiness" >/dev/null || fail "$BASE is not ready"
	curl -s "$BASE/actuator/prometheus" >"$WORK/metrics.before"
}

# Fills SEATS with A1 ... A50, B1 ... (ROWS rows of SEATS_PER_ROW seats).
build_seat_names() {
	local letters=ABCDEFGHIJKLMNOPQRSTUVWXYZ r n
	SEATS=()
	for ((r = 0; r < ROWS; r++)); do
		for ((n = 1; n <= SEATS_PER_ROW; n++)); do SEATS+=("${letters:r:1}$n"); done
	done
	TOTAL=${#SEATS[@]}
}

# Creates the show (per-user limit 4) and sets SID to its id.
create_show() {
	local seat_list
	seat_list=$(printf '"%s",' "${SEATS[@]}")
	SID=$(curl -s -X POST "$BASE/shows" -H "X-Admin-Key: $ADMIN_API_KEY" -H 'Content-Type: application/json' \
		-d "{\"name\":\"burst-$RUN\",\"seats\":[${seat_list%,}],\"price_paise\":25000,\"per_user_limit\":4}" |
		jq -r '.id // empty')
	[ -n "$SID" ] || fail "could not create a show (check ADMIN_API_KEY)"
	export SID
}

# Fills TOKENS[0..USERS-1] with one token per user.
mint_tokens() {
	local users=$((HOT_SEATS * STORM + RETRY_KEYS + STAMPEDE_USERS)) i token
	echo "show $SID: $TOTAL seats. Minting $users user tokens ..."
	TOKENS=()
	while read -r i token; do TOKENS[i]=$token; done < <(seq 0 $((users - 1)) | in_parallel mint)
	[ "${#TOKENS[@]}" = "$users" ] || fail "could not mint all tokens"
}

# ---------------------------------------------------------------- the burst

# Prints one line per request: SCENARIO TAG TOKEN SEATS KEY. Each scenario uses its own users,
# so a hot-seat user never runs into the per-user limit and muddles the hot-seat check.
burst_jobs() {
	local user=0 s u k i seats other

	# hot-seat storm: STORM different users per hot seat; the tag is the seat
	for ((s = 0; s < HOT_SEATS; s++)); do
		for ((u = 0; u < STORM; u++)); do
			echo "hot ${SEATS[s]} ${TOKENS[user++]} ${SEATS[s]} hot-$s"
		done
	done

	# retries: one user per key sends the identical request RETRY_FANOUT times; the tag is the key
	for ((k = 0; k < RETRY_KEYS; k++)); do
		for ((i = 0; i < RETRY_FANOUT; i++)); do
			echo "retry k$k ${TOKENS[user]} ${SEATS[HOT_SEATS + k]} retry-$RUN-$k"
		done
		((user++))
	done

	# stampede: random users and seats (not the hot or retry seats); a third ask for two seats
	local first=$((HOT_SEATS + RETRY_KEYS))
	for ((i = 0; i < STAMPEDE; i++)); do
		seats=${SEATS[first + RANDOM % (TOTAL - first)]}
		other=${SEATS[first + RANDOM % (TOTAL - first)]}
		((RANDOM % 3 == 0)) && [ "$other" != "$seats" ] && seats=$seats,$other
		echo "stampede - ${TOKENS[user + RANDOM % STAMPEDE_USERS]} $seats st-$i"
	done
}

# Polls the show's seat counts until the burst ends. Writes "ok", or the counts that don't add up.
watch_invariant() {
	until [ -f "$WORK/done" ]; do
		curl -s --max-time 10 "$BASE/shows/$SID" |
			jq -r '.counts | if .available + .held + .confirmed == .total then "ok" else tostring end'
		sleep 0.25
	done >"$WORK/invariant"
}

# Shuffles the jobs so scenarios interleave, then sends them all; results go to $WORK/results.
fire_burst() {
	burst_jobs | sort -R >"$WORK/jobs"
	echo "firing $(lines "$WORK/jobs") reserve requests, $CONCURRENCY at a time ..."
	watch_invariant &
	local start=$SECONDS
	in_parallel reserve <"$WORK/jobs" >"$WORK/results"
	touch "$WORK/done" # stops the watcher
	wait
	echo "done in $((SECONDS - start))s"
}

# ---------------------------------------------------------------- results

# Saves what we need to compare: the seats our 201s sold, the seats the API says are
# confirmed, and the metrics after the run.
collect_results() {
	grep ' 201$' "$WORK/results" | cut -d' ' -f3 | tr , '\n' | sort >"$WORK/sold"
	curl -s "$BASE/shows/$SID" | jq -r '.seats[] | select(.status == "confirmed") | .seat_name' | sort >"$WORK/confirmed"
	sleep 2 # the seat gauges refresh every second
	curl -s "$BASE/actuator/prometheus" >"$WORK/metrics.after"
}

# How many requests of each scenario got each outcome.
print_outcomes() {
	echo
	echo "outcomes:"
	cut -d' ' -f1,4 "$WORK/results" | sort | uniq -c | sort -k2,2 -k1,1nr
}

run_checks() {
	echo
	echo "checks:"

	# Every hot seat has exactly one 201, and everyone else got a clean "seat taken".
	local hot_wins hot_seats_won
	hot_wins=$(count '^hot .* 201$')
	hot_seats_won=$(grep -E '^hot .* 201$' "$WORK/results" | cut -d' ' -f2 | sort -u | wc -l | tr -d ' ')
	calc "wins = HOT_SEATS" "$hot_wins" "$HOT_SEATS"
	calc "seats with a winner = HOT_SEATS" "$hot_seats_won" "$HOT_SEATS"
	calc "seat_taken = HOT_SEATS x (STORM - 1)" "$(count '^hot .* 409-seat_taken$')" \
		"$((HOT_SEATS * (STORM - 1)))" "$HOT_SEATS x $((STORM - 1))"
	report "one winner per hot seat, the rest get 409 seat_taken"

	# Each retried key created one reservation; the other copies got the original back.
	calc "created = RETRY_KEYS" "$(count '^retry .* 201$')" "$RETRY_KEYS"
	calc "replays = RETRY_KEYS x (RETRY_FANOUT - 1)" "$(count '^retry .* 200-replay$')" \
		"$((RETRY_KEYS * (RETRY_FANOUT - 1)))" "$RETRY_KEYS x $((RETRY_FANOUT - 1))"
	report "one reservation per retried key, the other copies are replays"

	calc "201 + seat_taken + per_user_limit = STAMPEDE" \
		"$(grep '^stampede' "$WORK/results" | grep -cE ' (201|409-seat_taken|409-per_user_limit)$')" "$STAMPEDE"
	report "every stampede request got 201 or a clean 409"

	# The seats sold according to the 201s, against each other and against the API.
	local sold
	sold=$(lines "$WORK/sold")
	calc "distinct seats in 201s = seats in 201s" "$(sort -u "$WORK/sold" | wc -l | tr -d ' ')" "$sold"
	report "no seat sold twice"

	calc "confirmed by the API = sold in 201s" "$(lines "$WORK/confirmed")" "$sold"
	calc "seat names that differ = 0" "$(comm -3 "$WORK/sold" "$WORK/confirmed" | wc -l | tr -d ' ')" 0
	report "the show's confirmed seats are exactly the seats in 201 responses"

	calc "snapshots where the counts don't add up = 0" "$(grep -cv '^ok$' "$WORK/invariant")" 0 \
		"0 (of $(lines "$WORK/invariant") snapshots)"
	report "available + held + confirmed == total during the burst"

	# 000 = the connection failed before any HTTP response.
	calc "5xx + failed connections = 0" "$(count ' (000|5[0-9][0-9])[^ ]*$')" 0 "0 (of $(lines "$WORK/results") requests)"
	report "zero 5xx and zero failed connections"

	# The counters moved by exactly what the responses say happened.
	calc "confirmed_total change = 201 responses" "$(delta reservations_confirmed_total)" "$(count ' 201$')"
	calc "declined{seat_taken} change = 409 seat_taken" \
		"$(delta 'reservations_declined_total{reason="seat_taken"}')" "$(count ' 409-seat_taken$')"
	calc "declined{per_user_limit} change = 409 per_user_limit" \
		"$(delta 'reservations_declined_total{reason="per_user_limit"}')" "$(count ' 409-per_user_limit$')"
	calc "declined{idempotent_replay} change = 200 replays" \
		"$(delta 'reservations_declined_total{reason="idempotent_replay"}')" "$(count ' 200-replay$')"
	report "metrics: counters match the responses"

	calc "seats_confirmed gauge = confirmed by the API" \
		"$(metric "seats_confirmed{show_id=\"$SID\"}" "$WORK/metrics.after")" "$(lines "$WORK/confirmed")"
	report "metrics: the seat gauge agrees with the API"
}

# ---------------------------------------------------------------- main

main() {
	check_ready
	build_seat_names
	create_show
	mint_tokens
	fire_burst
	collect_results
	print_outcomes
	run_checks
	exit $FAILED
}

main
