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
        Headers = $Headers
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
        $statusCode = [int]$_.Exception.Response.StatusCode
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $content = $reader.ReadToEnd()
        $response = [pscustomobject]@{ StatusCode = $statusCode; Content = $content }
    } catch {
        throw "Smoke request failed for $Method $Path. $($_.Exception.Message)"
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

Invoke-SmokeRequest -Method GET -Path "/v1/conversations" -ExpectedStatus 401 | Out-Null

if ($AccessToken.Trim().Length -gt 0) {
    $headers = @{ Authorization = "Bearer $AccessToken" }
    Invoke-SmokeRequest -Method GET -Path "/v1/me" -ExpectedStatus 200 -Headers $headers | Out-Null
    $conversation = Invoke-SmokeRequest -Method POST -Path "/v1/conversations" -ExpectedStatus 200 -Headers $headers -Body @{ title = "deployment smoke" }
    $conversationId = ($conversation.Content | ConvertFrom-Json).id
    Invoke-SmokeRequest -Method GET -Path "/v1/conversations/$conversationId/messages" -ExpectedStatus 200 -Headers $headers | Out-Null
    Invoke-SmokeRequest -Method DELETE -Path "/v1/conversations/$conversationId" -ExpectedStatus 204 -Headers $headers | Out-Null
}

Write-Output "CatLifePet smoke test passed for $base"
