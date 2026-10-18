import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../consts.dart';
import '../../models/model.dart';
import 'remote_adb_mirror.dart';
import 'remote_adb_mirror_view.dart';

/// The ordinary canvas stays at (0, 0); only its viewport width changes.
/// Both children stay mounted when a source closes, preserving their session ownership.
class RemoteDisplayLayout extends StatefulWidget {
  const RemoteDisplayLayout({super.key, required this.ffi, required this.mirror, required this.ordinary,
    required this.onAdbFocusRequested});
  final FFI ffi;
  final RemoteAdbMirror mirror;
  final Widget ordinary;
  final VoidCallback onAdbFocusRequested;

  @override
  State<RemoteDisplayLayout> createState() => _RemoteDisplayLayoutState();
}

class _RemoteDisplayLayoutState extends State<RemoteDisplayLayout> {
  bool _ordinaryVisible = false;
  bool? _ordinaryEnabled;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([widget.mirror.hasFrame, widget.ffi.ashellStatusModel, widget.ffi.ffiModel]),
    builder: (context, _) => Obx(() {
      final model = widget.ffi.ffiModel;
      final status = widget.ffi.ashellStatusModel.data;
      final firstFrame = !model.waitForFirstImage.value;
      final ordinaryOff = status.video == false && status.screenshot == false && status.penetrate != true;
      if (ordinaryOff) _ordinaryEnabled = false;
      else if (status.video == true || status.screenshot == true || status.penetrate == true) _ordinaryEnabled = true;
      if (_ordinaryEnabled == false) {
        _ordinaryVisible = false;
      } else if (firstFrame && model.pi.isSet.value) {
        _ordinaryVisible = true;
      }
      // Unknown/stale status preserves the last picture during the original reconnect window.
      final adb = widget.mirror.hasFrame.value;
      final both = adb && _ordinaryVisible;
      widget.ffi.canvasModel.setDesktopAlignRight(both);
      return LayoutBuilder(builder: (context, constraints) {
        final width = constraints.maxWidth;
        final dividerWidth = width.clamp(0.0, 1.0).toDouble();
        final ordinaryWidth = both ? (width - dividerWidth) / 2 : width;
        return ColoredBox(color: kColorCanvas, child: Stack(fit: StackFit.expand, children: [
          Positioned(key: const ValueKey('ordinary-display'), left: 0, top: 0, bottom: 0, width: ordinaryWidth,
            child: Offstage(offstage: !_ordinaryVisible,
              child: ClipRect(child: widget.ordinary))),
          Positioned(key: const ValueKey('adb-display'), left: both ? ordinaryWidth + dividerWidth : 0, top: 0, right: 0, bottom: 0,
            child: Offstage(offstage: !adb && _ordinaryVisible,
              child: ClipRect(child: AnimatedBuilder(animation: widget.mirror, builder: (context, _) =>
                adb || widget.mirror.active || widget.mirror.error.isNotEmpty
                    ? RemoteAdbMirrorView(mirror: widget.mirror, onFocusRequested: widget.onAdbFocusRequested,
                        alignment: both ? Alignment.centerLeft : Alignment.center)
                    : const SizedBox.expand())))),
          if (both) Positioned(left: ordinaryWidth, top: 0, bottom: 0, width: dividerWidth,
            child: ColoredBox(color: Theme.of(context).dividerColor.withOpacity(0.65))),
        ]));
      });
    }));
}
