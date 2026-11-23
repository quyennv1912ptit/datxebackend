#Requires -Version 7.4
[CmdletBinding()]
param(
    [switch]$HealthOnly,
    [ValidateRange(1, 60)]
    [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$baseUrl = $null
$configuredUrl = [Environment]::GetEnvironmentVariable('PTITGO_STAGING_SMOKE_URL')
if (-not [Uri]::TryCreate($configuredUrl, [UriKind]::Absolute, [ref]$baseUrl) -or
    $baseUrl.Scheme -notin @('http', 'https') -or $baseUrl.AbsolutePath -ne '/' -or
    $baseUrl.UserInfo -or $baseUrl.Query -or $baseUrl.Fragment) {
    throw 'PTITGO_STAGING_SMOKE_URL phải là origin HTTP(S) độc lập.'
}
if (-not $baseUrl.IsLoopback) {
    $expectedHost = [Environment]::GetEnvironmentVariable('PTITGO_STAGING_SMOKE_HOST')
    if ($baseUrl.Scheme -ne 'https' -or $baseUrl.IdnHost -ne $expectedHost) {
        throw 'Staging qua mạng cần HTTPS và tên máy chủ đúng với PTITGO_STAGING_SMOKE_HOST đã xác nhận.'
    }
}

$tokens = @{}
$webOrigin = $null
if (-not $HealthOnly) {
    foreach ($role in @('CUSTOMER', 'DRIVER', 'ADMIN')) {
        $name = "PTITGO_STAGING_SMOKE_${role}_TOKEN"
        $token = [Environment]::GetEnvironmentVariable($name)
        if ($token -cnotmatch '^[A-Za-z0-9_.-]+$') {
            throw "Thiếu hoặc sai định dạng biến $name."
        }
        $tokens[$role] = $token
    }
    $configuredOrigin = [Environment]::GetEnvironmentVariable('PTITGO_STAGING_SMOKE_WEB_ORIGIN')
    if ($configuredOrigin) {
        $originUri = $null
        if (-not [Uri]::TryCreate($configuredOrigin, [UriKind]::Absolute, [ref]$originUri) -or
            $originUri.Scheme -notin @('http', 'https') -or $originUri.AbsolutePath -ne '/' -or
            $originUri.UserInfo -or $originUri.Query -or $originUri.Fragment) {
            throw 'PTITGO_STAGING_SMOKE_WEB_ORIGIN phải là origin của web admin staging.'
        }
        $webOrigin = $originUri.GetLeftPart([UriPartial]::Authority)
    }
}

function Invoke-JsonGet([string]$Path, [string]$Role, [string]$Origin) {
    $request = [System.Net.Http.HttpRequestMessage]::new('GET', [Uri]::new($baseUrl, $Path))
    $response = $null
    try {
        $request.Headers.Accept.ParseAdd('application/json')
        if ($Role) {
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $tokens[$Role])
            $request.Headers.Add('X-Role', $Role)
        }
        if ($Origin) { $request.Headers.Add('Origin', $Origin) }

        try { $response = $http.SendAsync($request).GetAwaiter().GetResult() }
        catch { throw "GET $Path thất bại ở lớp kết nối hoặc hết thời gian chờ." }
        if ([int]$response.StatusCode -ne 200) {
            throw "GET $Path trả HTTP $([int]$response.StatusCode), cần 200."
        }
        if ($Origin -and (-not $response.Headers.Contains('Access-Control-Allow-Origin') -or
                ($response.Headers.GetValues('Access-Control-Allow-Origin') -join ',') -cne $Origin)) {
            throw "GET $Path thiếu Access-Control-Allow-Origin khớp origin staging."
        }
        try { $json = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json -AsHashtable }
        catch { throw "GET $Path không trả JSON hợp lệ." }
        if ($json -isnot [System.Collections.IDictionary]) { throw "GET $Path cần JSON object." }
        return ,$json
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Test-Api([string]$Path, [string]$Role, [string]$ExpectedRole, [string]$Origin) {
    $json = Invoke-JsonGet $Path $Role $Origin
    if ($json['success'] -isnot [bool] -or -not $json['success']) {
        throw "GET $Path thiếu success=true."
    }
    if ($ExpectedRole) {
        if ($json['data'] -isnot [System.Collections.IDictionary] -or $json['data']['role'] -cne $ExpectedRole) {
            throw "GET $Path thiếu vai trò $ExpectedRole."
        }
    }
    Write-Output "PASS HTTP $Role $Path"
}

function Send-StompFrame($Socket, [string]$Frame, $CancellationToken) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Frame + [char]0)
    $Socket.SendAsync([ArraySegment[byte]]::new($bytes), 'Text', $true, $CancellationToken).GetAwaiter().GetResult() | Out-Null
}

function Test-Stomp([string]$Role) {
    $target = [UriBuilder]::new([Uri]::new($baseUrl, '/ws-location'))
    $target.Scheme = if ($baseUrl.Scheme -eq 'https') { 'wss' } else { 'ws' }
    $target.Port = $baseUrl.Port
    $socket = [System.Net.WebSockets.ClientWebSocket]::new()
    $deadline = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSeconds))
    $message = [IO.MemoryStream]::new()
    try {
        $socket.Options.AddSubProtocol('v12.stomp')
        if ($webOrigin) { $socket.Options.SetRequestHeader('Origin', $webOrigin) }
        $socket.ConnectAsync($target.Uri, $webSockets, $deadline.Token).GetAwaiter().GetResult() | Out-Null
        $connect = "CONNECT`naccept-version:1.2`nhost:$($baseUrl.IdnHost)`nheart-beat:0,0`nAuthorization:Bearer $($tokens[$Role])`nX-Role:$Role`n`n"
        Send-StompFrame $socket $connect $deadline.Token
        $buffer = [byte[]]::new(1024)
        do {
            $result = $socket.ReceiveAsync([ArraySegment[byte]]::new($buffer), $deadline.Token).GetAwaiter().GetResult()
            if ($result.MessageType -ne [System.Net.WebSockets.WebSocketMessageType]::Text -or
                $message.Length + $result.Count -gt 8192) {
                throw 'Phản hồi WebSocket vượt giới hạn hoặc sai định dạng.'
            }
            $message.Write($buffer, 0, $result.Count)
            $frame = [Text.Encoding]::UTF8.GetString($message.ToArray())
        } until ($frame.Contains([char]0))
        if ($frame -cnotmatch '^[\r\n]*CONNECTED\r?\n') { throw 'Thiếu STOMP CONNECTED.' }
        Send-StompFrame $socket "DISCONNECT`n`n" $deadline.Token
        Write-Output "PASS STOMP $Role /ws-location"
    } catch {
        # Không in khung ERROR của broker hoặc request chứa token.
        throw "STOMP $Role không hoàn tất CONNECT/DISCONNECT trong thời gian cho phép."
    } finally {
        $message.Dispose()
        $deadline.Dispose()
        $socket.Dispose()
    }
}

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseCookies = $false
$http = [System.Net.Http.HttpClient]::new($handler, $false)
$http.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
$http.MaxResponseContentBufferSize = 1MB
$webSockets = [System.Net.Http.HttpMessageInvoker]::new($handler, $false)
try {
    foreach ($path in @('/actuator/health', '/actuator/health/readiness')) {
        $health = Invoke-JsonGet $path
        if ($health['status'] -cne 'UP') { throw "GET $path chưa UP." }
        Write-Output "PASS HTTP $path"
    }
    if ($HealthOnly) {
        Write-Output 'SKIP HTTP theo vai trò, CORS và STOMP do dùng -HealthOnly.'
    } else {
        foreach ($role in @('CUSTOMER', 'DRIVER', 'ADMIN')) { Test-Api '/api/auth/me' $role $role }
        Test-Api '/api/customer/rides/active' 'CUSTOMER'
        Test-Api '/api/driver/rides/active' 'DRIVER'
        Test-Api '/api/admin/pricing-rules' 'ADMIN' '' $webOrigin
        if ($webOrigin) { Write-Output 'PASS CORS origin web admin staging' }
        else { Write-Output 'SKIP CORS do thiếu PTITGO_STAGING_SMOKE_WEB_ORIGIN.' }
        Test-Stomp 'CUSTOMER'
        Test-Stomp 'DRIVER'
    }
} finally {
    $webSockets.Dispose()
    $http.Dispose()
    $handler.Dispose()
}
