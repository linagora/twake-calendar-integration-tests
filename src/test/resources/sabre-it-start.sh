#!/bin/bash
# Starts esn-sabre for the integration tests.
#
# Compose passes the Kerberos keytab as base64 because CI's Docker daemon may not
# share the workspace: write it where esn-sabre expects it (KERBEROS_KEYTAB,
# /etc/krb5.keytab by default), readable by the nginx workers only.
set -e

if [ -n "${KERBEROS_KEYTAB_BASE64:-}" ]; then
  keytab=${KERBEROS_KEYTAB:-/etc/krb5.keytab}
  printf '%s' "$KERBEROS_KEYTAB_BASE64" | base64 -d > "$keytab"
  chown root:www-data "$keytab"
  chmod 0640 "$keytab"
fi

exec bash /var/www/scripts/start.sh
