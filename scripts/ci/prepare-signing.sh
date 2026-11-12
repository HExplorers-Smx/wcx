#!/usr/bin/env bash
# CI signing: one bundled secret, legacy secrets, or a per-run temporary key.
# NEVER commit a personal keystore or signing credentials to this public repository.
set -euo pipefail
umask 077

: "${RUNNER_TEMP:?RUNNER_TEMP must be set by GitHub Actions}"
KS_FILE="$RUNNER_TEMP/wekit-keystore.jks"
CRED_FILE="$RUNNER_TEMP/keystore-creds.env"
rm -f "$KS_FILE" "$CRED_FILE"

if [[ -n "${WEKIT_SIGNING_BUNDLE:-}" ]]; then
  # A single base64(JSON) secret containing keystore_base64 and credentials.
  python3 - "$KS_FILE" "$CRED_FILE" <<'PY'
import base64
import json
import os
import shlex
import sys
from pathlib import Path

data = json.loads(base64.b64decode(os.environ["WEKIT_SIGNING_BUNDLE"], validate=True))
if data.get("version") != 1:
    raise ValueError("Unsupported signing bundle version")
fields = ("keystore_base64", "store_password", "key_alias", "key_password")
if any(not isinstance(data.get(k), str) or not data[k] for k in fields):
    raise ValueError("Signing bundle is missing a required field")
keystore = base64.b64decode(data["keystore_base64"], validate=True)
if len(keystore) < 100:
    raise ValueError("Signing keystore is too small")
Path(sys.argv[1]).write_bytes(keystore)
creds = {
    "KS_PASS": data["store_password"],
    "KEY_ALIAS": data["key_alias"],
    "KEY_PASS": data["key_password"],
}
Path(sys.argv[2]).write_text(
    "".join(f"{key}={shlex.quote(value)}\n" for key, value in creds.items()),
    encoding="utf-8",
)
PY
  echo "Signing source: WEKIT_SIGNING_BUNDLE (GitHub Actions secret)"
elif [[ -n "${KEYSTORE_BASE64:-}" ]]; then
  if [[ -z "${STORE_PASSWORD:-}" || -z "${KEY_ALIAS:-}" || -z "${KEY_PASSWORD:-}" ]]; then
    echo "::error::Legacy signing secrets are incomplete"
    exit 1
  fi
  printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$KS_FILE"
  printf 'KS_PASS=%q\nKEY_ALIAS=%q\nKEY_PASS=%q\n' \
    "$STORE_PASSWORD" "$KEY_ALIAS" "$KEY_PASSWORD" > "$CRED_FILE"
  echo "Signing source: legacy GitHub Actions secrets"
else
  # Signing is always required for installable APKs. This key is intentionally
  # unique per run; it cannot upgrade an APK signed with another certificate.
  KS_PASS='wcx-ci-ephemeral-pass'
  KEY_ALIAS='wcx-ci'
  KEY_PASS="$KS_PASS"
  keytool -genkeypair -noprompt \
    -keystore "$KS_FILE" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass "$KS_PASS" -keypass "$KEY_PASS" \
    -dname "CN=WCX CI Temporary, OU=CI, O=WCX, L=CI, S=CI, C=CN" >/dev/null 2>&1
  printf 'KS_PASS=%q\nKEY_ALIAS=%q\nKEY_PASS=%q\n' \
    "$KS_PASS" "$KEY_ALIAS" "$KEY_PASS" > "$CRED_FILE"
  echo "::warning::No fixed signing secret: temporary key used for this run. Signed APKs from other runs cannot be installed as in-place updates."
fi

source "$CRED_FILE"
if ! keytool -list -keystore "$KS_FILE" \
    -storepass "$KS_PASS" -alias "$KEY_ALIAS" >/dev/null 2>&1; then
  echo "::error::Keystore password or key alias is invalid"
  exit 1
fi
if ! keytool -certreq -keystore "$KS_FILE" \
    -storepass "$KS_PASS" -alias "$KEY_ALIAS" -keypass "$KEY_PASS" \
    -file "$RUNNER_TEMP/wekit-signing-check.csr" >/dev/null 2>&1; then
  echo "::error::Private-key password is invalid"
  exit 1
fi
rm -f "$RUNNER_TEMP/wekit-signing-check.csr"
chmod 600 "$KS_FILE" "$CRED_FILE"
echo "Signing key validated."
