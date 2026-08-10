# CodeOffer 本地一键启动脚本(开发模式)
# 启动:AI 服务(8001) + Java 后端(8080);前端开发服务器可选(见输出提示)
$ErrorActionPreference = "Stop"
$ws = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $ws "data\logs"
New-Item -ItemType Directory -Path $logs -Force | Out-Null

# 0) 清理旧服务进程:端口被占用时新进程会启动失败,导致"改了配置/代码但重启不生效"
#    仅杀 java/python 进程树,避免误杀无关程序;uvicorn 子进程按命令行特征兜底清理
foreach ($port in 8001, 8080) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object {
            $proc = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
            if ($proc -and $proc.ProcessName -match '^(java|javaw|python|pythonw)$') {
                taskkill /F /T /PID $proc.Id 2>$null | Out-Null
            }
        }
}
Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'run\.py|uvicorn' } |
    ForEach-Object { taskkill /F /T /PID $_.ProcessId 2>$null | Out-Null }
Start-Sleep -Seconds 2
# 确认端口已释放;若仍被占用则失败退出,避免与残留实例并存(其启动清理会误标进行中任务)
$stuck = foreach ($port in 8001, 8080) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { $port }
}
if ($stuck) {
    Write-Host "[FAIL] 端口 $($stuck -join ', ') 仍被占用,请手动释放后重试" -ForegroundColor Red
    exit 1
}

# 1) 准备 Python 虚拟环境
if (-not (Test-Path (Join-Path $ws "ai-service\.venv"))) {
    Write-Host "[1/3] 创建 Python 虚拟环境..." -ForegroundColor Cyan
    python -m venv (Join-Path $ws "ai-service\.venv")
    & (Join-Path $ws "ai-service\.venv\Scripts\pip") install -r (Join-Path $ws "ai-service\requirements.txt")
} else {
    Write-Host "[1/3] Python 虚拟环境已就绪" -ForegroundColor DarkGray
}

# 2) 启动 AI 服务(8001)
#    经 run.py 以 SO_REUSEADDR 绑定,规避 Windows 僵尸 socket(强杀旧进程后端口 10048)。
#    注:外部 socket 模式不支持 --reload,热更新需在无僵尸端口时手动 uvicorn --reload。
Write-Host "[2/3] 启动 AI 服务 http://127.0.0.1:8001 ..." -ForegroundColor Cyan
Start-Process -FilePath (Join-Path $ws "ai-service\.venv\Scripts\python.exe") `
    -ArgumentList "run.py", "--port", "8001" `
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
Write-Host "  - AI 服务    http://127.0.0.1:8001/api/health" -ForegroundColor Green
Write-Host "  - 后端       http://127.0.0.1:8080/api/health" -ForegroundColor Green
Write-Host "  - 日志目录   $logs"
Write-Host "  - 前端开发   cd frontend && npm run dev (端口 5173);生产模式前端产物由后端托管"
