# 构建前端产物并复制到后端 static 目录(生产模式:由 Java 后端统一托管,端口 8080)
# 用法: .\scripts\build-frontend.ps1
$ErrorActionPreference = "Stop"
$ws = Split-Path -Parent $PSScriptRoot
$static = Join-Path $ws "backend\src\main\resources\static"

Write-Host "[1/2] 构建前端(frontend -> dist)..."
Push-Location (Join-Path $ws "frontend")
# PS 5.1 下 npm 的 stderr 警告会触发 Stop,临时降级后按退出码判断
$eap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
npm run build 2>$null
$code = $LASTEXITCODE
$ErrorActionPreference = $eap
Pop-Location
if ($code -ne 0) { Write-Host "[FAIL] 前端构建失败 (exit $code)"; exit 1 }

Write-Host "[2/2] 复制到后端 static 目录..."
Remove-Item (Join-Path $static "*") -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item -Path (Join-Path $ws "frontend\dist\*") -Destination $static -Recurse -Force

Write-Host "[OK] 前端产物已托管至后端。重启后端后访问 http://localhost:8080"
