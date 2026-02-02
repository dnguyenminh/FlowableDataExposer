<#
Create PR script — safe, idempotent, and interactive.

Usage examples:
  # dry-run (default) - shows what would happen
  pwsh -NoProfile -ExecutionPolicy Bypass ./scripts/create_pr.ps1 -DryRun

  # run and open PR (requires gh CLI authenticated)
  pwsh -NoProfile -ExecutionPolicy Bypass ./scripts/create_pr.ps1 -BranchName "docs/checkstyle-javadoc-and-tests" -OpenPr

  # force push directly to main (NOT RECOMMENDED)
  pwsh -NoProfile -ExecutionPolicy Bypass ./scripts/create_pr.ps1 -PushToMain -ConfirmPush

The script performs:
 - gradle clean build (aborts on failure)
 - checkstyle tasks (:core:checkstyleMain :web:checkstyleMain)
 - generates javadoc-missing report
 - creates a feature branch, commits staged changes (or all changes if none staged)
 - pushes branch and creates a GitHub PR using `gh` when available
#>
[CmdletBinding(SupportsShouldProcess=$true)]
param(
    [string]$BranchName = "docs/checkstyle-javadoc-and-tests",
    [string]$CommitMessage = "chore(docs+ci): add Javadoc, tests and Checkstyle enforcement (incremental)",
    [switch]$OpenPr,
    [switch]$DryRun,
    [switch]$PushToMain,
    [switch]$ConfirmPush
)

function Fail([string]$msg) {
    Write-Error $msg
    exit 1
}

Write-Output "Script: create_pr.ps1 — branch='$BranchName'  dryRun=$($DryRun.IsPresent)  pushToMain=$($PushToMain.IsPresent)"

if ($PushToMain.IsPresent -and -not $ConfirmPush.IsPresent) {
    Fail "Refusing to push to 'main' without -ConfirmPush. This is dangerous."
}

# 1) Run build + checks
if (-not $DryRun) {
    Write-Output "Running: ./gradlew.bat clean build (this may take a while)"
    $rc = & .\gradlew.bat clean build
    if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed — aborting. Fix the issues and re-run." }

    Write-Output "Running Checkstyle (blocking rules)"
    & .\gradlew.bat :core:checkstyleMain :web:checkstyleMain
    if ($LASTEXITCODE -ne 0) { Fail "Checkstyle (main) reported errors — fix or suppress them first." }

    Write-Output "Generating Javadoc-missing report (non-blocking)"
    pwsh -NoProfile -ExecutionPolicy Bypass -Command .\tools\report-missing-javadoc.ps1 | Out-Null
}
else {
    Write-Output "DRY RUN: skipping gradle build and checks"
}

# 2) Git: ensure repo is clean or staged changes exist
$gitStatus = git status --porcelain
if (-not $gitStatus) {
    Write-Output "No unstaged changes detected. Will commit any staged changes or create an empty branch commit if none staged."
} else {
    Write-Output "Working tree has changes — they will be included in the commit."
}

# Create branch
if ($DryRun) {
    Write-Output "DRY RUN: git checkout -b $BranchName"
} else {
    git fetch origin --prune
    git checkout -b $BranchName
}

# Stage changes if not already staged
$staged = git diff --name-only --cached
if (-not $staged) {
    Write-Output "No staged files — adding all changes to the commit (git add -A)"
    if (-not $DryRun) { git add -A }
}

# Commit
if ($DryRun) {
    Write-Output "DRY RUN: git commit -m '$CommitMessage'"
} else {
    git commit -m "$CommitMessage" --allow-empty
    if ($LASTEXITCODE -ne 0) { Fail "git commit failed" }
}

# Push
if ($PushToMain.IsPresent) {
    if ($DryRun) { Write-Output "DRY RUN: git push origin HEAD:main" }
    else { git push origin HEAD:main }
} else {
    if ($DryRun) { Write-Output "DRY RUN: git push -u origin $BranchName" }
    else { git push -u origin $BranchName }
}

if ($DryRun) { Write-Output "DRY RUN complete — no remote changes made." ; exit 0 }

# 3) Create PR using gh if available
$gh = Get-Command gh -ErrorAction SilentlyContinue
$prUrl = $null
$prTitle = "chore: Javadoc, tests & Checkstyle enforcement (incremental)"
$prBody = @'
Summary
- Add Javadoc to high‑impact public APIs
- Add focused unit tests and small hygiene fixes
- Add Checkstyle (method-length + hygiene) and Javadoc report for incremental remediation

QA
- ./gradlew.bat clean build
- :core:checkstyleMain and :web:checkstyleMain
- build/reports/javadoc-missing.txt

Checklist
- [ ] All tests pass locally
- [ ] Checkstyle (core + web) green
'@

if ($gh) {
    Write-Output "Creating PR via gh..."
    $out = gh pr create --base main --head $BranchName --title "$prTitle" --body "$prBody" --reviewer @backend-team-lead --label chore,ci 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "gh pr create failed: $out"
        $prUrl = $null
    } else {
        $prUrl = ($out | Select-String "https://github.com/\S+" -AllMatches).Matches[0].Value
    }
} else {
    Write-Warning "gh CLI not found — cannot create PR automatically."
}

if (-not $prUrl) {
    $remote = git remote get-url origin
    $user = (git config user.name) -replace '\s','-'
    $prUrl = "https://github.com/$(($remote -replace '^.*github.com[:/]',''))/compare/main...$user:$BranchName?expand=1"
    Write-Output "Open the following URL to create the PR in your browser:"
    Write-Output $prUrl
} else {
    Write-Output "PR created: $prUrl"
    if ($OpenPr.IsPresent) { gh pr view --web }
}

Write-Output "Done. CI will run on the pushed branch. Review the PR and merge when ready."
exit 0
