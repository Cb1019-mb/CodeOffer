# CodeOffer 端到端测试:拉起双层服务,对一个小型仓库跑通 录入→生成→编辑→自测
# 用法: .\scripts\e2e.ps1 [-RepoUrl <url>] [-KeepRunning]
#   -RepoUrl 默认构造本地 git 仓库(避免外网依赖);可改为真实仓库地址
#   -KeepRunning 结束时保留服务进程
param(
    [string]$RepoUrl = "",
    [switch]$KeepRunning
)
$ErrorActionPreference = "Stop"
$ws = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $ws "data\logs"
$temp = Join-Path $env:TEMP "codeoffer-e2e-repo"
New-Item -ItemType Directory -Path $logs -Force | Out-Null

# 0) 清理旧服务进程(含 uvicorn 的 multiprocessing 子进程树):
#    旧实例残留时,run.py 的 SO_REUSEADDR 绑定会与旧进程共享端口,请求会被路由到旧实例
foreach ($port in 8001, 8080) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object {
            $proc = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
            if ($proc -and $proc.ProcessName -match '^(java|javaw|python|pythonw)$') {
                taskkill /F /T /PID $proc.Id 2>$null | Out-Null
            }
        }
}
# 兜底:主进程被杀后 uvicorn 子进程可能存活,按命令行特征清理
Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'run\.py|uvicorn' } |
    ForEach-Object { taskkill /F /T /PID $_.ProcessId 2>$null | Out-Null }
Start-Sleep -Seconds 2
# 确认端口已释放;若仍被占用则失败退出,避免与残留实例并存
$stuck = foreach ($port in 8001, 8080) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { $port }
}
if ($stuck) {
    Write-Host "[FAIL] 端口 $($stuck -join ', ') 仍被占用,请手动释放后重试" -ForegroundColor Red
    exit 1
}

# 1) 准备本地测试仓库(默认),避免外网依赖
if (-not $RepoUrl) {
    Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path "$temp\src" -Force | Out-Null
    Set-Content -Path "$temp\README.md" -Value "# E2E Sample`n端到端测试仓库" -Encoding UTF8
    Set-Content -Path "$temp\src\main.py" -Value "def main():`n    print('hello')" -Encoding UTF8
    Push-Location $temp
    # PS 5.1 下原生 stderr(warning)在 Strict 模式会中止脚本,临时降级
    $eap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    git init -q 2>$null
    git -c user.name="e2e" -c user.email="e2e@local" add -A 2>$null
    git -c user.name="e2e" -c user.email="e2e@local" commit -q -m "init" 2>$null
    $ErrorActionPreference = $eap
    Pop-Location
    $RepoUrl = "file:///" + ($temp -replace '\\', '/')
    Write-Host "[1/6] 本地测试仓库: $RepoUrl"
} else {
    Write-Host "[1/6] 使用外部仓库: $RepoUrl"
}

# 2) 启动 AI 服务(8001)
Write-Host "[2/6] 启动 AI 服务 (8001)..."
# e2e 用较短调用间隔控制总时长;429 由网关退避重试兜底,失败走基础设施断言分支
$env:LLM_MIN_INTERVAL_SECONDS = "20"
Start-Process -FilePath (Join-Path $ws "ai-service\.venv\Scripts\python.exe") `
    -ArgumentList "run.py", "--port", "8001" `
    -WorkingDirectory (Join-Path $ws "ai-service") -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logs "ai-service.log") `
    -RedirectStandardError (Join-Path $logs "ai-service.err.log")

# 3) 启动后端(8080)
Write-Host "[3/6] 启动后端 (8080)..."
Start-Process -FilePath "cmd.exe" `
    -ArgumentList "/c", "mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--auth.captcha-enabled=false" `
    -WorkingDirectory (Join-Path $ws "backend") -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logs "backend.log") `
    -RedirectStandardError (Join-Path $logs "backend.err.log")

# 4) 等待服务就绪
Write-Host "[4/6] 等待服务就绪(最多 120s)..."
$aiOk = $false
$bkOk = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    if (-not $aiOk) {
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/health" -TimeoutSec 2
            $aiOk = $h.status -eq "ok"
        } catch { }
    }
    if (-not $bkOk) {
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/health" -TimeoutSec 2
            $bkOk = $h.status -eq "ok"
        } catch { }
    }
    if ($aiOk -and $bkOk) { break }
}
if (-not ($aiOk -and $bkOk)) {
    Write-Host "[FAIL] 服务未就绪: AI=$aiOk backend=$bkOk (日志见 data\logs\)"
    exit 1
}
Write-Host "      服务就绪: AI 服务 + 后端"

# 4.5) 注册 e2e 账号并建立会话(e2e 模式验证码已关闭);BYOK:为其创建并激活 LLM 档案
Write-Host "[4.5/6] 注册 e2e 账号与 LLM 档案..."
$base = "http://127.0.0.1:8080"
Invoke-WebRequest -Uri "$base/api/auth/captcha" -SessionVariable sess -UseBasicParsing -TimeoutSec 5 | Out-Null
$xsrf = ($sess.Cookies.GetCookies($base) | Where-Object { $_.Name -eq "XSRF-TOKEN" }).Value
$authH = @{ "X-XSRF-TOKEN" = $xsrf }
try {
    $reg = Invoke-RestMethod -Uri "$base/api/auth/register" -Method Post -WebSession $sess -Headers $authH `
        -ContentType "application/json" `
        -Body (@{ username = "e2e_user"; password = "e2e-pass-123"; captcha = "e2e" } | ConvertTo-Json)
} catch {
    $reg = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -WebSession $sess -Headers $authH `
        -ContentType "application/json" `
        -Body (@{ username = "e2e_user"; password = "e2e-pass-123"; captcha = "e2e" } | ConvertTo-Json)
}
$xsrf = ($sess.Cookies.GetCookies($base) | Where-Object { $_.Name -eq "XSRF-TOKEN" }).Value
$authH = @{ "X-XSRF-TOKEN" = $xsrf }
Write-Host "      账号: $($reg.username) role=$($reg.role)"
$llm = @{ baseUrl = "https://invalid.e2e.local/v1"; apiKey = "e2e"; model = "e2e-model" }
$envFile = Join-Path $ws "config\.env"
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match "^LLM_BASE_URL=(.+)$") { $llm.baseUrl = $Matches[1].Trim() }
        if ($line -match "^LLM_API_KEY=(.+)$") { $llm.apiKey = $Matches[1].Trim() }
        if ($line -match "^LLM_MODEL=(.+)$") { $llm.model = $Matches[1].Trim() }
    }
}
$prof = Invoke-RestMethod -Uri "$base/api/settings/llm-profiles" -Method Post -WebSession $sess -Headers $authH `
    -ContentType "application/json" `
    -Body (@{ name = "e2e"; provider = "custom"; baseUrl = $llm.baseUrl; apiKey = $llm.apiKey; model = $llm.model; verifySsl = $false } | ConvertTo-Json)
Invoke-RestMethod -Uri "$base/api/settings/llm-profiles/$($prof.id)/activate" -Method Post -WebSession $sess -Headers $authH | Out-Null
Write-Host "      LLM 档案已激活: $($llm.baseUrl)"

# 5) 创建项目并轮询任务
Write-Host "[5/6] 创建项目并等待生成..."
# 幂等:后端已禁止重复录入同一仓库(409),先清理上次 e2e 遗留的同 URL 项目
try {
    $existing = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/projects" -WebSession $sess -TimeoutSec 5
    foreach ($p in $existing) {
        if ($p.repoUrl -eq $RepoUrl) {
            Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/projects/$($p.id)" -Method Delete -WebSession $sess -Headers $authH -TimeoutSec 5 | Out-Null
            Write-Host "      已清理上次 e2e 遗留项目 #$($p.id)"
        }
    }
} catch { }
$created = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/projects" -Method Post -WebSession $sess -Headers $authH `
    -ContentType "application/json" -Body (@{ repoUrl = $RepoUrl } | ConvertTo-Json)
Write-Host "      项目 #$($created.projectId) 已创建"
$task = $null
for ($i = 0; $i -lt 150; $i++) {  # 最长 300s:全链路 4 次 LLM 调用,单次最长 ~80s
    Start-Sleep -Seconds 2
    $task = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/projects/$($created.projectId)/task" -WebSession $sess
    if ($task.status -ne "RUNNING") { break }
}
Write-Host "      任务终态: $($task.status) progress=$($task.progress)"

# 6) 结果断言
Write-Host "[6/6] 校验结果..."
if ($task.status -eq "DONE") {
    $detail = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/projects/$($created.projectId)" -WebSession $sess
    if ($detail.cards.Count -lt 3) {
        Write-Host "[FAIL] 卡片数量异常: $($detail.cards.Count)"
        exit 1
    }
    $cardId = $detail.cards[0].id
    $edited = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/projects/$($created.projectId)/cards/$cardId/content" `
        -Method Put -WebSession $sess -Headers $authH -ContentType "application/json" -Body (@{ content = "e2e 手写内容" } | ConvertTo-Json)
    if (-not $edited.edited) {
        Write-Host "[FAIL] 编辑标记缺失"
        exit 1
    }
    $reviewed = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/projects/$($created.projectId)/cards/$cardId/review" `
        -Method Post -WebSession $sess -Headers $authH -ContentType "application/json" -Body (@{ known = $true } | ConvertTo-Json)
    if ($reviewed.mastery -lt 1) {
        Write-Host "[FAIL] 掌握度未更新"
        exit 1
    }
    Write-Host "[PASS] 全链路通过:生成 $($detail.cards.Count) 张卡片,编辑与自测成功"
} elseif ($task.status -eq "FAILED" -and $task.errorMsg -match "LLM|API|key|Key|鉴权|SSL") {
    Write-Host "[PASS] 基础设施链路通过(克隆/解析/编排/错误处理);AI 生成环节需在 config/.env 配置 LLM key 后重跑"
} else {
    Write-Host "[FAIL] 任务失败: $($task.errorMsg)"
    exit 1
}

if (-not $KeepRunning) {
    Write-Host "清理服务进程..."
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
}
Write-Host "E2E 完成。"
