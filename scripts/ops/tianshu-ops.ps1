<#
.SYNOPSIS
  天枢 运维一体化脚本（Windows）：安装 / 升级 / 备份 / 恢复 / 验收。

.DESCRIPTION
  与 Linux 版 scripts/ops/*.sh 对齐。Windows 私有化交付（信创 x86 服务器、
  客户内网 Windows Server）常用本脚本。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tianshu-ops.ps1 -Action Verify -BaseUrl http://127.0.0.1:8080 -User tianshu -Password '***'
  powershell -ExecutionPolicy Bypass -File tianshu-ops.ps1 -Action Backup -InstallDir D:\tianshu -Out D:\backup
  powershell -ExecutionPolicy Bypass -File tianshu-ops.ps1 -Action Upgrade -Package D:\pkg\tianshu-offline-1.1.0.zip -InstallDir D:\tianshu
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet('Install', 'Upgrade', 'Backup', 'Restore', 'Verify')][string]$Action,
  [string]$InstallDir = 'D:\tianshu',
  [string]$BaseUrl = 'http://127.0.0.1:8080',
  [string]$User = '',
  [string]$Password = '',
  [string]$Package = '',
  [string]$Out = 'D:\backup\tianshu',
  [string]$File = '',
  [int]$Keep = 14,
  [switch]$ExpectEe,
  [switch]$Yes,
  [string]$PgDump = 'pg_dump',
  [string]$PgRestore = 'pg_restore'
)

$ErrorActionPreference = 'Stop'
$script:Results = @()

function Say($msg, $color = 'Cyan') { Write-Host "[ops] $msg" -ForegroundColor $color }
function Fail($msg) { Write-Host "[ops][error] $msg" -ForegroundColor Red; exit 1 }
function Record($level, $name, $detail) {
  $script:Results += [pscustomobject]@{ Level = $level; Check = $name; Detail = $detail }
  $c = switch ($level) { 'PASS' { 'Green' } 'WARN' { 'Yellow' } default { 'Red' } }
  Write-Host ('{0,-4} | {1,-34} | {2}' -f $level, $name, $detail) -ForegroundColor $c
}
function HttpCode($url) {
  # 用 HttpWebRequest：兼容 Windows PowerShell 5.1 与 PowerShell 7，且不会因 4xx/5xx 抛异常
  try {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Method = 'GET'
    $req.Timeout = 10000
    $req.AllowAutoRedirect = $false
    $resp = $req.GetResponse()
    $code = [int]$resp.StatusCode
    $resp.Close()
    return $code
  } catch [System.Net.WebException] {
    if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return 0 }
  } catch { return 0 }
}
function HttpJson($url, $token) {
  $h = @{}
  if ($token) { $h['Authorization'] = "Bearer $token" }
  try {
    return Invoke-RestMethod -Uri $url -Headers $h -TimeoutSec 15 -ErrorAction Stop
  } catch {
    if ($_.Exception.Response) {
      $code = [int]$_.Exception.Response.StatusCode
      throw "HTTP $code"
    }
    throw $_.Exception.Message
  }
}
function Load-Env($path) {
  $env:Map = @{}
  if (-not (Test-Path $path)) { return }
  Get-Content $path | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $env:Map[$kv[0].Trim()] = $kv[1]
  }
}

switch ($Action) {

  'Verify' {
    Write-Host '=====================================================================' -ForegroundColor Cyan
    Write-Host " 天枢 私有化交付验收  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Cyan
    Write-Host " 目标: $BaseUrl" -ForegroundColor Cyan
    Write-Host '=====================================================================' -ForegroundColor Cyan

    $h = HttpCode "$BaseUrl/actuator/health/liveness"
    if ($h -eq 200) { Record PASS 'actuator-liveness' '200' } else { Record FAIL 'actuator-liveness' "HTTP $h" }

    $hc = HttpCode "$BaseUrl/actuator/health"
    if ($hc -eq 200) { Record PASS 'actuator-health' '200' }
    elseif ($hc -in 401, 403) { Record WARN 'actuator-health' "受保护 (HTTP $hc)" }
    else { Record FAIL 'actuator-health' "HTTP $hc" }

    $idx = HttpCode "$BaseUrl/"
    if ($idx -eq 200) { Record PASS 'console-index' '200' } else { Record FAIL 'console-index' "HTTP $idx" }

    $token = $null
    if ($User -and $Password) {
      try {
        $body = @{ login = $User; password = $Password } | ConvertTo-Json -Compress
        $r = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 20
        $token = $r.data.token
        $script:UserId = $r.data.userId
        if ($token) { Record PASS 'login' "token_len=$($token.Length) userId=$($script:UserId)" } else { Record FAIL 'login' 'no token in response' }
      } catch {
        Record FAIL 'login' $_.Exception.Message
      }
    } else { Record WARN 'login' '未提供 -User/-Password' }

    $checks = [ordered]@{
      'api-agents'          = '/api/v1/agents'
      'api-tools'           = '/api/v1/tools'
      'api-templates'       = '/api/v1/templates'
      'api-skills'          = '/api/v1/skills'
      'api-sessions'        = '/api/v1/sessions'
      'api-approvals'       = '/api/v1/approvals'
      'api-scheduler'       = '/api/v1/scheduler/tasks'
      'api-audits'          = '/api/v1/audits/tool-executions'
      'api-orgs-mine'       = '/api/v1/orgs/mine'
      'api-billing'         = '/api/v1/billing/me'
      'api-usage'           = '/api/v1/usage/me'
      'api-config-settings' = '/api/v1/config/settings'
      'api-license'         = '/api/v1/admin/license'
      'api-admin-api-keys'  = '/api/v1/admin/api-keys'
    }
    foreach ($k in $checks.Keys) {
      try {
        $null = HttpJson "$BaseUrl$($checks[$k])" $token
        Record PASS $k '200'
      } catch {
        $m = "$($_.Exception.Message)"
        if ($m -match 'HTTP (401|403)') { Record WARN $k $m } else { Record FAIL $k $m }
      }
    }

    # 企业版能力探针（/api/v1/edition 需登录：200=EE 分发，404=未含 EE 模块）
    $editionCode = 0
    try {
      $null = HttpJson "$BaseUrl/api/v1/edition" $token
      $editionCode = 200
    } catch {
      if ("$($_.Exception.Message)" -match 'HTTP (\d{3})') { $editionCode = [int]$Matches[1] }
    }
    if ($ExpectEe) {
      if ($editionCode -eq 200) { Record PASS 'ee-edition' '200（企业版分发）' }
      else { Record FAIL 'ee-edition' "期望企业版但 HTTP $editionCode（404=未包含 tianshu-ee-*.jar）" }
    } elseif ($editionCode -eq 200) {
      Record PASS 'ee-edition' '200（企业版分发）'
    } else {
      Record WARN 'ee-edition' "HTTP $editionCode（社区版分发正常）"
    }

    # 长期记忆（向量库）探针（402=该能力不在当前套餐）
    if ($script:UserId) {
      try { $null = HttpJson "$BaseUrl/api/v1/memory/$($script:UserId)" $token; Record PASS 'long-term-memory' '200' }
      catch {
        $m = "$($_.Exception.Message)"
        if ($m -match 'HTTP 402') { Record WARN 'long-term-memory' '402 long_term_memory 不在当前套餐（产品门禁，非故障）' }
        else { Record WARN 'long-term-memory' $m }
      }
    } else { Record WARN 'long-term-memory' '未登录，跳过' }

    # 数据库与迁移
    Load-Env (Join-Path $InstallDir 'conf\tianshu.env')
    if (Get-Command psql -ErrorAction SilentlyContinue) {
      $pgUrl = $env:Map['PG_URL']
      if ($pgUrl) {
        $u = $pgUrl -replace '^jdbc:postgresql://', ''
        $hostPort = ($u -split '/')[0]; $db = ($u -split '/')[1]
        $hostOnly = ($hostPort -split ':')[0]; $port = ($hostPort -split ':')[1]
        if (-not $port) { $port = '5432' }
        $env:PGPASSWORD = $env:Map['PG_PASSWORD']
        try {
          $v = (& psql -h $hostOnly -p $port -U $env:Map['PG_USER'] -d $db -tAc 'select max(version) from flyway_schema_history where success' 2>$null)
          if ($v) { Record PASS 'flyway-version' "V$($v.Trim())" } else { Record WARN 'flyway-version' 'no rows' }
        } catch { Record WARN 'flyway-version' 'psql 查询失败' }
      }
    } else { Record WARN 'flyway-version' 'psql 不在 PATH' }

    if (Test-Path (Join-Path $InstallDir 'data\license\license.lic')) { Record PASS 'license-file' 'present' } else { Record WARN 'license-file' '未安装 license（社区版正常）' }

    # 磁盘余量：InstallDir 可能尚未创建（首次验收/仅验收社区包），不能因此让整个校验中断
    $driveName = $null
    if (Test-Path $InstallDir) { $driveName = (Get-Item $InstallDir).PSDrive.Name }
    elseif ($InstallDir -match '^([A-Za-z]):') { $driveName = $Matches[1] }
    if ($driveName) {
      try {
        $disk = Get-PSDrive -Name $driveName -ErrorAction Stop
        Record PASS 'disk-free' ("{0:N1} GB free on {1}:\" -f ($disk.Free / 1GB), $driveName)
      } catch { Record WARN 'disk-free' "无法读取驱动器 ${driveName}: 的空间" }
    } else { Record WARN 'disk-free' "无法确定 $InstallDir 所在驱动器" }

    $pass = ($script:Results | Where-Object Level -eq 'PASS').Count
    $warn = ($script:Results | Where-Object Level -eq 'WARN').Count
    $fail = ($script:Results | Where-Object Level -eq 'FAIL').Count
    Write-Host '---------------------------------------------------------------------'
    Write-Host " 结果: PASS=$pass  WARN=$warn  FAIL=$fail"
    $logDir = Join-Path $InstallDir 'logs'
    if (-not (Test-Path $logDir)) { $logDir = $env:TEMP }
    $json = Join-Path $logDir 'verify-install.json'
    $script:Results | ConvertTo-Json -Depth 4 | Set-Content -Path $json -Encoding UTF8
    Write-Host " 报告: $json"
    exit ($(if ($fail -eq 0) { 0 } else { 1 }))
  }

  'Backup' {
    $envFile = Join-Path $InstallDir 'conf\tianshu.env'
    if (-not (Test-Path $envFile)) { Fail "找不到 $envFile" }
    Load-Env $envFile
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    New-Item -ItemType Directory -Force -Path $Out | Out-Null
    $stage = Join-Path $env:TEMP "_ts_backup_$stamp"
    New-Item -ItemType Directory -Force -Path "$stage\db", "$stage\conf" | Out-Null

    $u = $env:Map['PG_URL'] -replace '^jdbc:postgresql://', ''
    $hostPort = ($u -split '/')[0]; $db = ($u -split '/')[1]
    $hostOnly = ($hostPort -split ':')[0]; $port = ($hostPort -split ':')[1]; if (-not $port) { $port = '5432' }
    $env:PGPASSWORD = $env:Map['PG_PASSWORD']
    Say "导出数据库 $db@$hostOnly`:$port"
    & $PgDump -h $hostOnly -p $port -U $env:Map['PG_USER'] -d $db -Fc --no-owner --no-privileges -f "$stage\db\tianshu.dump"
    if ($LASTEXITCODE -ne 0) { Fail 'pg_dump 失败' }

    foreach ($d in @('data\workspace', 'data\skills', 'data\license', 'registry-blobs', 'registry-keys')) {
      $p = Join-Path $InstallDir $d
      if (Test-Path $p) {
        $name = $d -replace '\\', '-'
        Compress-Archive -Path $p -DestinationPath "$stage\vol-$name.zip" -Force
      }
    }
    (Get-Content $envFile) -replace '(?i)((PASSWORD|SECRET|API_KEY|TOKEN)=).*', '$1***REDACTED***' |
      Set-Content "$stage\conf\tianshu.env.redacted" -Encoding UTF8

    $manifest = @(
      "backupTime=$stamp",
      "hostname=$env:COMPUTERNAME",
      "installDir=$InstallDir",
      "appJar=$((Get-ChildItem (Join-Path $InstallDir 'lib') -Filter 'tianshu-app*.jar' | Select-Object -First 1).Name)"
    ) -join "`n"
    $manifest | Set-Content "$stage\MANIFEST.txt" -Encoding UTF8

    $archive = Join-Path $Out "tianshu-backup-$stamp.zip"
    Compress-Archive -Path "$stage\*" -DestinationPath $archive -Force
    (Get-FileHash $archive -Algorithm SHA256).Hash | Set-Content "$archive.sha256" -Encoding ASCII
    Remove-Item $stage -Recurse -Force

    Get-ChildItem $Out -Filter 'tianshu-backup-*.zip' | Sort-Object LastWriteTime -Descending |
      Select-Object -Skip $Keep | ForEach-Object { Say "清理旧备份 $($_.Name)"; Remove-Item $_.FullName, "$($_.FullName).sha256" -Force -ErrorAction SilentlyContinue }

    Say "完成: $archive" 'Green'
  }

  'Restore' {
    if (-not $File) { Fail '必须指定 -File' }
    if (-not (Test-Path $File)) { Fail "备份不存在: $File" }
    if (Test-Path "$File.sha256") {
      $want = (Get-Content "$File.sha256").Trim()
      $got = (Get-FileHash $File -Algorithm SHA256).Hash
      if ($want -ne $got) { Fail 'SHA256 校验失败' }
      Say '备份完整性校验通过'
    }
    if (-not $Yes) {
      $a = Read-Host "即将覆盖 $InstallDir 的数据库与数据卷，输入 yes 继续"
      if ($a -ne 'yes') { Fail '已取消' }
    }
    $envFile = Join-Path $InstallDir 'conf\tianshu.env'
    Load-Env $envFile
    $u = $env:Map['PG_URL'] -replace '^jdbc:postgresql://', ''
    $hostPort = ($u -split '/')[0]; $db = ($u -split '/')[1]
    $hostOnly = ($hostPort -split ':')[0]; $port = ($hostPort -split ':')[1]; if (-not $port) { $port = '5432' }
    $env:PGPASSWORD = $env:Map['PG_PASSWORD']

    $stage = Join-Path $env:TEMP "_ts_restore_$(Get-Date -Format 'yyyyMMddHHmmss')"
    Expand-Archive -Path $File -DestinationPath $stage -Force
    Say '停止服务'
    if (Get-Service tianshu -ErrorAction SilentlyContinue) { Stop-Service tianshu -Force }
    Say '恢复数据库'
    & $PgRestore -h $hostOnly -p $port -U $env:Map['PG_USER'] -d $db --clean --if-exists --no-owner --no-privileges "$stage\db\tianshu.dump"
    Get-ChildItem "$stage" -Filter 'vol-*.zip' | ForEach-Object {
      Say "还原 $($_.Name)"
      Expand-Archive -Path $_.FullName -DestinationPath $InstallDir -Force
    }
    Remove-Item $stage -Recurse -Force
    if (Get-Service tianshu -ErrorAction SilentlyContinue) { Start-Service tianshu; Start-Sleep -Seconds 20 }
    $c = HttpCode "$BaseUrl/actuator/health/liveness"
    if ($c -eq 200) { Say '恢复完成，服务已就绪' 'Green' } else { Fail "服务未就绪（HTTP $c）" }
  }

  'Upgrade' {
    if (-not $Package) { Fail '必须指定 -Package' }
    if (-not (Test-Path $Package)) { Fail "包不存在: $Package" }
    $work = Join-Path $env:TEMP "_ts_upgrade_$(Get-Date -Format 'yyyyMMddHHmmss')"
    Expand-Archive -Path $Package -DestinationPath $work -Force
    $newJar = Get-ChildItem $work -Recurse -Filter 'tianshu-app-*.jar' | Select-Object -First 1
    if (-not $newJar) { Fail '包内无应用 jar' }
    $libDir = Join-Path $InstallDir 'lib'
    $oldJar = Get-ChildItem $libDir -Filter 'tianshu-app*.jar' | Select-Object -First 1
    Say "当前版本 $($oldJar.Name) -> 新版本 $($newJar.Name)"

    & $PSCommandPath -Action Backup -InstallDir $InstallDir -Out (Join-Path $InstallDir 'backup')
    if ($LASTEXITCODE -ne 0) { Fail '备份失败，终止升级' }

    if (Get-Service tianshu -ErrorAction SilentlyContinue) { Stop-Service tianshu -Force }
    Copy-Item $oldJar.FullName "$($oldJar.FullName).prev" -Force
    Copy-Item $newJar.FullName (Join-Path $libDir $newJar.Name) -Force
    Remove-Item $oldJar.FullName -Force
    if (Get-Service tianshu -ErrorAction SilentlyContinue) { Start-Service tianshu }

    $ok = $false
    for ($i = 0; $i -lt 60; $i++) {
      if ((HttpCode "$BaseUrl/actuator/health/liveness") -eq 200) { $ok = $true; break }
      Start-Sleep -Seconds 3
    }
    if (-not $ok) {
      Say '探活失败，回滚' 'Yellow'
      if (Get-Service tianshu -ErrorAction SilentlyContinue) { Stop-Service tianshu -Force }
      Copy-Item "$($oldJar.FullName).prev" $oldJar.FullName -Force
      if (Get-Service tianshu -ErrorAction SilentlyContinue) { Start-Service tianshu }
      Fail '升级失败并已回滚（数据库如需回退请用 Restore）'
    }
    Add-Content (Join-Path $InstallDir 'logs\upgrade-history.txt') "upgradedAt=$(Get-Date -Format o) from=$($oldJar.Name) to=$($newJar.Name)"
    Say "升级完成: $($oldJar.Name) -> $($newJar.Name)" 'Green'
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
  }

  'Install' {
    Say 'Windows 安装：请先完成以下前置，然后运行 Install' 'Yellow'
@'
前置清单（40 分钟内可完成）：
  1) 安装 JDK 25（或用分发包内 runtime\jdk），设置 JAVA_HOME
  2) 安装 PostgreSQL 16 + pgvector 扩展（或使用分发包内 docker compose）
  3) 安装 Redis 7（或使用 docker compose）
  4) 解包分发包到指定目录，例如 D:\tianshu-offline-1.0.0
运行：
  powershell -ExecutionPolicy Bypass -File tianshu-ops.ps1 -Action Install -Package D:\pkg\tianshu-offline-1.0.0.zip -InstallDir D:\tianshu
'@
    if (-not $Package) { return }
    $work = Join-Path $env:TEMP "_ts_install_$(Get-Date -Format 'yyyyMMddHHmmss')"
    Expand-Archive -Path $Package -DestinationPath $work -Force
    foreach ($d in 'bin', 'lib', 'conf', 'data', 'data\workspace', 'data\skills', 'data\license', 'logs', 'backup') {
      New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir $d) | Out-Null
    }
    $jar = Get-ChildItem $work -Recurse -Filter 'tianshu-app-*.jar' | Select-Object -First 1
    if (-not $jar) { Fail '包内无应用 jar' }
    Copy-Item $jar.FullName (Join-Path $InstallDir 'lib') -Force
    if (Test-Path (Join-Path $work 'runtime')) { Copy-Item (Join-Path $work 'runtime') $InstallDir -Recurse -Force }
    foreach ($f in 'LICENSE', 'NOTICE', 'LICENSE-EE.md') {
      $p = Join-Path $work $f
      if (Test-Path $p) { Copy-Item $p (Join-Path $InstallDir 'lib') -Force }
    }

    $envFile = Join-Path $InstallDir 'conf\tianshu.env'
    if (-not (Test-Path $envFile)) {
      $pgPwd = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
      $redisPwd = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
      $authSecret = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 48 | ForEach-Object { [char]$_ })
      $adminPwd = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 18 | ForEach-Object { [char]$_ })
      @"
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
TIANSHU_PUBLIC_URL=http://localhost:8080
PG_URL=jdbc:postgresql://127.0.0.1:5432/tianshu
PG_USER=postgres
PG_PASSWORD=$pgPwd
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=$redisPwd
AUTH_ENABLED=true
AUTH_SECRET=$authSecret
SESSION_PROVIDER=jpa
VECTOR_PROVIDER=pgvector
TIANSHU_BOOTSTRAP_ADMIN_PASSWORD=$adminPwd
ARK_API_KEY=
EMBED_API_KEY=
"@ | Set-Content $envFile -Encoding UTF8
      Say "初始管理员口令: tianshu / $adminPwd（请立即修改）" 'Yellow'
    }

    Say '启动（前台验证用，生产请注册为 Windows 服务）'
    $props = Get-Content $envFile | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object { $kv = $_ -split '=', 2; $kv[0] = $kv[1] }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    Say "启动命令: $java -jar $InstallDir\lib\$(Split-Path $jar -Leaf)"
    Write-Host '安装完成，请执行 Verify 验收:' -ForegroundColor Green
    Write-Host "  powershell -File $PSCommandPath -Action Verify -BaseUrl http://127.0.0.1:8080 -User tianshu -Password '<管理员口令>'"
  }
}
