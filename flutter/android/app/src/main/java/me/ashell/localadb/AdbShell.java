package me.ashell.localadb;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbStream;

/** Android shell-v2 framing: channel byte, little-endian length, payload; exit is channel 3. */
final class AdbShell {
    private static final int OUTPUT_LIMIT = 65536;

    static Map<String, Object> execute(AdbConnection connection, String command,
                                      AtomicBoolean cancelled) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Integer exitCode = null;
        boolean truncated = false;
        // Read to the explicit exit packet. libadb 3.1.1's legacy shell EOF path may wait
        // indefinitely or discard buffered bytes when CLSE races with the final output.
        try (AdbStream stream = AdbStreamCompat.open(connection, "shell,v2,raw:sh")) {
            // A short ASCII OPEN avoids libadb 3.1.1's multi-byte destination allocation bug.
            // Send the user's UTF-8 script over stdin, followed by shell-v2 CLOSE_STDIN.
            byte[] script = (command + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] input = ByteBuffer.allocate(script.length + 10).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) 0).putInt(script.length).put(script).put((byte) 4).putInt(0).array();
            stream.write(input, 0, input.length);
            stream.flush();
            byte[] header = new byte[5];
            byte[] buffer = new byte[4096];
            while (exitCode == null && !truncated) {
                readFully(stream, header, header.length, cancelled);
                int channel = header[0] & 0xff;
                long length = (header[1] & 0xffL) | ((header[2] & 0xffL) << 8)
                        | ((header[3] & 0xffL) << 16) | ((header[4] & 0xffL) << 24);
                if (length > 1048576 || channel < 1 || channel > 3) throw new IOException("Invalid shell packet");
                if (channel == 3) {
                    if (length != 1) throw new IOException("Invalid exit packet");
                    readFully(stream, buffer, 1, cancelled);
                    exitCode = buffer[0] & 0xff;
                    continue;
                }
                while (length > 0) {
                    int size = (int) Math.min(length, buffer.length);
                    readFully(stream, buffer, size, cancelled);
                    int remaining = OUTPUT_LIMIT - output.size();
                    output.write(buffer, 0, Math.min(remaining, size));
                    length -= size;
                    if (size > remaining) {
                        truncated = true;
                        break;
                    }
                }
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("output", new String(output.toByteArray(), StandardCharsets.UTF_8));
        result.put("truncated", truncated);
        result.put("exitCode", exitCode);
        return result;
    }

    private static void readFully(AdbStream stream, byte[] buffer, int length,
                                  AtomicBoolean cancelled) throws IOException {
        int offset = 0;
        while (offset < length) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
            // Direct read drains the library's already-buffered bytes even after peer CLSE.
            int size = stream.read(buffer, offset, length - offset);
            if (size <= 0) throw new EOFException("Missing shell exit packet");
            offset += size;
        }
    }
}
