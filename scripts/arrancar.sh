#!/usr/bin/env bash
# SOLO DESARROLLO: deja lista la base de atlas (Podman o Docker) y arranca la aplicación en el puerto 8080, en
# primer plano (Ctrl+C la detiene).
#
# Uso:   scripts/arrancar.sh [--con-catedra]
#   (sin opción)   NO toca la cátedra: sin sincronización al arrancar, sin chequeo periódico y sin Kafka. Sirve para
#                  desarrollar y para el recorrido del cliente aunque la VPN esté caída.
#   --con-catedra  usa la configuración del .env tal cual: pide el snapshot, verifica versiones y escucha Kafka.
#                  Requiere la VPN (ZeroTier) levantada.
#
# Variables opcionales:
#   ATLAS_JWT_PUBLIC_KEY_LOCATION   clave pública con la que se validan los tokens
#                                   (por defecto ~/.atlas-dev/dev-public.pem, el par de DESARROLLO que usa cronos)
#
# Requisitos: el archivo .env (copiar de .env.example), Podman o Docker con compose, y java 21 para gradlew.
set -euo pipefail
cd "$(dirname "$0")/.."

CON_CATEDRA=0
case "${1:-}" in
  "") ;;
  --con-catedra) CON_CATEDRA=1 ;;
  -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
  *) echo "Opción desconocida: $1 (usar --con-catedra o ninguna)" >&2; exit 2 ;;
esac

[ -f .env ] || { echo "Falta el archivo .env: copiar .env.example y completarlo." >&2; exit 2; }

if command -v podman >/dev/null; then MOTOR=podman; elif command -v docker >/dev/null; then MOTOR=docker
else echo "Falta Podman o Docker." >&2; exit 2; fi

export ATLAS_JWT_PUBLIC_KEY_LOCATION="${ATLAS_JWT_PUBLIC_KEY_LOCATION:-$HOME/.atlas-dev/dev-public.pem}"
[ -f "$ATLAS_JWT_PUBLIC_KEY_LOCATION" ] || { echo "No existe la clave pública: $ATLAS_JWT_PUBLIC_KEY_LOCATION" >&2; exit 2; }

if ss -ltn 2>/dev/null | grep -q ':8080 '; then
  echo "El puerto 8080 ya está ocupado (¿atlas ya está corriendo?). No se arranca otra copia." >&2; exit 1
fi

echo "== Base de datos (atlas-db)"
"$MOTOR" compose up -d db
for _ in $(seq 1 30); do
  estado=$("$MOTOR" inspect -f '{{.State.Health.Status}}' atlas-db 2>/dev/null || echo "?")
  [ "$estado" = "healthy" ] && break
  sleep 2
done
[ "$estado" = "healthy" ] || { echo "La base no quedó sana (estado: $estado)." >&2; exit 1; }
echo "   atlas-db lista"

if [ "$CON_CATEDRA" = 1 ]; then
  echo "== Atlas CON la cátedra (necesita la VPN)"
else
  echo "== Atlas SIN tocar la cátedra"
  export ATLAS_SYNC_ON_STARTUP=false ATLAS_SYNC_PERIODIC_CHECK=false ATLAS_SYNC_KAFKA_ENABLED=false
fi
exec ./gradlew bootRun
