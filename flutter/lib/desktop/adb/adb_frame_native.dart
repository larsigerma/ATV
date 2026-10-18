import 'dart:ffi';
import 'dart:io';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';

typedef _TakeNative = Pointer<Uint8> Function(Pointer<Utf8>, Pointer<Utf8>, Uint64, Pointer<Uint32>);
typedef _Take = Pointer<Uint8> Function(Pointer<Utf8>, Pointer<Utf8>, int, Pointer<Uint32>);
typedef _FreeNative = Void Function(Pointer<Uint8>, Uint32);
typedef _Free = void Function(Pointer<Uint8>, int);

// Same library handle as the existing desktop bridge; no generated API changes.
final _library = Platform.isWindows ? DynamicLibrary.open('ashell.dll')
    : Platform.isLinux ? DynamicLibrary.open('libashell.so') : DynamicLibrary.process();
final _take = _library.lookupFunction<_TakeNative, _Take>('ashell_take_adb_frame');
final _free = _library.lookupFunction<_FreeNative, _Free>('ashell_free_adb_frame');

Uint8List? takeAdbFrame(String session, String owner, int generation) {
  if (Platform.isAndroid || Platform.isIOS) return null;
  final sessionPtr = session.toNativeUtf8();
  final ownerPtr = owner.toNativeUtf8();
  final length = calloc<Uint32>();
  Pointer<Uint8> frame = nullptr;
  try {
    frame = _take(sessionPtr, ownerPtr, generation, length);
    if (frame == nullptr) return null;
    final count = length.value;
    if (count < 17 || count > 16 + 1280 * 1280 * 4) throw StateError('Invalid ADB frame size');
    // Own the Dart copy before releasing Rust's allocation, including on decode failure.
    return Uint8List.fromList(frame.asTypedList(count));
  } finally {
    if (frame != nullptr) _free(frame, length.value);
    calloc.free(length);
    malloc.free(ownerPtr);
    malloc.free(sessionPtr);
  }
}
