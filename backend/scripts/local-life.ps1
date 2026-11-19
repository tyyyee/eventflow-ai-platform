#requires -Version 7.2
param(
    [ValidateSet('start','stop','status','start-backend','stop-backend','verify')][string]$Action='status',
    [ValidateSet('stub','deepseek')][string]$Mode='stub',
    [string]$JavaHome='F:\Java17\JDK 17',
    [string]$MysqlHome='F:\software\Mysql\mysql-8.0.31-winx64',
    [string]$RedisHome='F:\software\Redis-x64-3.2.100',
    [string]$RocketMqHome=$env:ROCKETMQ_HOME,
    [switch]$Verification
)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state=Join-Path $project '.local/local-life'
New-Item -ItemType Directory -Force -Path $state | Out-Null
if(!(Test-Path -LiteralPath (Join-Path $state 'maven-settings.xml'))){Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'assistant-maven-settings.xml') -Destination (Join-Path $state 'maven-settings.xml')}
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($project))).Substring(0,12).ToLowerInvariant()
$runtime=Join-Path ([IO.Path]::GetTempPath()) "qulife-local-$hash"
$rootClient=Join-Path $runtime 'root.cnf'
function Save($file,$value){[IO.File]::WriteAllText((Join-Path $state $file),$value,[Text.UTF8Encoding]::new($false))}
function Port([int]$number){$probe=[Net.Sockets.TcpClient]::new();try{$probe.Connect('127.0.0.1',$number);return $true}catch{return $false}finally{$probe.Dispose()}}
function Quote($value){return '"'+$value+'"'}
function Launch($name,$binary,$arguments,$port,$working=$state){
    if(Port $port){
        $saved=Join-Path $state "$name.pid"
        if(!(Test-Path -LiteralPath $saved)){throw "Port $port is occupied by an unowned service."}
        $owned=Get-CimInstance Win32_Process -Filter "ProcessId=$([int](Get-Content -LiteralPath $saved))"
        if(!$owned -or !($owned.CommandLine.Contains($project) -or $owned.CommandLine.Contains($runtime))){throw "Port $port is occupied by another process."}
        return
    }
    $process=Start-Process -FilePath $binary -ArgumentList $arguments -WorkingDirectory $working -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $state "$name.stdout.log") -RedirectStandardError (Join-Path $state "$name.stderr.log")
    Save "$name.pid" ([string]$process.Id)
    for($i=0;$i -lt 120;$i++){if(Port $port){return};if($process.HasExited){throw "$name exited; inspect .local/local-life/$name.stderr.log"};Start-Sleep -Milliseconds 300}
    throw "$name startup timed out; inspect .local/local-life logs."
}
function Halt($name){
    $saved=Join-Path $state "$name.pid"
    if(!(Test-Path -LiteralPath $saved)){return}
    $owned=Get-CimInstance Win32_Process -Filter "ProcessId=$([int](Get-Content -LiteralPath $saved))" -ErrorAction SilentlyContinue
    if(!$owned){return}
    if(!($owned.CommandLine.Contains($project) -or $owned.CommandLine.Contains($runtime))){throw "Saved $name process belongs to another project; refusing to stop."}
    Stop-Process -Id $owned.ProcessId
    Wait-Process -Id $owned.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
}
if($Action -eq 'stop-backend'){Halt 'frontend';Halt 'backend'}
if($Action -eq 'stop'){
    Halt 'frontend';Halt 'backend';Halt 'broker';Halt 'namesrv'
    if(Port 47380){$secret=Get-Content -LiteralPath (Join-Path $state 'credentials.json') -Raw|ConvertFrom-Json;@("AUTH $($secret.redisPassword)",'SHUTDOWN SAVE')|& (Join-Path $RedisHome 'redis-cli.exe') -p 47380|Out-Null}
    if(Port 44307){& (Join-Path $MysqlHome 'bin/mysqladmin.exe') ('--defaults-extra-file='+$rootClient) shutdown;if($LASTEXITCODE -ne 0){throw 'MySQL shutdown failed'}}
}
if($Action -in 'start','start-backend','verify'){
    $credential=Join-Path $state 'credentials.json'
    if(!(Test-Path -LiteralPath $credential)){
        function Secret{return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()}
        Save 'credentials.json' (@{mysqlPassword=(Secret);redisPassword=(Secret);authSecret=(Secret);demoPassword=(Secret);operatorPassword=(Secret)}|ConvertTo-Json)
    }
    $secret=Get-Content -LiteralPath $credential -Raw|ConvertFrom-Json
    $env:JAVA_HOME=$JavaHome;$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
    $env:DATABASE_USERNAME='assistant';$env:DATABASE_PASSWORD=$secret.mysqlPassword;$env:APP_AUTH_SECRET=$secret.authSecret
    $env:DATABASE_URL='jdbc:mysql://127.0.0.1:44307/qulife_local?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000'
    $env:REDIS_PORT='47380';$env:REDIS_PASSWORD=$secret.redisPassword;$env:SPRING_DATA_REDIS_DATABASE='0'
    $env:ROCKETMQ_NAMESERVER='127.0.0.1:50876';$env:UPGRADE_TOPIC='assistant-orders-v1';$env:UPGRADE_GROUP='assistant-orders-v1'
    $env:CLOSE_TOPIC='assistant-close-v1';$env:CLOSE_GROUP='assistant-close-v1';$env:SERVER_PORT='8293'
    $env:LOCAL_LIFE_ENABLED='true';$env:LOCAL_LIFE_DEMO='true';$env:LOCAL_LIFE_DEMO_PASSWORD=$secret.demoPassword;$env:LOCAL_LIFE_OPERATOR_PASSWORD=$secret.operatorPassword;
    $env:CORE32_ENABLED='false';$env:HISTORY_REPLICA_ENABLED='false';$env:SANDBOX_PAYMENTS='true';$env:ASSISTANT_MODE=$Mode
    if($Verification){$env:DATABASE_URL=$env:DATABASE_URL.Replace('/qulife_local?','/qulife_local_verify?');$env:SPRING_DATA_REDIS_DATABASE='13';$env:UPGRADE_TOPIC='local-life-verify-orders';$env:UPGRADE_GROUP='local-life-verify-orders';$env:CLOSE_TOPIC='local-life-verify-close';$env:CLOSE_GROUP='local-life-verify-close'}
    if($Action -eq 'start'){
        New-Item -ItemType Directory -Force -Path $runtime | Out-Null
        $mysqlData=Join-Path $runtime 'mysql'
        Save 'mysql.ini' "[mysqld]`nbasedir=$($MysqlHome.Replace('\','/'))`ndatadir=$($mysqlData.Replace('\','/'))`nbind-address=127.0.0.1`nport=44307`nmysqlx=0`ncharacter-set-server=utf8mb4`ninnodb-buffer-pool-size=128M`nmax-connections=60`ndefault-time-zone=+00:00`n"
        $mysqlDefault=Join-Path $runtime 'mysql.ini'
        [IO.File]::AppendAllText((Join-Path $state 'mysql.ini'),"log-bin-trust-function-creators=1`n",[Text.UTF8Encoding]::new($false))
        Copy-Item -LiteralPath (Join-Path $state 'mysql.ini') -Destination $mysqlDefault -Force
        if(!(Test-Path -LiteralPath (Join-Path $mysqlData 'auto.cnf'))){
            & (Join-Path $MysqlHome 'bin/mysqld.exe') ('--defaults-file='+$mysqlDefault) --initialize-insecure *> (Join-Path $state 'mysql-initialize.log')
            if($LASTEXITCODE -ne 0){throw 'Isolated MySQL initialization failed'}
        }
        Launch 'mysql' (Join-Path $MysqlHome 'bin/mysqld.exe') @('--defaults-file='+$mysqlDefault,'--console') 44307
        $configured=Join-Path $state 'mysql-configured'
        if(!(Test-Path -LiteralPath $configured)){
            "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($secret.mysqlPassword)';" | & (Join-Path $MysqlHome 'bin/mysql.exe') '--host=127.0.0.1' '--port=44307' '--user=root'
            if($LASTEXITCODE -ne 0){throw 'MySQL root initialization failed'}
            Save 'root.cnf' "[client]`nuser=root`npassword=$($secret.mysqlPassword)`nhost=127.0.0.1`nport=44307`n"
            Copy-Item -LiteralPath (Join-Path $state 'root.cnf') -Destination $rootClient -Force
            "CREATE DATABASE IF NOT EXISTS qulife_local CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS qulife_local_verify CHARACTER SET utf8mb4; CREATE USER IF NOT EXISTS 'assistant'@'127.0.0.1' IDENTIFIED BY '$($secret.mysqlPassword)'; GRANT ALL PRIVILEGES ON qulife_local.* TO 'assistant'@'127.0.0.1'; GRANT ALL PRIVILEGES ON qulife_local_verify.* TO 'assistant'@'127.0.0.1';" | & (Join-Path $MysqlHome 'bin/mysql.exe') ('--defaults-extra-file='+$rootClient)
            if($LASTEXITCODE -ne 0){throw 'MySQL application schema setup failed'}
            Save 'mysql-configured' 'ready'
        }
        $redisData=Join-Path $runtime 'redis';New-Item -ItemType Directory -Force -Path $redisData | Out-Null
        Save 'redis.conf' "bind 127.0.0.1`nport 47380`nrequirepass $($secret.redisPassword)`nappendonly yes`nmaxmemory 128mb`nmaxmemory-policy noeviction`ndir $($redisData.Replace('\','/'))`n"
        Launch 'redis' (Join-Path $RedisHome 'redis-server.exe') @((Quote (Join-Path $state 'redis.conf'))) 47380
        $distribution=if($RocketMqHome){$RocketMqHome}else{Join-Path $project '.local/rocketmq/distribution/rocketmq-all-5.3.2-bin-release'}
        if(!(Test-Path -LiteralPath (Join-Path $distribution 'lib/rocketmq-broker-5.3.2.jar'))){throw 'Local RocketMQ 5.3.2 distribution is required; see existing RocketMQ installation instructions.'}
        $env:ROCKETMQ_HOME=$distribution
        Save 'namesrv.conf' "listenPort=50876`nbindAddress=127.0.0.1`n"
        Save 'broker.conf' "brokerClusterName=qulife-local`nbrokerName=assistant-broker`nbrokerId=0`nnamesrvAddr=127.0.0.1:50876`nbrokerIP1=127.0.0.1`nbrokerIP2=127.0.0.1`nbindAddress=127.0.0.1`nlistenPort=50911`nhaListenPort=50912`nstorePathRootDir=$((Join-Path $runtime 'rocketmq').Replace('\','/'))`nbrokerRole=ASYNC_MASTER`nflushDiskType=SYNC_FLUSH`nautoCreateTopicEnable=true`ntimerWheelEnable=true`ntimerMaxDelaySec=86400`nmappedFileSizeCommitLog=67108864`nmappedFileSizeConsumeQueue=600000`nfileReservedTime=24`n"
        $rocketCommon=@('-Xms64m','-Xmx384m','-XX:MaxDirectMemorySize=128m','-Dfile.encoding=UTF-8',('-Drocketmq.home.dir='+(Quote $distribution)),'-cp',(Quote (Join-Path $distribution 'lib/*')))
        Launch 'namesrv' (Join-Path $JavaHome 'bin/java.exe') ($rocketCommon+@('org.apache.rocketmq.namesrv.NamesrvStartup','-c',(Quote (Join-Path $state 'namesrv.conf')))) 50876
        Launch 'broker' (Join-Path $JavaHome 'bin/java.exe') ($rocketCommon+@('org.apache.rocketmq.broker.BrokerStartup','-c',(Quote (Join-Path $state 'broker.conf')))) 50911
    }
    if($Action -eq 'verify'){
        $env:DATABASE_URL='jdbc:mysql://127.0.0.1:44307/qulife_local_verify?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000'
        $env:SPRING_DATA_REDIS_DATABASE='13';$env:ASSISTANT_INTEGRATION='true';$env:UPGRADE_TOPIC='assistant-verify-orders';$env:UPGRADE_GROUP='assistant-verify-orders';$env:CLOSE_TOPIC='assistant-verify-close';$env:CLOSE_GROUP='assistant-verify-close'
        $maven=Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9') -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue|Select-Object -First 1 -ExpandProperty FullName
        if(!$maven){$maven=(Get-Command mvn.cmd).Source}
        Push-Location (Join-Path $project 'backend')
        try {& $maven ('-Dmaven.repo.local='+(Join-Path $env:USERPROFILE '.m2/repository')) -s (Join-Path $state 'maven-settings.xml') -o -Psharding '-Dtest=AssistantIntegrationTest,LocalLifeIntegrationTest' test *> (Join-Path $state 'test-integration.log');$code=$LASTEXITCODE;Get-Content -LiteralPath (Join-Path $state 'test-integration.log') -Tail 20;exit $code}finally{Pop-Location}
    }
    $backend=Join-Path $project 'backend/target/life-choice-backend-1.0.0.jar'
    if(!(Test-Path -LiteralPath $backend)){throw 'Package the backend before starting it.'}
    Launch 'backend' (Join-Path $JavaHome 'bin/java.exe') @('-Xmx384m','-Dfile.encoding=UTF-8','-jar',(Quote $backend),'--upgrade.demo-data=true') 8293 $project
    $ready=$false
    for($attempt=0;$attempt -lt 60;$attempt++){
        try {$health=Invoke-RestMethod 'http://127.0.0.1:8293/actuator/health/readiness' -TimeoutSec 2;if($health.status -eq 'UP'){$ready=$true;break}}catch{}
        Start-Sleep -Milliseconds 500
    }
    if(!$ready){throw 'Backend did not become ready; inspect .local/local-life/backend logs.'}
    $env:DEV_PORT='4386';$env:API_TARGET='http://127.0.0.1:8293';$env:VITE_SANDBOX_PAYMENTS='true'
    Launch 'frontend' (Get-Command node.exe).Source @((Quote (Join-Path $project 'frontend/node_modules/vite/bin/vite.js')),'--host','127.0.0.1','--port','4386','--strictPort') 4386 (Join-Path $project 'frontend')
}
foreach($entry in @(@('mysql',44307),@('redis',47380),@('namesrv',50876),@('broker',50911),@('backend',8293),@('frontend',4386))){Write-Output "$($entry[0]): $(if(Port $entry[1]){'listening'}else{'stopped'}) ($($entry[1]))"}
