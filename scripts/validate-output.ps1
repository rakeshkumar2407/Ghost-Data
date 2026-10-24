param(
    [string]$OutputPath = (Join-Path (Get-Location) "output.csv"),
    [string]$DatasetDirectory = (Join-Path (Get-Location) "dataset"),
    [string]$RequestsFile = "deletion_requests.csv"
)

$ErrorActionPreference = "Stop"

function Compare-StepOrder($left, $right, $phaseOrder, $operationOrder) {
    $comparison = $phaseOrder[$left.phase].CompareTo($phaseOrder[$right.phase])
    if ($comparison -ne 0) { return $comparison }
    $comparison = $operationOrder[$left.operation].CompareTo($operationOrder[$right.operation])
    if ($comparison -ne 0) { return $comparison }
    $comparison = [string]::CompareOrdinal($left.system_id, $right.system_id)
    if ($comparison -ne 0) { return $comparison }
    return [string]::CompareOrdinal($left.resource_id, $right.resource_id)
}

if (-not (Test-Path -LiteralPath $OutputPath -PathType Leaf)) {
    throw "Output CSV not found: $OutputPath"
}
$requestsPath = Join-Path $DatasetDirectory $RequestsFile
if (-not (Test-Path -LiteralPath $requestsPath -PathType Leaf)) {
    throw "Request dataset not found: $requestsPath"
}

$header = (Get-Content -LiteralPath $OutputPath -TotalCount 1)
if ($header -ne "case_id,deletion_status,steps_json") {
    throw "Output header must be exactly: case_id,deletion_status,steps_json"
}

$requests = @(Import-Csv -LiteralPath $requestsPath)
$rows = @(Import-Csv -LiteralPath $OutputPath)
$expectedIds = [string[]]@($requests | ForEach-Object { $_.case_id })
[Array]::Sort($expectedIds, [System.StringComparer]::Ordinal)

if ($rows.Count -ne $expectedIds.Count) {
    throw "Output has $($rows.Count) rows; expected $($expectedIds.Count)."
}

$knownEvidence = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$manifestPath = Join-Path $DatasetDirectory "evidence_files.csv"
foreach ($item in (Import-Csv -LiteralPath $manifestPath)) {
    [void]$knownEvidence.Add($item.file_id)
}
foreach ($source in @(
    @{ Path = "system_activity.csv"; Field = "activity_id" },
    @{ Path = "user_audit_log.csv"; Field = "audit_id" }
)) {
    foreach ($item in (Import-Csv -LiteralPath (Join-Path $DatasetDirectory $source.Path))) {
        [void]$knownEvidence.Add($item.($source.Field))
    }
}

$allowed = @{
    FINDING = @{
        REPORT_REMAINING_RECORD = "PRESENT"
        REPORT_EXPOSED_PII = "PRESENT"
        REPORT_ACTIVE_DATA_FLOW = "ACTIVE"
        REPORT_RESTORE_RISK = "ACTIVE"
    }
    REMEDIATION = @{
        DISABLE_DATA_FLOW = "DISABLED"
        BLOCK_RESTORE = "BLOCKED"
        DELETE_RECORD = "ABSENT"
        REDACT_RECORD = "REDACTED"
        ANONYMIZE_RECORD = "ANONYMIZED"
    }
    VERIFICATION = @{
        VERIFY_RECORD_ABSENT = "ABSENT"
        VERIFY_RECORD_PRESENT = "PRESENT"
        VERIFY_RECORD_REDACTED = "REDACTED"
        VERIFY_RECORD_ANONYMIZED = "ANONYMIZED"
        VERIFY_DATA_FLOW_DISABLED = "DISABLED"
        VERIFY_RESTORE_BLOCKED = "BLOCKED"
    }
}
$phaseOrder = @{ FINDING = 0; REMEDIATION = 1; VERIFICATION = 2 }
$operationOrder = @{
    REPORT_REMAINING_RECORD = 0
    REPORT_EXPOSED_PII = 1
    REPORT_ACTIVE_DATA_FLOW = 2
    REPORT_RESTORE_RISK = 3
    DISABLE_DATA_FLOW = 0
    BLOCK_RESTORE = 1
    DELETE_RECORD = 2
    REDACT_RECORD = 3
    ANONYMIZE_RECORD = 4
    VERIFY_RECORD_ABSENT = 0
    VERIFY_RECORD_PRESENT = 1
    VERIFY_RECORD_REDACTED = 2
    VERIFY_RECORD_ANONYMIZED = 3
    VERIFY_DATA_FLOW_DISABLED = 4
    VERIFY_RESTORE_BLOCKED = 5
}

for ($rowIndex = 0; $rowIndex -lt $rows.Count; $rowIndex++) {
    $row = $rows[$rowIndex]
    if ($row.case_id -cne $expectedIds[$rowIndex]) {
        throw "Row $($rowIndex + 2) has case_id '$($row.case_id)'; expected '$($expectedIds[$rowIndex])' in sorted order."
    }
    if ($row.deletion_status -cnotin @("COMPLETE", "INCOMPLETE", "NOT_APPLICABLE")) {
        throw "Case $($row.case_id) has an invalid deletion_status."
    }

    if (-not $row.steps_json.TrimStart().StartsWith("[")) {
        throw "Case $($row.case_id) steps_json must be a JSON array."
    }
    try {
        $steps = ConvertFrom-Json -InputObject $row.steps_json
    } catch {
        throw "Case $($row.case_id) has invalid steps_json: $($_.Exception.Message)"
    }
    if ($null -eq $steps) { $steps = @() }
    if ($steps -isnot [Array]) { throw "Case $($row.case_id) steps_json must be a JSON array." }
    if ($row.deletion_status -eq "NOT_APPLICABLE") {
        if ($steps.Count -ne 0) { throw "NOT_APPLICABLE case $($row.case_id) must have no steps." }
        continue
    }
    if ($steps.Count -eq 0) { throw "Reviewed case $($row.case_id) must include audit steps." }

    $phases = @{}
    $previousStep = $null
    for ($stepIndex = 0; $stepIndex -lt $steps.Count; $stepIndex++) {
        $step = $steps[$stepIndex]
        $requiredFields = @("step_no", "phase", "system_id", "resource_id", "operation", "state", "evidence_id")
        foreach ($field in $requiredFields) {
            if ($null -eq $step.PSObject.Properties[$field]) {
                throw "Case $($row.case_id), step $($stepIndex + 1) is missing '$field'."
            }
        }
        if ([int]$step.step_no -ne ($stepIndex + 1)) {
            throw "Case $($row.case_id) has non-consecutive step numbers."
        }
        if ((-not $allowed.ContainsKey($step.phase)) -or (-not $allowed[$step.phase].ContainsKey($step.operation)) -or
            ($allowed[$step.phase][$step.operation] -cne $step.state)) {
            throw "Case $($row.case_id), step $($stepIndex + 1) has an invalid phase/operation/state combination."
        }
        if ([string]::IsNullOrWhiteSpace($step.system_id) -or [string]::IsNullOrWhiteSpace($step.resource_id)) {
            throw "Case $($row.case_id), step $($stepIndex + 1) needs system_id and resource_id."
        }

        $phases[$step.phase] = $true
        if ($null -ne $previousStep -and (Compare-StepOrder $previousStep $step $phaseOrder $operationOrder) -gt 0) {
            throw "Case $($row.case_id) has steps outside the required canonical order."
        }
        $previousStep = $step

        $requiresCitation = $row.deletion_status -eq "COMPLETE" -or $step.phase -eq "FINDING"
        $isFutureStep = $row.deletion_status -eq "INCOMPLETE" -and $step.phase -in @("REMEDIATION", "VERIFICATION")
        if ($requiresCitation -and ($null -eq $step.evidence_id -or [string]::IsNullOrWhiteSpace($step.evidence_id))) {
            throw "Case $($row.case_id), step $($stepIndex + 1) requires an evidence citation."
        }
        if ($requiresCitation -and -not $knownEvidence.Contains([string]$step.evidence_id)) {
            throw "Case $($row.case_id), step $($stepIndex + 1) cites unknown evidence '$($step.evidence_id)'."
        }
        if ($isFutureStep -and $step.evidence_id -cne "") {
            throw "Case $($row.case_id), future remediation/verification evidence_id must be empty."
        }
        if ($row.deletion_status -eq "COMPLETE" -and $step.phase -cne "VERIFICATION") {
            throw "COMPLETE case $($row.case_id) may contain verification steps only."
        }
    }

    if (($row.deletion_status -eq "INCOMPLETE") -and
        ((-not $phases.ContainsKey("FINDING")) -or (-not $phases.ContainsKey("REMEDIATION")) -or
            (-not $phases.ContainsKey("VERIFICATION")))) {
        throw "INCOMPLETE case $($row.case_id) requires finding, remediation, and verification steps."
    }
}

Write-Output "Valid output: $($rows.Count) rows, sorted case IDs, valid step contracts and evidence citations."
