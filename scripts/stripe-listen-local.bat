@echo off
setlocal EnableExtensions
rem Local-profile Stripe webhook tunnel (detached wrapper).
rem Loads local secrets, pins the CLI + egress proxy, runs in the foreground of
rem this process so a detached parent can keep it alive and capture the whsec.
set "ROOT=%~dp0.."
pushd "%ROOT%"
if not exist logs mkdir logs
if exist "env\env.local.bat" call "env\env.local.bat"

set "HTTP_PROXY=http://127.0.0.1:7899"
set "HTTPS_PROXY=http://127.0.0.1:7899"
if not defined STRIPE_CLI set "STRIPE_CLI=C:\Users\hyx19\AppData\Local\Microsoft\WinGet\Links\stripe.exe"
if not defined STRIPE_FORWARD_URL set "STRIPE_FORWARD_URL=http://localhost:8080/api/v1/billing/stripe/webhook"
set "EVENTS=checkout.session.completed,customer.subscription.created,customer.subscription.updated,customer.subscription.deleted,invoice.paid,invoice.payment_failed"

"%STRIPE_CLI%" --api-key %STRIPE_SECRET_KEY% listen --forward-to %STRIPE_FORWARD_URL% --events %EVENTS%
popd
endlocal
