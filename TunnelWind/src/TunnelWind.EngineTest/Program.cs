// Headless smoke test: drives TunnelManager directly against the extracted
// psiphon-tunnel-core.exe and prints every notice for ~20 seconds.
// Usage: TunnelWind.EngineTest.exe [seconds]
using TunnelWind.Core;

var seconds = args.Length > 0 && int.TryParse(args[0], out var s) ? s : 20;
var cts = new CancellationTokenSource(TimeSpan.FromSeconds(seconds + 5));

var engineDir = Path.Combine(
    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TunnelWind");
Directory.CreateDirectory(engineDir);

var manager = new TunnelManager();
string lastNoticeShort = "";
manager.LogLine += l => Console.Error.WriteLine(l);
manager.Notice += n => { lastNoticeShort = n; };
manager.StateChanged += st => Console.WriteLine($"STATE: {st}");
manager.StatsChanged += (up, down) => Console.WriteLine($"STATS: up={up} down={down}");

var engineSrc = AppDomain.CurrentDomain.BaseDirectory;
var engine = Path.Combine(engineSrc, "psiphon-tunnel-core.exe");
if (!File.Exists(engine)) { Console.WriteLine("missing engine exe"); return 2; }
manager.EnsureEngine(engine);

var settings = Settings.Load();   // honors your saved ports (e.g. 8080/1080)
manager.Start(settings);

try
{
    await Task.Delay(TimeSpan.FromSeconds(seconds), cts.Token);
}
catch (OperationCanceledException) { }

Console.WriteLine("--- summary ---");
Console.WriteLine($"state={manager.State} http={manager.HttpProxyPort} socks={manager.SocksProxyPort}");
manager.Stop();
manager.Dispose();
return manager.State == TunnelState.Connected || manager.HttpProxyPort > 0 ? 0 : 1;
