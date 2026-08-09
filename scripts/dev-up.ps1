# CodeOffer 本地一键启动脚本(开发模式)
# 启动:AI 服务(8000) + Java 后端(8080);前端开发服务器可选(见输出提示)
$ErrorActionPreference = "Stop"
$ws = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $ws "data\logs"
New-Item -ItemType Directory -Path $logs -Force | Out-Null

# 1) 准备 Python 虚拟环境
if (-not (Test-Path (Join-Path $ws "ai-service\.venv"))) {
    Write-Host "[1/3] 创建 Python 虚拟环境..." -ForegroundColor Cyan
    python -m venv (Join-Path $ws "ai-service\.venv")
    & (Join-Path $ws "ai-service\.venv\Scripts\pip") install -r (Join-Path $ws "ai-service\requirements.txt")
} else {
    Write-Host "[1/3] Python 虚拟环境已就绪" -ForegroundColor DarkGray
}

# 2) 启动 AI 服务(8000)
Write-Host "[2/3] 启动 AI 服务 http://127.0.0.1:8000 ..." -ForegroundColor Cyan
Start-Process -FilePath (Join-Path $ws "ai-service\.venv\Scripts\python.exe") `
    -ArgumentList "-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", "8000", "--reload" `
    -WorkingDirectory (Join-Path $ws "ai-service") -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logs "ai-service.log") `
    -RedirectStandardError (Join-Path $logs "ai-service.err.log")

# 3) 启动 Java 后端(8080)
Write-Host "[3/3] 启动后端 http://127.0.0.1:8080 ..." -ForegroundColor Cyan
Start-Process -FilePath "cmd.exe" `
    -ArgumentList "/c", "mvnw.cmd spring-boot:run" `
    -WorkingDirectory (Join-Path $ws "backend") -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logs "backend.log") `
    -RedirectStandardError (Join-Path $logs "backend.err.log")

Write-Host ""
Write-Host "CodeOffer 开发环境启动中:" -ForegroundColor Green
Write-Host "  - AI 服务    http://127.0.0.1:8000/api/health" -ForegroundColor Green
Write-Host "  - 后端       http://127.0.0.1:8080/api/health" -ForegroundColor Green
Write-Host "  - 日志目录   $logs"
Write-Host "  - 前端开发   cd frontend && npm run dev (端口 5173);生产模式前端产物由后端托管"
