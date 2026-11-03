using System.Windows;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using HAlign = System.Windows.HorizontalAlignment;
using IoFile = System.IO.File;
using IoPath = System.IO.Path;
using WpfMsgBox = System.Windows.MessageBox;
using TunnelWind.Core;

namespace TunnelWind.App;

public partial class MainWindow : Window
{
    private readonly TunnelManager _tunnel = new();
    private Settings _settings = new();
    private System.Windows.Forms.NotifyIcon? _tray;
    private System.Windows.Forms.ContextMenuStrip? _menu;
    private bool _reallyClosing;
    private bool _trayNoticeShown;
    private readonly List<string> _logBuffer = new();
    private const int MaxLogLines = 400;
    private string _selectedRegion = "";        // "" = auto (best performance)

    public MainWindow()
    {
        InitializeComponent();
        _settings = Settings.Load();
        SetRegionSelection(_settings.EgressRegion ?? "");
        SystemProxyCheck.IsChecked = _settings.UseSystemProxy;
        AutoConnectCheck.IsChecked = _settings.AutoConnect;
        BypassOwnRegionCheck.IsChecked = _settings.SplitTunnelOwnRegion;
        BypassRegionsBox.Text = _settings.SplitTunnelRegionsCsv ?? "";

        _tunnel.StateChanged += OnStateChanged;
        _tunnel.StatsChanged += OnStatsChanged;
        _tunnel.LogLine += AppendLog;
        _tunnel.Notice += AppendNotice;
        _tunnel.RegionsChanged += OnRegionsChanged;
        _tunnel.EngineDied += OnEngineDied;
        _tunnel.SignalChanged += () => Dispatcher.BeginInvoke(() => SetStateUi(_tunnel.State));

        try
        {
            _menu = new System.Windows.Forms.ContextMenuStrip();
            var showItem = _menu.Items.Add("Show Tunnel Wind");
            showItem.Font = new System.Drawing.Font(showItem.Font, System.Drawing.FontStyle.Bold);
            showItem.Click += (_, _) => ShowFromTray();
            _menu.Items.Add("Connect").Click += (_, _) => Dispatcher.BeginInvoke(async () => await ConnectAsync());
            _menu.Items.Add("Disconnect").Click += (_, _) => Dispatcher.BeginInvoke(async () => await DisconnectAsync());
            _menu.Items.Add("-");
            _menu.Items.Add("Exit").Click += (_, _) => ExitFromTray();

            _tray = new System.Windows.Forms.NotifyIcon
            {
                Text = "Tunnel Wind",
                Visible = true,
                Icon = System.Drawing.SystemIcons.Shield,
                ContextMenuStrip = _menu,
            };
            _tray.DoubleClick += (_, _) => ShowFromTray();
        }
        catch { /* tray is optional */ }

        Closing += OnMainWindowClosing;

        SetStateUi(TunnelState.Stopped);

        Loaded += async (_, _) =>
        {
            VersionText.Text = "v" + Identity.AppVersion;

            // stale-proxy guard: if a previous session crashed while the system
            // proxy pointed at our local port, clear it now (tunnel is stopped).
            SystemProxy.EnsureStaleClear(_settings.LocalHttpProxyPort > 0 ? _settings.LocalHttpProxyPort : 8080);

            var engineSrc = System.IO.Path.Combine(
                AppDomain.CurrentDomain.BaseDirectory, "engine", "psiphon-tunnel-core.exe");
            if (!IoFile.Exists(engineSrc))
            {
                AppendLog("[engine] psiphon-tunnel-core.exe missing in engine\\ â€” see TunnelWind/README.md");
                return;
            }
            var enginePath = _tunnel.EnsureEngine(engineSrc);
            AppendLog($"[engine] ready: {enginePath}");
            var check = EngineIntegrity.Verify(engineSrc, enginePath);
            AppendLog(check.Message);
            _integrityOk = check.Ok;

            // hot-apply settings when the split-tunnel boxes change (chips handle themselves)
            BypassOwnRegionCheck.Click += (_, _) => { SaveSettingsFromUi(); OnSettingsDirty_Restart(); };
            BypassRegionsBox.LostFocus += (_, _) => SaveSettingsFromUi();

            if (_settings.AutoConnect) await ConnectAsync();
        };
    }

    // ---------------- connect / disconnect ----------------

    private async void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        if (_tunnel.State == TunnelState.Stopped) await ConnectAsync();
        else await DisconnectAsync();
    }

    private bool _integrityOk;

    private async Task ConnectAsync()
    {
        if (!_integrityOk)
        {
            var bundled = IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "engine", "psiphon-tunnel-core.exe");
            var local = IoPath.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "TunnelWind", "psiphon-tunnel-core.exe");
            var check = EngineIntegrity.Verify(bundled, local);
            AppendLog(check.Message);
            if (!check.Ok)
            {
                WpfMsgBox.Show(
                    "The tunnel engine binary failed its integrity check.\n\n" + check.Message,
                    "Tunnel Wind", MessageBoxButton.OK, MessageBoxImage.Stop);
                return;
            }
            _integrityOk = true;
        }

        SaveSettingsFromUi();
        AppendLog("[ui] connecting...");
        await Task.Run(() => _tunnel.Start(_settings));
        // system proxy is applied as soon as the HTTP port notice arrives
    }

    private async Task DisconnectAsync()
    {
        AppendLog("[ui] disconnecting...");
        await Task.Run(() =>
        {
            try { if (SystemProxy.IsCurrentlySet) SystemProxy.Restore(); } catch { }
            _tunnel.Stop();
        });
    }

    private void SettingsButton_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new SettingsWindow(_settings) { Owner = this };
        var changed = dlg.ShowDialog() == true && dlg.Saved;
        if (changed)
        {
            AppendLog("[ui] settings saved");
            OnSettingsDirty_Restart();
        }
    }

    // ---------------- updates ----------------

    private async void UpdateButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_settings.UpdateManifestUrl))
            {
                var url = Prompt.Show(
                    owner: this,
                    title: "Update channel",
                    label: "Update manifest URL (https://.../update.json - empty disables the check):",
                    initial: _settings.UpdateManifestUrl ?? "");
                if (url == null || string.IsNullOrWhiteSpace(url))
                {
                    AppendLog("[update] channel not configured");
                    return;
                }
                _settings.UpdateManifestUrl = url.Trim();
                _settings.Save();
            }

            AppendLog("[update] checking " + _settings.UpdateManifestUrl);
            var manifest = await UpdateService.FetchManifestAsync(_settings.UpdateManifestUrl);
            if (manifest == null)
            {
                AppendLog("[update] manifest invalid");
                return;
            }

            var cmp = UpdateService.CompareVersions(manifest.AppVersion, Identity.AppVersion);
            AppendLog($"[update] server version {manifest.AppVersion}; ours {Identity.AppVersion}");

            if (cmp <= 0 && string.IsNullOrWhiteSpace(manifest.EngineUrl))
            {
                AppendLog("[update] up to date");
                return;
            }

            var engineNote = string.IsNullOrWhiteSpace(manifest.EngineUrl)
                ? ""
                : " - new engine package present";
            var confirm = WpfMsgBox.Show(
                $"Update available: {manifest.AppVersion} (current {Identity.AppVersion}){engineNote}\n\n" +
                $"{manifest.Notes ?? ""}\n\nInstall now? (tunnel will disconnect)",
                "Tunnel Wind Update", MessageBoxButton.YesNo, MessageBoxImage.Question);

            if (confirm != MessageBoxResult.Yes) return;

            if (_tunnel.State != TunnelState.Stopped) await DisconnectAsync();
            if (SystemProxy.IsCurrentlySet) { try { SystemProxy.Restore(); } catch { } }

            if (!string.IsNullOrWhiteSpace(manifest.EngineUrl))
            {
                AppendLog("[update] installing engine...");
                var engineDest = IoPath.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "TunnelWind", "psiphon-tunnel-core.exe");
                await UpdateService.InstallEngineUpdateAsync(manifest, engineDest, CancellationToken.None);
                AppendLog("[update] engine installed (integrity pin rotated)");
            }

            if (!string.IsNullOrWhiteSpace(manifest.AppUrl))
            {
                AppendLog("[update] downloading app package...");
                await UpdateService.PrepareAppUpdateAsync(manifest, CancellationToken.None);
                AppendLog("[update] swapping binaries and relaunching...");
                UpdateService.ApplyAppSwap();
                System.Windows.Application.Current.Shutdown();
                return;
            }

            WpfMsgBox.Show("Engine updated. Start a new connection to use it.", "Tunnel Wind",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            AppendLog("[update] error: " + ex.Message);
            WpfMsgBox.Show(ex.Message, "Tunnel Wind Update", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    // ---------------- tray / close ----------------

    private void ShowFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void ExitFromTray()
    {
        _reallyClosing = true;
        try { if (SystemProxy.IsCurrentlySet) SystemProxy.Restore(); } catch { }
        if (_tunnel.State != TunnelState.Stopped) _tunnel.Stop();
        Close();
        System.Windows.Application.Current.Shutdown();
    }

    private void OnMainWindowClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (!_reallyClosing)
        {
            // close-to-tray: keep the tunnel alive in the background
            e.Cancel = true;
            Hide();
            if (!_trayNoticeShown)
            {
                _trayNoticeShown = true;
                try
                {
                    var tray = _tray;
                    if (tray != null)
                    {
                        tray.BalloonTipTitle = "Tunnel Wind is still running";
                        tray.BalloonTipText = "The tunnel keeps your connection. Double-click the tray icon (or right-click, Show) to restore the window; Exit fully quits.";
                        tray.ShowBalloonTip(4000);
                    }
                }
                catch { }
            }
            return;
        }

        // real exit path
        if (_tunnel.State != TunnelState.Stopped)
        {
            _tunnel.Stop();
        }
        try { if (SystemProxy.IsCurrentlySet) SystemProxy.Restore(); } catch { }
        _tray?.Dispose();
        _tray = null;
    }


    private void OnStateChanged(TunnelState state) => Dispatcher.BeginInvoke(() => SetStateUi(state));

    private void OnStatsChanged(ulong sent, ulong recv) => Dispatcher.BeginInvoke(() =>
    {
        TrafficText.Text = $"â†‘ {FormatBytes(sent)}   â†“ {FormatBytes(recv)}";
    });

    private void SetStateUi(TunnelState state)
    {
        StatusDot.Foreground = (Brush)FindResource("DangerBrush");
        StatusText.Text = "Disconnected";
        ConnectButtonText.Text = "CONNECT";
        ConnectButton.Background = (Brush)FindResource("AccentBrush");

        switch (state)
        {
            case TunnelState.Starting:
                StatusDot.Foreground = (Brush)FindResource("AccentHiBrush");
                StatusText.Text = "Connectingâ€¦";
                ConnectButtonText.Text = "DISCONNECT";
                ConnectButton.Background = (Brush)FindResource("FieldBrush");
                break;
            case TunnelState.Connected:
                StatusDot.Foreground = (Brush)FindResource("OkBrush");
                StatusText.Text = "Connected" + (string.IsNullOrEmpty(_tunnel.ActiveProtocol) ? "" : $" · {_tunnel.ActiveProtocol}")
                    + (string.IsNullOrEmpty(_tunnel.ConnectedRegion) ? "" : $" · {_tunnel.ConnectedRegion}");
                ConnectButtonText.Text = "DISCONNECT";
                ConnectButton.Background = (Brush)FindResource("FieldBrush");
                break;
            case TunnelState.Stopping:
                StatusDot.Foreground = (Brush)FindResource("TextDimBrush");
                StatusText.Text = "Disconnectingâ€¦";
                break;
        }
        UpdatePortsText();
    }

    private void UpdatePortsText()
    {
        if (_tunnel.HttpProxyPort <= 0 && _tunnel.SocksProxyPort <= 0)
        {
            PortsText.Text = _tunnel.State == TunnelState.Connected
                ? "tunnel connected" : "proxy: not connected";
            return;
        }
        PortsText.Text = $"HTTP :{_tunnel.HttpProxyPort}  SOCKS :{_tunnel.SocksProxyPort}  (127.0.0.1)";
        if (_tunnel.State == TunnelState.Connected && SystemProxyCheck.IsChecked == true && _tunnel.HttpProxyPort > 0)
        {
            try { SystemProxy.Apply(_tunnel.HttpProxyPort); } catch (Exception ex) { AppendLog("[proxy] " + ex.Message); }
        }
    }

    private void AppendLog(string line) => Dispatcher.BeginInvoke(() =>
    {
        var normalized = NormalizeForDedup(line);
        if (_logBuffer.Count > 0 && normalized == _lastNormalized)
        {
            _lastRepeatCount++;
            // collapse repeats: rewrite the last entry with a ×N suffix
            _logBuffer[^1] = $"[{DateTime.Now:HH:mm:ss}] {normalized}  ×{_lastRepeatCount}";
            LogList.ItemsSource = null;
            LogList.ItemsSource = _logBuffer;
            if (LogList.Items.Count > 0) LogList.ScrollIntoView(LogList.Items[^1]);
            return;
        }
        _lastNormalized = normalized;
        _lastRepeatCount = 1;
        _logBuffer.Add($"[{DateTime.Now:HH:mm:ss}] {Truncate(line)}");
        if (_logBuffer.Count > MaxLogLines) _logBuffer.RemoveAt(0);
        LogList.ItemsSource = null;
        LogList.ItemsSource = _logBuffer;
        if (LogList.Items.Count > 0) LogList.ScrollIntoView(LogList.Items[^1]);
    });

    /// <summary>
    /// Collapse consecutive identical engine lines in the UI log:
    ///  - strips the engine's own "repeats":N counter (it increments on every repeat);
    ///  - folds repeated lines into the last one with a ×N suffix.
    /// </summary>
    private static string NormalizeForDedup(string line)
    {
        // remove engine repeat counters so "…\", \"repeats\":2}" == "…\":1}}"
        var stripped = System.Text.RegularExpressions.Regex.Replace(line, "\"repeats\"\\s*:\\s*\\d+", "");
        return stripped.Length > 400 ? stripped[..400] : stripped;
    }

    private string _lastNormalized = "";
    private int _lastRepeatCount = 1;

    private void AppendNotice(string notice) => AppendLog($"[notice] {notice}");

    private void OnRegionsChanged()
    {
        Dispatcher.BeginInvoke(() =>
        {
            var items = _tunnel.AvailableEgressRegions.OrderBy(r => r).ToList();
            if (items.Count > 0)
            {
                BuildRegionChips(items);
                if (string.IsNullOrEmpty(_selectedRegion) || items.Contains(_selectedRegion, StringComparer.OrdinalIgnoreCase))
                {
                    // keep selection automatically
                }
                else
                {
                    AppendLog($"[ui] saved region {_selectedRegion} not in available list — reverted to auto");
                    SetRegionSelection("");
                }
            }
        });
    }

    // ---------------- region chips ----------------

    private void BuildRegionChips(List<string> regions)
    {
        RegionChips.Children.Clear();

        RegionChips.Children.Add(MakeChip("", "AUTO", "Exception: best performance"));
        foreach (var r in regions)
        {
            RegionChips.Children.Add(MakeChip(r, r, CountryName(r)));
        }
        HighlightSelectedChip();
        UpdateRegionHint();
    }

    private System.Windows.Controls.Button MakeChip(string code, string caption, string tooltip)
    {
        var chip = new System.Windows.Controls.Button
        {
            Content = caption,
            Tag = code,
            ToolTip = tooltip,
            Foreground = Brushes.White,
            FontWeight = FontWeights.Medium,
            FontSize = 12,
            Padding = new Thickness(12, 5, 12, 5),
            Margin = new Thickness(0, 4, 8, 4),
            Cursor = System.Windows.Input.Cursors.Hand,
            BorderThickness = new Thickness(0),
        };
        chip.Click += RegionChip_Click;
        return chip;
    }

    private void RegionChip_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.Button chip && chip.Tag is string code)
        {
            SetRegionSelection(code);
            SaveSettingsFromUi();
            OnSettingsDirty_Restart();
        }
    }

    private void SetRegionSelection(string code)
    {
        _selectedRegion = code ?? "";
        if (RegionChips.Children.Count > 0) HighlightSelectedChip();
        UpdateRegionHint();
    }

    private void HighlightSelectedChip()
    {
        foreach (var child in RegionChips.Children)
        {
            if (child is System.Windows.Controls.Button chip)
            {
                bool selected = string.Equals(chip.Tag as string ?? "", _selectedRegion, StringComparison.OrdinalIgnoreCase);
                chip.Background = selected
                    ? (Brush)FindResource("AccentHiBrush")
                    : (Brush)FindResource("FieldBrush");
            }
        }
    }

    private void UpdateRegionHint()
    {
        RegionHintText.Text = _selectedRegion switch
        {
            "" => "Auto (best performance)",
            var code => $"Exit: {code} — {CountryName(code)}",
        };
    }

    private static string CountryName(string code) => code.ToUpperInvariant() switch
    {
        "AT" => "Austria", "AU" => "Australia", "BE" => "Belgium", "BR" => "Brazil",
        "CA" => "Canada", "CH" => "Switzerland", "CZ" => "Czechia", "DE" => "Germany",
        "DK" => "Denmark", "ES" => "Spain", "FI" => "Finland", "FR" => "France",
        "GB" => "United Kingdom", "HR" => "Croatia", "HK" => "Hong Kong", "HU" => "Hungary",
        "ID" => "Indonesia", "IE" => "Ireland", "IN" => "India", "IT" => "Italy",
        "JP" => "Japan", "KR" => "South Korea", "LT" => "Lithuania", "MX" => "Mexico",
        "MY" => "Malaysia", "NL" => "Netherlands", "NO" => "Norway", "NZ" => "New Zealand",
        "PH" => "Philippines", "PL" => "Poland", "PT" => "Portugal", "RO" => "Romania",
        "RS" => "Serbia", "SE" => "Sweden", "SG" => "Singapore", "TH" => "Thailand",
        "TR" => "Türkiye", "UA" => "Ukraine", "US" => "United States", "VN" => "Vietnam",
        "ZA" => "South Africa",
        _ => code,
    };


    private void OnEngineDied(string reason)
    {
        // kill-mode: engine died unexpectedly — restore system proxy immediately
        Dispatcher.BeginInvoke(() =>
        {
            try { if (SystemProxy.IsCurrentlySet) SystemProxy.Restore(); } catch { }
            AppendLog($"[kill-mode] engine died unexpectedly ({reason}) — system proxy restored");
            StatusText.Text = "Engine stopped unexpectedly";
        });
    }

    private void SaveSettingsFromUi()
    {
        _settings.EgressRegion = _selectedRegion;
        _settings.UseSystemProxy = SystemProxyCheck.IsChecked == true;
        _settings.AutoConnect = AutoConnectCheck.IsChecked == true;
        _settings.SplitTunnelOwnRegion = BypassOwnRegionCheck.IsChecked == true;
        _settings.SplitTunnelRegionsCsv = (BypassRegionsBox.Text ?? "").Trim();
        _settings.Save();
    }

    /// <summary>
    /// Applies region/bypass changes: if the tunnel is already up, perform a
    /// hot-restart (stop → start) so the change takes effect immediately.
    /// </summary>
    private async void OnSettingsDirty_Restart()
    {
        if (_tunnel.State is TunnelState.Connected or TunnelState.Starting)
        {
            AppendLog("[ui] settings changed — restarting tunnel…");
            try
            {
                if (SystemProxy.IsCurrentlySet) SystemProxy.Restore();
            }
            catch { }
            await Task.Run(() => _tunnel.Stop());
            await ConnectAsync();
        }
    }

    private static string Truncate(string s) => s.Length <= 300 ? s : s[..300] + "â€¦";

    private static string FormatBytes(ulong b)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double v = b; int u = 0;
        while (v >= 1024 && u < units.Length - 1) { v /= 1024; u++; }
        return $"{v:0.0} {units[u]}";
    }
}

/// <summary>Small modal prompt (still used for the update-channel first-time URL).</summary>
public static class Prompt
{
    public static string? Show(Window owner, string title, string label, string initial)
    {
        var box = new System.Windows.Controls.TextBox
        {
            Text = initial,
            Margin = new Thickness(0, 8, 0, 12),
        };
        var ok = new System.Windows.Controls.Button
        {
            Content = "OK",
            IsDefault = true,
            Width = 80,
            Height = 28,
            Margin = new Thickness(0, 0, 8, 0),
        };
        var cancel = new System.Windows.Controls.Button
        {
            Content = "Cancel",
            IsCancel = true,
            Width = 80,
            Height = 28,
        };

        var dlg = new Window
        {
            Title = title,
            Owner = owner,
            Width = 480,
            SizeToContent = System.Windows.SizeToContent.Height,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = (Brush)owner.TryFindResource("PanelBrush") ?? Brushes.DimGray,
            Foreground = (Brush)owner.TryFindResource("TextBrush") ?? Brushes.White,
            Content = new System.Windows.Controls.StackPanel
            {
                Margin = new Thickness(16),
                Children =
                {
                    new System.Windows.Controls.TextBlock
                    {
                        Text = label,
                        TextWrapping = System.Windows.TextWrapping.Wrap,
                        FontSize = 12,
                    },
                    box,
                    new System.Windows.Controls.StackPanel
                    {
                        Orientation = System.Windows.Controls.Orientation.Horizontal,
                        HorizontalAlignment = HAlign.Right,
                        Children = { ok, cancel },
                    },
                },
            },
        };

        ok.Click += (_, _) => dlg.DialogResult = true;
        cancel.Click += (_, _) => dlg.DialogResult = false;

        return dlg.ShowDialog() == true ? box.Text : null;
    }
}


