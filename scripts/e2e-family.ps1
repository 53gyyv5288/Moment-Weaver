# ============================================================================
# Moment Weaver · Family 功能端到端验证脚本
# ============================================================================
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/e2e-family.ps1
#
# 前置：
#   - 后端已启动（localhost:8080）
#   - 前端已启动（localhost:5173）
#   - MySQL 已迁移 V8/V9/V10（Flyway 自动执行）
#
# 演示流程：
#   1. 管理员 gyy_5288@qq.com 登录（应该是家族管理员，迁移时自动创建「我的家族」）
#   2. 在家族下创建一个 editor 成员 "张三"
#   3. 张三首次登录被强制改密
#   4. 张三创建一个家族项目
#   5. viewer "妈妈" 加入家族但不能创建项目
# ============================================================================

$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://localhost:8080/api/v1'

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; throw $msg }
function Info($msg) { Write-Host "  [INFO] $msg" -ForegroundColor Yellow }

# ----------------------------------------------------------------------------
# 1) 管理员登录（用您现有的 gyy_5288 账号；如果 V8 migration 已执行，isFamilyAdmin=true）
# ----------------------------------------------------------------------------
Step "1) 管理员 gyy_5288@qq.com 登录"
$loginResp = Invoke-RestMethod -Uri "$BaseUrl/auth/login" `
    -Method POST -ContentType 'application/json' `
    -Body '{"identifier":"gyy_5288@qq.com","password":"<your_password>"}'
$adminToken = $loginResp.data.accessToken
$adminUser  = $loginResp.data.user
if ($adminUser.isFamilyAdmin -ne $true) { Fail "该账号应已被标记为家族管理员（V8 migration），实际 isFamilyAdmin=$($adminUser.isFamilyAdmin)" }
Ok "管理员登录成功，userId=$($adminUser.id)，isFamilyAdmin=$($adminUser.isFamilyAdmin)"

# ----------------------------------------------------------------------------
# 2) 查"我的家族" —— 应该是 V10 migration 自动创建的"我的家族"
# ----------------------------------------------------------------------------
Step "2) 查我的家族列表"
$famsResp = Invoke-RestMethod -Uri "$BaseUrl/families" `
    -Method GET -Headers @{ Authorization = "Bearer $adminToken" }
if ($famsResp.code -ne 0) { Fail "查家族失败：$($famsResp.message)" }
$family = $famsResp.data | Select-Object -First 1
if (-not $family) { Fail "管理员应自动加入 V10 migration 创建的『我的家族』" }
if ($family.myRole -ne 'admin') { Fail "管理员角色应为 admin，实际=$($family.myRole)" }
Ok "找到家族：$($family.name)（id=$($family.id)），myRole=$($family.myRole)"

# ----------------------------------------------------------------------------
# 3) 在家族下创建 editor 成员"张三"（手机 13900000001 / 初始密码 zhang123456）
# ----------------------------------------------------------------------------
Step "3) 管理员创建家族成员 张三"
$zhangPwd = 'zhang12345678'
$createResp = Invoke-RestMethod -Uri "$BaseUrl/families/$($family.id)/members" `
    -Method POST -ContentType 'application/json' `
    -Headers @{ Authorization = "Bearer $adminToken" } `
    -Body (@{
        displayName = '张三'
        phone       = '13900000001'
        password    = $zhangPwd
        role        = 'editor'
    } | ConvertTo-Json)
if ($createResp.code -ne 0) { Fail "创建成员失败：$($createResp.message)" }
$zhangUserId = $createResp.data.userId
if ($createResp.data.mustChangePassword -ne $true) { Fail "新成员 mustChangePassword 应为 true" }
Ok "成员已创建：userId=$zhangUserId，初始密码=$zhangPwd，role=$($createResp.data.role)"

# ----------------------------------------------------------------------------
# 4) 张三首次登录（应被强制改密）
# ----------------------------------------------------------------------------
Step "4) 张三首次登录（应返回 mustChangePassword=true）"
$zhangLoginResp = Invoke-RestMethod -Uri "$BaseUrl/auth/login" `
    -Method POST -ContentType 'application/json' `
    -Body (@{ identifier = '13900000001'; password = $zhangPwd } | ConvertTo-Json)
$zhangToken = $zhangLoginResp.data.accessToken
$zhangUser  = $zhangLoginResp.data.user
if ($zhangUser.mustChangePassword -ne $true) { Fail "张三首次登录 mustChangePassword 应为 true" }
Ok "张三登录成功，mustChangePassword=$($zhangUser.mustChangePassword)（前端应跳 /change-password）"

# ----------------------------------------------------------------------------
# 5) 张三改密
# ----------------------------------------------------------------------------
Step "5) 张三改密（oldPassword=zhang12345678 → newPassword=zhangNewPwd123）"
$newPwd = 'zhangNewPwd123'
$chgResp = Invoke-RestMethod -Uri "$BaseUrl/auth/change-password" `
    -Method POST -ContentType 'application/json' `
    -Headers @{ Authorization = "Bearer $zhangToken" } `
    -Body (@{ oldPassword = $zhangPwd; newPassword = $newPwd } | ConvertTo-Json)
if ($chgResp.code -ne 0) { Fail "改密失败：$($chgResp.message)" }
Ok "改密成功"

# 再次登录确认 mustChangePassword=false
$zhangReLogin = Invoke-RestMethod -Uri "$BaseUrl/auth/login" `
    -Method POST -ContentType 'application/json' `
    -Body (@{ identifier = '13900000001'; password = $newPwd } | ConvertTo-Json)
if ($zhangReLogin.data.user.mustChangePassword -ne $false) { Fail "改密后 mustChangePassword 应为 false" }
Ok "再次登录确认 mustChangePassword=false"

# ----------------------------------------------------------------------------
# 6) 张三在家族下创建一个项目
# ----------------------------------------------------------------------------
Step "6) 张三在家族下创建项目"
$projResp = Invoke-RestMethod -Uri "$BaseUrl/projects" `
    -Method POST -ContentType 'application/json' `
    -Headers @{ Authorization = "Bearer $zhangToken" } `
    -Body (@{
        type        = 'family'
        name        = '爷爷的故事'
        description = '张三负责整理'
        familyId    = $family.id
    } | ConvertTo-Json)
if ($projResp.code -ne 0) { Fail "张三创建家族项目失败：$($projResp.message)" }
$project = $projResp.data
if ($project.familyId -ne "$($family.id)") { Fail "项目的 familyId 应为 $($family.id)，实际=$($project.familyId)" }
Ok "项目创建成功：id=$($project.id)，familyId=$($project.familyId)"

# ----------------------------------------------------------------------------
# 7) 张三查项目列表，应能看到家族项目
# ----------------------------------------------------------------------------
Step "7) 张三的项目列表（含家族项目）"
$listResp = Invoke-RestMethod -Uri "$BaseUrl/projects?page=1&size=50" `
    -Method GET -Headers @{ Authorization = "Bearer $zhangToken" }
$inList = $listResp.data.records | Where-Object { $_.id -eq "$($project.id)" }
if (-not $inList) { Fail "张三的项目列表里应该能看到刚创建的家族项目" }
Ok "张三能看到家族项目 ✓"

# ----------------------------------------------------------------------------
# 8) 管理员再创建一个 viewer 成员"妈妈"
# ----------------------------------------------------------------------------
Step "8) 管理员创建 viewer 成员 妈妈"
$momPwd = 'mom12345678'
$momResp = Invoke-RestMethod -Uri "$BaseUrl/families/$($family.id)/members" `
    -Method POST -ContentType 'application/json' `
    -Headers @{ Authorization = "Bearer $adminToken" } `
    -Body (@{
        displayName = '妈妈'
        phone       = '13900000002'
        password    = $momPwd
        role        = 'viewer'
    } | ConvertTo-Json)
if ($momResp.code -ne 0) { Fail "创建妈妈失败：$($momResp.message)" }
$momUserId = $momResp.data.userId
Ok "妈妈已创建：userId=$momUserId，role=viewer"

# ----------------------------------------------------------------------------
# 9) 妈妈登录后尝试创建家族项目 → 应被拒绝（403 FAMILY_VIEWER_READONLY）
# ----------------------------------------------------------------------------
Step "9) viewer 妈妈尝试创建家族项目 → 应被 403 拒绝"
$momLoginResp = Invoke-RestMethod -Uri "$BaseUrl/auth/login" `
    -Method POST -ContentType 'application/json' `
    -Body (@{ identifier = '13900000002'; password = $momPwd } | ConvertTo-Json)
$momToken = $momLoginResp.data.accessToken

try {
    $badResp = Invoke-RestMethod -Uri "$BaseUrl/projects" `
        -Method POST -ContentType 'application/json' `
        -Headers @{ Authorization = "Bearer $momToken" } `
        -Body (@{
            type     = 'family'
            name     = '妈妈的项目'
            familyId = $family.id
        } | ConvertTo-Json)
    Fail "viewer 应被拒绝，但请求成功了（code=$($badResp.code)）"
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -ne 403) { Fail "viewer 应返回 403，实际=$statusCode" }
    Ok "viewer 创建项目被 403 拒绝 ✓（权限差异化生效）"
}

# ----------------------------------------------------------------------------
# 10) 妈妈查家族项目列表 → 应能看到（只读）
# ----------------------------------------------------------------------------
Step "10) viewer 妈妈查家族项目（应能看到，只读）"
$momProjList = Invoke-RestMethod -Uri "$BaseUrl/families/$($family.id)/projects" `
    -Method GET -Headers @{ Authorization = "Bearer $momToken" }
if ($momProjList.code -ne 0) { Fail "viewer 查项目失败：$($momProjList.message)" }
$canSee = $momProjList.data | Where-Object { $_.id -eq "$($project.id)" }
if (-not $canSee) { Fail "viewer 应能看到家族下的项目" }
Ok "viewer 能看到家族项目（只读）✓"

# ----------------------------------------------------------------------------
# 11) 妈妈查家族成员列表 → 应能看到自己 + 张三 + admin 共 3 人
# ----------------------------------------------------------------------------
Step "11) 妈妈查家族成员"
$momMemList = Invoke-RestMethod -Uri "$BaseUrl/families/$($family.id)/members" `
    -Method GET -Headers @{ Authorization = "Bearer $momToken" }
if ($momMemList.data.Count -lt 3) { Fail "应至少有 3 名成员（admin + 张三 + 妈妈），实际=$($momMemList.data.Count)" }
Ok "家族成员数 = $($momMemList.data.Count) ✓"

# ----------------------------------------------------------------------------
# 12) 管理员移除张三
# ----------------------------------------------------------------------------
Step "12) 管理员移除张三"
$removeResp = Invoke-RestMethod -Uri "$BaseUrl/families/$($family.id)/members/$zhangUserId" `
    -Method DELETE -Headers @{ Authorization = "Bearer $adminToken" }
if ($removeResp.code -ne 0) { Fail "移除失败：$($removeResp.message)" }
Ok "张三已被移除"

# ----------------------------------------------------------------------------
# 13) 张三再次查家族项目 → 应被 403（已非成员）
# ----------------------------------------------------------------------------
Step "13) 张三查家族项目 → 应被 403"
try {
    Invoke-RestMethod -Uri "$BaseUrl/families/$($family.id)/projects" `
        -Method GET -Headers @{ Authorization = "Bearer $zhangToken" } `
        | Out-Null
    Fail "已移除的张三不应能再访问家族项目"
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -ne 403) { Fail "应返回 403，实际=$statusCode" }
    Ok "张三被 403 拒绝 ✓"
}

Write-Host "`n`n========================================" -ForegroundColor Green
Write-Host "  全部 13 步通过！" -ForegroundColor Green
Write-Host "========================================`n" -ForegroundColor Green
