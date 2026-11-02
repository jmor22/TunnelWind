using System.Windows;

namespace TunnelWind.App;

public partial class App : System.Windows.Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        try { TunnelWind.Core.UpdateService.CleanupSwapLeftovers(); } catch { }

        // single-instance guard: a second launch focuses nothing but exits quietly
        _mutex = new Mutex(true, "Global\\TunnelWind_SingleInstance", out var owned);
        if (!owned)
        {
            System.Windows.MessageBox.Show(
                "Tunnel Wind is already running (check the tray).",
                "Tunnel Wind", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        DispatcherUnhandledException += (_, args) =>
        {
            System.Windows.MessageBox.Show(args.Exception.Message, "TunnelWind", MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };
    }

    private Mutex? _mutex;

    protected override void OnExit(ExitEventArgs e)
    {
        try { _mutex?.ReleaseMutex(); } catch { }
        try { _mutex?.Dispose(); } catch { }
        base.OnExit(e);
    }
}
