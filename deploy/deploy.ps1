param(
    [ValidateSet('deploy', 'build', 'start', 'stop', 'restart', 'status', 'logs')]
    [string]$Action = 'deploy',
    [int]$Port = 8090,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$DataDir,
    [switch]$SkipTests,
    [switch]$SkipBuild,
    [switch]$NoDeps
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Backend = Join-Path $Root 'backend'
$Frontend = Join-Path $Root 'frontend'
$Runtime = Join-Path $Root 'deploy-runtime'
$Jar = Join-Path $Runtime 'kb-console.jar'
$PidFile = Join-Path $Runtime 'app.pid'
$LogDir = Join-Path $Runtime 'logs'
$AppLog = Join-Path $LogDir 'app.log'
$StdoutLog = Join-Path $LogDir 'console.out'
$StderrLog = Join-Path $LogDir 'console.err'

function Write-Step([string]$Message) {
    Write-Host "[deploy] $Message" -ForegroundColor Cyan
}

function Fail([string]$Message) {
    throw "[deploy] $Message"
}

function Quote-Arg([string]$Value) {
    if ($Value -match '[\s"]') {
        return '"' + ($Value -replace '"', '\"') + '"'
    }
    return $Value
}

function Get-JavaVersionLine([string]$JavaExe) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $line = (& $JavaExe -version 2>&1 | Select-Object -First 1)
        return [string]$line
    } catch {
        return $null
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Resolve-Java {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($JavaHome) {
        $candidates.Add((Join-Path $JavaHome 'bin\java.exe'))
    }
    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe'))
    }
    foreach ($root in @('D:\tools\jdk', 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft')) {
        if (Test-Path $root) {
            Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '(jdk|java).*(17|21)' } |
                ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin\java.exe')) }
        }
    }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates.Add($command.Source) }

    $checked = @()
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path $candidate)) { continue }
        $line = Get-JavaVersionLine $candidate
        if ($line -match 'version "(\d+)' -and [int]$Matches[1] -ge 17) {
            return $candidate
        }
        $checked += "$candidate : $line"
    }
    if ($checked.Count -gt 0) {
        Fail ("未找到 JDK 17+。已检测到：`n" + ($checked -join "`n") + "`n请设置 JAVA_HOME 或传入 -JavaHome。")
    }
    Fail '未找到 Java。请安装 JDK 17，或设置 JAVA_HOME / 传入 -JavaHome。'
}

function Assert-Java17([string]$JavaExe) {
    $firstLine = Get-JavaVersionLine $JavaExe
    if ($firstLine -notmatch 'version "(\d+)') {
        Fail "无法识别 Java 版本：$firstLine"
    }
    if ([int]$Matches[1] -lt 17) {
        Fail "需要 JDK 17+，当前为：$firstLine"
    }
    return $firstLine
}

function Assert-Command([string]$Name, [string]$Hint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Fail "未找到 $Name。$Hint"
    }
}

function Resolve-DataDir {
    if ($DataDir) { return $DataDir }
    if ($env:KB_DATA_DIR) { return $env:KB_DATA_DIR }
    $existing = Join-Path $Backend 'data'
    if (Test-Path $existing) { return $existing }
    return (Join-Path $Runtime 'data')
}

function Get-TrackedProcess {
    if (-not (Test-Path $PidFile)) { return $null }
    $saved = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $saved) { return $null }
    $process = Get-Process -Id $saved -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId=$saved" -ErrorAction SilentlyContinue).CommandLine
    if ($commandLine -and $commandLine -notmatch 'kb-console\.jar') { return $null }
    return $process
}

function Stop-App {
    $process = Get-TrackedProcess
    if ($process) {
        Write-Step "停止旧实例 PID=$($process.Id)"
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $process.Id -Timeout 20 -ErrorAction SilentlyContinue
    }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}

function Stop-PortOwnerIfOurs {
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $connection) { return }
    $ownerId = $connection.OwningProcess
    $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId=$ownerId" -ErrorAction SilentlyContinue).CommandLine
    if ($commandLine -and $commandLine -match 'kb-console.*\.jar') {
        Write-Step "端口 $Port 上是旧版本应用 PID=$ownerId，先停止"
        Stop-Process -Id $ownerId -Force -ErrorAction SilentlyContinue
        for ($i = 0; $i -lt 20; $i++) {
            if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { return }
            Start-Sleep -Milliseconds 500
        }
    }
    $remaining = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($remaining) {
        Fail "端口 $Port 被其他进程占用（PID=$($remaining.OwningProcess)），请先释放。"
    }
}

function Invoke-Build {
    Assert-Command 'mvn.cmd' '请安装 Maven 3.6+ 并加入 PATH。'
    Assert-Command 'npm.cmd' '请安装 Node.js 18+ 并加入 PATH。'
    $javaExe = Resolve-Java
    $javaVersion = Assert-Java17 $javaExe
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExe)
    Write-Step "Java：$javaVersion"

    Push-Location $Frontend
    try {
        if (-not (Test-Path 'node_modules')) {
            Write-Step '安装前端依赖'
            & npm.cmd ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { Fail 'npm ci 失败' }
        }
        Write-Step '构建前端'
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { Fail '前端构建失败' }
    } finally {
        Pop-Location
    }

    Push-Location $Backend
    try {
        Write-Step '构建后端并打入前端静态资源'
        $mavenArgs = @('-B', '-Pwith-frontend', 'package')
        if ($SkipTests) { $mavenArgs += '-DskipTests' }
        & mvn.cmd @mavenArgs
        if ($LASTEXITCODE -ne 0) { Fail 'Maven 构建失败' }
    } finally {
        Pop-Location
    }

    $built = Get-ChildItem (Join-Path $Backend 'target') -Filter 'kb-console-backend-*.jar' |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $built) { Fail '未找到构建产物 jar' }

    New-Item -ItemType Directory -Force -Path $Runtime, $LogDir | Out-Null
    Copy-Item $built.FullName $Jar -Force
    Set-Content -Path (Join-Path $Runtime 'version.txt') -Value ("{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $built.Name) -Encoding ASCII
    Write-Step "部署包已生成：$Jar"
}

function Start-App {
    if (-not (Test-Path $Jar)) { Fail "未找到 $Jar，请先执行 deploy 或 build。" }
    $javaExe = Resolve-Java
    Assert-Java17 $javaExe | Out-Null
    $resolvedData = Resolve-DataDir
    New-Item -ItemType Directory -Force -Path $Runtime, $LogDir, $resolvedData | Out-Null

    Stop-App
    Stop-PortOwnerIfOurs

    $dbPath = (Join-Path $resolvedData 'kbdb').Replace('\', '/')
    $uploadPath = (Join-Path $resolvedData 'uploads').Replace('\', '/')
    $logPath = $AppLog.Replace('\', '/')
    $arguments = @(
        '-Xms256m',
        '-Xmx1024m',
        '-Dfile.encoding=UTF-8',
        '-jar',
        $Jar,
        "--server.port=$Port",
        '--server.shutdown=graceful',
        "--spring.datasource.url=jdbc:h2:file:$dbPath;AUTO_SERVER=TRUE",
        "--kb.storage-dir=$uploadPath",
        "--logging.file.name=$logPath"
    )

    Write-Step "启动应用，端口 $Port，数据目录 $resolvedData"
    $argumentLine = ($arguments | ForEach-Object { Quote-Arg $_ }) -join ' '
    $process = Start-Process -FilePath $javaExe -ArgumentList $argumentLine -WorkingDirectory $Runtime `
        -RedirectStandardOutput $StdoutLog -RedirectStandardError $StderrLog -WindowStyle Hidden -PassThru
    Set-Content -Path $PidFile -Value $process.Id -Encoding ASCII

    for ($i = 0; $i -lt 90; $i++) {
        if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
            Show-LogTail
            Fail '应用进程已退出，启动失败。'
        }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2
            if ($health.code -eq '0' -and $health.data.status -eq 'UP') {
                Write-Step "启动成功：http://localhost:$Port/"
                Show-Status
                return
            }
        } catch {
        }
        Start-Sleep -Seconds 1
    }

    Show-LogTail
    Fail '等待健康检查超时（90 秒）。'
}

function Show-LogTail {
    $source = if (Test-Path $AppLog) { $AppLog } elseif (Test-Path $StdoutLog) { $StdoutLog } else { $null }
    if ($source) {
        Write-Host "---- 最近日志：$source ----" -ForegroundColor Yellow
        Get-Content $source -Tail 30 -ErrorAction SilentlyContinue
        Write-Host '---- 日志结束 ----' -ForegroundColor Yellow
    }
}

function Test-TcpEndpoint([string]$Url) {
    try {
        $uri = [Uri]$Url
        $client = New-Object Net.Sockets.TcpClient
        try {
            $client.Connect($uri.Host, $uri.Port)
            return $true
        } finally {
            $client.Close()
        }
    } catch {
        return $false
    }
}

function Show-Dependencies {
    if ($NoDeps) { return }
    if ($env:KB_ES_ENABLED -ne 'false') {
        $esUrl = if ($env:ELASTICSEARCH_URL) { $env:ELASTICSEARCH_URL } else { 'http://localhost:9200' }
        $state = if (Test-TcpEndpoint $esUrl) { '可用' } else { '不可用，检索将回退本地 H2' }
        Write-Host "  Elasticsearch : $esUrl  $state"
    }
    if ($env:KB_LLM_ENABLED -ne 'false') {
        $ollamaUrl = if ($env:OLLAMA_BASE_URL) { $env:OLLAMA_BASE_URL } else { 'http://localhost:11434' }
        $state = if (Test-TcpEndpoint $ollamaUrl) { '可用' } else { '不可用，只返回引用不生成回答' }
        Write-Host "  Ollama        : $ollamaUrl  $state"
    }
}

function Show-Status {
    $process = Get-TrackedProcess
    if (-not $process) {
        Write-Host "应用未运行（端口 $Port）。"
        return $false
    }
    Write-Host "应用运行中：PID=$($process.Id)"
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 3
        Write-Host "  健康检查     : $($health.data.status)"
    } catch {
        Write-Host '  健康检查     : 尚未就绪'
    }
    Write-Host "  页面          : http://localhost:$Port/"
    Write-Host "  接口          : http://localhost:$Port/api/health"
    Write-Host "  日志          : $AppLog"
    Show-Dependencies
    return $true
}

function Show-Logs {
    if (-not (Test-Path $AppLog) -and -not (Test-Path $StdoutLog)) {
        Write-Host '暂无日志，应用可能尚未启动。'
        return
    }
    Write-Host "日志目录：$LogDir"
    Show-LogTail
}

switch ($Action) {
    'build' {
        Invoke-Build
    }
    'deploy' {
        Stop-App
        Stop-PortOwnerIfOurs
        if (-not $SkipBuild) { Invoke-Build }
        Start-App
    }
    'start' {
        Start-App
    }
    'stop' {
        Stop-App
        Write-Step '已停止'
    }
    'restart' {
        Stop-App
        Start-App
    }
    'status' {
        if (-not (Show-Status)) { exit 1 }
    }
    'logs' {
        Show-Logs
    }
}