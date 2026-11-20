# 问渠 Windows ACL 写收束 runner（受限令牌 + 写能力 SID + Low 完整性）
#
# 设计目标（照 DSH 的好做法）：
#   - 读不受限、写只允许落在 roots + 私有临时目录；写到约束外被 NTFS 直接拒绝；
#   - 命令由**降级令牌**（Low 完整性 + WRITE_RESTRICTED 限制性 SID）启动，
#     而不是把一条能写全盘的 shell 裸跑；
#   - **自检是功能性的**：真正 spawn 一个受限进程，验证「写授权目录成功 / 写约束外失败 /
#     读系统文件成功」三条都成立才报告可用；任何一条不成立 => ok=false => 上层 fail-closed
#     拒绝执行命令。这样即使本脚本在某些 Windows 版本上不适用，也只会「不能跑」，
#     绝不会静默退回无限制执行。
#
# 机制：
#   CreateRestrictedToken(flags = DISABLE_MAX_PRIVILEGE | WRITE_RESTRICTED)，
#   restricting SID = 每次生成的写能力 SID；再把令牌降到 Low 完整性。
#   WRITE_RESTRICTED 让**写**只看限制性 SID（所以只有被授予能力 SID 的目录可写），
#   读仍走正常 SID（所以系统文件、workspace 外的只读访问不受影响）。
#   被授予目录同时加：能力 SID 的允许 ACE + Low 完整性标签（否则 Low 令牌写不动 Medium 对象）。
#
# 诚实边界（与 DSH 一致）：NTFS 硬链接可把同一文件对象别名到约束外；读不受限；
# 因此本档始终是 partial 强制执行。
#
# ⚠️ 本脚本未在 Windows 真机验证过（开发环境是 macOS）。它靠 -SelfTest 在目标机现场自证；
#    自检不过就不可用，绝不会 fail-open。上线前请在 Windows CI / 真机跑一遍 -SelfTest。

param(
  [switch]$SelfTest,
  [string]$CwdB64,
  [string]$RootsB64,
  [string]$CommandB64
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
using System.ComponentModel;

public static class WqAcl
{
    [StructLayout(LayoutKind.Sequential)]
    public struct SID_AND_ATTRIBUTES { public IntPtr Sid; public uint Attributes; }

    [StructLayout(LayoutKind.Sequential)]
    public struct TOKEN_MANDATORY_LABEL { public SID_AND_ATTRIBUTES Label; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct STARTUPINFO
    {
        public int cb;
        public string lpReserved;
        public string lpDesktop;
        public string lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }

    [DllImport("kernel32.dll")] public static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll")] public static extern IntPtr GetStdHandle(int n);
    [DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetHandleInformation(IntPtr h, uint mask, uint flags);
    [DllImport("kernel32.dll", SetLastError = true)] public static extern uint WaitForSingleObject(IntPtr h, uint ms);
    [DllImport("kernel32.dll", SetLastError = true)] public static extern bool GetExitCodeProcess(IntPtr p, out uint code);
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);

    [DllImport("advapi32.dll", SetLastError = true)] public static extern bool OpenProcessToken(IntPtr h, uint acc, out IntPtr tok);
    [DllImport("advapi32.dll", SetLastError = true)] public static extern bool DuplicateTokenEx(IntPtr existing, uint access, IntPtr attrs, int impLevel, int tokenType, out IntPtr newTok);
    [DllImport("advapi32.dll", SetLastError = true)] public static extern bool CreateRestrictedToken(IntPtr tok, uint flags, uint disableCount, IntPtr disableSids, uint deleteCount, IntPtr deleteSids, uint restrictCount, IntPtr restrictSids, out IntPtr newTok);
    [DllImport("advapi32.dll", SetLastError = true)] public static extern bool SetTokenInformation(IntPtr tok, int cls, ref TOKEN_MANDATORY_LABEL info, uint len);
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)] public static extern bool CreateProcessAsUserW(IntPtr token, string app, StringBuilder cmd, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr env, string cwd, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)] public static extern bool CreateProcessWithTokenW(IntPtr token, uint logonFlags, string app, StringBuilder cmd, uint flags, IntPtr env, string cwd, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)] public static extern bool ConvertStringSidToSidW(string s, out IntPtr sid);
    [DllImport("kernel32.dll")] public static extern IntPtr LocalFree(IntPtr p);

    const uint TOKEN_ALL_ACCESS = 0xF01FF;
    const uint DISABLE_MAX_PRIVILEGE = 0x1;
    const uint WRITE_RESTRICTED = 0x8;
    const int TokenIntegrityLevel = 25;
    const int TokenPrimary = 1;
    const int SecurityImpersonation = 2;
    const uint SE_GROUP_INTEGRITY = 0x20;
    const int STARTF_USESTDHANDLES = 0x100;
    const uint CREATE_UNICODE_ENVIRONMENT = 0x400;
    const uint HANDLE_FLAG_INHERIT = 0x1;

    // 返回子进程退出码；任何一步失败抛 Win32Exception（绝不放行未受限 spawn）。
    public static int Run(string app, string commandLine, string cwd, string capSidStr, string[] envPairs)
    {
        IntPtr capSid;
        if (!ConvertStringSidToSidW(capSidStr, out capSid)) throw new Win32Exception(Marshal.GetLastWin32Error(), "ConvertStringSidToSid");
        IntPtr lowSid;
        if (!ConvertStringSidToSidW("S-1-16-4096", out lowSid)) throw new Win32Exception(Marshal.GetLastWin32Error(), "ConvertStringSidToSid(low)");

        IntPtr hProc = GetCurrentProcess();
        IntPtr hTok;
        if (!OpenProcessToken(hProc, TOKEN_ALL_ACCESS, out hTok)) throw new Win32Exception(Marshal.GetLastWin32Error(), "OpenProcessToken");

        // 一条限制性 SID：写能力 SID。
        int ssaSize = Marshal.SizeOf(typeof(SID_AND_ATTRIBUTES));
        IntPtr ssa = Marshal.AllocHGlobal(ssaSize);
        SID_AND_ATTRIBUTES one; one.Sid = capSid; one.Attributes = 0;
        Marshal.StructureToPtr(one, ssa, false);

        IntPtr restricted;
        uint flags = DISABLE_MAX_PRIVILEGE | WRITE_RESTRICTED;
        if (!CreateRestrictedToken(hTok, flags, 0, IntPtr.Zero, 0, IntPtr.Zero, 1, ssa, out restricted))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateRestrictedToken");

        // 降到 Low 完整性。
        TOKEN_MANDATORY_LABEL tml;
        tml.Label.Sid = lowSid;
        tml.Label.Attributes = SE_GROUP_INTEGRITY;
        if (!SetTokenInformation(restricted, TokenIntegrityLevel, ref tml, (uint)Marshal.SizeOf(typeof(TOKEN_MANDATORY_LABEL))))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "SetTokenInformation(integrity)");

        IntPtr primary;
        if (!DuplicateTokenEx(restricted, TOKEN_ALL_ACCESS, IntPtr.Zero, SecurityImpersonation, TokenPrimary, out primary))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "DuplicateTokenEx");

        // 标准句柄强制可继承，子进程输出直接回到 Node 的管道。
        IntPtr hIn = GetStdHandle(-10), hOut = GetStdHandle(-11), hErr = GetStdHandle(-12);
        SetHandleInformation(hIn, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
        SetHandleInformation(hOut, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
        SetHandleInformation(hErr, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);

        STARTUPINFO si = new STARTUPINFO();
        si.cb = Marshal.SizeOf(typeof(STARTUPINFO));
        si.dwFlags = STARTF_USESTDHANDLES;
        si.hStdInput = hIn; si.hStdOutput = hOut; si.hStdError = hErr;
        PROCESS_INFORMATION pi;

        IntPtr env = BuildEnv(envPairs);
        var cmd = new StringBuilder(commandLine);
        bool ok = CreateProcessAsUserW(primary, app, cmd, IntPtr.Zero, IntPtr.Zero, true,
                                       CREATE_UNICODE_ENVIRONMENT, env, cwd, ref si, out pi);
        if (!ok)
        {
            int e1 = Marshal.GetLastWin32Error();
            cmd = new StringBuilder(commandLine);
            ok = CreateProcessWithTokenW(primary, 0, app, cmd, CREATE_UNICODE_ENVIRONMENT, env, cwd, ref si, out pi);
            if (!ok)
                throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateProcessAsUser/WithToken 失败（首个错误 " + e1 + "）");
        }

        WaitForSingleObject(pi.hProcess, 0xFFFFFFFF);
        uint code;
        GetExitCodeProcess(pi.hProcess, out code);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        CloseHandle(primary);
        CloseHandle(restricted);
        CloseHandle(hTok);
        Marshal.FreeHGlobal(ssa);
        if (env != IntPtr.Zero) Marshal.FreeHGlobal(env);
        return (int)code;
    }

    static IntPtr BuildEnv(string[] pairs)
    {
        if (pairs == null || pairs.Length == 0) return IntPtr.Zero;
        var sb = new StringBuilder();
        foreach (var p in pairs) sb.Append(p).Append('\0');
        sb.Append('\0');
        return Marshal.StringToHGlobalUni(sb.ToString());
    }
}
'@

function Decode-B64([string]$s) {
  if ([string]::IsNullOrEmpty($s)) { return $null }
  [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))
}

# 随机写能力 SID（S-1-5-21-a-b-c-d，d 为相对标识符）。它只出现在限制性 SID 与授权 ACE 里。
function New-CapSid {
  $r = { [int](Get-Random -Minimum 1 -Maximum 2000000000) }
  "S-1-5-21-$(& $r)-$(& $r)-$(& $r)-$(& $r)"
}

function Grant-Write([string]$dir, [string]$sid) {
  $acl = Get-Acl -LiteralPath $dir
  $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
    $sid, 'Modify', [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',
    [System.Security.AccessControl.PropagationFlags]'None', [System.Security.AccessControl.AccessControlType]'Allow')
  $acl.AddAccessRule($rule) | Out-Null
  Set-Acl -LiteralPath $dir -AclObject $acl
}

# Low 完整性标签：Low 令牌只能写 Low/更低的对象，故被授予目录必须降为 Low（含已有子项）。
function Set-LowIntegrity([string]$dir) {
  & icacls $dir /setintegritylevel '(OI)(CI)L' /T /C /Q | Out-Null
}

function Get-EnvPairs {
  $pairs = @()
  foreach ($k in [Environment]::GetEnvironmentVariables().Keys) {
    $pairs += ("{0}={1}" -f $k, [Environment]::GetEnvironmentVariable([string]$k))
  }
  return ,$pairs
}

function New-CmdLine([string]$command) {
  # 只用于 cmd.exe：/d /s /c "<command>"。/s 让 cmd 走「剥一层引号」规则，命令原样进入。
  $comspec = $env:ComSpec
  if (-not $comspec) { $comspec = "$env:SystemRoot\System32\cmd.exe" }
  $inner = $command.Replace('"', '\"')
  return @{ app = $comspec; line = '"' + $comspec + '" /d /s /c "' + $inner + '"' }
}

# ---------- 自检：功能性验证，任何一条不满足即不可用 ----------
function Invoke-SelfTest {
  $root = Join-Path $env:TEMP ("wenqu-sbx-selftest-" + [Guid]::NewGuid().ToString('N'))
  $outsideDir = Split-Path $env:USERPROFILE -Parent
  $outside = Join-Path $outsideDir ("wenqu-sbx-probe-" + [Guid]::NewGuid().ToString('N') + ".txt")
  try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $capSid = New-CapSid
    Grant-Write $root $capSid
    Set-LowIntegrity $root
    $envPairs = Get-EnvPairs

    # 1) 写授权目录：应成功
    $inside = Join-Path $root 'a.txt'
    $c1 = New-CmdLine ("echo ok> `"$inside`"")
    [void][WqAcl]::Run($c1.app, $c1.line, $root, $capSid, $envPairs)
    $writeInside = Test-Path -LiteralPath $inside

    # 2) 写约束外：应被拒（文件不存在）
    $c2 = New-CmdLine ("echo bad> `"$outside`"")
    try { [void][WqAcl]::Run($c2.app, $c2.line, $root, $capSid, $envPairs) } catch { }
    $wroteOutside = Test-Path -LiteralPath $outside

    # 3) 读系统文件：应成功（读不受限）
    $winini = Join-Path $env:SystemRoot 'win.ini'
    $c3 = New-CmdLine ("type `"$winini`" > NUL")
    $readOk = $false
    try { $readOk = ([WqAcl]::Run($c3.app, $c3.line, $root, $capSid, $envPairs) -eq 0) } catch { $readOk = $false }

    $ok = $writeInside -and (-not $wroteOutside) -and $readOk
    $reason = if ($ok) { '' } else {
      "自检未通过：写授权目录=$writeInside，写约束外=$wroteOutside（应为 False），读系统文件=$readOk"
    }
    @{ ok = $ok; reason = $reason } | ConvertTo-Json -Compress
  } catch {
    @{ ok = $false; reason = ("自检异常：" + $_.Exception.Message) } | ConvertTo-Json -Compress
  } finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $outside) { Remove-Item -LiteralPath $outside -Force -ErrorAction SilentlyContinue }
  }
}

if ($SelfTest) {
  Write-Output (Invoke-SelfTest)
  exit 0
}

# ---------- 执行一次命令 ----------
try {
  $cwd = Decode-B64 $CwdB64
  $roots = @()
  if ($RootsB64) { $roots = @((Decode-B64 $RootsB64) | ConvertFrom-Json) }
  $argv = @()
  if ($CommandB64) { $argv = @((Decode-B64 $CommandB64) | ConvertFrom-Json) }

  $command = if ($argv.Length -ge 5) { [string]$argv[-1] } else { ($argv -join ' ') }
  if ([string]::IsNullOrEmpty($cwd)) { $cwd = (Get-Location).Path }

  $capSid = New-CapSid
  foreach ($r in $roots) {
    if (-not (Test-Path -LiteralPath $r -PathType Container)) { throw "root 不存在或不是目录：$r" }
    Grant-Write $r $capSid
    Set-LowIntegrity $r
  }
  # 私有临时目录：每次执行新建，授予写 + Low，用完删。
  $tmp = Join-Path $env:TEMP ("wenqu-sbx-" + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $tmp -Force | Out-Null
  Grant-Write $tmp $capSid
  Set-LowIntegrity $tmp
  try {
    $envPairs = Get-EnvPairs
    $envPairs += ("TEMP=$tmp"); $envPairs += ("TMP=$tmp")
    $c = New-CmdLine $command
    $code = [WqAcl]::Run($c.app, $c.line, $cwd, $capSid, $envPairs)
    exit $code
  } finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
  }
} catch {
  [Console]::Error.WriteLine("wenqu-acl-run: " + $_.Exception.Message)
  exit 127
}
