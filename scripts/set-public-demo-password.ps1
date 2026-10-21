param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$target = Join-Path $root '.env'
if (!(Test-Path -LiteralPath $target)) {
  throw 'Local .env does not exist. Run scripts/init-local.ps1 first.'
}
$lines = [IO.File]::ReadAllLines($target)
$found = $false
for ($i = 0; $i -lt $lines.Length; $i++) {
  if ($lines[$i].StartsWith('DEMO_PASSWORD=')) {
    $lines[$i] = 'DEMO_PASSWORD=CampusDemo2026!'
    $found = $true
  }
}
if (!$found) { $lines += 'DEMO_PASSWORD=CampusDemo2026!' }
[IO.File]::WriteAllLines($target, $lines, (New-Object Text.UTF8Encoding $false))
Write-Host 'Local DEMO_PASSWORD now matches the public team acceptance credential.'
