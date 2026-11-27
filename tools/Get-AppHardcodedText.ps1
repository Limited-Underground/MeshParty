<#
.SYNOPSIS
Lists English text still written directly in MeshParty's Java source (D-197).

.DESCRIPTION
A heuristic inventory, not a gate. It finds string literals in app/src/main/java that look like
sentences or labels (start with a capital letter and contain a space, or end in . ! ? …),
skipping log, diagnostics and exception-message-free lines. Text that is sent to the feedback
service or written to diagnostics stays in English on purpose (see docs/LANGUAGES.md).
Prints per-file counts; -Detail prints each line.
#>
[CmdletBinding()]
param([switch] $Detail)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path $PSScriptRoot '..\app\src\main\java'
$literal = [regex]'"((?:[^"\\]|\\.)*?)"'
# Text.t carries its English fallback next to the resource name; comments, thread names and
# exception messages that never reach the screen are skipped too.
$skip = [regex]'(Text\.t\(|"text_|Log\.|MeshDiagnostics\.|import |@Suppress|UUID\.fromString|\.put\("|getString\(|\.optString\(|IOException\(|UNKNOWN_CHANNEL_SENDER|^\s*(/?\*|//)|\}, ")'
# English on purpose (docs/LANGUAGES.md): feedback and abuse reports sent to the service, the
# diagnostics export, and each language's name in itself.
$englishFiles = @('FeedbackEmail.java', 'FeedbackSubmission.java', 'FeedbackClient.java', 'AbuseReports.java',
    'MeshDiagnostics.java', 'AppLanguage.java')
$total = 0
$rows = foreach ($file in Get-ChildItem -LiteralPath $root -Recurse -Filter *.java) {
    if ($englishFiles -contains $file.Name) { continue }
    $hits = @()
    $number = 0
    foreach ($line in [IO.File]::ReadAllLines($file.FullName)) {
        $number++
        if ($skip.IsMatch($line)) { continue }
        foreach ($m in $literal.Matches($line)) {
            $text = $m.Groups[1].Value
            if ($text -cmatch '^[A-Z][a-z]' -and ($text.Contains(' ') -or $text -match '[.!?…]$')) {
                $hits += "{0}:{1}: {2}" -f $file.Name, $number, $text
            }
        }
    }
    if ($hits.Count -gt 0) {
        $total += $hits.Count
        if ($Detail) { $hits | ForEach-Object { Write-Output $_ } }
        [pscustomobject]@{ File = $file.Name; Strings = $hits.Count }
    }
}
$rows | Sort-Object Strings -Descending | Format-Table -AutoSize | Out-String | Write-Output
Write-Output "Total likely user-facing literals: $total"
