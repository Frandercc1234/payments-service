#!/usr/bin/env bash
# Prueba de humo de punta a punta contra el stack levantado con `docker compose up`:
#   pago -> outbox -> relay -> RabbitMQ -> notifications-service (y lo mismo con la confirmación por webhook).
# Requiere: curl, openssl. Uso: ./scripts/smoke-test.sh
set -euo pipefail

API="${API:-http://localhost:8080}"
NOTIF="${NOTIF:-http://localhost:8081}"
SECRET="${WEBHOOK_SECRET:-dev-only-secret-change-me}"
JSON='Content-Type: application/json'

id_of() { sed -E 's/.*"id":"([^"]+)".*/\1/'; }
count_notifications() { curl -fsS "$NOTIF/notifications?paymentId=$PID" | grep -o '"eventType"' | wc -l | tr -d ' '; }

wait_for_notifications() {
  local expected="$1"
  for _ in $(seq 1 60); do
    [ "$(count_notifications)" -ge "$expected" ] && return 0
    sleep 1
  done
  echo "FALLO: no llegaron $expected notificaciones en 60 s (hay $(count_notifications))" >&2
  exit 1
}

echo "1) Crear dos cuentas y un pago"
A=$(curl -fsS -X POST "$API/accounts" -H "$JSON" -d '{"ownerName":"Ana","currency":"CRC","initialBalance":100000}' | id_of)
B=$(curl -fsS -X POST "$API/accounts" -H "$JSON" -d '{"ownerName":"Beto","currency":"CRC","initialBalance":0}' | id_of)
PID=$(curl -fsS -X POST "$API/payments" -H "$JSON" -H "Idempotency-Key: smoke-$(date +%s)-$RANDOM" \
  -d "{\"sourceAccountId\":\"$A\",\"destinationAccountId\":\"$B\",\"amount\":25000,\"currency\":\"CRC\"}" | id_of)
echo "   pago $PID"

echo "2) Esperar la notificación payment.created (outbox -> relay -> RabbitMQ -> consumidor)"
wait_for_notifications 1

echo "3) Confirmación del proveedor (webhook firmado)"
BODY="{\"eventId\":\"smoke-$(date +%s)-$RANDOM\",\"type\":\"payment.confirmed\",\"paymentId\":\"$PID\"}"
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')
curl -fsS -X POST "$API/webhooks/provider" -H "$JSON" -H "X-Signature: sha256=$SIG" -d "$BODY" >/dev/null
wait_for_notifications 2

echo "4) Reenviar el MISMO webhook: no debe generar otra notificación"
curl -fsS -X POST "$API/webhooks/provider" -H "$JSON" -H "X-Signature: sha256=$SIG" -d "$BODY" >/dev/null
sleep 3

FINAL=$(count_notifications)
if [ "$FINAL" != "2" ]; then
  echo "FALLO: se esperaban exactamente 2 notificaciones y hay $FINAL" >&2
  exit 1
fi
echo "OK: 2 notificaciones (payment.created y payment.confirmed) para el pago $PID"
