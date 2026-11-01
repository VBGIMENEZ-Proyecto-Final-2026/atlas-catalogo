#!/usr/bin/env bash
# SOLO DESARROLLO: firma con RS256 un JWT de prueba usando una clave privada local, para probar atlas-catalogo sin
# cronos-turnos. Los tokens reales los emite cronos-turnos; atlas solo conoce su clave pública.
#
# Uso:   scripts/dev-token.sh <clave-privada.pem> <usuario> [ROLE_USER|ROLE_ADMIN ...]
# Sin roles usa ROLE_USER. La vigencia es de una hora; para cambiarla: DEV_TOKEN_TTL=<segundos>
# (un valor negativo, por ejemplo -3600, da un token ya vencido, útil para probar el rechazo).
#
# La clave privada nunca debe commitearse ni usarse fuera del desarrollo local (ver README, "Probar la API").
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Uso: $0 <clave-privada.pem> <usuario> [ROLE_USER|ROLE_ADMIN ...]" >&2
  exit 2
fi
key="$1"; user="$2"; shift 2
roles=("$@"); [ "${#roles[@]}" -gt 0 ] || roles=(ROLE_USER)

[ -r "$key" ] || { echo "No se puede leer la clave privada: $key" >&2; exit 1; }
# El usuario y los roles van dentro de un JSON armado a mano: solo caracteres inofensivos.
[[ "$user" =~ ^[A-Za-z0-9._@-]+$ ]] || { echo "Usuario inválido: solo letras, números y . _ @ -" >&2; exit 1; }
for rol in "${roles[@]}"; do
  [[ "$rol" =~ ^ROLE_[A-Z_]+$ ]] || { echo "Rol inválido: $rol (ejemplo: ROLE_USER)" >&2; exit 1; }
done

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
exp=$((now + ${DEV_TOKEN_TTL:-3600}))
# Con una vigencia negativa el token nace vencido. Se lo emite una hora antes de vencer: un token cuyo "iat"
# sea posterior a su "exp" es incoherente y se rechazaría por eso, no por estar vencido.
iat=$now
if [ "$exp" -le "$now" ]; then iat=$((exp - 3600)); fi
auth="${roles[*]}"   # roles separados por espacio, como el claim "auth" de JHipster

header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
payload=$(printf '{"sub":"%s","auth":"%s","iat":%s,"exp":%s}' "$user" "$auth" "$iat" "$exp" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$key" -binary | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
