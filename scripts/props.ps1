$RepoRoot = Split-Path $PSScriptRoot -Parent
$Props = @{}
foreach ($line in Get-Content (Join-Path $RepoRoot 'gradle.properties')) {
    if ($line -match '^\s*([^#!][^=]*?)\s*=\s*(.*)$') { $Props[$Matches[1]] = $Matches[2].Trim() }
}
foreach ($key in @($Props.Keys | Where-Object { $_ -like '*_dir' })) {
    $Props[$key] = [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($Props[$key])) { $Props[$key] } else { Join-Path $RepoRoot $Props[$key] }))
}
