using System.Windows;
using TunnelWind.Core;

namespace TunnelWind.App;

public partial class SettingsWindow : Window
{
    public bool Saved { get; private set; }

    private readonly Settings _settings;

    public SettingsWindow(Settings settings)
    {
        InitializeComponent();
        _settings = settings;

        HttpPortBox.Text = _settings.LocalHttpProxyPort > 0 ? _settings.LocalHttpProxyPort.ToString() : "";
        SocksPortBox.Text = _settings.LocalSocksProxyPort > 0 ? _settings.LocalSocksProxyPort.ToString() : "";
        ExposeLanCheck.IsChecked = _settings.ExposeProxiesToLan;
        StartWithWindowsCheck.IsChecked = _settings.StartWithWindows;
        UpstreamBox.Text = _settings.UpstreamProxyUrl ?? "";
        UpdateBox.Text = _settings.UpdateManifestUrl ?? "";
    }

    private void Ok_Click(object sender, RoutedEventArgs e)
    {
        var httpParsed = int.TryParse(HttpPortBox.Text.Trim(), out int httpPort);
        var socksParsed = int.TryParse(SocksPortBox.Text.Trim(), out int socksPort);
        if (httpPort < 0 || httpPort > 65535 || socksPort < 0 || socksPort > 65535)
        {
            System.Windows.MessageBox.Show("Ports must be 0..65535 (0 = auto).");
            return;
        }

        _settings.LocalHttpProxyPort = httpParsed ? httpPort : 0;
        _settings.LocalSocksProxyPort = socksParsed ? socksPort : 0;
        _settings.ExposeProxiesToLan = ExposeLanCheck.IsChecked == true;
        var startWithWindows = StartWithWindowsCheck.IsChecked == true;
        try
        {
            AutoStart.Set(startWithWindows);
            _settings.StartWithWindows = startWithWindows;
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show("Could not update autostart: " + ex.Message);
        }
        _settings.UpstreamProxyUrl = string.IsNullOrWhiteSpace(UpstreamBox.Text) ? null : UpstreamBox.Text.Trim();
        _settings.UpdateManifestUrl = (UpdateBox.Text ?? "").Trim();

        _settings.Save();
        Saved = true;
        DialogResult = true;
        Close();
    }
}
