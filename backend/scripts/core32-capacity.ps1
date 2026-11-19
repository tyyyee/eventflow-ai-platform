#requires -Version 7.2
param([string]$MysqlHome='F:\software\Mysql\mysql-8.0.31-winx64',[string]$JavaHome='F:\Java17\JDK 17',[switch]$ResetSyntheticData)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state=Join-Path $project '.local/core32-capacity';New-Item -ItemType Directory -Force $state | Out-Null
$mysql=Join-Path $MysqlHome 'bin/mysql.exe'
function Sql($folder,$statement){Push-Location $folder;try{$value=$statement | & $mysql --defaults-extra-file=root.cnf --batch --skip-column-names 2>&1;if($LASTEXITCODE -ne 0){throw 'Capacity database operation failed.'};return $value}finally{Pop-Location}}
$source=Join-Path $project '.local/sharding/source'
$environment=@{startedAt=(Get-Date).ToString('o');schema='qupiao_core_capacity';sameHost=$true;sourcePort=33307;targetPorts=@(33400,33401,33402,33403);totalConnectionsPerMode=24;totalBufferPoolPerMode=268435456}
$system=Get-CimInstance Win32_OperatingSystem;$processor=Get-CimInstance Win32_Processor
$environment.hardware=@{cpu=$processor.Name;logicalProcessors=$processor.NumberOfLogicalProcessors;memoryMiB=[math]::Round($system.TotalVisibleMemorySize/1024);freeMiB=[math]::Round($system.FreePhysicalMemory/1024)}
$pidFile=Join-Path $project '.local/core32/backend.pid';$wasRunning=$false
if(Test-Path $pidFile){$ownedPid=[int](Get-Content $pidFile);$process=Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid" -ErrorAction SilentlyContinue;$wasRunning=$null -ne $process}
$originalBuffer=[long](Sql $source 'SELECT @@innodb_buffer_pool_size;')
$environment.originalSourceBuffer=$originalBuffer
try {
    if($wasRunning){& (Join-Path $PSScriptRoot 'core32-local.ps1') halt}
    Sql $source 'SET GLOBAL innodb_buffer_pool_size=268435456;' | Out-Null
    foreach($folder in @($source)+(0..3 | ForEach-Object {Join-Path $project ".local/core32/db$_"})){
        # Only the fixed benchmark schema is disposable; never reset any catalog/demo schema.
        if($ResetSyntheticData){Sql $folder 'DROP DATABASE IF EXISTS qupiao_core_capacity;' | Out-Null}
        Sql $folder "CREATE DATABASE IF NOT EXISTS qupiao_core_capacity CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON qupiao_core_capacity.* TO 'qupiao'@'127.0.0.1';" | Out-Null
        if([long](Sql $folder 'SELECT @@innodb_buffer_pool_size;') -ne $(if($folder -eq $source){268435456}else{67108864})){throw 'Unexpected buffer-pool budget'}
    }
    $evidence=Join-Path $project 'docs/verification/2026-10-27-core32-capacity';New-Item -ItemType Directory -Force $evidence | Out-Null
    $environment | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $evidence 'environment.json') -Encoding utf8
    $env:JAVA_HOME=$JavaHome;$env:MAVEN_OPTS='-Dfile.encoding=UTF-8';$env:CORE32_CAPACITY='true'
    $env:CORE32_PASSWORD=(Get-Content (Join-Path $project '.local/sharding/credentials.json') -Raw | ConvertFrom-Json).password
    $maven=Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9') -Recurse -Filter mvn.cmd | Select-Object -First 1 -ExpandProperty FullName
    Push-Location (Join-Path $project 'backend');try{& $maven -Psharding '-Dtest=CoreCapacityBenchmarkTest' test > core32-capacity.log 2>&1;$testCode=$LASTEXITCODE;Get-Content core32-capacity.log -Tail 18;if($testCode -ne 0){throw 'Capacity benchmark failed; partial results retained.'}}finally{Pop-Location}
} finally {
    Sql $source "SET GLOBAL innodb_buffer_pool_size=$originalBuffer;" | Out-Null
    if($wasRunning){& (Join-Path $PSScriptRoot 'core32-local.ps1') run}
}
