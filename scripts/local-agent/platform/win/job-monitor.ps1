# 问渠 Windows 命令沙箱的 Job 守护。
#
# 由 SandboxProvider 在命令子进程启动后拉起，只做一件事：把目标进程放进一个 Job，
# 然后按住 Job 句柄等待执行器的指令：
#   RELEASE —— 命令正常结束，关掉句柄但不 Terminate，保留 cmd & / nohup 起的后台进程；
#   KILL    —— 命令超时，TerminateJobObject 连同整棵树一起终止。
#
# 不接管命令的 stdio（由执行器直接读子进程管道），因此不会改变输出/编码行为。
param(
  [Parameter(Mandatory = $true)][int]$TargetPid
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class WenquJob
{
    [StructLayout(LayoutKind.Sequential)]
    public struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr CreateJobObject(IntPtr lpJobAttributes, string lpName);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool SetInformationJobObject(
        IntPtr hJob,
        int JobObjectInfoClass,
        ref JOBOBJECT_BASIC_LIMIT_INFORMATION lpJobObjectInfo,
        uint cbJobObjectInfoLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr OpenProcess(uint dwDesiredAccess, bool bInheritHandle, uint dwProcessId);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool TerminateJobObject(IntPtr hJob, uint uExitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool CloseHandle(IntPtr hObject);
}
'@

function Fail-Last ([string]$what) {
  $code = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
  throw "$what 失败（Win32 错误码 $code）"
}

# 不设 KILL_ON_JOB_CLOSE：正常释放时不能误杀用户起的后台进程。
$job = [WenquJob]::CreateJobObject([IntPtr]::Zero, $null)
if ($job -eq [IntPtr]::Zero) { Fail-Last 'CreateJobObject' }

$limits = New-Object 'WenquJob+JOBOBJECT_BASIC_LIMIT_INFORMATION'
$size = [uint32][Runtime.InteropServices.Marshal]::SizeOf([type]'WenquJob+JOBOBJECT_BASIC_LIMIT_INFORMATION')
if (-not [WenquJob]::SetInformationJobObject($job, 2, [ref]$limits, $size)) { Fail-Last 'SetInformationJobObject' }

# PROCESS_ALL_ACCESS：AssignProcessToJobObject 需要 PROCESS_SET_QUOTA。
$proc = [WenquJob]::OpenProcess(0x1F0FFF, $false, [uint32]$TargetPid)
if ($proc -eq [IntPtr]::Zero) { Fail-Last 'OpenProcess' }
if (-not [WenquJob]::AssignProcessToJobObject($job, $proc)) { Fail-Last 'AssignProcessToJobObject' }

try {
  while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }
    switch ($line.Trim()) {
      'KILL' {
        [void][WenquJob]::TerminateJobObject($job, 1)
        break
      }
      'RELEASE' { break }
    }
  }
}
finally {
  [void][WenquJob]::CloseHandle($proc)
  [void][WenquJob]::CloseHandle($job)
}
