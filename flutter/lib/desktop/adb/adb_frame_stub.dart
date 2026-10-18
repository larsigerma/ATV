import 'dart:typed_data';

// The ADB desktop media decoder is not built for the web client.
Uint8List? takeAdbFrame(String session, String owner, int generation) => null;
