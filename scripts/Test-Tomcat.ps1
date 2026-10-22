param(
    [Parameter(Mandatory = $true)][string]$TomcatHome,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JdbcNativeDirectory,
    [ValidateSet('NOT_CONFIGURED', 'AVAILABLE', 'UNAVAILABLE')]
    [string]$ExpectedDatabaseStatus = 'NOT_CONFIGURED'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$repository = Split-Path -Parent $PSScriptRoot
$java = Join-Path $JavaHome 'bin\java.exe'
$homeDirectory = (Resolve-Path -LiteralPath $TomcatHome).Path
$war = Join-Path $repository 'target\event-booking.war'
if (-not (Test-Path -LiteralPath $war)) { throw 'Build target/event-booking.war first.' }
$serverInfo = & $java -cp (Join-Path $homeDirectory 'lib\catalina.jar') org.apache.catalina.util.ServerInfo
if ($LASTEXITCODE -ne 0 -or ($serverInfo -join "`n") -notmatch 'Apache Tomcat/10\.1\.44\b') {
    throw 'This smoke test requires Tomcat 10.1.44.'
}
if (($serverInfo -join "`n") -notmatch 'JVM Version:\s+17(?:[.+\s]|$)') {
    throw 'This smoke test requires a Java 17 runtime.'
}

# Allocate a loopback port and an independent CATALINA_BASE; keep files as evidence.
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$baseDirectory = Join-Path $repository ('target\tomcat-smoke-' + [guid]::NewGuid().ToString('N'))
foreach ($directory in @('conf', 'logs', 'temp', 'webapps', 'work')) {
    New-Item -ItemType Directory -Path (Join-Path $baseDirectory $directory) | Out-Null
}
foreach ($file in @('web.xml', 'catalina.properties', 'logging.properties')) {
    Copy-Item -LiteralPath (Join-Path $homeDirectory "conf\$file") -Destination (Join-Path $baseDirectory "conf\$file")
}
$serverXml = @"
<?xml version="1.0" encoding="UTF-8"?>
<Server port="-1">
  <Service name="P1Smoke">
    <Connector address="127.0.0.1" port="$port" protocol="HTTP/1.1" URIEncoding="UTF-8" connectionTimeout="5000"/>
    <Engine name="Catalina" defaultHost="localhost">
      <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="false">
        <Valve className="org.apache.catalina.valves.ErrorReportValve" showReport="false" showServerInfo="false"/>
      </Host>
    </Engine>
  </Service>
</Server>
"@
Set-Content -LiteralPath (Join-Path $baseDirectory 'conf\server.xml') -Value $serverXml -Encoding UTF8
Copy-Item -LiteralPath $war -Destination (Join-Path $baseDirectory 'webapps\event-booking.war')
$arguments = @(
    ('"-Dcatalina.home={0}"' -f $homeDirectory),
    ('"-Dcatalina.base={0}"' -f $baseDirectory),
    ('"-Djava.io.tmpdir={0}\temp"' -f $baseDirectory),
    ('"-Djava.util.logging.config.file={0}\conf\logging.properties"' -f $baseDirectory),
    '-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager'
)
if ($JdbcNativeDirectory) {
    $arguments += ('"-Djava.library.path={0}"' -f (Resolve-Path -LiteralPath $JdbcNativeDirectory).Path)
}
$arguments += @('-cp', ('"{0}\bin\bootstrap.jar;{0}\bin\tomcat-juli.jar"' -f $homeDirectory),
    'org.apache.catalina.startup.Bootstrap', 'start')
$serverProcess = $null
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(3)
try {
    $serverProcess = Start-Process -FilePath $java -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -WorkingDirectory $repository -RedirectStandardOutput (Join-Path $baseDirectory 'logs\stdout.log') `
        -RedirectStandardError (Join-Path $baseDirectory 'logs\stderr.log')
    $url = "http://127.0.0.1:$port/event-booking/"
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    $html = $null
    do {
        if ($serverProcess.HasExited) { throw 'The isolated Tomcat process exited during startup.' }
        try {
            $response = $client.GetAsync($url).GetAwaiter().GetResult()
            try {
                if ([int]$response.StatusCode -eq 200) {
                    $html = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                    $charset = $response.Content.Headers.ContentType.CharSet
                }
            } finally { $response.Dispose() }
        } catch [System.Net.Http.HttpRequestException] { }
          catch [System.Threading.Tasks.TaskCanceledException] { }
        if ($null -eq $html) { Start-Sleep -Milliseconds 300 }
    } while ($null -eq $html -and [DateTime]::UtcNow -lt $deadline)
    if ($null -eq $html) { throw 'HTTP smoke request did not return 200 before the deadline.' }
    Set-Content -LiteralPath (Join-Path $baseDirectory 'logs\home-response.html') -Value $html -Encoding UTF8

    # Read the source explicitly as UTF-8, including on Windows PowerShell.
    $servletSource = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repository 'src\main\java\com\eventbooking\controller\HomeServlet.java')
    $welcome = [regex]::Match($servletSource, 'setAttribute\("welcomeMessage", "([^"]+)"\)').Groups[1].Value
    if (-not $welcome -or -not $html.Contains($welcome)) { throw 'Vietnamese UTF-8 text did not round-trip.' }
    if ($charset -ne 'UTF-8') { throw 'Response encoding must be UTF-8.' }
    if (-not $html.Contains(('id="database-status">{0}<' -f $ExpectedDatabaseStatus))) {
        throw 'Unexpected database startup status.'
    }
    if ([regex]::Matches($html, '<li>').Count -ne 4 -or $html.Contains('${') -or $html.Contains('<c:')) {
        throw 'JSTL iteration or EL rendering failed.'
    }
    if ($html -match 'jdbc:sqlserver|java\.sql\.|Exception at') { throw 'Internal details appeared in the smoke response.' }
    foreach ($path in @('WEB-INF/views/home.jsp', 'missing')) {
        $response = $client.GetAsync($url + $path).GetAwaiter().GetResult()
        try {
            if ([int]$response.StatusCode -ne 404) { throw "Expected HTTP 404 for $path." }
        } finally { $response.Dispose() }
    }
    $response = $client.PostAsync($url, [System.Net.Http.StringContent]::new('')).GetAwaiter().GetResult()
    try {
        if ([int]$response.StatusCode -ne 405) { throw 'The foundation page must reject POST.' }
    } finally { $response.Dispose() }
    [pscustomobject]@{ Result = 'PASS'; Tomcat = '10.1.44'; Java = '17'; Database = $ExpectedDatabaseStatus; Evidence = $baseDirectory }
} finally {
    $client.Dispose()
    if ($null -ne $serverProcess) {
        if (-not $serverProcess.HasExited) {
            # Terminate only the disposable JVM created above; no shared service is touched.
            $serverProcess.Kill()
            $serverProcess.WaitForExit()
        }
        $serverProcess.Dispose()
    }
    Write-Output "Tomcat smoke evidence: $baseDirectory"
}
