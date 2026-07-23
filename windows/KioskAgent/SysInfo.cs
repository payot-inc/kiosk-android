using System.Runtime.InteropServices;

namespace KioskAgent;

/// <summary>
/// 시스템 진단 정보 수집 — 안드로이드 getEnv()(KioskEnv.kt)의 윈도우 대응.
/// 메모리(GlobalMemoryStatusEx), 시스템 드라이브(DriveInfo), 업타임, OS/CPU 를 모은다.
/// GET /sysinfo 로 노출한다.
/// </summary>
internal static class SysInfo
{
    [StructLayout(LayoutKind.Sequential)]
    private struct MemoryStatusEx
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MemoryStatusEx buffer);

    public static object Collect(long agentUptimeSec, string agentVersion)
    {
        ulong memoryTotal = 0, memoryAvailable = 0;
        uint memoryLoadPercent = 0;
        var mem = new MemoryStatusEx { dwLength = (uint)Marshal.SizeOf<MemoryStatusEx>() };
        if (GlobalMemoryStatusEx(ref mem))
        {
            memoryTotal = mem.ullTotalPhys;
            memoryAvailable = mem.ullAvailPhys;
            memoryLoadPercent = mem.dwMemoryLoad;
        }

        long diskTotal = 0, diskFree = 0;
        try
        {
            var systemDrive = Path.GetPathRoot(Environment.SystemDirectory) ?? "C:\\";
            var drive = new DriveInfo(systemDrive);
            diskTotal = drive.TotalSize;
            diskFree = drive.AvailableFreeSpace;
        }
        catch { /* 드라이브 조회 실패 시 0 */ }

        return new
        {
            agentVersion,
            machine = Environment.MachineName,
            os = Environment.OSVersion.VersionString,
            arch = RuntimeInformation.OSArchitecture.ToString(),
            cpuCount = Environment.ProcessorCount,
            memoryTotal,
            memoryAvailable,
            memoryLoadPercent,
            diskTotal,
            diskFree,
            systemUptimeSec = Environment.TickCount64 / 1000,
            agentUptimeSec
        };
    }
}
