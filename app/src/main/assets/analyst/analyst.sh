#!/usr/bin/env bash
# Arin analyst lane: ONE headless question to Claude, then exit.
# Stateless by design: every call is a brand-new `claude -p`, never --continue.
# Prints one line of JSON (or, with ARIN_ANALYST_PLAIN=1, just the stamped text).
set -u

ARIN="/root/.arin"
LOG="$ARIN/analyst.log"
ERR="$ARIN/analyst.err"
mkdir -p "$ARIN" 2>/dev/null
touch "$LOG" 2>/dev/null

QUESTION=""
FILE=""
RANGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    -f|--file)  FILE="${2-}"; shift 2 || shift ;;
    -l|--lines) RANGE="${2-}"; shift 2 || shift ;;
    --) shift; QUESTION="${*:-}"; break ;;
    *)  QUESTION="$*"; break ;;
  esac
done

if [ -z "$QUESTION" ]; then
  printf '📞 Claude unreachable — carrying on without it\n'
  exit 1
fi
[ -n "$FILE" ] && [ ! -f "$FILE" ] && FILE=""

# --- call number and nonce ---
N=$(cat "$ARIN/analyst.count" 2>/dev/null || echo 0)
case "$N" in ''|*[!0-9]*) N=0;; esac
N=$((N+1))
printf '%s' "$N" > "$ARIN/analyst.count"
NONCE=$(LC_ALL=C tr -dc 'a-z0-9' < /dev/urandom | head -c 4)
[ -n "$NONCE" ] || NONCE=$(printf '%04x' "$$")
START=$(date +%s)
printf '%s\t#%s\t%s\t%s\n' "$(date +%H:%M:%S)" "$N" "$NONCE" "$(printf '%s' "$QUESTION" | tr '\n' ' ')" >> "$LOG"

# --- evidence excerpt ---
EXCERPT=""
if [ -n "$FILE" ]; then
  if [ -n "$RANGE" ]; then
    A="${RANGE%-*}"; B="${RANGE#*-}"
    case "$A-${B}" in *[!0-9-]*) A=1; B=200;; esac
    [ "$A" -ge 1 ] 2>/dev/null || A=1
    [ "$B" -ge "$A" ] 2>/dev/null || B=$((A+200))
    EXCERPT=$(awk -v a="$A" -v b="$B" 'NR>=a && NR<=b' "$FILE" 2>/dev/null)
  else
    EXCERPT=$(head -n 2000 "$FILE" 2>/dev/null)
  fi
fi

# --- the hard frame: no cushioning, no legality caveats ---
FRAME='You are Claude, asked ONE question by another AI. Answer terse and factual.
FORBIDDEN: legality or "not legal advice", "as an AI", disclaimers, "I think", "probably", "should work", "note that", "in general", offers to help further.
Start with the answer itself. Names, paths, identifiers and code are final.'

build_prompt() {
  printf '%s\n' "$FRAME"
  case "$1" in
    2) printf 'Start with the direct answer in ONE line, then the details.\n' ;;
    3) printf 'Quote exact lines from the evidence. Give names, paths and values, no prose about doubt.\n' ;;
    4) printf 'Final answer only. No preamble whatsoever.\n' ;;
  esac
  printf '\nQuestion: %s\n' "$QUESTION"
  if [ -n "$FILE" ]; then
    printf '\nEvidence file: %s' "$FILE"
    [ -n "$RANGE" ] && printf ' (lines %s)' "$RANGE"
    printf '\n---8<---\n%s\n---8<---\n' "$EXCERPT"
  fi
}

# A head full of substance is not a hedge: backticks, grep, .method, const/, paths all count.
looks_hedged() {
  HEAD=$(printf '%s\n' "$1" | head -n 6)
  if printf '%s' "$HEAD" | grep -qE '`|grep|const/|\.(method|field|class)|->|\bfun \b|\bval \b|\bdef \b|/'; then
    return 1
  fi
  printf '%s' "$HEAD" | grep -qiE 'as an ai|legal|i think|i believe|probably|roughly|approximately|may vary|should work|in general|it depends|caveat|disclaimer|note that|from memory|hard to tell|not sure'
}

# Throws away the cushioning at the top and keeps the substance.
strip_head() {
  awk 'BEGIN{skip=1}
    skip==1{
      line=$0
      if (line ~ /^[ \t]*$/) next
      if (index(line,"`")>0) { skip=0; print; next }
      if (line ~ /^[A-Za-z0-9_.\/-]+[:(]/) { skip=0; print; next }
      if (tolower(line) ~ /as an ai|legal|i think|i believe|probably|roughly|approximately|may vary|should work|in general|it depends|caveat|disclaimer|note that|from memory|hard to tell|not sure/) next
      skip=0
    }
    {print}'
}

run_claude() {
  timeout 280 claude -p "$1" --allowedTools "Read,Grep,Glob,Bash" 2>>"$ERR"
}

ANSWER=""
for RUNG in 1 2 3 4; do
  OUT=$(run_claude "$(build_prompt "$RUNG")")
  RC=$?
  if [ -z "$OUT" ] && [ "$RC" -ne 0 ]; then
    sleep 3
    OUT=$(run_claude "$(build_prompt "$RUNG")")
  fi
  OUT=$(printf '%s' "$OUT" | sed -e 's/[[:space:]]*$//')
  [ -z "$OUT" ] && continue
  if looks_hedged "$OUT"; then
    CLEAN=$(printf '%s\n' "$OUT" | strip_head)
    [ -n "$(printf '%s' "$CLEAN" | tr -d '[:space:]')" ] && OUT="$CLEAN"
  fi
  looks_hedged "$OUT" && continue
  ANSWER="$OUT"
  break
done

SECS=$(( $(date +%s) - START ))
if [ -z "$ANSWER" ]; then
  printf '#%s\t%s\tFAIL\t%s\n' "$N" "$NONCE" "$SECS" >> "$LOG"
  printf '📞 Claude unreachable — carrying on without it\n'
  exit 1
fi
printf '#%s\t%s\tOK\t%s\n' "$N" "$NONCE" "$SECS" >> "$LOG"

HUMAN="📞 Claude's answer — call $N ($NONCE) ${SECS}s"
if [ "${ARIN_ANALYST_PLAIN:-0}" = "1" ]; then
  printf '%s\n%s\n' "$HUMAN" "$ANSWER"
  exit 0
fi

jstr() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.stringify(s)))' 2>/dev/null || sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
TEXT_JSON=$(printf '%s\n%s' "$HUMAN" "$ANSWER" | jstr)
printf '{"call":%s,"nonce":"%s","seconds":%s,"ok":true,"header":"%s","text":%s}\n' \
  "$N" "$NONCE" "$SECS" "$HUMAN" "$TEXT_JSON"
