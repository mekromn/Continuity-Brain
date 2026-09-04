#!/usr/bin/env bash
set -euo pipefail

fail=0

forbidden_path_regex='(^|/)(conversations\.json|chat\.html|user\.json|message_feedback\.json|shared_conversations\.json)$|\.(db|sqlite|sqlite3|zip|cbbrain|cbenc|jks|keystore|p12|pfx)$'
while IFS= read -r path; do
  if [[ "$path" =~ $forbidden_path_regex ]]; then
    echo "PRIVACY GUARD: forbidden private/archive artifact is tracked: $path" >&2
    fail=1
  fi
done < <(git ls-files)

if git grep -IEn -- '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|\bsk-[A-Za-z0-9_-]{20,}\b' -- . \
  ':(exclude)scripts/privacy-guard.sh' >/tmp/continuity-brain-secret-scan.txt 2>/dev/null; then
  echo "PRIVACY GUARD: possible private key or API secret detected:" >&2
  cat /tmp/continuity-brain-secret-scan.txt >&2
  fail=1
fi

if [[ $fail -ne 0 ]]; then
  exit 1
fi

echo "Continuity Brain privacy guard passed: no tracked archive/database/key artifacts or obvious secrets found."
