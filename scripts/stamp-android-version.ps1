param(
    [Parameter(Mandatory = $true)][string]$ChannelOrTag,
    [long]$Timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds(),
    [string]$BaseVersion,
    [string]$CommitSha
)

$ErrorActionPreference = 'Stop'
# Both channels count seconds since September 2020. This is above the
# published nightly code (29,818,801); publishing workflows share a lock.
$versionCode = $Timestamp - 1600000000
if ($versionCode -le 29818801 -or $versionCode -gt 2100000000) {
    throw "Invalid Android version code: $versionCode"
}

$versionName = if ($ChannelOrTag -eq 'nightly') {
    if ($BaseVersion -notmatch '^v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$' -or
        $CommitSha -notmatch '^[0-9a-fA-F]{7,40}$') {
        throw 'A release tag and commit hash are required for nightly builds'
    }
    $base = $BaseVersion -replace '^v', ''
    "$base.$($CommitSha.Substring(0, 7).ToLowerInvariant())"
} elseif ($ChannelOrTag.StartsWith('v')) {
    $ChannelOrTag.Substring(1)
} else {
    $ChannelOrTag
}

"ANDROID_VERSION_NAME=$versionName`nANDROID_VERSION_CODE=$versionCode" |
    Out-File -FilePath $env:GITHUB_ENV -Encoding utf8 -Append
