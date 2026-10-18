import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import '../../common.dart' show isWindows;
import '../../consts.dart';
import '../../models/platform_model.dart' show bind;
import 'remote_adb_mirror.dart';

/// Inline ADB surface. Its pointer events never enter the ordinary image canvas.
class RemoteAdbMirrorView extends StatelessWidget {
  const RemoteAdbMirrorView({super.key, required this.mirror, required this.onFocusRequested,
    this.alignment = Alignment.center});
  final RemoteAdbMirror mirror;
  final VoidCallback onFocusRequested;
  final Alignment alignment;
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([mirror, mirror.frameUpdates]),
    builder: (context, _) {
      final error = mirror.error.isNotEmpty ? mirror.error : mirror.inputError;
      return MouseRegion(onEnter: (_) {
        bind.hostStopSystemKeyPropagate(stopped: false);
        if (!isWindows) { onFocusRequested(); mirror.ffi.inputModel.enterOrLeave(true); }
      }, onExit: (_) {
        bind.hostStopSystemKeyPropagate(stopped: true);
        if (!isWindows) mirror.ffi.inputModel.enterOrLeave(false);
      }, child: ColoredBox(color: kColorCanvas, child: Listener(behavior: HitTestBehavior.opaque,
        onPointerDown: (_) => onFocusRequested(),
        child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: () {}, onPanUpdate: (_) {},
          child: Stack(fit: StackFit.expand, children: [
            if (mirror.image != null) _AdbFrame(key: ValueKey(mirror.frameGeneration), bytes: mirror.image!, mirror: mirror,
                rgbaWidth: mirror.rgbaWidth, rgbaHeight: mirror.rgbaHeight,
                sourceWidth: mirror.sourceWidth, sourceHeight: mirror.sourceHeight, alignment: alignment)
            else Center(child: Padding(padding: const EdgeInsets.all(24),
              child: Text(mirror.summary, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white70)))),
            if (mirror.image != null && error.isNotEmpty) Positioned(left: 12, right: 12, bottom: 12,
              child: IgnorePointer(child: Center(child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(6)),
                child: Text(error,
                  textAlign: TextAlign.center, style: const TextStyle(color: Colors.white70, fontSize: 11)),
              )))),
          ])))));
    });
}

/// At most one decode and one displayed image; bypass Flutter's still-image cache.
class _AdbFrame extends StatefulWidget {
  const _AdbFrame({super.key, required this.bytes, required this.mirror, required this.alignment,
    required this.rgbaWidth, required this.rgbaHeight,
    required this.sourceWidth, required this.sourceHeight});
  final Uint8List bytes;
  final int rgbaWidth, rgbaHeight;
  final RemoteAdbMirror mirror;
  final Alignment alignment;
  final int sourceWidth, sourceHeight;
  @override
  State<_AdbFrame> createState() => _AdbFrameState();
}

class _AdbFrameState extends State<_AdbFrame> {
  ui.Image? _image;
  bool _decoding = false;
  bool _failed = false;
  int? _pointer;
  Offset _last = Offset.zero;
  Size _box = Size.zero;
  int _sourceWidth = 0, _sourceHeight = 0;
  bool _physicalGeometry = false;
  DateTime _lastMove = DateTime.fromMillisecondsSinceEpoch(0);

  Rect _imageRect() {
    if (_image == null || _sourceWidth <= 0 || _sourceHeight <= 0 || _box.isEmpty) return Rect.zero;
    // Match ordinary capture dimensions, then the ordinary canvas's selected view scale.
    final divisor = math.max(1, _sourceWidth ~/ 350);
    final captureScale = _physicalGeometry
        ? math.min(1.0 / divisor, 1280.0 / math.max(_sourceWidth, _sourceHeight)) : 1.0;
    final width = _sourceWidth * captureScale;
    final height = _sourceHeight * captureScale;
    final canvas = widget.mirror.ffi.canvasModel;
    final adaptive = canvas.viewStyle.style == kRemoteViewStyleAdaptive;
    final scale = adaptive ? math.min(_box.width / width, _box.height / height) *
        (canvas.viewStyle.scale > 0 ? canvas.scale / canvas.viewStyle.scale : 1.0) : canvas.scale;
    return widget.alignment.inscribe(Size(width * scale, height * scale), Offset.zero & _box);
  }

  void _touch(int action, Offset local) {
    final image = _image;
    if (image == null || _box.isEmpty) return;
    final rect = _imageRect();
    if (rect.isEmpty) return;
    final x = ((local.dx - rect.left) * _sourceWidth / rect.width).floor().clamp(0, _sourceWidth - 1).toInt();
    final y = ((local.dy - rect.top) * _sourceHeight / rect.height).floor().clamp(0, _sourceHeight - 1).toInt();
    widget.mirror.touch(action, x, y, _sourceWidth, _sourceHeight);
  }

  @override
  void initState() {
    super.initState();
    widget.mirror.ffi.canvasModel.addListener(_canvasChanged);
    _decode();
  }

  void _canvasChanged() { if (mounted) setState(() {}); }

  @override
  void didUpdateWidget(covariant _AdbFrame oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.mirror.adbPriority || widget.mirror.ffi.ffiModel.viewOnly) _pointer = null;
    if (!identical(oldWidget.bytes, widget.bytes)) _decode();
  }

  Future<void> _decode() async {
    if (_decoding || !mounted) return;
    _decoding = true;
    final bytes = widget.bytes;
    final sourceWidth = widget.sourceWidth, sourceHeight = widget.sourceHeight;
    final rgbaWidth = widget.rgbaWidth, rgbaHeight = widget.rgbaHeight;
    ui.Codec? codec;
    ui.ImmutableBuffer? buffer;
    ui.ImageDescriptor? descriptor;
    try {
      if (rgbaWidth != 0 || rgbaHeight != 0) {
        if (rgbaWidth <= 0 || rgbaHeight <= 0 || rgbaWidth > 1280 || rgbaHeight > 1280 ||
            bytes.length != rgbaWidth * rgbaHeight * 4) throw StateError('Invalid ADB pixels');
        buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
        descriptor = ui.ImageDescriptor.raw(buffer, width: rgbaWidth, height: rgbaHeight,
            rowBytes: rgbaWidth * 4, pixelFormat: ui.PixelFormat.rgba8888);
        codec = await descriptor.instantiateCodec();
      } else {
        codec = await ui.instantiateImageCodec(bytes);
      }
      final frame = await codec.getNextFrame();
      if (!mounted) { frame.image.dispose(); return; }
      final previous = _image;
      final width = sourceWidth > 0 ? sourceWidth : frame.image.width;
      final height = sourceHeight > 0 ? sourceHeight : frame.image.height;
      if (_pointer != null && (_sourceWidth != width || _sourceHeight != height)) {
        _touch(1, _last); _pointer = null;
      }
      setState(() {
        _image = frame.image; _failed = false;
        _sourceWidth = width; _sourceHeight = height;
        _physicalGeometry = sourceWidth > 0 && sourceHeight > 0;
      });
      WidgetsBinding.instance.addPostFrameCallback((_) => previous?.dispose());
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    } finally {
      codec?.dispose();
      descriptor?.dispose();
      buffer?.dispose();
      _decoding = false;
      if (mounted && !identical(bytes, widget.bytes)) _decode();
    }
  }

  @override
  Widget build(BuildContext context) => _failed
      ? const Center(child: Text('ADB 画面解析失败', style: TextStyle(color: Colors.white70)))
      : LayoutBuilder(builder: (context, constraints) {
        _box = constraints.biggest;
        return Listener(behavior: HitTestBehavior.opaque,
          onPointerDown: (event) {
            if (_pointer != null || !widget.mirror.adbPriority || widget.mirror.ffi.ffiModel.viewOnly) return;
            final image = _image;
            if (image == null) return;
            final rect = _imageRect();
            if (!rect.contains(event.localPosition)) return;
            _pointer = event.pointer; _last = event.localPosition;
            _touch(0, _last);
          },
          onPointerMove: (event) {
            if (_pointer != event.pointer) return;
            _last = event.localPosition;
            if (DateTime.now().difference(_lastMove).inMilliseconds >= 16) {
              _lastMove = DateTime.now(); _touch(2, _last);
            }
          },
          onPointerUp: (event) {
            if (_pointer != event.pointer) return;
            _touch(1, event.localPosition); _pointer = null;
          },
          onPointerCancel: (event) {
            if (_pointer != event.pointer) return;
            _touch(1, _last); _pointer = null;
          },
          child: Stack(fit: StackFit.expand, children: [
            Positioned.fromRect(rect: _imageRect(), child: RawImage(image: _image,
                fit: BoxFit.fill, filterQuality: FilterQuality.low)),
          ]));
      });

  @override
  void dispose() {
    if (_pointer != null) _touch(1, _last);
    widget.mirror.ffi.canvasModel.removeListener(_canvasChanged);
    _image?.dispose(); _image = null; super.dispose();
  }
}
