#requires -Version 7.2
param(
    [ValidateSet('start','stop','status','start-backend','stop-backend','start-kafka','stop-kafka','start-rocketmq','stop-rocketmq','start-redis','stop-redis','start-history','stop-history')]
    [string]$Action='status',
    [string]$JavaHome='F:\Java17\JDK 17',
    [string]$KafkaHome='F:\software\kafka\kafka_2.13-3.9.0',
    [string]$RedisHome='F:\software\Redis-x64-3.2.100',
    [string]$MysqlHome='F:\software\Mysql\mysql-8.0.31-winx64'
)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state=Join-Path $project '.local/integration'
$java=Join-Path $JavaHome 'bin/java.exe'
New-Item -ItemType Directory -Force -Path $state | Out-Null
function Save($file,$content) { [IO.File]::WriteAllText((Join-Path $state $file),$content,[Text.UTF8Encoding]::new($false)) }
function Port($number) {
    $client=[Net.Sockets.TcpClient]::new()
    try { $client.Connect('127.0.0.1',$number);return $true } catch {return $false} finally {$client.Dispose()}
}
function Launch($name,$file,$arguments,$port) {
    $pidFile=Join-Path $state "$name.pid"
    if(Port $port) {
        if(!(Test-Path $pidFile)) {throw "Port $port occupied by an unowned service."}
        $owned=Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue
        if(!$owned) {throw "Port $port occupied but saved process is gone."}
        return
    }
    foreach($stream in 'stdout','stderr') {
        $previous=Join-Path $state "$name.$stream.log"
        if(Test-Path $previous) {Copy-Item $previous (Join-Path $state "$name.$((Get-Date).ToString('yyyyMMddHHmmssfff')).$stream.previous.log")}
    }
    $process=Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $state -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $state "$name.stdout.log") -RedirectStandardError (Join-Path $state "$name.stderr.log")
    Save "$name.pid" ([string]$process.Id)
    for($i=0;$i -lt 100;$i++) {
        if(Port $port) {return}
        if($process.HasExited) {throw "$name exited; inspect .local/integration/$name.stderr.log"}
        Start-Sleep -Milliseconds 300
    }
    throw "$name startup timed out; inspect local logs."
}
function Halt($name) {
    $pidFile=Join-Path $state "$name.pid"
    if(!(Test-Path $pidFile)) {return}
    $ownedPid=[int](Get-Content $pidFile)
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid" -ErrorAction SilentlyContinue
    if(!$process) {return}
    $expected=if($name -eq 'backend'){'life-choice-backend'}elseif($name -eq 'kafka'){'kafka.Kafka'}else{'redis-server'}
    if(!$process.CommandLine.Contains($expected)) {throw "PID ownership changed for $name; refusing to stop."}
    Stop-Process -Id $ownedPid
    Wait-Process -Id $ownedPid -Timeout 15 -ErrorAction SilentlyContinue
}
$credential=Join-Path $state 'credentials.json'
if(!(Test-Path $credential)) {
    function Secret {return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()}
    Save 'credentials.json' (@{redisPassword=(Secret);authSecret=(Secret)} | ConvertTo-Json)
}
$secrets=Get-Content $credential -Raw | ConvertFrom-Json
$dbPassword=(Get-Content (Join-Path $project '.local/sharding/credentials.json') -Raw | ConvertFrom-Json).password
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($project))).Substring(0,12).ToLowerInvariant()
$runtime=Join-Path ([IO.Path]::GetTempPath()) "qupiao-integration-$hash"
if($Action -in 'stop','stop-backend') {Halt 'backend'}
if($Action -eq 'stop-kafka') {Halt 'kafka'}
if($Action -in 'stop','stop-rocketmq') {& (Join-Path $PSScriptRoot 'rocketmq-local.ps1') stop -JavaHome $JavaHome}
if($Action -in 'start','start-rocketmq') {& (Join-Path $PSScriptRoot 'rocketmq-local.ps1') start -JavaHome $JavaHome}
if($Action -in 'stop','stop-redis') {
    if(Port 36380) {
        $env:REDISCLI_AUTH=$secrets.redisPassword
        # Redis 3.2 CLI lacks REDISCLI_AUTH. Pipe AUTH through stdin to keep it out of argv/logs.
        @("AUTH $($secrets.redisPassword)",'SHUTDOWN SAVE') | & (Join-Path $RedisHome 'redis-cli.exe') -p 36380 | Out-Null
        Remove-Item Env:REDISCLI_AUTH
    }
}
if($Action -eq 'stop-history') {
    foreach($name in 'history0','history1') {
        Push-Location (Join-Path $project ".local/sharding/$name")
        try {& (Join-Path $MysqlHome 'bin/mysqladmin.exe') --defaults-extra-file=root.cnf shutdown; if($LASTEXITCODE -ne 0){throw "Shutdown failed: $name"}} finally {Pop-Location}
    }
}
if($Action -in 'start','start-history') {& (Join-Path $PSScriptRoot 'sharding-local.ps1') start -MysqlHome $MysqlHome}
if($Action -in 'start','start-redis') {
    $data=Join-Path $runtime 'redis';New-Item -ItemType Directory -Force -Path $data | Out-Null
    Save 'redis.conf' "bind 127.0.0.1`nport 36380`nrequirepass $($secrets.redisPassword)`nappendonly yes`nmaxmemory 128mb`nmaxmemory-policy noeviction`ndir $($data.Replace('\','/'))`n"
    Launch 'redis' (Join-Path $RedisHome 'redis-server.exe') @('redis.conf') 36380
}
if($Action -eq 'start-kafka') {
    $data=Join-Path $runtime 'kafka';New-Item -ItemType Directory -Force -Path $data | Out-Null
    Save 'kafka.properties' @"
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:39094
listeners=PLAINTEXT://127.0.0.1:39093,CONTROLLER://127.0.0.1:39094
advertised.listeners=PLAINTEXT://127.0.0.1:39093
inter.broker.listener.name=PLAINTEXT
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
log.dirs=$($data.Replace('\','/'))
num.partitions=3
num.network.threads=2
num.io.threads=4
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
log.retention.hours=24
log.segment.bytes=16777216
"@
    Save 'log4j.properties' "log4j.rootLogger=INFO,stdout`nlog4j.appender.stdout=org.apache.log4j.ConsoleAppender`nlog4j.appender.stdout.layout=org.apache.log4j.PatternLayout`nlog4j.appender.stdout.layout.ConversionPattern=%d %-5p %c - %m%n`n"
    if(!(Test-Path (Join-Path $data 'meta.properties'))) {
        Push-Location $state
        try {
            & $java '-Dlog4j.configuration=file:log4j.properties' '-cp' (Join-Path $KafkaHome 'libs/*') kafka.tools.StorageTool format -t fL6g3nShT-eMCtK--X86sx -c kafka.properties > (Join-Path $state 'kafka-format.log') 2>&1
            if($LASTEXITCODE -ne 0) {throw 'Kafka format failed; inspect local log.'}
        } finally {Pop-Location}
    }
    Launch 'kafka' $java @('-Xms128m','-Xmx384m','-Dlog4j.configuration=file:log4j.properties','-cp',('"'+(Join-Path $KafkaHome 'libs/*')+'"'),'kafka.Kafka','kafka.properties') 39093
}
if($Action -in 'start','start-backend') {
    $env:DATABASE_URL='jdbc:mysql://127.0.0.1:33307/qupiao_sharding_source?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000'
    $env:DATABASE_USERNAME='qupiao';$env:DATABASE_PASSWORD=$dbPassword
    $env:HISTORY_REPLICA_ENABLED='true';$env:HISTORY_DB_PASSWORD=$dbPassword
    $env:HISTORY_URLS='jdbc:mysql://127.0.0.1:33317/qupiao_history?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000,jdbc:mysql://127.0.0.1:33318/qupiao_history?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000'
    $env:REDIS_PORT='36380';$env:REDIS_PASSWORD=$secrets.redisPassword
    $env:ROCKETMQ_NAMESERVER='127.0.0.1:39876';$env:UPGRADE_TOPIC='qupiao-integration-orders';$env:UPGRADE_GROUP='qupiao-integration-orders-v1'
    $env:APP_AUTH_SECRET=$secrets.authSecret;$env:SANDBOX_PAYMENTS='true';$env:PLANNING_MODE='disabled';$env:SERVER_PORT='38093'
    $jar=Join-Path $project 'backend/target/life-choice-backend-1.0.0.jar'
    $ready=$false
    for($attempt=1;$attempt -le 3;$attempt++) {
        Launch 'backend' $java @('-Xms128m','-Xmx768m','-jar',('"'+$jar+'"'),'--upgrade.benchmark=true','--upgrade.demo-data=true') 38093
        for($poll=0;$poll -lt 100;$poll++) {
            try {if((Invoke-RestMethod 'http://127.0.0.1:38093/actuator/health/readiness' -TimeoutSec 2).status -eq 'UP') {$ready=$true;break}} catch {}
            if(!(Get-Process -Id ([int](Get-Content (Join-Path $state 'backend.pid'))) -ErrorAction SilentlyContinue)) {break}
            Start-Sleep -Milliseconds 300
        }
        if($ready){break}
        Halt 'backend'
    }
    if(!$ready){throw 'Backend did not become ready after three bounded attempts; inspect preserved startup logs.'}
}
if($Action -in 'start','status','start-backend','start-kafka','start-redis','stop','stop-history','start-history') {
    foreach($pair in @(@('source',33307),@('history0',33317),@('history1',33318),@('redis',36380),@('rocketmq-namesrv',39876),@('rocketmq-broker',39911),@('backend',38093))) {Write-Output "$($pair[0]): $(if(Port $pair[1]){'listening'}else{'stopped'})"}
}
