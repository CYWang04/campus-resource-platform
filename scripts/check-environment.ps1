param([switch]$CheckDockerEngine)

$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()

function Check-NativeVersion {
    param([string]$Label, [string]$Command, [string[]]$CommandArgs, [string]$Expected)
    $resolved = Get-Command $Command -ErrorAction SilentlyContinue
    if (-not $resolved) {
        Write-Host "[MISSING] $Label"
        $failures.Add($Label)
        return
    }
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $Command @CommandArgs 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0 -or ($Expected -and $output -notmatch $Expected)) {
        Write-Host "[FAIL] $Label : $($output.Trim())"
        $failures.Add($Label)
        return
    }
    $firstLine = ($output.Trim() -split '\r?\n')[0]
    Write-Host "[OK] $Label : $firstLine"
}

Check-NativeVersion -Label 'Java JDK 21' -Command 'java' -CommandArgs @('--version') -Expected '(openjdk|java) 21\.'
Check-NativeVersion -Label 'Java compiler 21' -Command 'javac' -CommandArgs @('-version') -Expected 'javac 21\.'
Check-NativeVersion -Label 'Node.js 24' -Command 'node' -CommandArgs @('--version') -Expected '^v24\.'
Check-NativeVersion -Label 'npm' -Command 'npm.cmd' -CommandArgs @('--version')
Check-NativeVersion -Label 'Git' -Command 'git' -CommandArgs @('--version')
Check-NativeVersion -Label 'VS Code' -Command 'code' -CommandArgs @('--version')
Check-NativeVersion -Label 'Docker CLI' -Command 'docker' -CommandArgs @('--version')
Check-NativeVersion -Label 'Docker Compose' -Command 'docker' -CommandArgs @('compose','version')

if (Get-Command 'code' -ErrorAction SilentlyContinue) {
    $installed = @(& code --list-extensions 2>$null)
    if ($LASTEXITCODE -ne 0) {
        Write-Host '[FAIL] VS Code extension inventory'
        $failures.Add('VS Code extension inventory')
    } else {
        foreach ($extension in @('vscjava.vscode-java-pack','vmware.vscode-boot-dev-pack','Vue.volar','dbaeumer.vscode-eslint','esbenp.prettier-vscode')) {
            if ($installed -icontains $extension) { Write-Host "[OK] Extension: $extension" }
            else { Write-Host "[MISSING] Extension: $extension"; $failures.Add($extension) }
        }
    }
}

if ($CheckDockerEngine) {
    if (Get-Command 'docker' -ErrorAction SilentlyContinue) {
        $ErrorActionPreference = 'Continue'
        $engineOutput = & docker info --format '{{.ServerVersion}}' 2>&1 | Out-String
        $engineExitCode = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($engineExitCode -eq 0) { Write-Host "[OK] Docker Engine: $($engineOutput.Trim())" }
        else { Write-Host '[NOT READY] Docker Engine. Complete the Windows restart and start Docker Desktop.'; $failures.Add('Docker Engine') }
    } else {
        $failures.Add('Docker Engine')
    }
}

if ($failures.Count -gt 0) {
    Write-Host ('Not ready: ' + ($failures -join ', '))
    Write-Host 'After installing tools, fully restart VS Code and open a new terminal to refresh PATH.'
    exit 1
}

Write-Host 'Environment checks passed. Application build/deployment has not been tested by this script.'
