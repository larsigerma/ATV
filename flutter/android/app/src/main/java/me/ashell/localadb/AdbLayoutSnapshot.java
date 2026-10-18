package me.ashell.localadb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Private helper/app format: real node geometry and plain text, never pixels or serialized framework objects. */
final class AdbLayoutSnapshot {
    static final int MAGIC = 0x4c41594e; // LAYN
    static final int PROGRESS = 0x4c415950; // LAYP, last operation entered (not a ready state)
    static final int ERROR = 0x4c415945; // LAYE
    static final int MAX_NODES = 4096;
    static final int MAX_TEXT = 1024;
    static final int MAX_TOTAL_TEXT = 256 * 1024;
    static final int MAX_BYTES = 2 * 1024 * 1024;

    static final class Node {
        final int left, top, right, bottom;
        final boolean clickable;
        final String text;

        Node(int left, int top, int right, int bottom, boolean clickable, String text) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.clickable = clickable; this.text = text;
        }
    }

    final List<Node> nodes = new ArrayList<>();
    private int textChars;

    void add(int left, int top, int right, int bottom, boolean clickable, CharSequence value) {
        if (nodes.size() >= MAX_NODES || right <= left || bottom <= top) return;
        int end = value == null ? 0 : Math.min(value.length(), Math.min(MAX_TEXT, MAX_TOTAL_TEXT - textChars));
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        String text = end == 0 ? "" : value.subSequence(0, end).toString();
        nodes.add(new Node(left, top, right, bottom, clickable, text));
        textChars += text.length();
    }

    void write(DataOutputStream output) throws IOException {
        // At most 3 bytes per UTF-16 character plus 19 bytes per node: below MAX_BYTES.
        output.writeInt(nodes.size());
        for (Node node : nodes) {
            output.writeInt(node.left); output.writeInt(node.top);
            output.writeInt(node.right); output.writeInt(node.bottom);
            output.writeBoolean(node.clickable); output.writeUTF(node.text);
        }
    }

    static AdbLayoutSnapshot read(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_NODES) throw new IOException("Invalid layout node count");
        AdbLayoutSnapshot snapshot = new AdbLayoutSnapshot();
        for (int i = 0; i < count; i++) {
            int left = input.readInt(), top = input.readInt(), right = input.readInt(), bottom = input.readInt();
            boolean clickable = input.readBoolean();
            String text = input.readUTF();
            if (right <= left || bottom <= top || text.length() > MAX_TEXT
                    || text.length() > MAX_TOTAL_TEXT - snapshot.textChars) throw new IOException("Invalid layout node");
            snapshot.add(left, top, right, bottom, clickable, text);
        }
        if (input.available() != 0) throw new IOException("Unexpected layout data");
        return snapshot;
    }
}
