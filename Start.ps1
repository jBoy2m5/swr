param(
    [int]$Port = 8091,
    [string]$SqlServer = 'localhost',
    [int]$SqlPort = 1433,
    [string]$SqlUser = 'sa',
    [string]$CredentialFile
)
$ErrorActionPreference = 'Stop'
$javaCommand = (Get-Command java.exe -ErrorAction Stop).Source
$javacCommand = (Get-Command javac.exe -ErrorAction Stop).Source
$sqlcmd = (Get-Command sqlcmd.exe -ErrorAction Stop).Source
$jdbc = Join-Path $PSScriptRoot 'lib\mssql-jdbc-12.8.1.jre11.jar'
if (-not (Test-Path $jdbc)) { throw 'Missing JDBC jar in lib.' }

$cache = Join-Path $PSScriptRoot '.local'
New-Item -ItemType Directory -Path "$cache\classes" -Force | Out-Null
$localCredential = Join-Path $cache 'sql-login.xml'
$asked = $false
if ($CredentialFile) {
    $credential = Import-Clixml -LiteralPath $CredentialFile
} elseif ($env:BMM_DB_PASSWORD) {
    $credential = [pscredential]::new($SqlUser,(ConvertTo-SecureString $env:BMM_DB_PASSWORD -AsPlainText -Force))
} elseif (Test-Path $localCredential) {
    $credential = Import-Clixml -LiteralPath $localCredential
} else {
    $credential = [pscredential]::new($SqlUser,(Read-Host "SQL Server password for $SqlUser (first run)" -AsSecureString))
    $asked = $true
}

$env:BMM_JDBC = "jdbc:sqlserver://${SqlServer}:${SqlPort};databaseName=BepMeMoiMiniDB;encrypt=true;trustServerCertificate=true;loginTimeout=4"
$env:BMM_DB_USER = $credential.UserName
$env:BMM_DB_PASSWORD = $credential.GetNetworkCredential().Password
$env:BMM_PORT = "$Port"
$env:SQLCMDPASSWORD = $env:BMM_DB_PASSWORD
try {
    & $sqlcmd -S "tcp:$SqlServer,$SqlPort" -U $env:BMM_DB_USER -d master -C -l 5 -b -f 65001 -i "$PSScriptRoot\database\Setup.sql"
    if ($LASTEXITCODE -ne 0) { throw 'SQL setup failed. Check the login and SQL Server TCP port.' }
} finally {
    Remove-Item Env:SQLCMDPASSWORD -ErrorAction SilentlyContinue
}

if ($asked) { $credential | Export-Clixml -LiteralPath $localCredential }
$sources = Get-ChildItem -LiteralPath "$PSScriptRoot\src" -Filter '*.java' -File | ForEach-Object FullName
& $javacCommand -encoding UTF-8 --release 17 -d "$cache\classes" $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }

Push-Location -LiteralPath $PSScriptRoot
try {
    & $javaCommand '-Dfile.encoding=UTF-8' -cp "$cache\classes;$jdbc" App
    if ($LASTEXITCODE -ne 0) { throw 'The website stopped with an error.' }
} finally {
    Pop-Location
    Remove-Item Env:BMM_DB_PASSWORD -ErrorAction SilentlyContinue
}
