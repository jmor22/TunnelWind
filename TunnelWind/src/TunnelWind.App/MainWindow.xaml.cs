using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using HAlign = System.Windows.HorizontalAlignment;
using IoFile = System.IO.File;
using IoPath = System.IO.Path;
using WpfMsgBox = System.Windows.MessageBox;
using Button = System.Windows.Controls.Button;
using TextBox = System.Windows.Controls.TextBox;
using Clipboard = System.Windows.Clipboard;
using Color = System.Windows.Media.Color;
using ButtonBase = System.Windows.Controls.Primitives.ButtonBase;
using TunnelWind.Core;

namespace TunnelWind.App;

/// <summary>Row model for the server browser (rebuilt on filter/favorite/latency change).</summary>
public sealed class ServerRow
{
    public string Code { get; init; } = "";
    public string Name { get; init; } = "";
    public string Subtitle { get; init; } = "";
    public string Badge { get; init; } = "";
    public string LatencyText { get; init; } = "";
    public Brush LatencyFg { get; init; } = Brushes.Transparent;
    public Brush LatencyBg { get; init; } = Brushes.Transparent;
    public string StarGlyph { get; init; } = "";
    public Brush StarFg { get; init; } = Brushes.Gray;
}

public partial class MainWindow : Window
{
    private readonly TunnelManager _tunnel = new();
    private Settings _settings = new();
    private System.Windows.Forms.NotifyIcon? _tray;
    private System.Windows.Forms.ContextMenuStrip? _menu;
    private bool _reallyClosing;
    private bool _trayNoticeShown;
    private readonly List<string> _logBuffer = new();
    private const int MaxLogLines = 600;
    private string _selectedRegion = "";
    private bool _integrityOk;
    private string _lastNormalized = "";
    private int _lastRepeatCount = 1;

    private readonly Dictionary<string, int> _latency = new(StringComparer.OrdinalIgnoreCase);
    private readonly DispatcherTimer _sessionTimer;
    private DateTime _connectedAt;
    private bool _measuring;
    private System.Drawing.Icon? _trayIconOff;
    private System.Drawing.Icon? _trayIconBusy;
    private System.Drawing.Icon? _trayIconOn;

    public MainWindow()
    {
        InitializeComponent();
        _settings = Settings.Load();

        ApplyAppIcon();

        _sessionTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _sessionTimer.Tick += (_, _) => UpdateSession();

        ApplySettingsToUi();
        _selectedRegion = _settings.EgressRegion ?? "";

        _tunnel.StateChanged += OnStateChanged;
        _tunnel.StatsChanged += OnStatsChanged;
        _tunnel.LogLine += AppendLog;
        _tunnel.Notice += AppendNotice;
        _tunnel.RegionsChanged += OnRegionsChanged;
        _tunnel.EngineDied += OnEngineDied;
        _tunnel.SignalChanged += () => Dispatcher.BeginInvoke(() =>
        {
            SetStateUi(_tunnel.State);
            UpdateRegionHint();
        });

        BuildTray();
        Closing += OnMainWindowClosing;
        StateChanged += (_, _) =>
            RootBorder.Margin = WindowState == WindowState.Maximized ? new Thickness(7) : new Thickness(0);

        SetStateUi(TunnelState.Stopped);
        RebuildServerList();
        RebuildFavorites();

        Loaded += async (_, _) =>
        {
            VersionText.Text = "v" + Identity.AppVersion;

            SystemProxy.EnsureStaleClear(_settings.LocalHttpProxyPort > 0 ? _settings.LocalHttpProxyPort : 8080);

            var engineSrc = IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "engine", "psiphon-tunnel-core.exe");
            if (!IoFile.Exists(engineSrc))
            {
                AppendLog("[engine] psiphon-tunnel-core.exe missing in engine\\ — see TunnelWind/README.md");
                return;
            }
            var enginePath = _tunnel.EnsureEngine(engineSrc);
            AppendLog($"[engine] ready: {enginePath}");
            var check = EngineIntegrity.Verify(engineSrc, enginePath);
            AppendLog(check.Message);
            _integrityOk = check.Ok;

            if (_settings.AutoConnect) await ConnectAsync();
        };
    }

    // ---------------------------------------------------------------- shell

    private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void Maximize_Click(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void Nav_Checked(object sender, RoutedEventArgs e)
    {
        if (HomePage == null) return;   // fires during InitializeComponent
        if (SettingsPage.Visibility == Visibility.Visible) SaveSettings();
        ShowPage(sender == NavServers ? ServersPage
            : sender == NavSettings ? SettingsPage
            : sender == NavLogs ? LogsPage
            : HomePage);
    }

    private void ShowPage(UIElement page)
    {
        HomePage.Visibility = page == HomePage ? Visibility.Visible : Visibility.Collapsed;
        ServersPage.Visibility = page == ServersPage ? Visibility.Visible : Visibility.Collapsed;
        SettingsPage.Visibility = page == SettingsPage ? Visibility.Visible : Visibility.Collapsed;
        LogsPage.Visibility = page == LogsPage ? Visibility.Visible : Visibility.Collapsed;
    }

    // ---------------------------------------------------------------- connect / disconnect

    private void PowerButton_Click(object sender, MouseButtonEventArgs e)
    {
        if (_tunnel.State is TunnelState.Connected or TunnelState.Starting) _ = DisconnectAsync();
        else _ = ConnectAsync();
    }

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

        SaveSettings();
        AppendLog("[ui] connecting...");
        await Task.Run(() => _tunnel.Start(_settings));
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

    private async Task RestartTunnelAsync()
    {
        try { if (SystemProxy.IsCurrentlySet) SystemProxy.Restore(); } catch { }
        await Task.Run(() => _tunnel.Stop());
        await ConnectAsync();
    }

    private async void ApplyLiveIfConnected()
    {
        if (_tunnel.State is TunnelState.Connected or TunnelState.Starting)
        {
            AppendLog("[ui] applying settings — restarting tunnel…");
            await RestartTunnelAsync();
        }
    }

    // ---------------------------------------------------------------- state UI

    private void OnStateChanged(TunnelState state) => Dispatcher.BeginInvoke(() =>
    {
        SetStateUi(state);
        UpdateRegionHint();
    });

    private void OnStatsChanged(ulong sent, ulong recv) => Dispatcher.BeginInvoke(() =>
    {
        UploadText.Text = FormatBytes(sent);
        DownloadText.Text = FormatBytes(recv);
    });

    private void SetStateUi(TunnelState state)
    {
        var connected = state == TunnelState.Connected;
        var starting = state == TunnelState.Starting;
        var stopping = state == TunnelState.Stopping;

        var glyph = connected ? Res("OkBrush") : starting ? Res("AccentHiBrush") : stopping ? Res("DimBrush") : Res("DimBrush");
        PowerGlyph.Fill = glyph;

        PowerButton.BorderBrush = connected ? Res("OkBrush")
            : starting ? Res("AccentBrush")
            : Res("BorderBrush");
        PowerButton.Background = connected ? new SolidColorBrush(Color.FromRgb(0x13, 0x2E, 0x27))
            : Res("CardBrush");

        if (connected)
        {
            PowerButton.Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromRgb(0x34, 0xD3, 0x99),
                BlurRadius = 34,
                ShadowDepth = 0,
                Opacity = 0.55,
            };
        }
        else if (starting)
        {
            PowerButton.Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromRgb(0x3B, 0x82, 0xF6),
                BlurRadius = 24,
                ShadowDepth = 0,
                Opacity = 0.45,
            };
        }
        else
        {
            PowerButton.Effect = null;
        }

        StatusText.Text = connected ? "Protected" : starting ? "Connecting…" : stopping ? "Disconnecting…" : "Not protected";
        StatusText.Foreground = connected ? Res("OkBrush") : Res("TextBrush");
        ConnectButtonText.Text = connected ? "DISCONNECT" : starting ? "CONNECTING" : stopping ? "STOPPING" : "CONNECT";

        SidebarDot.Fill = connected ? Res("OkBrush") : starting ? Res("AccentHiBrush") : Res("WarnBrush");
        SidebarStatus.Text = connected ? "Protected" : starting ? "Connecting…" : "Not protected";

        if (connected)
        {
            if (_sessionTimer.IsEnabled == false)
            {
                _connectedAt = DateTime.Now;
                _sessionTimer.Start();
            }
        }
        else
        {
            _sessionTimer.Stop();
            SessionText.Text = "--:--";
        }

        UpdatePorts();
        UpdateConnectionCard();
        UpdateTrayState(state);
    }

    private void UpdatePorts()
    {
        PortsHttp.Text = _tunnel.HttpProxyPort > 0 ? ":" + _tunnel.HttpProxyPort : "—";
        PortsSocks.Text = _tunnel.SocksProxyPort > 0 ? ":" + _tunnel.SocksProxyPort : "—";

        var sysProxy = _settings.UseSystemProxy && _tunnel.State == TunnelState.Connected && _tunnel.HttpProxyPort > 0;
        if (sysProxy)
        {
            try { SystemProxy.Apply(_tunnel.HttpProxyPort); } catch (Exception ex) { AppendLog("[proxy] " + ex.Message); }
        }
        ProxyState.Text = _settings.UseSystemProxy
            ? (_tunnel.State == TunnelState.Connected ? "on" : "off")
            : "disabled";
        ProxyState.Foreground = _settings.UseSystemProxy && _tunnel.State == TunnelState.Connected
            ? Res("OkBrush") : Res("DimBrush");
    }

    private void UpdateConnectionCard()
    {
        ProtocolValue.Text = !string.IsNullOrEmpty(_tunnel.ActiveProtocol) && _tunnel.State == TunnelState.Connected
            ? _tunnel.ActiveProtocol
            : _settings.ProtocolLabel();
    }

    private void UpdateSession()
    {
        if (_tunnel.State != TunnelState.Connected) return;
        var elapsed = DateTime.Now - _connectedAt;
        SessionText.Text = $"{(int)elapsed.TotalMinutes:00}:{elapsed.Seconds:00}";
    }

    private void UpdateRegionHint()
    {
        if (_tunnel.State == TunnelState.Connected && !string.IsNullOrEmpty(_tunnel.ConnectedRegion))
        {
            RegionHintText.Text = CountryName(_tunnel.ConnectedRegion) +
                (string.IsNullOrEmpty(_tunnel.ActiveProtocol) ? "" : " · " + _tunnel.ActiveProtocol);
        }
        else if (string.IsNullOrEmpty(_selectedRegion))
        {
            RegionHintText.Text = "Fastest server · automatic";
        }
        else
        {
            RegionHintText.Text = CountryName(_selectedRegion) + " · selected";
        }
    }

    // ---------------------------------------------------------------- servers

    private void OnRegionsChanged() => Dispatcher.BeginInvoke(() =>
    {
        var items = _tunnel.AvailableEgressRegions.OrderBy(r => r).ToList();
        if (items.Count > 0)
        {
            _settings.CachedRegionsCsv = string.Join(",", items);
            _settings.Save();
            if (!string.IsNullOrEmpty(_selectedRegion) &&
                !items.Contains(_selectedRegion, StringComparer.OrdinalIgnoreCase))
            {
                AppendLog($"[ui] saved region {_selectedRegion} is no longer available — reverted to automatic");
                _selectedRegion = "";
                _settings.EgressRegion = "";
                _settings.Save();
            }
            RebuildServerList();
        }
    });

    private void ServerSearch_TextChanged(object sender, TextChangedEventArgs e) => RebuildServerList();

    private void RebuildServerList()
    {
        if (ServerList == null) return;

        var query = (ServerSearch.Text ?? "").Trim();
        var regions = _tunnel.AvailableEgressRegions.Count > 0
            ? _tunnel.AvailableEgressRegions.ToList()
            : _settings.CachedRegions.ToList();

        var favorites = new HashSet<string>(_settings.FavoriteRegions, StringComparer.OrdinalIgnoreCase);
        var recents = _settings.RecentRegions.ToList();

        var rows = new List<ServerRow>();
        if (query.Length == 0) rows.Add(MakeRow(""));

        var filtered = regions
            .Where(r => query.Length == 0
                        || CountryName(r).Contains(query, StringComparison.OrdinalIgnoreCase)
                        || r.Equals(query, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(r => favorites.Contains(r))
            .ThenByDescending(r =>
            {
                var i = recents.FindIndex(x => x.Equals(r, StringComparison.OrdinalIgnoreCase));
                return i >= 0 ? 100 - i : 0;
            })
            .ThenBy(r => CountryName(r))
            .ToList();

        foreach (var code in filtered) rows.Add(MakeRow(code));

        ServerList.ItemsSource = rows;
        ServerList.SelectedItem = rows.FirstOrDefault(r => r.Code.Equals(_selectedRegion, StringComparison.OrdinalIgnoreCase));
        ServerCountText.Text = regions.Count == 0
            ? "connect once to discover locations"
            : $"{regions.Count} locations";
        UpdateFastestCheck();
    }

    private ServerRow MakeRow(string code)
    {
        var favorite = code.Length > 0 && _settings.FavoriteRegions.Contains(code, StringComparer.OrdinalIgnoreCase);
        _latency.TryGetValue(code, out var latency);
        var hasLatency = latency > 0;
        var latencyFg = !hasLatency ? Brushes.Transparent
            : latency < 120 ? Res("OkBrush")
            : latency < 250 ? new SolidColorBrush(Color.FromRgb(0xF5, 0xC4, 0x51))
            : Res("WarnBrush");

        return new ServerRow
        {
            Code = code,
            Name = code.Length == 0 ? "Fastest server" : CountryName(code),
            Subtitle = code.Length == 0 ? "Automatic selection" : code,
            Badge = code.Length == 0 ? "AUTO" : code,
            LatencyText = hasLatency ? $"{latency} ms" : "",
            LatencyFg = latencyFg,
            LatencyBg = hasLatency ? Res("FieldBrush") : Brushes.Transparent,
            StarGlyph = code.Length == 0 ? "" : (favorite ? "★" : "☆"),
            StarFg = favorite ? Res("AccentHiBrush") : Res("DimBrush"),
        };
    }

    private void UpdateFastestCheck()
    {
        if (FastestCheck == null) return;
        FastestCheck.Text = string.IsNullOrEmpty(_selectedRegion) ? "✓" : "";
    }

    private void ServerList_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (IsWithinButton(e.OriginalSource as DependencyObject)) return;
        if (ServerList.SelectedItem is ServerRow row) SelectRegion(row.Code);
    }

    private static bool IsWithinButton(DependencyObject? source)
    {
        while (source != null)
        {
            if (source is ButtonBase) return true;
            source = VisualTreeHelper.GetParent(source);
        }
        return false;
    }

    private void Star_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string code } || code.Length == 0) return;
        var favorites = _settings.FavoriteRegions.ToList();
        var index = favorites.FindIndex(x => x.Equals(code, StringComparison.OrdinalIgnoreCase));
        if (index >= 0) favorites.RemoveAt(index);
        else favorites.Insert(0, code);
        _settings.FavoriteRegionsCsv = string.Join(",", favorites.Take(12));
        _settings.Save();
        RebuildServerList();
        RebuildFavorites();
        e.Handled = true;
    }

    private void Fastest_Click(object sender, RoutedEventArgs e) => SelectRegion("");

    private void SelectRegion(string code)
    {
        code ??= "";
        if (code.Equals(_selectedRegion, StringComparison.OrdinalIgnoreCase) && _tunnel.State != TunnelState.Connected)
            return;

        _selectedRegion = code;
        _settings.EgressRegion = code;
        if (code.Length > 0)
        {
            var recents = _settings.RecentRegions.ToList();
            recents.RemoveAll(x => x.Equals(code, StringComparison.OrdinalIgnoreCase));
            recents.Insert(0, code);
            _settings.RecentRegionsCsv = string.Join(",", recents.Take(8));
        }
        _settings.Save();

        UpdateRegionHint();
        RebuildServerList();
        RebuildFavorites();
        ApplyLiveIfConnected();
    }

    private void RebuildFavorites()
    {
        if (FavoritesPanel == null) return;
        FavoritesPanel.Items.Clear();
        var favorites = _settings.FavoriteRegions.Take(5).ToList();
        FavoritesEmpty.Visibility = favorites.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        foreach (var code in favorites)
        {
            var button = new Button { Style = (Style)FindResource("QuickRow"), Tag = code };
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(34) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var badge = new Border
            {
                Width = 36,
                Height = 22,
                CornerRadius = new CornerRadius(6),
                Background = Res("FieldBrush"),
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock
                {
                    Text = code,
                    FontSize = 9.5,
                    Foreground = Res("DimBrush"),
                    HorizontalAlignment = System.Windows.HorizontalAlignment.Center,
                    VerticalAlignment = System.Windows.VerticalAlignment.Center,
                },
            };
            Grid.SetColumn(badge, 0);
            grid.Children.Add(badge);

            var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(new TextBlock { Text = CountryName(code), FontSize = 13.5, FontWeight = FontWeights.Medium });
            text.Children.Add(new TextBlock { Text = code, FontSize = 10.5, Foreground = Res("DimBrush") });
            Grid.SetColumn(text, 1);
            grid.Children.Add(text);

            if (code.Equals(_selectedRegion, StringComparison.OrdinalIgnoreCase))
            {
                var check = new TextBlock
                {
                    Text = "✓", FontSize = 13, Foreground = Res("AccentHiBrush"),
                    VerticalAlignment = VerticalAlignment.Center,
                };
                Grid.SetColumn(check, 2);
                grid.Children.Add(check);
            }

            button.Content = grid;
            button.Click += (_, _) => SelectRegion(code);
            FavoritesPanel.Items.Add(button);
        }
    }

    private async void Measure_Click(object sender, RoutedEventArgs e)
    {
        if (_measuring) return;

        var hex = ServerListFetcher.TryReadStoredHexList();
        if (string.IsNullOrEmpty(hex))
        {
            AppendLog("[latency] no stored server list yet — fetching one…");
            try { await ServerListFetcher.FetchAndStoreAsync(); } catch { }
            hex = ServerListFetcher.TryReadStoredHexList();
        }
        if (string.IsNullOrEmpty(hex))
        {
            AppendLog("[latency] server list unavailable — connect once and retry");
            return;
        }

        _measuring = true;
        MeasureButton.IsEnabled = false;
        MeasureButton.Content = "Measuring…";
        try
        {
            var regions = _tunnel.AvailableEgressRegions.Count > 0
                ? _tunnel.AvailableEgressRegions.ToList()
                : _settings.CachedRegions.ToList();

            var results = await LatencyProbe.MeasureAsync(hex, regions);
            _latency.Clear();
            foreach (var kv in results) _latency[kv.Key] = kv.Value;
            AppendLog($"[latency] measured {results.Count} regions");
            RebuildServerList();
        }
        catch (Exception ex)
        {
            AppendLog("[latency] error: " + ex.Message);
        }
        finally
        {
            _measuring = false;
            MeasureButton.IsEnabled = true;
            MeasureButton.Content = "Measure latency";
        }
    }

    // ---------------------------------------------------------------- settings

    private void ApplySettingsToUi()
    {
        SystemProxyCheck.IsChecked = _settings.UseSystemProxy;
        AutoConnectCheck.IsChecked = _settings.AutoConnect;
        StartWithWindowsCheck.IsChecked = _settings.StartWithWindows;
        BypassOwnRegionCheck.IsChecked = _settings.SplitTunnelOwnRegion;
        BypassRegionsBox.Text = _settings.SplitTunnelRegionsCsv ?? "";
        HttpPortBox.Text = _settings.LocalHttpProxyPort > 0 ? _settings.LocalHttpProxyPort.ToString() : "";
        SocksPortBox.Text = _settings.LocalSocksProxyPort > 0 ? _settings.LocalSocksProxyPort.ToString() : "";
        ExposeLanCheck.IsChecked = _settings.ExposeProxiesToLan;
        UpstreamBox.Text = _settings.UpstreamProxyUrl ?? "";
        UpdateBox.Text = _settings.UpdateManifestUrl ?? "";

        ProtoAuto.IsChecked = _settings.Protocol == "auto";
        ProtoSsh.IsChecked = _settings.Protocol == "ssh";
        ProtoOssh.IsChecked = _settings.Protocol == "ossh";
        ProtoTls.IsChecked = _settings.Protocol == "tls";
        ProtoQuic.IsChecked = _settings.Protocol == "quic";
        ProtoMeek.IsChecked = _settings.Protocol == "meek";
        ProtocolValue.Text = _settings.ProtocolLabel();
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        SaveSettings();
        AppendLog("[ui] settings saved");
        ApplyLiveIfConnected();
    }

    private void SaveSettings()
    {
        int ParsePort(TextBox box)
        {
            var text = (box.Text ?? "").Trim();
            if (!int.TryParse(text, out var value)) return 0;
            return value is < 0 or > 65535 ? 0 : value;
        }

        _settings.EgressRegion = _selectedRegion;
        _settings.UseSystemProxy = SystemProxyCheck.IsChecked == true;
        _settings.AutoConnect = AutoConnectCheck.IsChecked == true;
        _settings.SplitTunnelOwnRegion = BypassOwnRegionCheck.IsChecked == true;
        _settings.SplitTunnelRegionsCsv = (BypassRegionsBox.Text ?? "").Trim();
        _settings.LocalHttpProxyPort = ParsePort(HttpPortBox);
        _settings.LocalSocksProxyPort = ParsePort(SocksPortBox);
        _settings.ExposeProxiesToLan = ExposeLanCheck.IsChecked == true;
        _settings.UpstreamProxyUrl = string.IsNullOrWhiteSpace(UpstreamBox.Text) ? null : UpstreamBox.Text.Trim();
        _settings.UpdateManifestUrl = (UpdateBox.Text ?? "").Trim();

        _settings.Protocol = ProtoSsh.IsChecked == true ? "ssh"
            : ProtoOssh.IsChecked == true ? "ossh"
            : ProtoTls.IsChecked == true ? "tls"
            : ProtoQuic.IsChecked == true ? "quic"
            : ProtoMeek.IsChecked == true ? "meek"
            : "auto";

        var startWithWindows = StartWithWindowsCheck.IsChecked == true;
        if (startWithWindows != _settings.StartWithWindows)
        {
            try
            {
                AutoStart.Set(startWithWindows);
                _settings.StartWithWindows = startWithWindows;
            }
            catch (Exception ex)
            {
                AppendLog("[autostart] " + ex.Message);
            }
        }

        _settings.Save();
        ProtocolValue.Text = _settings.ProtocolLabel();
        SaveHint.Text = "Saved just now";
    }

    // ---------------------------------------------------------------- updates

    private async void Update_Click(object sender, RoutedEventArgs e)
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
                UpdateBox.Text = _settings.UpdateManifestUrl;
                _settings.Save();
            }

            UpdateStatusText.Text = "Checking…";
            AppendLog("[update] checking " + _settings.UpdateManifestUrl);
            var manifest = await UpdateService.FetchManifestAsync(_settings.UpdateManifestUrl);
            if (manifest == null)
            {
                UpdateStatusText.Text = "Manifest invalid";
                AppendLog("[update] manifest invalid");
                return;
            }

            var cmp = UpdateService.CompareVersions(manifest.AppVersion, Identity.AppVersion);
            AppendLog($"[update] server version {manifest.AppVersion}; ours {Identity.AppVersion}");

            if (cmp <= 0 && string.IsNullOrWhiteSpace(manifest.EngineUrl))
            {
                UpdateStatusText.Text = "Up to date · " + Identity.AppVersion;
                AppendLog("[update] up to date");
                return;
            }

            UpdateStatusText.Text = "Update available: " + manifest.AppVersion;
            var engineNote = string.IsNullOrWhiteSpace(manifest.EngineUrl) ? "" : " - new engine package present";
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
            UpdateStatusText.Text = "Update failed";
            AppendLog("[update] error: " + ex.Message);
            WpfMsgBox.Show(ex.Message, "Tunnel Wind Update", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    // ---------------------------------------------------------------- logs

    private void LogCopy_Click(object sender, RoutedEventArgs e)
    {
        try { Clipboard.SetText(string.Join(Environment.NewLine, _logBuffer)); } catch { }
    }

    private void LogClear_Click(object sender, RoutedEventArgs e)
    {
        _logBuffer.Clear();
        LogList.ItemsSource = null;
        LogCountText.Text = "";
    }

    private void AppendLog(string line) => Dispatcher.BeginInvoke(() =>
    {
        var normalized = NormalizeForDedup(line);
        if (_logBuffer.Count > 0 && normalized == _lastNormalized)
        {
            _lastRepeatCount++;
            _logBuffer[^1] = $"[{DateTime.Now:HH:mm:ss}] {normalized}  ×{_lastRepeatCount}";
        }
        else
        {
            _lastNormalized = normalized;
            _lastRepeatCount = 1;
            _logBuffer.Add($"[{DateTime.Now:HH:mm:ss}] {Truncate(line)}");
            if (_logBuffer.Count > MaxLogLines) _logBuffer.RemoveAt(0);
        }

        LogList.ItemsSource = null;
        LogList.ItemsSource = _logBuffer;
        if (LogList.Items.Count > 0) LogList.ScrollIntoView(LogList.Items[^1]);
        LogCountText.Text = $"{_logBuffer.Count} lines";
    });

    private static string NormalizeForDedup(string line)
    {
        var stripped = System.Text.RegularExpressions.Regex.Replace(line, "\"repeats\"\\s*:\\s*\\d+", "");
        return stripped.Length > 400 ? stripped[..400] : stripped;
    }

    private void AppendNotice(string notice) => AppendLog($"[notice] {notice}");

    // ---------------------------------------------------------------- icons

    private string AppIconPath => IoPath.Combine(AppContext.BaseDirectory, "app.ico");

    private void ApplyAppIcon()
    {
        try
        {
            if (IoFile.Exists(AppIconPath))
            {
                Icon = new System.Windows.Media.Imaging.BitmapImage(new Uri(AppIconPath));
            }
        }
        catch { }
    }

    private System.Drawing.Icon LoadTrayIcon(string file)
    {
        var size = System.Windows.Forms.SystemInformation.SmallIconSize;
        try
        {
            var path = IoPath.Combine(AppContext.BaseDirectory, file);
            if (IoFile.Exists(path))
            {
                return new System.Drawing.Icon(path, size);
            }
        }
        catch { }
        return new System.Drawing.Icon(System.Drawing.SystemIcons.Shield, size);
    }

    /// <summary>Swap the tray icon + tooltip to match the tunnel state.</summary>
    private void UpdateTrayState(TunnelState state)
    {
        if (_tray == null) return;
        try
        {
            _tray.Icon = state switch
            {
                TunnelState.Connected => _trayIconOn ?? _trayIconOff,
                TunnelState.Starting or TunnelState.Stopping => _trayIconBusy ?? _trayIconOff,
                _ => _trayIconOff,
            };
            _tray.Text = state switch
            {
                TunnelState.Connected =>
                    "Tunnel Wind — Protected" +
                    (string.IsNullOrEmpty(_tunnel.ConnectedRegion) ? "" : " (" + _tunnel.ConnectedRegion + ")"),
                TunnelState.Starting => "Tunnel Wind — Connecting…",
                TunnelState.Stopping => "Tunnel Wind — Disconnecting…",
                _ => "Tunnel Wind — Not connected",
            };
        }
        catch { }
    }

    // ---------------------------------------------------------------- tray / close

    private void BuildTray()
    {
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

            _trayIconOff = LoadTrayIcon("tray_off.ico");
            _trayIconBusy = LoadTrayIcon("tray_busy.ico");
            _trayIconOn = LoadTrayIcon("tray_on.ico");

            _tray = new System.Windows.Forms.NotifyIcon
            {
                Text = "Tunnel Wind",
                Visible = true,
                Icon = _trayIconOff,
                ContextMenuStrip = _menu,
            };
            _tray.DoubleClick += (_, _) => ShowFromTray();
        }
        catch { /* tray is optional */ }
    }

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
            e.Cancel = true;
            Hide();
            if (!_trayNoticeShown)
            {
                _trayNoticeShown = true;
                try
                {
                    if (_tray != null)
                    {
                        _tray.BalloonTipTitle = "Tunnel Wind is still running";
                        _tray.BalloonTipText = "The tunnel keeps your connection. Double-click the tray icon to restore the window; Exit fully quits.";
                        _tray.ShowBalloonTip(4000);
                    }
                }
                catch { }
            }
            return;
        }

        if (_tunnel.State != TunnelState.Stopped) _tunnel.Stop();
        try { if (SystemProxy.IsCurrentlySet) SystemProxy.Restore(); } catch { }
        _tray?.Dispose();
        _tray = null;
        _trayIconOff?.Dispose();
        _trayIconBusy?.Dispose();
        _trayIconOn?.Dispose();
        _trayIconOff = _trayIconBusy = _trayIconOn = null;
    }

    // ---------------------------------------------------------------- helpers

    private Brush Res(string key) => (Brush)FindResource(key);

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
        Dispatcher.BeginInvoke(() =>
        {
            try { if (SystemProxy.IsCurrentlySet) SystemProxy.Restore(); } catch { }
            AppendLog($"[kill-mode] engine died unexpectedly ({reason}) — system proxy restored");
        });
    }

    private static string Truncate(string s) => s.Length <= 300 ? s : s[..300] + "…";

    private static string FormatBytes(ulong b)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double v = b; int u = 0;
        while (v >= 1024 && u < units.Length - 1) { v /= 1024; u++; }
        return $"{v:0.0} {units[u]}";
    }
}

/// <summary>Small modal prompt (used for the update-channel first-time URL).</summary>
public static class Prompt
{
    public static string? Show(Window owner, string title, string label, string initial)
    {
        var box = new System.Windows.Controls.TextBox
        {
            Text = initial,
            Margin = new Thickness(0, 8, 0, 12),
            Background = (Brush)owner.TryFindResource("FieldBrush") ?? Brushes.DimGray,
            Foreground = (Brush)owner.TryFindResource("TextBrush") ?? Brushes.White,
            BorderBrush = (Brush)owner.TryFindResource("BorderBrush") ?? Brushes.Gray,
            Padding = new Thickness(8, 5, 8, 5),
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
            Width = 500,
            SizeToContent = System.Windows.SizeToContent.Height,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = (Brush)owner.TryFindResource("CardBrush") ?? Brushes.DimGray,
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
