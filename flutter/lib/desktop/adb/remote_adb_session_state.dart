import 'package:flutter/foundation.dart';

import '../../common.dart' show SessionID;

/// Owned by RemotePage, so toolbar/dialog lifetimes cannot reset the switch.
class RemoteAdbSessionState extends ChangeNotifier {
  RemoteAdbSessionState(this.sessionId);

  final SessionID sessionId;
  bool _disposed = false;
  bool _peerReady = false;
  bool _viewOnly = false;
  bool _shellReady = false;
  bool _visible = false;
  int _epoch = 0;
  Map<String, dynamic>? _snapshot;
  Map<String, dynamic>? get snapshot => _snapshot;

  bool get available => _peerReady && !_viewOnly && _shellReady;
  bool get visible => available && _visible;
  int get epoch => _epoch;

  void _update(VoidCallback change) {
    if (_disposed) return;
    final wasAvailable = available;
    final wasVisible = visible;
    final previousSnapshot = _snapshot;
    change();
    if (!available) {
      _visible = false;
    } else if (!wasAvailable) {
      _visible = true;
    }
    if (available != wasAvailable || visible != wasVisible || !identical(previousSnapshot, _snapshot)) notifyListeners();
  }

  void updatePeer({required bool ready, required bool viewOnly}) {
    _update(() {
      if (_peerReady != ready) {
        _epoch++;
        _shellReady = false;
        _snapshot = null;
      }
      _peerReady = ready;
      _viewOnly = viewOnly;
    });
  }

  // A status reply started before a mutation must not undo its newer result.
  void advanceEpoch({int? expectedEpoch}) {
    if (expectedEpoch != null && expectedEpoch != _epoch) return;
    if (!_disposed) _epoch++;
  }

  void apply(Map<String, dynamic> state, bool controlAllowed,
      {int? expectedEpoch}) {
    if (expectedEpoch != null && expectedEpoch != _epoch) return;
    _update(() {
      _shellReady = state['supported'] == true &&
          state['connected'] == true && controlAllowed;
    });
  }

  void acceptSnapshot(Map<String, dynamic> value) {
    if (!_peerReady || value['supported'] is! bool || value['connected'] is! bool) return;
    final revision = (value['revision'] as num?)?.toInt();
    if (revision == null || (_snapshot != null && _snapshot!['instance'] == value['instance']
        && revision <= (_snapshot!['revision'] as num).toInt())) return;
    _update(() {
      _epoch++;
      _snapshot = Map<String, dynamic>.unmodifiable(value);
      _shellReady = value['supported'] == true && value['connected'] == true;
    });
  }

  void toggle() {
    if (_disposed || !available) return;
    _visible = !_visible;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
