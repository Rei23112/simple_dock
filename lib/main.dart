import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() => runApp(const SimpleDockApp());

class SimpleDockApp extends StatelessWidget {
  const SimpleDockApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Simple Dock',
    locale: const Locale('ja', 'JP'),
    supportedLocales: const [Locale('ja', 'JP')],
    localizationsDelegates: const [
      GlobalMaterialLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
    ],
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xfff05d5e)),
      useMaterial3: true,
      fontFamily: 'sans-serif',
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
  bool _hideOnKeyboard = true;
  bool _followRotation = true;
  bool _usageAccess = false;
  bool _batteryOptimizationExempt = false;
  double _iconSize = 56;
  double _opacity = .92;
  double _showPosition = 50;
  int _recentCount = 5;
  String _orientation = 'bottom';
  String _iconShape = 'round';
  bool _permissionDialogVisible = false;
  Timer? _settingsTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    _settingsTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _load();
    }
  }

  @override
  void didChangeMetrics() {
    final views = WidgetsBinding.instance.platformDispatcher.views;
    if (views.isEmpty) return;
    final bottomInset = views.first.viewInsets.bottom;
    if (bottomInset > 0 && _hideOnKeyboard) {
      _channel.invokeMethod('collapseDock');
    } else if (bottomInset == 0) {
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
      _hideOnKeyboard = prefs.getBool('hideOnKeyboard') ?? true;
      _followRotation = prefs.getBool('followRotation') ?? true;
      _iconSize = prefs.getDouble('iconSize') ?? 56;
      _opacity = prefs.getDouble('opacity') ?? .92;
      _showPosition = prefs.getDouble('showPosition') ?? 50;
      _recentCount = (prefs.getInt('recentCount') ?? 5).clamp(1, 8);
      _orientation = prefs.getString('orientation') ?? 'bottom';
      final storedShape = prefs.getString('iconShape') ?? 'round';
      _iconShape = storedShape == 'circle' ? 'round' : storedShape;
    });
    final allowed =
        await _channel.invokeMethod<bool>('hasOverlayPermission') ?? false;
    await _checkUsageAccess();
    await _checkBatteryOptimization();
    if (prefs.getBool('overlayEnabled') == true && !allowed) {
      _showPermissionDialog(
        title: '表示権限が必要です',
        message: '他のアプリの上に dock を表示するため、表示権限を許可してください。',
        actionLabel: '表示権限を設定',
        action: () async {
          await _channel.invokeMethod('openOverlaySettings');
        },
      );
    } else if (prefs.getBool('overlayEnabled') == true && allowed) {
      await _channel.invokeMethod('startDock');
      if (_hideOnLaunch && !_usageAccess) {
        _showPermissionDialog(
          title: '使用状況へのアクセスが必要です',
          message: '外部アプリの起動検知と recent の更新に使用します。',
          actionLabel: '使用状況を設定',
          action: _openUsageAccessSettings,
        );
      }
    }
  }

  void _showPermissionDialog({
    required String title,
    required String message,
    required String actionLabel,
    required Future<void> Function() action,
  }) {
    if (!mounted || _permissionDialogVisible) return;
    _permissionDialogVisible = true;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(title),
          content: Text(message),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('あとで'),
            ),
            FilledButton(
              onPressed: () async {
                Navigator.of(context).pop();
                await action();
              },
              child: Text(actionLabel),
            ),
          ],
        ),
      );
      _permissionDialogVisible = false;
    });
  }

  Future<void> _checkUsageAccess() async {
    final granted =
        await _channel.invokeMethod<bool>('hasUsageAccess') ?? false;
    if (mounted) setState(() => _usageAccess = granted);
  }

  Future<void> _openUsageAccessSettings() async {
    await _channel.invokeMethod('openUsageAccessSettings');
  }

  Future<void> _checkBatteryOptimization() async {
    final exempt =
        await _channel.invokeMethod<bool>('hasBatteryOptimizationExemption') ??
        false;
    if (mounted) setState(() => _batteryOptimizationExempt = exempt);
  }

  Future<void> _openBatterySettings() async {
    await _channel.invokeMethod('openBatterySettings');
  }

  Future<void> _set(String key, Object value) async {
    final prefs = await SharedPreferences.getInstance();
    if (value is bool) await prefs.setBool(key, value);
    if (value is int) await prefs.setInt(key, value);
    if (value is double) await prefs.setDouble(key, value);
    if (value is String) await prefs.setString(key, value);
    _settingsTimer?.cancel();
    _settingsTimer = Timer(const Duration(milliseconds: 140), () async {
      await _channel.invokeMethod('updateSettings', {
        'hideOnLaunch': _hideOnLaunch,
        'hideAfterDelay': _hideAfterDelay,
        'hideOnKeyboard': _hideOnKeyboard,
        'followRotation': _followRotation,
        'iconSize': _iconSize,
        'opacity': _opacity,
        'showPosition': _showPosition,
        'recentCount': _recentCount,
        'orientation': _orientation,
        'iconShape': _iconShape,
      });
      await _channel.invokeMethod('refreshDock');
    });
    if (key == 'hideOnLaunch' && value == true) {
      await _checkUsageAccess();
      if (!_usageAccess && mounted) {
        await _openUsageAccessSettings();
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
      _showPermissionDialog(
        title: '表示権限が必要です',
        message: '他のアプリの上に dock を表示するため、表示権限を許可してください。',
        actionLabel: '表示権限を設定',
        action: () async {
          await _channel.invokeMethod('openOverlaySettings');
        },
      );
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
                    DropdownMenuItem(value: 'round', child: Text('round')),
                    DropdownMenuItem(
                      value: 'squircle',
                      child: Text('squircle'),
                    ),
                    DropdownMenuItem(value: 'square', child: Text('square')),
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
              _slider('表示ボタンの位置', _showPosition, 0, 100, (v) {
                setState(() => _showPosition = v);
                _set('showPosition', v);
              }),
              ListTile(
                contentPadding: const EdgeInsets.only(left: 16),
                title: const Text('外部アプリの起動検知'),
                subtitle: Text(_usageAccess ? '許可済み' : '許可が必要です'),
                trailing: const Icon(Icons.open_in_new_rounded),
                onTap: _openUsageAccessSettings,
              ),
              ListTile(
                contentPadding: const EdgeInsets.only(left: 16),
                title: const Text('バッテリー最適化の除外'),
                subtitle: Text(
                  _batteryOptimizationExempt ? '許可済み' : 'タスクキル対策に許可を推奨',
                ),
                trailing: const Icon(Icons.battery_saver_rounded),
                onTap: _openBatterySettings,
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
              SwitchListTile(
                contentPadding: const EdgeInsets.only(left: 16),
                title: const Text('キーボード表示時に隠す'),
                value: _hideOnKeyboard,
                onChanged: (v) {
                  setState(() => _hideOnKeyboard = v);
                  _set('hideOnKeyboard', v);
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
