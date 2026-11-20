#requires -Version 7.2
param([string]$JavaHome='F:\Java17\JDK 17',[string]$MysqlHome='F:\software\Mysql\mysql-8.0.31-winx64',[string]$Tests='',[switch]$Package)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state=Join-Path $project '.local/integration'
$secrets=Get-Content (Join-Path $state 'credentials.json') -Raw | ConvertFrom-Json
$password=(Get-Content (Join-Path $project '.local/sharding/credentials.json') -Raw | ConvertFrom-Json).password
$schema='qupiao_verify_'+(Get-Date -Format 'yyyyMMddHHmmss')
Push-Location (Join-Path $project '.local/sharding/source')
try {
    "CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON $schema.* TO 'qupiao'@'127.0.0.1';" | & (Join-Path $MysqlHome 'bin/mysql.exe') --defaults-extra-file=root.cnf
    if($LASTEXITCODE -ne 0){throw 'Could not create dedicated regression schema.'}
} finally {Pop-Location}
$env:JAVA_HOME=$JavaHome;$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
$env:DATABASE_URL="jdbc:mysql://127.0.0.1:33307/${schema}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000"
$env:UPGRADE_TEST_DB_URL=$env:DATABASE_URL;$env:DATABASE_USERNAME='qupiao';$env:DATABASE_PASSWORD=$password;$env:UPGRADE_DB_PASSWORD=$password
$env:REDIS_PORT='36380';$env:REDIS_PASSWORD=$secrets.redisPassword;$env:UPGRADE_REDIS_PASSWORD=$secrets.redisPassword
$env:SPRING_DATA_REDIS_CONNECT_TIMEOUT='5s';$env:SPRING_DATA_REDIS_TIMEOUT='1s'
$env:UPGRADE_TEST_REDIS_DB='13';$env:SPRING_DATA_REDIS_DATABASE='13'
$env:ROCKETMQ_NAMESERVER='127.0.0.1:39876';$env:UPGRADE_TOPIC="$schema-orders";$env:UPGRADE_GROUP="$schema-orders"
$env:CLOSE_TOPIC="$schema-close";$env:CLOSE_GROUP="$schema-close";$env:APP_AUTH_SECRET=$secrets.authSecret
$env:UPGRADE_INTEGRATION='true';$env:UPGRADE_RELIABILITY_INTEGRATION='true';$env:SHARDING_INTEGRATION='false';$env:HISTORY_REPLICA_ENABLED='false'
$env:SHARDING_BENCH='false';$env:SHARDING_BENCH_CLEANUP='false'
$env:CORE32_ENABLED='false';$env:CORE32_INTEGRATION='false'
$env:ROCKETMQ_INTEGRATION='true'
$maven=Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9') -Recurse -Filter mvn.cmd | Select-Object -First 1 -ExpandProperty FullName
Write-Output "Regression schema: $schema; Redis DB13; separate RocketMQ topics/groups."
[IO.File]::WriteAllText((Join-Path $state 'regression-environment.json'),(@{schema=$schema;redisDatabase=13;topic=$env:UPGRADE_TOPIC;startedAt=(Get-Date).ToString('o')} | ConvertTo-Json))
Push-Location (Join-Path $project 'backend')
try {
    $options=@('-Psharding','-B','-ntp');if($Tests){$options+="-Dtest=$Tests"};if($Package){$options+=@('clean','package')}else{$options+='test'}
    & $maven @options > integration-regression.log 2>&1
    $code=$LASTEXITCODE
    Get-Content integration-regression.log -Tail 18
    exit $code
} finally {Pop-Location}
