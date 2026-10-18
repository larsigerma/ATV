import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../common.dart';
import 'remote_adb_controller.dart';
import 'remote_adb_session_state.dart';

const remoteAdbActions = <String, String>{
  'pair': '远程 ADB 连接配对',
  'shell': '远程 ADB 命令',
  'paste': '远程输入',
  'revoke': '撤销 ADB 配对',
  'status': '刷新 ADB 状态',
  'disconnect': '断开 ADB 连接',
};

/// The route owns its controller because the toolbar unmounts when auto-hidden.
Future<void> showRemoteAdbDialog(BuildContext context, SessionID sessionId, String action) {
  final sessionState = context.read<RemoteAdbSessionState?>();
  return showDialog<void>(context: context, barrierDismissible: false,
      builder: (_) => _RemoteAdbDialog(sessionId: sessionId, action: action,
          sessionState: sessionState));
}

class _RemoteAdbDialog extends StatefulWidget {
  final SessionID sessionId;
  final String action;
  final RemoteAdbSessionState? sessionState;
  const _RemoteAdbDialog({required this.sessionId, required this.action,
      this.sessionState});
  @override State<_RemoteAdbDialog> createState() => _RemoteAdbDialogState();
}

class _RemoteAdbDialogState extends State<_RemoteAdbDialog> {
  final pairPort = TextEditingController();
  final pairCode = TextEditingController();
  final connectPort = TextEditingController();
  final command = TextEditingController(text: 'id');
  final inputText = TextEditingController();
  final inputFocus = FocusNode();
  late final RemoteAdbController adb = RemoteAdbController(widget.sessionId,
      sessionState: widget.sessionState);
  String mode = 'auto';
  String validation = '';
  Offset offset = Offset.zero;

  @override void initState() {
    super.initState();
    adb.refresh();
  }

  @override void dispose() {
    adb.dispose();
    pairPort.dispose(); pairCode.dispose(); connectPort.dispose(); command.dispose();
    inputText.dispose();
    inputFocus.dispose();
    super.dispose();
  }

  int? _port(TextEditingController field) {
    if (field.text.trim().isEmpty) return 0;
    final value = int.tryParse(field.text.trim());
    if (value == null || value < 1 || value > 65535) {
      setState(() => validation = '端口必须为 1–65535，或留空自动发现。');
      return null;
    }
    return value;
  }

  Future<void> _scan() async {
    final result = await adb.run('discover');
    if (!mounted || result == null) return;
    // A fresh scan replaces stale ports, including clearing ports no longer advertised.
    pairPort.text = (result['pairPort'] ?? 0) == 0 ? '' : result['pairPort'].toString();
    connectPort.text = (result['connectPort'] ?? 0) == 0 ? '' : result['connectPort'].toString();
    setState(() => validation = '');
  }

  void _pair() {
    final port = mode == 'auto' ? 0 : _port(pairPort);
    final connection = mode == 'auto' ? 0 : _port(connectPort);
    if (port == null || connection == null) return;
    final code = pairCode.text.trim();
    if (!RegExp(r'^[0-9]{6}$').hasMatch(code)) {
      setState(() => validation = '请输入系统配对窗口中的 6 位配对码。');
      return;
    }
    pairCode.clear();
    setState(() => validation = '');
    adb.run('pairConnect', {'port': port, 'connectPort': connection, 'code': code});
  }

  Widget _portInput(TextEditingController value, String label) => TextField(
    key: ObjectKey(value), controller: value, enabled: !adb.busy,
    keyboardType: TextInputType.number, maxLength: 5,
    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
    decoration: InputDecoration(labelText: label, counterText: '', border: const OutlineInputBorder()),
  );

  List<Widget> _pairing() => [
    const Text('首次配对：手机开启“无线调试”，选择“使用配对码配对设备”，并保持系统配对码窗口打开。'),
    const SizedBox(height: 10),
    Wrap(spacing: 8, runSpacing: 8, children: [
      for (final item in const {'auto': '只填配对码', 'manual': '手动端口'}.entries)
        ChoiceChip(label: Text(item.value), selected: mode == item.key,
            onSelected: adb.busy ? null : (_) {
              FocusScope.of(context).unfocus();
              setState(() { mode = item.key; validation = ''; });
            }),
    ]),
    const SizedBox(height: 16),
    if (!adb.stale && !adb.discoverySupported)
      const Text('手机 ADB 模块版本较旧，请先更新 Android 安装包以使用端口扫描和一键配对。'),
    if (mode == 'auto')
      const Text('输入 6 位配对码后点击“配对并连接 ADB”。手机会自动发现端口、配对并连接；发现失败时可切换“手动端口”。')
    else ...[
      const Text('配对端口见系统配对码窗口，连接端口见无线调试首页。填写的端口会直接使用，留空则自动发现；也可扫描填充后核对。'),
      const SizedBox(height: 12),
      Row(children: [
        Expanded(child: _portInput(pairPort, '配对端口（留空自动）')),
        const SizedBox(width: 12),
        Expanded(child: _portInput(connectPort, '连接端口（留空自动）')),
      ]),
      Align(alignment: Alignment.centerRight, child: TextButton.icon(
          onPressed: adb.ready && adb.discoverySupported ? _scan : null,
          icon: const Icon(Icons.radar, size: 18), label: const Text('扫描并填充端口'))),
    ],
    const SizedBox(height: 12),
    TextField(key: const ValueKey('pairCode'), controller: pairCode,
        enabled: !adb.busy, obscureText: true, maxLength: 6,
        autocorrect: false, enableSuggestions: false, keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        onSubmitted: (_) { if (adb.ready && adb.discoverySupported) _pair(); },
        decoration: const InputDecoration(labelText: '6 位配对码', border: OutlineInputBorder())),
    ElevatedButton.icon(onPressed: adb.ready && adb.discoverySupported ? _pair : null,
        icon: const Icon(Icons.link), label: const Text('配对并连接 ADB')),
    const SizedBox(height: 12),
    const Divider(),
    Text(adb.stale ? '手机配对状态尚未确认，请点击刷新按钮查询。'
        : adb.paired ? '手机已有本应用配对记录。保持无线调试开启即可直接连接，无需填写配对码或打开配对码窗口。'
        : '以前已配对过？可以尝试直接连接，无需重复输入配对码。'),
    const SizedBox(height: 8),
    ElevatedButton.icon(onPressed: adb.ready ? () {
      final port = mode == 'manual' ? _port(connectPort) : 0;
      if (port != null) adb.run('connect', {'port': port});
    } : null, icon: const Icon(Icons.check_circle_outline),
        label: const Text('我已配对，连接并验证 Shell')),
  ];

  List<Widget> _shell() => [
    if (!adb.connected) ...[
      const Text('当前 ADB 尚未连接。已有配对记录时，可直接连接后执行命令。'),
      OutlinedButton(onPressed: adb.ready ? () => adb.run('connect', {'port': 0}) : null,
          child: const Text('连接已配对的 ADB')),
    ],
    TextField(controller: command, enabled: !adb.busy, minLines: 2, maxLines: 5,
        maxLength: 4096, autocorrect: false, enableSuggestions: false,
        decoration: const InputDecoration(labelText: 'Shell 命令（不加 adb shell）', border: OutlineInputBorder())),
    ElevatedButton.icon(onPressed: adb.ready && adb.connected ? () {
      if (command.text.trim().isNotEmpty) adb.run('shell', {'command': command.text.trim()});
    } : null, icon: const Icon(Icons.play_arrow), label: const Text('执行命令')),
    const Text('所有 PC 共用手机 ADB；新版手机端最多并行执行 2 条命令，其余有界排队。单条命令最长 20 秒，输出上限 64 KiB；取消仅影响本次命令。'),
    const SizedBox(height: 10),
    Container(constraints: const BoxConstraints(minHeight: 120, maxHeight: 280),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(border: Border.all(color: Theme.of(context).dividerColor),
            borderRadius: BorderRadius.circular(8)),
        child: SingleChildScrollView(child: SelectableText(
            adb.output.isEmpty ? '等待命令输出…' : adb.output,
            style: const TextStyle(fontFamily: 'monospace')))),
  ];

  Future<void> _readClipboard() async {
    try {
      final data = await Clipboard.getData(Clipboard.kTextPlain);
      if (!mounted) return;
      final text = data?.text;
      if (text == null || text.isEmpty) {
        setState(() => validation = '电脑剪贴板中没有文本。');
        return;
      }
      inputText.text = text;
      setState(() => validation = '');
    } catch (_) {
      if (mounted) setState(() => validation = '无法读取电脑剪贴板，也可直接在文本框中粘贴。');
    }
  }

  Future<void> _sendText() async {
    if (!adb.ready || !adb.connected || !adb.textInputSupported) return;
    final text = inputText.text;
    final error = RemoteAdbController.validatePasteText(text);
    if (error != null) {
      setState(() => validation = error);
      return;
    }
    setState(() => validation = '');
    final result = await adb.run('paste', {'text': text});
    if (!mounted) return;
    if (result != null) inputText.clear();
    inputFocus.requestFocus();
  }

  KeyEventResult _inputKey(FocusNode node, KeyEvent event) {
    if (event.logicalKey != LogicalKeyboardKey.enter &&
        event.logicalKey != LogicalKeyboardKey.numpadEnter) {
      return KeyEventResult.ignored;
    }
    // Let the IME finish composition; Shift+Enter keeps normal multiline input.
    if (HardwareKeyboard.instance.isShiftPressed ||
        !inputText.value.composing.isCollapsed) {
      return KeyEventResult.ignored;
    }
    if (event is KeyDownEvent) _sendText();
    return KeyEventResult.handled;
  }

  List<Widget> _paste() => [
    const Text('在远程手机输入框中点击后，可直接按 Ctrl+V 粘贴电脑文本；此窗口用于编辑后发送。支持中文、换行和表情。'),
    const Text('在下方输入框按回车或点击“确定”发送，Shift+Enter 换行。'),
    const SizedBox(height: 12),
    if (!adb.stale && !adb.textInputSupported)
      const Text('手机版本尚不支持远程输入，请更新 Android 客户端。'),
    if (!adb.connected)
      OutlinedButton(onPressed: adb.ready ? () => adb.run('connect', {'port': 0}) : null,
          child: const Text('连接已配对的 ADB')),
    Focus(canRequestFocus: false, onKeyEvent: _inputKey, child: TextField(
        controller: inputText, focusNode: inputFocus, autofocus: true,
        readOnly: adb.busy, minLines: 4, maxLines: 8,
        autocorrect: false, enableSuggestions: false,
        decoration: const InputDecoration(labelText: '输入或粘贴文本', border: OutlineInputBorder()))),
    const SizedBox(height: 12),
    Wrap(spacing: 12, runSpacing: 8, children: [
      OutlinedButton.icon(onPressed: adb.busy ? null : _readClipboard,
          icon: const Icon(Icons.content_paste), label: const Text('读取电脑剪贴板')),
      ElevatedButton.icon(onPressed: adb.ready && adb.connected && adb.textInputSupported ? _sendText : null,
          icon: const Icon(Icons.send), label: const Text('确定')),
    ]),
    const SizedBox(height: 12),
    const Text('仅发送本次文本，会替换手机剪贴板。输入框须允许粘贴；取消或关闭窗口不能撤回已发送的内容。'),
  ];

  List<Widget> _content() {
    switch (widget.action) {
      case 'pair': return _pairing();
      case 'shell': return _shell();
      case 'paste': return _paste();
      case 'revoke': return [
        const Text('将清除手机上本应用的 ADB 身份与配对记录、取消排队操作并断开所有 PC 共用的 ADB。之后需要重新配对。'),
        const SizedBox(height: 12),
        const Text('系统无线调试列表中的旧条目仍需在手机设置中手动忘记。'),
        const SizedBox(height: 16),
        ElevatedButton(onPressed: adb.ready ? () => adb.run('revoke') : null,
            child: const Text('确认撤销本应用 ADB 配对')),
      ];
      case 'disconnect': return [
        const Text('断开所有 PC 共用的 ADB 连接，保留配对记录，之后可以直接重连。'),
        const SizedBox(height: 16),
        ElevatedButton(onPressed: adb.ready && adb.connected ? () => adb.run('disconnect') : null,
            child: const Text('确认断开 ADB')),
      ];
      default: return [
        Text('配对记录：' + (adb.stale ? '状态未确认' : adb.paired ? '已保存' : '无记录')),
        Text('Shell 状态：' + (adb.connected ? '连接并验证成功' : '尚未连接或状态失效')),
        Text('上次成功连接端口：' + (adb.state['connectPort'] ?? 0).toString()),
        if (adb.state['keepAliveSupported'] == true) ...[
          const SizedBox(height: 12),
          Text('ADB 辅助保活：${adb.keepAliveSummary}'),
          const Text('连接后自动为本应用设置电池优化豁免；不保证进程免杀。断开 ADB 保留已有豁免，可在手机系统电池设置中恢复。'),
        ],
        const SizedBox(height: 12),
        const Text('打开弹窗时查询状态，本次操作返回后更新。手机本地或其他 PC 操作后，请点击刷新按钮查询最新状态。配对记录不代表系统尚未撤销授权，连接时会重新验证。'),
      ];
    }
  }

  @override Widget build(BuildContext context) => AnimatedBuilder(animation: adb, builder: (context, _) {
    return LayoutBuilder(builder: (context, bounds) {
      final width = min(640.0, max(0.0, bounds.maxWidth - 32));
      final height = min(widget.action == 'pair' || widget.action == 'shell' || widget.action == 'paste' ? 680.0 : 450.0,
          max(0.0, bounds.maxHeight - 32));
      final dx = max(0.0, (bounds.maxWidth - width) / 2 - 8);
      final dy = max(0.0, (bounds.maxHeight - height) / 2 - 8);
      final position = Offset(offset.dx.clamp(-dx, dx).toDouble(), offset.dy.clamp(-dy, dy).toDouble());
      return CallbackShortcuts(bindings: {
        const SingleActivator(LogicalKeyboardKey.escape): () => Navigator.pop(context),
      }, child: Center(child: Transform.translate(offset: position, child: SizedBox(
        width: width, height: height,
        child: Material(elevation: 24, color: Theme.of(context).dialogBackgroundColor,
          borderRadius: BorderRadius.circular(16), clipBehavior: Clip.antiAlias,
          child: Column(children: [
            MouseRegion(cursor: SystemMouseCursors.move, child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onPanUpdate: (event) => setState(() => offset = Offset(
                  (position.dx + event.delta.dx).clamp(-dx, dx).toDouble(),
                  (position.dy + event.delta.dy).clamp(-dy, dy).toDouble())),
              child: Padding(padding: const EdgeInsets.fromLTRB(20, 10, 8, 10), child: Row(children: [
                const Icon(Icons.adb), const SizedBox(width: 10),
                Expanded(child: Text(remoteAdbActions[widget.action] ?? 'ADB',
                    style: Theme.of(context).textTheme.titleLarge)),
                const Icon(Icons.drag_indicator, size: 18),
                IconButton(tooltip: '关闭', onPressed: () => Navigator.pop(context), icon: const Icon(Icons.close)),
              ])),
            )),
            const Divider(height: 1),
            Expanded(child: SingleChildScrollView(padding: const EdgeInsets.all(20), child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Row(children: [
                  Icon(adb.connected ? Icons.check_circle : Icons.info_outline,
                      color: adb.connected ? Colors.green : null, size: 20),
                  const SizedBox(width: 8),
                  Expanded(child: Text(adb.summary, style: const TextStyle(fontWeight: FontWeight.bold))),
                  IconButton(tooltip: '刷新 Android 状态', onPressed: adb.refreshing ? null : adb.refresh,
                      icon: const Icon(Icons.refresh)),
                ]),
                if (!adb.controlAllowed) const Text('当前为只读会话，只能查看 ADB 状态。'),
                if (adb.state['busy'] == true)
                  Text((adb.state['phase'] ?? adb.state['operation'] ?? '处理中').toString()
                      + ' · 排队 ' + (adb.state['queued'] ?? 0).toString()),
                SelectableText(adb.message),
                if (adb.statusError.isNotEmpty) SelectableText(adb.statusError,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                if (validation.isNotEmpty) Text(validation, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                if (adb.busy || adb.refreshing) const Padding(
                    padding: EdgeInsets.symmetric(vertical: 8), child: LinearProgressIndicator()),
                const SizedBox(height: 16),
                ..._content(),
              ],
            ))),
            const Divider(height: 1),
            Padding(padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8), child: Row(children: [
              if (adb.busy) TextButton(onPressed: adb.cancel, child: const Text('取消本 PC 请求')),
              const Spacer(),
              TextButton(onPressed: () => Navigator.pop(context), child: const Text('关闭')),
            ])),
          ]),
        ),
      ))));
    });
  });
}
