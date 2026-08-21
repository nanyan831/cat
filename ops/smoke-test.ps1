param(
    [Parameter(Mandatory = $true)]
    [string] $BaseUrl,

    [string] $AccessToken = ""
)

$ErrorActionPreference = "Stop"
$base = $BaseUrl.TrimEnd("/")

function Invoke-SmokeRequest {
    param(
        [string] $Method,
        [string] $Path,
        [int[]] $ExpectedStatus,
        [hashtable] $Headers = @{},
        [object] $Body = $null
    )

    $args = @{
        Method = $Method
        Uri = "$base$Path"
        UseBasicParsing = $true
        TimeoutSec = 15
    }
    if ($Headers.Count -gt 0) {
        $args.Headers = $Headers
    }
    if ($null -ne $Body) {
        $args.ContentType = "application/json"
        $args.Body = ($Body | ConvertTo-Json -Depth 10)
    }

    try {
        $response = Invoke-WebRequest @args
        $statusCode = [int]$response.StatusCode
        $content = $response.Content
    } catch [System.Net.WebException] {
        if ($null -eq $_.Exception.Response) {
            throw "Smoke request failed for $Method $Path. $($_.Exception.Message)"
        }
        $statusCode = [int]$_.Exception.Response.StatusCode
        $responseStream = $_.Exception.Response.GetResponseStream()
        if ($null -ne $responseStream) {
            $reader = New-Object System.IO.StreamReader($responseStream)
            $content = $reader.ReadToEnd()
        } else {
            $content = ""
        }
        $response = [pscustomobject]@{ StatusCode = $statusCode; Content = $content }
    } catch {
        if ($null -eq $_.Exception.Response) {
            throw "Smoke request failed for $Method $Path. $($_.Exception.Message)"
        }
        $statusCode = [int]$_.Exception.Response.StatusCode
        try {
            $content = $_.ErrorDetails.Message
        } catch {
            $content = ""
        }
        $response = [pscustomobject]@{ StatusCode = $statusCode; Content = $content }
    }

    if ($ExpectedStatus -notcontains $statusCode) {
        throw "Unexpected status for $Method ${Path}: $statusCode, expected $($ExpectedStatus -join ',')"
    }
    return $response
}

$health = Invoke-SmokeRequest -Method GET -Path "/health" -ExpectedStatus 200
$healthBody = $health.Content | ConvertFrom-Json
if ($healthBody.service -ne "catlifepet-server") {
    throw "Unexpected health service: $($healthBody.service)"
}

$homeResponse = Invoke-SmokeRequest -Method GET -Path "/" -ExpectedStatus 200
$homeValid = $homeResponse.Content -match "CatLifePet"
$homeValid = $homeValid -and ($homeResponse.Content -match "个人作品记录")
$homeValid = $homeValid -and ($homeResponse.Content -match "/privacy")
if (-not $homeValid) {
    throw "Home page does not contain expected personal project text."
}

Invoke-SmokeRequest -Method GET -Path "/v1/conversations" -ExpectedStatus 401 | Out-Null

$privacyHtml = Invoke-SmokeRequest -Method GET -Path "/privacy" -ExpectedStatus 200
$privacyHtmlValid = $privacyHtml.Content -match "CatLifePet"
$privacyHtmlValid = $privacyHtmlValid -and ($privacyHtml.Content -match "1132994878@qq.com")
$privacyHtmlValid = $privacyHtmlValid -and ($privacyHtml.Content -match "DeepSeek")
if (-not $privacyHtmlValid) {
    throw "Privacy HTML does not contain expected release policy text."
}

$privacyMarkdown = Invoke-SmokeRequest -Method GET -Path "/privacy.md" -ExpectedStatus 200
if ($privacyMarkdown.Content -notmatch "https://catlifepet.top/privacy") {
    throw "Privacy markdown does not contain the public privacy URL."
}

if ($AccessToken.Trim().Length -gt 0) {
    $headers = @{ Authorization = "Bearer $AccessToken" }
    Invoke-SmokeRequest -Method GET -Path "/v1/me" -ExpectedStatus 200 -Headers $headers | Out-Null
    $conversation = Invoke-SmokeRequest -Method POST -Path "/v1/conversations" -ExpectedStatus 200 -Headers $headers -Body @{ title = "deployment smoke" }
    $conversationId = ($conversation.Content | ConvertFrom-Json).id
    Invoke-SmokeRequest -Method GET -Path "/v1/conversations/$conversationId/messages" -ExpectedStatus 200 -Headers $headers | Out-Null
    Invoke-SmokeRequest -Method DELETE -Path "/v1/conversations/$conversationId" -ExpectedStatus 204 -Headers $headers | Out-Null
}

Write-Output "CatLifePet smoke test passed for $base"
