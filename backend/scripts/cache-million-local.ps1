#requires -Version 7.2
param([ValidateSet('run','start','stop','status')][string]$Action='run',[switch]$Smoke,
    [string]$JavaHome='F:\Java17\JDK 17',[string]$MysqlHome='F:\software\Mysql\mysql-8.0.31-winx64',
    [string]$RedisHome='F:\software\Redis-x64-3.2.100',[string]$NodeExecutable='node')
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state=Join-Path $project '.local/cache-million'
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($project))).Substring(0,12).ToLowerInvariant()
$runtime=Join-Path ([IO.Path]::GetTempPath()) "qupiao-cache-million-$hash"
New-Item -ItemType Directory -Force $state,$runtime,(Join-Path $runtime 'mysql'),(Join-Path $runtime 'redis') | Out-Null
function Save($file,$value){[IO.File]::WriteAllText($file,$value,[Text.UTF8Encoding]::new($false))}
function Port($number){$client=[Net.Sockets.TcpClient]::new();try{$client.Connect('127.0.0.1',$number);return $true}catch{return $false}finally{$client.Dispose()}}
function Owned($name,$config){$file=Join-Path $state "$name.pid";if(!(Test-Path $file)){return $false};$ownedPid=[int](Get-Content $file);$process=Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid" -ErrorAction SilentlyContinue;return $process -and $process.CommandLine.Contains($config)}
function Sql($query){Push-Location $state;try{$value=$query | & (Join-Path $MysqlHome 'bin/mysql.exe') --defaults-extra-file=root.cnf --batch --skip-column-names --default-character-set=utf8mb4 2>&1;if($LASTEXITCODE -ne 0){throw 'Isolated cache database operation failed; credentials were not logged.'};return $value}finally{Pop-Location}}
function Launch($name,$file,$arguments,$config,$port){
    if(Port $port){if(!(Owned $name $config)){throw "Unowned listener on port $port"};return}
    $process=Start-Process $file -ArgumentList $arguments -WorkingDirectory $state -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $state "$name.stdout.log") -RedirectStandardError (Join-Path $state "$name.stderr.log")
    Save (Join-Path $state "$name.pid") ([string]$process.Id)
    for($poll=0;$poll -lt 100;$poll++){if(Port $port){return};if($process.HasExited){throw "$name exited; inspect .local/cache-million/$name.stderr.log"};Start-Sleep -Milliseconds 300};throw "$name startup timed out"
}
$credentials=Join-Path $state 'credentials.json'
if(!(Test-Path $credentials)){function Secret{return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()};Save $credentials (@{rootPassword=(Secret);dbPassword=(Secret);redisPassword=(Secret);authSecret=(Secret)} | ConvertTo-Json)}
$secrets=Get-Content $credentials -Raw | ConvertFrom-Json
$mysqlConfig=Join-Path $runtime 'mysql/server.ini';$redisConfig=Join-Path $runtime 'redis/redis.conf'
function StopServices{
    if(Port 33507){if(!(Owned 'mysql' $mysqlConfig)){throw 'Cache MySQL ownership changed'};Push-Location $state;try{& (Join-Path $MysqlHome 'bin/mysqladmin.exe') --defaults-extra-file=root.cnf shutdown;if($LASTEXITCODE -ne 0){throw 'Cache MySQL shutdown failed'}}finally{Pop-Location}}
    if(Port 36480){if(!(Owned 'redis' $redisConfig)){throw 'Cache Redis ownership changed'};@("AUTH $($secrets.redisPassword)",'SHUTDOWN NOSAVE') | & (Join-Path $RedisHome 'redis-cli.exe') -p 36480 | Out-Null}
}
if($Action -eq 'stop'){StopServices;exit 0}
if($Action -eq 'status'){foreach($port in 33507,36480){Write-Output "$port`: $(if(Port $port){'listening'}else{'stopped'})"};exit 0}
$data=Join-Path $runtime 'mysql/data'
$new=!(Test-Path (Join-Path $data 'mysql'))
if($new){& (Join-Path $MysqlHome 'bin/mysqld.exe') --no-defaults --initialize-insecure "--basedir=$MysqlHome" "--datadir=$data" --console > (Join-Path $state 'mysql-initialize.log') 2>&1;if($LASTEXITCODE -ne 0){throw 'Cache MySQL initialization failed'}}
Save $mysqlConfig @"
[mysqld]
basedir="$($MysqlHome.Replace('\','/'))"
datadir="$($data.Replace('\','/'))"
port=33507
bind-address=127.0.0.1
mysqlx=OFF
skip-log-bin
innodb-buffer-pool-size=256M
innodb-redo-log-capacity=128M
max-connections=64
performance-schema=ON
default-time-zone=+00:00
log-error="$((Join-Path $runtime 'mysql/server.log').Replace('\','/'))"
"@
Save (Join-Path $state 'root.cnf') "[client]`nuser=root`n$(if(!$new){"password=$($secrets.rootPassword)`n"})host=127.0.0.1`nport=33507`nprotocol=tcp`n"
Save (Join-Path $state 'bench.cnf') "[client]`nuser=cachebench`npassword=$($secrets.dbPassword)`nhost=127.0.0.1`nport=33507`nprotocol=tcp`n"
Launch 'mysql' (Join-Path $MysqlHome 'bin/mysqld.exe') @('--defaults-file="'+$mysqlConfig+'"') $mysqlConfig 33507
for($poll=0;$poll -lt 60;$poll++){try{if((Sql 'SELECT @@port;') -eq 33507){break}}catch{};if($poll -eq 59){throw 'Cache MySQL not ready'};Start-Sleep -Milliseconds 250}
if($new){Sql "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($secrets.rootPassword)';" | Out-Null;Save (Join-Path $state 'root.cnf') "[client]`nuser=root`npassword=$($secrets.rootPassword)`nhost=127.0.0.1`nport=33507`nprotocol=tcp`n"}
Sql "CREATE DATABASE IF NOT EXISTS qupiao_cache_bench CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS 'cachebench'@'127.0.0.1' IDENTIFIED BY '$($secrets.dbPassword)'; GRANT ALL PRIVILEGES ON qupiao_cache_bench.* TO 'cachebench'@'127.0.0.1';" | Out-Null
Save $redisConfig "bind 127.0.0.1`nport 36480`nrequirepass $($secrets.redisPassword)`nmaxmemory 256mb`nmaxmemory-policy noeviction`nappendonly no`nsave `"`"`ndir $((Join-Path $runtime 'redis').Replace('\','/'))`n"
Launch 'redis' (Join-Path $RedisHome 'redis-server.exe') @('"'+$redisConfig+'"') $redisConfig 36480
if($Action -eq 'start'){Write-Output 'Isolated MySQL 33507 and Redis 36480 ready.';exit 0}
$wasRunning=$false;$pidFile=Join-Path $project '.local/core32/backend.pid'
if(Test-Path $pidFile){$ownedPid=[int](Get-Content $pidFile);$process=Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid" -ErrorAction SilentlyContinue;if($process){if(!$process.CommandLine.Contains((Join-Path $project '.local/core32/core-backend-'))){throw 'Demo backend PID ownership changed'};$wasRunning=$true}}
$environment=@{startedAt=(Get-Date).ToString('o');sourcePort=33507;redisPort=36480;schema='qupiao_cache_bench';shopRows=1000000;mysqlBufferBytes=268435456;redisMaxBytes=268435456;demoBackendTemporarilyStopped=$wasRunning}
$system=Get-CimInstance Win32_OperatingSystem;$cpu=Get-CimInstance Win32_Processor
$environment.hardware=@{cpu=$cpu.Name;logicalProcessors=$cpu.NumberOfLogicalProcessors;memoryMiB=[math]::Round($system.TotalVisibleMemorySize/1024);freeMiB=[math]::Round($system.FreePhysicalMemory/1024)}
Save (Join-Path $state 'environment.json') ($environment | ConvertTo-Json -Depth 6)
try{
    if($wasRunning){& (Join-Path $PSScriptRoot 'core32-local.ps1') halt}
    $env:JAVA_HOME=$JavaHome;$env:CACHE_BENCH_MYSQL=(Join-Path $MysqlHome 'bin/mysql.exe')
    $options=@((Join-Path $PSScriptRoot 'cache-million-benchmark.mjs'));if($Smoke){$options+='--smoke'}
    & $NodeExecutable @options
    $nodeExit=$LASTEXITCODE
    Save (Join-Path $state 'node-exit.json') (@{exitCode=$nodeExit;recordedAt=(Get-Date).ToString('o')} | ConvertTo-Json)
    if($nodeExit -ne 0){throw "Cache benchmark client exited with code $nodeExit; partial evidence retained."}
}finally{
    StopServices
    if($wasRunning){& (Join-Path $PSScriptRoot 'core32-local.ps1') run}
}
