#requires -Version 7.2
param([ValidateSet('init','start','stop','status','test','maintain','run','halt')][string]$Action='status',[string]$Command='status',[string]$MysqlHome='F:\software\Mysql\mysql-8.0.31-winx64',[string]$JavaHome='F:\Java17\JDK 17',[switch]$EnableFaults)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state=Join-Path $project '.local/core32';$baseState=Join-Path $project '.local/sharding'
$password=(Get-Content (Join-Path $baseState 'credentials.json') -Raw | ConvertFrom-Json).password
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($project))).Substring(0,12).ToLowerInvariant()
$runtime=Join-Path ([IO.Path]::GetTempPath()) "qupiao-core32-$hash"
$mysql=Join-Path $MysqlHome 'bin/mysql.exe';$server=Join-Path $MysqlHome 'bin/mysqld.exe'
$specs=0..3 | ForEach-Object {@{name="db$_";port=33400+$_}}
function Save($file,$text){[IO.File]::WriteAllText($file,$text,[Text.UTF8Encoding]::new($false))}
function Sql($folder,$query){Push-Location $folder;try{$result=$query | & $mysql --defaults-extra-file=root.cnf --batch --skip-column-names --default-character-set=utf8mb4 2>&1;if($LASTEXITCODE -ne 0){throw 'Database operation failed; inspect local configuration/logs.'};return $result}finally{Pop-Location}}
if($Action -eq 'init'){
    New-Item -ItemType Directory -Force $state | Out-Null
    Sql (Join-Path $baseState 'source') "CREATE DATABASE IF NOT EXISTS qupiao_core_catalog CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON qupiao_core_catalog.* TO 'qupiao'@'127.0.0.1';" | Out-Null
    foreach($spec in $specs){
        $folder=Join-Path $state $spec.name;$data=Join-Path $runtime ($spec.name+'/data');New-Item -ItemType Directory -Force $folder,(Split-Path $data) | Out-Null
        if(!(Test-Path (Join-Path $data 'mysql'))){& $server --no-defaults --initialize-insecure "--basedir=$MysqlHome" "--datadir=$data" --console > (Join-Path $folder 'initialize.log') 2>&1;if($LASTEXITCODE -ne 0){throw "Initialize failed: $($spec.name)"}}
        Save (Join-Path $folder 'server.ini') @"
[mysqld]
basedir="$($MysqlHome.Replace('\','/'))"
datadir="$($data.Replace('\','/'))"
port=$($spec.port)
bind-address=127.0.0.1
mysqlx=OFF
skip-log-bin
innodb-buffer-pool-size=64M
innodb-redo-log-capacity=64M
max-connections=40
default-time-zone=+00:00
log-error="$((Join-Path (Split-Path $data) 'server.log').Replace('\','/'))"
pid-file="$((Join-Path (Split-Path $data) 'server.pid').Replace('\','/'))"
"@
        if(!(Test-Path (Join-Path $folder 'root.cnf'))){Save (Join-Path $folder 'root.cnf') "[client]`nuser=root`nhost=127.0.0.1`nport=$($spec.port)`nprotocol=tcp`n"}
    }
}
if($Action -in 'init','start'){
    foreach($spec in $specs){
        $folder=Join-Path $state $spec.name
        try{$ready=(Sql $folder 'SELECT @@port;') -eq $spec.port}catch{$ready=$false}
        if(!$ready){
            $probe=[Net.Sockets.TcpClient]::new();try{$probe.Connect('127.0.0.1',$spec.port);throw "Unowned listener on port $($spec.port)"}catch [Net.Sockets.SocketException]{}finally{$probe.Dispose()}
            Start-Process $server -ArgumentList '--defaults-file=server.ini' -WorkingDirectory $folder -WindowStyle Hidden | Out-Null
            for($poll=0;$poll -lt 60;$poll++){try{if((Sql $folder 'SELECT @@port;') -eq $spec.port){$ready=$true;break}}catch{};Start-Sleep -Milliseconds 500}
            if(!$ready){throw "Not ready: $($spec.name)"}
        }
        if($Action -eq 'init'){
            Sql $folder "ALTER USER 'root'@'localhost' IDENTIFIED BY '$password';" | Out-Null
            Save (Join-Path $folder 'root.cnf') "[client]`nuser=root`npassword=$password`nhost=127.0.0.1`nport=$($spec.port)`nprotocol=tcp`n"
            Sql $folder "CREATE DATABASE IF NOT EXISTS qupiao_core32 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS 'qupiao'@'127.0.0.1' IDENTIFIED BY '$password'; GRANT ALL PRIVILEGES ON qupiao_core32.* TO 'qupiao'@'127.0.0.1';" | Out-Null
        }
    }
}
if($Action -eq 'stop'){foreach($spec in $specs){Push-Location (Join-Path $state $spec.name);try{& (Join-Path $MysqlHome 'bin/mysqladmin.exe') --defaults-extra-file=root.cnf shutdown}finally{Pop-Location}}}
if($Action -eq 'halt'){
    $pidFile=Join-Path $state 'backend.pid';if(Test-Path $pidFile){$ownedPid=[int](Get-Content $pidFile);$process=Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid" -ErrorAction SilentlyContinue;if($process){if(!$process.CommandLine.Contains((Join-Path $state 'core-backend-'))){throw 'Saved PID is no longer this project backend.'};Stop-Process -Id $ownedPid}}
}
if($Action -in 'test','maintain','run'){
    $env:JAVA_HOME=$JavaHome;$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
    $env:CORE32_PASSWORD=$password;$env:CORE32_INTEGRATION='true'
    $integrationSecrets=Get-Content (Join-Path $project '.local/integration/credentials.json') -Raw | ConvertFrom-Json
    $env:CORE32_REDIS_PASSWORD=$integrationSecrets.redisPassword
    $env:CORE32_REDIS_DATABASE='11'
    $catalogSchema=if($Action -eq 'test'){'qupiao_core_verification'}else{'qupiao_core_catalog'}
    $shardSchema=if($Action -eq 'test'){'qupiao_core32_verification'}else{'qupiao_core32'}
    if($Action -eq 'test'){
        Sql (Join-Path $baseState 'source') "CREATE DATABASE IF NOT EXISTS $catalogSchema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON $catalogSchema.* TO 'qupiao'@'127.0.0.1';" | Out-Null
        foreach($spec in $specs){Sql (Join-Path $state $spec.name) "CREATE DATABASE IF NOT EXISTS $shardSchema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON $shardSchema.* TO 'qupiao'@'127.0.0.1';" | Out-Null}
    }
    $env:CORE32_SOURCE_URL="jdbc:mysql://127.0.0.1:33307/${catalogSchema}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000"
    $env:CORE32_URLS=(0..3 | ForEach-Object {"jdbc:mysql://127.0.0.1:$(33400+$_)/${shardSchema}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000"}) -join ','
    if($Action -eq 'run'){
        $env:UPGRADE_FAULTS=if($EnableFaults){'true'}else{'false'};$env:UPGRADE_FAULT_DIRECTORY='faults'
        New-Item -ItemType Directory -Force (Join-Path $state 'faults') | Out-Null
        $env:DATABASE_URL=$env:CORE32_SOURCE_URL;$env:DATABASE_USERNAME='qupiao';$env:DATABASE_PASSWORD=$password
        $env:CORE32_ENABLED='true';$env:CORE32_DB_PASSWORD=$password;$env:HISTORY_REPLICA_ENABLED='false'
        $env:REDIS_PORT='36380';$env:REDIS_PASSWORD=$integrationSecrets.redisPassword;$env:SPRING_DATA_REDIS_DATABASE='11'
        # Local Windows handshake can exceed 300ms while four schemas initialize.
        $env:SPRING_DATA_REDIS_CONNECT_TIMEOUT='5s';$env:SPRING_DATA_REDIS_TIMEOUT='1s'
        $env:ROCKETMQ_NAMESERVER='127.0.0.1:39876';$env:UPGRADE_TOPIC='qupiao-core32-inactive-legacy'
        $env:APP_AUTH_SECRET=$integrationSecrets.authSecret;$env:SANDBOX_PAYMENTS='true';$env:PLANNING_MODE='disabled';$env:SERVER_PORT='38193'
        $jar=Join-Path $project 'backend/target/life-choice-backend-1.0.0.jar';$sha=(Get-FileHash $jar -Algorithm SHA256).Hash.Substring(0,12).ToLowerInvariant();$copy=Join-Path $state "core-backend-$sha.jar"
        if(!(Test-Path $copy)){Copy-Item $jar $copy}
        $probe=[Net.Sockets.TcpClient]::new();try{$probe.Connect('127.0.0.1',38193);throw 'Port 38193 is already occupied.'}catch [Net.Sockets.SocketException]{}finally{$probe.Dispose()}
        $process=Start-Process (Join-Path $JavaHome 'bin/java.exe') -ArgumentList @('-Xms128m','-Xmx768m','-jar',('"'+$copy+'"'),'--upgrade.benchmark=true','--upgrade.demo-data=true') -WorkingDirectory $state -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $state 'backend.stdout.log') -RedirectStandardError (Join-Path $state 'backend.stderr.log')
        Save (Join-Path $state 'backend.pid') ([string]$process.Id)
        for($poll=0;$poll -lt 100;$poll++){try{if((Invoke-RestMethod http://127.0.0.1:38193/actuator/health/readiness -TimeoutSec 2).status -eq 'UP'){Write-Output 'Core backend ready at http://127.0.0.1:38193';exit 0}}catch{};if($process.HasExited){throw 'Core backend exited; inspect .local/core32/backend.stdout.log'};Start-Sleep -Milliseconds 400};throw 'Core backend readiness timeout.'
    }
    $maven=Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9') -Recurse -Filter mvn.cmd | Select-Object -First 1 -ExpandProperty FullName
    Push-Location (Join-Path $project 'backend');try{if($Action -eq 'test'){& $maven -Psharding '-Dtest=Core32*Test' test}else{& $maven -Psharding compile exec:java '-Dexec.mainClass=com.hmdp.upgrade.core32.CoreMain' "-Dexec.args=$Command"};if($LASTEXITCODE -ne 0){throw 'Core sharding command failed.'}}finally{Pop-Location}
}
if($Action -in 'init','start','status'){foreach($spec in $specs){try{Write-Output "$($spec.name): $(Sql (Join-Path $state $spec.name) 'SELECT VERSION(),@@port,@@server_uuid;')"}catch{Write-Output "$($spec.name): stopped"}}}
