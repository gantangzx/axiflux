@echo off
setlocal EnableExtensions
rem ============================================================================
rem Stripe webhook tunnel for the DEV profile (local test-mode end-to-end billing).
rem
rem Forwards Stripe test events to the local app. The signing secret printed on
rem startup ("whsec_...") MUST be copied into env\env.dev.bat as STRIPE_WEBHOOK_SECRET
rem and the app restarted (the secret can change every time the tunnel restarts).
rem
rem Config (set in env\env.dev.bat, loaded here):
rem   STRIPE_API_KEY       sk_test_...   (or reuse STRIPE_SECRET_KEY)
rem   HTTP_PROXY/HTTPS_PROXY             only if your network needs a proxy
rem   STRIPE_FORWARD_URL   default http://localhost:8080/api/v1/billing/stripe/webhook
rem   STRIPE_CLI          full path to stripe.exe if not on PATH
rem ============================================================================

set "ROOT=%~dp0.."
pushd "%ROOT%"
if exist "env\env.dev.bat" call "env\env.dev.bat"
if not defined STRIPE_API_KEY if defined STRIPE_SECRET_KEY set "STRIPE_API_KEY=%STRIPE_SECRET_KEY%"
if not defined STRIPE_FORWARD_URL set "STRIPE_FORWARD_URL=http://localhost:8080/api/v1/billing/stripe/webhook"
if not defined STRIPE_CLI set "STRIPE_CLI=stripe"

set "EVENTS=checkout.session.completed,customer.subscription.created,customer.subscription.updated,customer.subscription.deleted,invoice.paid,invoice.payment_failed"

"%STRIPE_CLI%" listen --forward-to %STRIPE_FORWARD_URL% --events %EVENTS%
popd
endlocal
