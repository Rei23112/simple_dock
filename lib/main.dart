import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() => runApp(const SimpleDockApp());

class SimpleDockApp extends StatelessWidget {
  const SimpleDockApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Simple Dock',
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xfff05d5e)),
      useMaterial3: true,
    ),
    home: const DockHomePage(),
  );
}

class DockHomePage extends StatefulWidget {
  const DockHomePage({super.key});

  @override
  State<DockHomePage> createState() => _DockHomePageState();
}

class _DockHomePageState extends State<DockHomePage>
    with WidgetsBindingObserver {
  static const _channel = MethodChannel('simple_dock/android');
  List<Map<String, dynamic>> _apps = [];
  bool _overlayEnabled = false;
  bool _hideOnLaunch = true;
  bool _hideAfterDelay = false;
  bool _followRotation = true;
  double _iconSize = 56;
  double _opacity = .92;
  int _recentCount = 5;
  String _orientation = 'bottom';
  String _iconShape = 'circle';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeMetrics() {
    final views = WidgetsBinding.instance.platformDispatcher.views;
    if (views.isEmpty) return;
    final bottomInset = views.first.viewInsets.bottom;
    if (bottomInset > 0) {
      _channel.invokeMethod('collapseDock');
    } else {
      _channel.invokeMethod('refreshDock');
    }
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final apps =
        await _channel.invokeMethod<List<dynamic>>('getAppsWithVisibility') ??
        [];
    if (!mounted) return;
    setState(() {
      _apps = apps
          .map((item) => Map<String, dynamic>.from(item as Map))
          .toList();
      _overlayEnabled = prefs.getBool('overlayEnabled') ?? false;
      _hideOnLaunch = prefs.getBool('hideOnLaunch') ?? true;
      _hideAfterDelay = prefs.getBool('hideAfterDelay') ?? false;
      _followRotation = prefs.getBool('followRotation') ?? true;
      _iconSize = prefs.getDouble('iconSize') ?? 56;
      _opacity = prefs.getDouble('opacity') ?? .92;
      _recentCount = prefs.getInt('recentCount') ?? 5;
      _orientation = prefs.getString('orientation') ?? 'bottom';
      _iconShape = prefs.getString('iconShape') ?? 'circle';
    });
    final allowed =
        await _channel.invokeMethod<bool>('hasOverlayPermission') ?? false;
    if (prefs.getBool('overlayEnabled') == true && allowed) {
      await _channel.invokeMethod('startDock');
    }
  }

  Future<void> _set(String key, Object value) async {
    final prefs = await SharedPreferences.getInstance();
    if (value is bool) await prefs.setBool(key, value);
    if (value is int) await prefs.setInt(key, value);
    if (value is double) await prefs.setDouble(key, value);
    if (value is String) await prefs.setString(key, value);
    await _channel.invokeMethod('updateSettings', {
      'hideOnLaunch': _hideOnLaunch,
      'hideAfterDelay': _hideAfterDelay,
      'followRotation': _followRotation,
      'iconSize': _iconSize,
      'opacity': _opacity,
      'recentCount': _recentCount,
      'orientation': _orientation,
      'iconShape': _iconShape,
    });
    if (key == 'hideOnLaunch' && value == true) {
      final usageAccess =
          await _channel.invokeMethod<bool>('hasUsageAccess') ?? false;
      if (!usageAccess && mounted) {
        await _channel.invokeMethod('openUsageAccessSettings');
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('外部起動の検知には使用状況へのアクセスを許可してください')),
        );
      }
    }
  }

  Future<void> _reloadApps() async {
    await _load();
    await _channel.invokeMethod('refreshDock');
  }

  Future<void> _toggleOverlay(bool value) async {
    final allowed =
        await _channel.invokeMethod<bool>('hasOverlayPermission') ?? false;
    if (!allowed) {
      setState(() => _overlayEnabled = true);
      await _set('overlayEnabled', true);
      await _channel.invokeMethod('openOverlaySettings');
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('「他のアプリの上に表示」を許可してください')));
      return;
    }
    setState(() => _overlayEnabled = value);
    await _set('overlayEnabled', value);
    await _channel.invokeMethod(value ? 'startDock' : 'stopDock');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Simple Dock',
          style: TextStyle(fontWeight: FontWeight.w800),
        ),
        actions: [
          IconButton(
            onPressed: _reloadApps,
            icon: const Icon(Icons.refresh_rounded),
            tooltip: 'アプリを更新',
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 40),
        children: [
          Card(
            child: ListTile(
              title: const Text(
                'Simple Dock',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
              subtitle: Text(_overlayEnabled ? '起動中' : '停止中'),
              trailing: Switch(
                value: _overlayEnabled,
                onChanged: _toggleOverlay,
              ),
            ),
          ),
          const SizedBox(height: 14),
          Text(
            '表示設定',
            style: Theme.of(context).textTheme.titleLarge
                ?.copyWith(fontWeight: FontWeight.w800),
          ),
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: const Text('ドック'),
            children: [
              ListTile(
                contentPadding: const EdgeInsets.only(left: 16),
                title: const Text('ドックに表示するアプリ'),
                subtitle: const Text('長押しメニューからも変更できます'),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (_) =>
                          DockAppsPage(channel: _channel, apps: _apps),
                    ),
                  );
                },
              ),
              SwitchListTile(
                contentPadding: const EdgeInsets.only(left: 16),
                title: const Text('画面回転に追従'),
                value: _followRotation,
                onChanged: (v) {
                  setState(() => _followRotation = v);
                  _set('followRotation', v);
                },
              ),
              ListTile(
                contentPadding: const EdgeInsets.only(left: 16),
                title: const Text('ドックの位置'),
                trailing: DropdownButton<String>(
                  value: _orientation,
                  items: const [
                    DropdownMenuItem(value: 'bottom', child: Text('下')),
                    DropdownMenuItem(value: 'left', child: Text('左')),
                    DropdownMenuItem(value: 'right', child: Text('右')),
                  ],
                  onChanged: (v) {
                    if (v != null) {
                      setState(() => _orientation = v);
                      _set('orientation', v);
                    }
                  },
                ),
              ),
            ],
          ),
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: const Text('外観'),
            children: [
              ListTile(
                contentPadding: const EdgeInsets.only(left: 16),
                title: const Text('アイコンの形'),
                trailing: DropdownButton<String>(
                  value: _iconShape,
                  items: const [
                    DropdownMenuItem(value: 'circle', child: Text('まる')),
                    DropdownMenuItem(value: 'square', child: Text('四角')),
                    DropdownMenuItem(value: 'squircle', child: Text('スワークル')),
                  ],
                  onChanged: (v) {
                    if (v != null) {
                      setState(() => _iconShape = v);
                      _set('iconShape', v);
                    }
                  },
                ),
              ),
              _slider('アイコンサイズ', _iconSize, 36, 72, (v) {
                setState(() => _iconSize = v);
                _set('iconSize', v);
              }),
              _slider('背後の透明度', _opacity, .45, 1, (v) {
                setState(() => _opacity = v);
                _set('opacity', v);
              }),
            ],
          ),
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: const Text('動作'),
            children: [
              SwitchListTile(
                contentPadding: const EdgeInsets.only(left: 16),
                title: const Text('アプリ起動時に自動で隠す'),
                value: _hideOnLaunch,
                onChanged: (v) {
                  setState(() => _hideOnLaunch = v);
                  _set('hideOnLaunch', v);
                },
              ),
              SwitchListTile(
                contentPadding: const EdgeInsets.only(left: 16),
                title: const Text('表示後に自動で隠す'),
                value: _hideAfterDelay,
                onChanged: (v) {
                  setState(() => _hideAfterDelay = v);
                  _set('hideAfterDelay', v);
                },
              ),
              _slider('最近使ったアプリの数', _recentCount.toDouble(), 1, 8, (v) {
                setState(() => _recentCount = v.round());
                _set('recentCount', _recentCount);
              }, divisions: 7),
            ],
          ),
          const SizedBox(height: 180),
        ],
      ),
    );
  }

  Widget _slider(
    String label,
    double value,
    double min,
    double max,
    ValueChanged<double> onChanged, {
    int? divisions,
  }) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(label),
      Row(
        children: [
          Expanded(
            child: Slider(
              value: value,
              min: min,
              max: max,
              divisions: divisions,
              onChanged: onChanged,
            ),
          ),
          Text(
            label.contains('数') ? '${value.round()}' : value.toStringAsFixed(2),
          ),
        ],
      ),
    ],
  );
}

class DockAppsPage extends StatefulWidget {
  const DockAppsPage({required this.channel, required this.apps, super.key});

  final MethodChannel channel;
  final List<Map<String, dynamic>> apps;

  @override
  State<DockAppsPage> createState() => _DockAppsPageState();
}

class _DockAppsPageState extends State<DockAppsPage> {
  late List<Map<String, dynamic>> _apps = List<Map<String, dynamic>>.from(
    widget.apps,
  );

  Future<void> _loadApps() async {
    final apps =
        await widget.channel.invokeMethod<List<dynamic>>(
          'getAppsWithVisibility',
        ) ??
        [];
    if (!mounted) return;
    setState(() {
      _apps = apps
          .map((item) => Map<String, dynamic>.from(item as Map))
          .toList();
    });
  }

  Future<void> _setVisibility(Map<String, dynamic> app, bool visible) async {
    await widget.channel.invokeMethod('setAppHidden', {
      'package': app['package'],
      'hidden': !visible,
    });
    await _loadApps();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('ドックに表示するアプリ')),
    body: ListView.builder(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      itemCount: _apps.length,
      itemBuilder: (context, index) {
        final app = _apps[index];
        return SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(app['label'] as String),
          value: !(app['hidden'] as bool),
          onChanged: (visible) => _setVisibility(app, visible),
        );
      },
    ),
  );
}
