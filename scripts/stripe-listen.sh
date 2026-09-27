#!/usr/bin/env bash
# Stripe webhook tunnel for the DEV profile (test-mode end-to-end billing).
# Copy the "whsec_..." printed on startup into env/env.dev.sh as
# STRIPE_WEBHOOK_SECRET, then restart the app.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
[[ -f env/env.dev.sh ]] && source env/env.dev.sh
: "${STRIPE_API_KEY:=${STRIPE_SECRET_KEY:-}}"
export STRIPE_API_KEY
: "${STRIPE_FORWARD_URL:=http://localhost:8080/api/v1/billing/stripe/webhook}"
: "${STRIPE_CLI:=stripe}"

EVENTS="checkout.session.completed,customer.subscription.created,customer.subscription.updated,customer.subscription.deleted,invoice.paid,invoice.payment_failed"
exec "$STRIPE_CLI" listen --forward-to "$STRIPE_FORWARD_URL" --events "$EVENTS"
