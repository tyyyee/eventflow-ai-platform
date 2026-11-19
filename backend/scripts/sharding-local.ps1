#requires -Version 7.2
param(
    [ValidateSet('init','start','stop','status','test','benchmark','sync','backfill','verify','local','sharded')][string]$Action = 'status',
    [string]$MysqlHome = '',
    [string]$Maven = '',
    [string]$RuntimeRoot = '',
    [string]$JavaHome = ''
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state = Join-Path $project '.local/sharding'
if (!$RuntimeRoot) {
    $projectHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($project))).Substring(0,12).ToLowerInvariant()
    $RuntimeRoot = Join-Path ([IO.Path]::GetTempPath()) ("qupiao-mysql-" + $projectHash)
}
if (!$MysqlHome) { $MysqlHome = Split-Path (Split-Path (Get-Command mysql.exe -ErrorAction Stop).Source) }
$server = Join-Path $MysqlHome 'bin/mysqld.exe'
$client = Join-Path $MysqlHome 'bin/mysql.exe'
$admin = Join-Path $MysqlHome 'bin/mysqladmin.exe'
$specs = @(
    @{name='source';port=33307;schema='qupiao_sharding_source'},
    @{name='history0';port=33317;schema='qupiao_history'},
    @{name='history1';port=33318;schema='qupiao_history'}
)
function Save-Utf8($path,$content) { [IO.File]::WriteAllText($path,$content,[Text.UTF8Encoding]::new($false)) }
function Sql($spec,$statement) {
    $config = Join-Path $state ($spec.name + '/root.cnf')
    Push-Location (Split-Path $config)
    try {
        $answer = $statement | & $client --defaults-extra-file=root.cnf --batch --skip-column-names --default-character-set=utf8mb4 2>&1
        if ($LASTEXITCODE -ne 0) { throw "MySQL operation failed for $($spec.name); inspect local logs." }
        return $answer
    } finally { Pop-Location }
}
if ($Action -eq 'init') {
    New-Item -ItemType Directory -Force -Path $state | Out-Null
    $credentialsFile = Join-Path $state 'credentials.json'
    if (!(Test-Path -LiteralPath $credentialsFile)) {
        $secret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
        Save-Utf8 $credentialsFile (@{password=$secret;created=(Get-Date).ToString('o')} | ConvertTo-Json)
    }
    $password = (Get-Content -LiteralPath $credentialsFile -Raw | ConvertFrom-Json).password
    foreach ($spec in $specs) {
        $dir = Join-Path $state $spec.name
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $runtimeDir = Join-Path $RuntimeRoot $spec.name
        New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
        $data = Join-Path $runtimeDir 'data'
        if (!(Test-Path -LiteralPath (Join-Path $data 'mysql'))) {
            & $server --no-defaults --initialize-insecure "--basedir=$MysqlHome" "--datadir=$data" --console 2>&1 | Out-File (Join-Path $dir 'initialize.log')
            if ($LASTEXITCODE -ne 0) { throw "Initialization failed: $($spec.name)" }
        }
        $ini = @"
[mysqld]
basedir="$($MysqlHome.Replace('\','/'))"
datadir="$($data.Replace('\','/'))"
port=$($spec.port)
bind-address=127.0.0.1
mysqlx=OFF
skip-log-bin
innodb-buffer-pool-size=$(if($spec.name -eq 'source'){'128M'}else{'64M'})
innodb-redo-log-capacity=64M
max-connections=40
performance-schema=ON
default-time-zone=+00:00
log-error="$((Join-Path $runtimeDir 'server.log').Replace('\','/'))"
pid-file="$((Join-Path $runtimeDir 'server.pid').Replace('\','/'))"
"@
        Save-Utf8 (Join-Path $dir 'server.ini') $ini
        $rootFile = Join-Path $dir 'root.cnf'
        if (!(Test-Path -LiteralPath $rootFile)) {
            Save-Utf8 $rootFile "[client]`nuser=root`nhost=127.0.0.1`nprotocol=tcp`nport=$($spec.port)`n"
        }
    }
}
if ($Action -in 'init','start') {
    if (!(Test-Path -LiteralPath (Join-Path $state 'credentials.json'))) { throw 'Run init first.' }
    $password = (Get-Content -LiteralPath (Join-Path $state 'credentials.json') -Raw | ConvertFrom-Json).password
    foreach ($spec in $specs) {
        $dir = Join-Path $state $spec.name
        try { $ready = (Sql $spec 'SELECT @@port;') -eq $spec.port } catch { $ready = $false }
        if (!$ready) {
            $probe = [Net.Sockets.TcpClient]::new()
            try { $probe.Connect('127.0.0.1',$spec.port); throw "Port already occupied: $($spec.port)" }
            catch [Net.Sockets.SocketException] { } finally { $probe.Dispose() }
            $ini = Join-Path $dir 'server.ini'
            Start-Process -FilePath $server -ArgumentList @('--defaults-file=server.ini') -WorkingDirectory $dir -WindowStyle Hidden | Out-Null
            $ready = $false
            for ($i=0;$i -lt 60;$i++) {
                try { if ((Sql $spec 'SELECT @@port;') -eq $spec.port) { $ready=$true; break } } catch { }
                Start-Sleep -Milliseconds 500
            }
            if (!$ready) { throw "Server not ready: $($spec.name)" }
        }
        if ($Action -eq 'init') {
            # Random credentials stay in ignored local files; never emit them.
            Sql $spec "ALTER USER 'root'@'localhost' IDENTIFIED BY '$password';" | Out-Null
            Save-Utf8 (Join-Path $dir 'root.cnf') "[client]`nuser=root`npassword=$password`nhost=127.0.0.1`nprotocol=tcp`nport=$($spec.port)`n"
            Sql $spec "CREATE DATABASE IF NOT EXISTS $($spec.schema) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS 'qupiao'@'127.0.0.1' IDENTIFIED BY '$password'; GRANT ALL PRIVILEGES ON $($spec.schema).* TO 'qupiao'@'127.0.0.1';" | Out-Null
            Sql $spec "CREATE DATABASE IF NOT EXISTS qupiao_history_bench CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON qupiao_history_bench.* TO 'qupiao'@'127.0.0.1'; SET GLOBAL innodb_buffer_pool_size=$(if($spec.name -eq 'source'){134217728}else{67108864});" | Out-Null
        }
    }
}
if ($Action -eq 'stop') {
    foreach ($spec in $specs) {
        $file=Join-Path $state ($spec.name+'/root.cnf')
        if (Test-Path -LiteralPath $file) {
            Push-Location (Split-Path $file)
            try { & $admin --defaults-extra-file=root.cnf shutdown 2>&1 | Out-Null } finally { Pop-Location }
        }
    }
}
if ($Action -in 'test','benchmark','sync','backfill','verify','local','sharded') {
    if (!$Maven) {
        $candidate = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9') -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
        $Maven = if ($candidate) { $candidate.FullName } else { (Get-Command mvn.cmd).Source }
    }
    $password = (Get-Content -LiteralPath (Join-Path $state 'credentials.json') -Raw | ConvertFrom-Json).password
    if (!$JavaHome) {
        $properties = & (Get-Command java.exe -ErrorAction Stop).Source -XshowSettings:properties -version 2>&1 | ForEach-Object { $_.ToString() }
        $JavaHome = (($properties | Select-String '^\s*java.home\s*=\s*(.+)$').Matches.Groups[1].Value).Trim()
    }
    if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) { throw 'A JDK 17+ is required; pass -JavaHome.' }
    $env:JAVA_HOME=$JavaHome
    $env:SHARDING_INTEGRATION='true'
    $env:SHARDING_BENCH=if($Action -eq 'benchmark'){'true'}else{'false'}
    $env:SHARDING_DB_PASSWORD=$password
    Push-Location (Join-Path $project 'backend')
    try {
        if($Action -eq 'benchmark') { & $Maven '-Psharding' '-Dtest=HistoryBenchmarkTest' test }
        elseif($Action -eq 'test') { & $Maven '-Psharding' test }
        else { & $Maven '-Psharding' compile exec:java '-Dexec.mainClass=com.hmdp.upgrade.sharding.HistoryMaintenanceMain' "-Dexec.args=$Action" }
        if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
    } finally { Pop-Location }
}
if ($Action -in 'init','start','status') {
    foreach ($spec in $specs) {
        try { $info=Sql $spec "SELECT VERSION(),@@port,@@datadir;"; Write-Output "$($spec.name): $info" }
        catch { Write-Output "$($spec.name): unavailable" }
    }
}
