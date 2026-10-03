$base = 'D:\workspace\' + 'tianshu' + '-opensource'
$ui = "$base\axiflux-app\ui"
Push-Location $ui
npx tsc --noEmit 2>&1 | Select-Object -First 10
Write-Output ("TSC_EXIT=" + $LASTEXITCODE)
npm run build 2>&1 | Select-Object -Last 6
Write-Output ("BUILD_EXIT=" + $LASTEXITCODE)
Pop-Location
