import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../common.dart';
import '../../consts.dart';
import '../../models/model.dart';
import 'remote_adb_controller.dart';
import 'remote_adb_session_state.dart';
import 'remote_adb_mirror.dart';

/// ADB navigation has its own position, controller and command path.
class RemoteAdbSidebar extends StatefulWidget {
  const RemoteAdbSidebar({super.key, required this.ffi, required this.state, required this.mirror});

  final FFI ffi;
  final RemoteAdbSessionState state;
  final RemoteAdbMirror mirror;

  @override
  State<RemoteAdbSidebar> createState() => _RemoteAdbSidebarState();
}

class _RemoteAdbSidebarState extends State<RemoteAdbSidebar> {
  late final RemoteAdbController _adb;
  PeerInfo? _peer;
  bool _peerReady = false;
  bool _viewOnly = false;
  bool _probePending = false;
  bool _sending = false;
  bool _peerUpdateScheduled = false;
  bool? _pushedPriority;
  Offset? _position;
  final TextEditingController _url = TextEditingController();

  @override
  void initState() {
    super.initState();
    _adb = RemoteAdbController(widget.ffi.sessionId, sessionState: widget.state);
    widget.ffi.ffiModel.addListener(_schedulePeerUpdate);
    widget.mirror.addListener(_applyTouchState);
    _schedulePeerUpdate();
  }

  void _applyTouchState() {
    if (!mounted) return;
    // New peers publish verified Shell snapshots directly to the controller.
    // Touch notifications must not mark that newer state stale before an older query returns.
    if (widget.state.snapshot != null) return;
    if (!widget.mirror.supported) { _pushedPriority = null; return; }
    final priority = widget.mirror.adbPriority;
    // Mirror also notifies for local button progress, not just remote capability changes.
    // Never replay a cached touch value over a newer status/shell reply.
    if (_pushedPriority == priority) return;
    _pushedPriority = priority;
    _adb.stale = true;
    widget.state.advanceEpoch();
    if (priority) {
      widget.state.apply({'supported': true, 'connected': true},
          !widget.ffi.ffiModel.viewOnly);
    } else {
      // Touch startup/recovery is not proof that the shared shell disconnected.
      _probePending = true;
      _schedulePeerUpdate();
    }
  }

  void _schedulePeerUpdate() {
    if (_peerUpdateScheduled) return;
    _peerUpdateScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _peerUpdateScheduled = false;
      if (!mounted) return;
      final model = widget.ffi.ffiModel;
      final ready = model.isPeerAndroid && model.pi.isSet.value;
      final newPeer = !identical(_peer, model.pi);
      final shouldRefresh = ready &&
          (!_peerReady || newPeer || (_viewOnly && !model.viewOnly));
      if (newPeer) {
        _pushedPriority = null;
        _adb.stale = true;
        widget.state.updatePeer(ready: false, viewOnly: model.viewOnly);
      }
      _peer = model.pi;
      _peerReady = ready;
      _viewOnly = model.viewOnly;
      widget.state.updatePeer(ready: ready, viewOnly: model.viewOnly);
      // One query when an Android session becomes ready; no periodic polling.
      _probePending = ready && (_probePending || shouldRefresh);
      if (_probePending && !_adb.refreshing && !_adb.busy) {
        _probePending = false;
        _adb.refresh();
      }
    });
  }

  Future<void> _navigate(int keyCode) => _executeShell('input keyevent $keyCode');

  Future<void> _openUrl() async {
    final text = _url.text.trim();
    final uri = Uri.tryParse(text.contains('://') ? text : 'https://$text');
    if (text.isEmpty || text.length > 2048 || RegExp(r'[\x00-\x1f\x7f]').hasMatch(text) ||
        uri == null || !uri.hasAuthority || uri.host.isEmpty || uri.userInfo.isNotEmpty ||
        (uri.scheme != 'http' && uri.scheme != 'https')) {
      showToast('请输入有效的 HTTP 或 HTTPS 网址。');
      return;
    }
    // One shell argument: quotes, $, &, backticks and substitutions stay literal.
    final escaped = uri.toString().replaceAll("'", "'\\''");
    final command = "am start -a android.intent.action.VIEW -d '$escaped'";
    if (command.length > 4096) { showToast('网址过长，请缩短后重试。'); return; }
    await _executeShell(command);
  }

  Future<void> _executeShell(String command) async {
    if (_sending || _adb.busy || _adb.refreshing || !widget.state.available ||
        widget.ffi.ffiModel.viewOnly) return;
    final peer = widget.ffi.ffiModel.pi;
    setState(() => _sending = true);
    try {
      // Reuse a confirmed connection; query only when this controller is stale.
      // The Android endpoint still authorizes and checks every shell command.
      if (_adb.stale) await _adb.refresh();
      if (!mounted || !identical(peer, widget.ffi.ffiModel.pi)) return;
      if (!_adb.connected || !_adb.ready) {
        showToast(_adb.statusError.isNotEmpty ? _adb.statusError : _adb.summary);
        return;
      }
      if (!widget.state.available || widget.ffi.ffiModel.viewOnly) return;
      final result = await _adb.run('shell', {'command': command});
      if (!mounted) return;
      if (result == null || result['exitCode'] != 0) showToast(_adb.message);
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  void dispose() {
    widget.ffi.ffiModel.removeListener(_schedulePeerUpdate);
    widget.mirror.removeListener(_applyTouchState);
    _adb.dispose();
    _url.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge([widget.state, _adb, widget.mirror,
        widget.ffi.ffiModel, widget.ffi.canvasModel]),
      builder: (context, _) {
        _schedulePeerUpdate();
        if (!widget.state.visible || !widget.ffi.ffiModel.isPeerAndroid ||
            !widget.ffi.ffiModel.pi.isSet.value || widget.ffi.ffiModel.viewOnly) {
          return const SizedBox.shrink();
        }
        return LayoutBuilder(builder: (context, constraints) {
          if (constraints.maxWidth <= 0 || constraints.maxHeight <= 0) {
            return const SizedBox.shrink();
          }
          // Same geometry and per-control scaling as makeMobileActionsOverlayEntry.
          final screen = MediaQuery.of(context).size;
          final availableHeight = math.max(45.0,
              screen.height - kDesktopRemoteTabBarHeight - screen.height * 0.1);
          final scale = math.min(math.max(widget.ffi.canvasModel.scale * 2.0, 0.85),
              availableHeight / (45 * 12));
          final width = 70.0 * scale;
          final height = 45.0 * 12 * scale;
          Offset clamp(Offset value) => Offset(
              value.dx.clamp(0.0, math.max(0.0, constraints.maxWidth - width)).toDouble(),
              value.dy.clamp(0.0, math.max(0.0, constraints.maxHeight - height)).toDouble());
          final position = clamp(_position ?? Offset(
              constraints.maxWidth * 0.25 + 90 * scale,
              constraints.maxHeight * 0.1));
          final enabled = !_sending && !_adb.busy && !_adb.refreshing;
          Widget button(IconData icon, String label, int keyCode) => IconButton(
              color: Colors.white,
              disabledColor: Colors.white38,
              splashRadius: kDesktopIconButtonSplashRadius,
              icon: Icon(icon, semanticLabel: label), iconSize: 24 * scale,
              onPressed: enabled ? () => _navigate(keyCode) : null);
          return Stack(children: [
            Positioned(left: position.dx, top: position.dy, width: width, height: height,
              child: GestureDetector(
                onPanUpdate: (event) => setState(() {
                  _position = clamp(clamp(_position ?? position) + event.delta);
                }),
                child: Card(
                    color: Colors.transparent,
                    shadowColor: Colors.transparent,
                    child: Container(
                      decoration: BoxDecoration(
                        color: MyTheme.accent.withOpacity(0.4),
                        borderRadius: BorderRadius.circular(15 * scale),
                      ),
                      child: LayoutBuilder(builder: (context, box) => SingleChildScrollView(
                        primary: false,
                        child: ConstrainedBox(constraints: BoxConstraints(minHeight: box.maxHeight),
                          child: IntrinsicHeight(child: Column(
                        mainAxisAlignment: MainAxisAlignment.spaceAround,
                        children: [
                          Padding(
                            padding: EdgeInsets.symmetric(horizontal: 4 * scale),
                            child: FittedBox(fit: BoxFit.scaleDown,
                              child: Text('Shell栏', style: TextStyle(
                                  color: Colors.white, fontSize: 12 * scale))),
                          ),
                          button(Icons.arrow_back, 'ADB 返回', 4),
                          button(Icons.home, 'ADB 主页', 3),
                          button(Icons.more_horiz, 'ADB 多任务', 187),
                          _divider(),
                          // KEYCODE_WAKEUP only wakes; POWER would toggle a lit screen off.
                          _modeButton(scale, '点亮屏', Colors.blue, enabled,
                              () => _navigate(224)),
                          _divider(),
                          _modeButton(scale, '开投屏', Colors.blue, widget.mirror.canStart,
                              () => widget.mirror.setEnabled(true)),
                          _modeButton(scale, '关投屏', Colors.red, widget.mirror.canStopVideo,
                              () => widget.mirror.setEnabled(false)),
                          _divider(),
                          _modeButton(scale, '开无视', Colors.blue, widget.mirror.canScreenshot,
                              () => widget.mirror.setEnabled(true, screenshot: true)),
                          _modeButton(scale, '关无视', Colors.red, widget.mirror.canStopScreenshot,
                              () => widget.mirror.setEnabled(false, screenshot: true)),
                          _divider(),
                          _modeButton(scale, '开黑屏', Colors.blue, widget.mirror.canBlackOn,
                              () => widget.mirror.setBlack(true)),
                          _modeButton(scale, '关黑屏', Colors.red, widget.mirror.canBlackOff,
                              () => widget.mirror.setBlack(false)),
                          _divider(),
                          _modeButton(scale, '开穿透', Colors.blue, widget.mirror.canLayout,
                              () => widget.mirror.setEnabled(true, layout: true)),
                          _modeButton(scale, '关穿透', Colors.red, widget.mirror.canStopLayout,
                              () => widget.mirror.setEnabled(false, layout: true)),
                          _divider(),
                          Container(
                            margin: const EdgeInsets.symmetric(vertical: 4),
                            width: 70.0 * scale,
                            child: TextField(
                              controller: _url,
                              style: TextStyle(fontSize: 13 * scale),
                              decoration: InputDecoration(
                                hintText: 'Enter URL',
                                contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
                                filled: true,
                                fillColor: Colors.white,
                                border: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(8.0),
                                  borderSide: BorderSide.none,
                                ),
                              ),
                              onSubmitted: enabled ? (_) => _openUrl() : null,
                            ),
                          ),
                          TextButton(
                            onPressed: enabled ? _openUrl : null,
                            style: TextButton.styleFrom(
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                              textStyle: TextStyle(fontSize: 16 * scale, fontWeight: FontWeight.w500),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
                            ),
                            child: const Text('搜索'),
                          ),
                          _divider(),
                          IconButton(
                            color: Colors.white,
                            onPressed: widget.state.toggle,
                            splashRadius: kDesktopIconButtonSplashRadius,
                            icon: const Icon(Icons.keyboard_arrow_down, semanticLabel: '收起 Shell栏'),
                            iconSize: 24 * scale,
                          ),
                        ],
                      ))))),
                    ),
                ),
              ),
            ),
          ]);
        });
      },
    );
  }

  Widget _divider() => const Divider(height: 0, thickness: 2, indent: 10, endIndent: 10, color: Colors.white54);

  Widget _modeButton(double scale, String label, Color color, bool enabled, VoidCallback action) =>
    ElevatedButton(onPressed: enabled ? action : null,
      // Match AntiShakeButton's visual properties exactly; state remains owned by ADB.
      style: ButtonStyle(
          backgroundColor: MaterialStateProperty.resolveWith((states) =>
              states.contains(MaterialState.disabled) ? Colors.black26 : color),
          foregroundColor: MaterialStateProperty.resolveWith((states) =>
              states.contains(MaterialState.disabled) ? Colors.white70 : Colors.white),
          padding: MaterialStateProperty.all(const EdgeInsets.symmetric(horizontal: 12, vertical: 12)),
          textStyle: MaterialStateProperty.all(TextStyle(fontSize: 12 * scale, fontWeight: FontWeight.w500)),
          shape: MaterialStateProperty.all(RoundedRectangleBorder(borderRadius: BorderRadius.circular(6))),
          elevation: MaterialStateProperty.all(4)),
      child: Text(label));
}
