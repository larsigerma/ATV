package me.ashell.localadb;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import hbb.KeyEventConverter;
import hbb.MessageOuterClass;
import io.github.muntashirakon.adb.AdbStream;

/** Typing on the existing control socket; never holds a device key between PC events. */
final class LocalAdbKeyboard {
    private final ArrayDeque<Stroke> pending = new ArrayDeque<>();
    private final Map<Integer, Integer> modifiers = new HashMap<>();
    private final KeyCharacterMap characters = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    synchronized void offer(int owner, byte[] data) {
        final MessageOuterClass.KeyEvent event;
        try { event = MessageOuterClass.KeyEvent.parseFrom(data); }
        catch (IOException e) { return; }
        KeyEvent key = KeyEventConverter.INSTANCE.toAndroidKeyEvent(event);
        int held = modifiers.containsKey(owner) ? modifiers.get(owner) : 0;
        int meta = KeyEvent.normalizeMetaState(held | key.getMetaState());
        if (event.hasSeq()) {
            // Seq is committed text (also sent with down=false by input_string), not a key-up.
            String text = event.getSeq();
            if (text.length() == 1 && (meta & (KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON)) != 0) {
                int character = text.charAt(0);
                // Translate mode may encode Ctrl+A..Z as U+0001..001A.
                if (character >= 1 && character <= 26) character += 'a' - 1;
                offerCharacter(owner, character, meta);
            } else offerText(owner, text);
            return;
        }
        boolean down = event.getDown() || event.getPress();
        if (event.hasChr() && event.getMode() != MessageOuterClass.KeyboardMode.Map
                && event.getMode() != MessageOuterClass.KeyboardMode.Translate) {
            if (down) offerCharacter(owner, event.getChr(), meta);
            return;
        }
        int code = event.hasControlKey() && event.getControlKey() == MessageOuterClass.ControlKey.RWin
                ? KeyEvent.KEYCODE_META_RIGHT : key.getKeyCode();
        int bit = modifierBit(code);
        if (bit != 0) {
            int next = down && !event.getPress() ? held | bit : held & ~bit;
            if (next == 0) modifiers.remove(owner);
            else if (modifiers.containsKey(owner) || modifiers.size() < 64) modifiers.put(owner, next);
            return;
        }
        if (!down) return;
        // Lock state is supplied by the PC on each key; do not toggle the phone's lock state.
        if (code == KeyEvent.KEYCODE_CAPS_LOCK || code == KeyEvent.KEYCODE_NUM_LOCK || code == KeyEvent.KEYCODE_SCROLL_LOCK) return;
        if (code > KeyEvent.KEYCODE_UNKNOWN && code <= KeyEvent.getMaxKeyCode()) enqueue(owner, stroke(code, meta));
    }

    private void offerCharacter(int owner, int unicode, int meta) {
        if (!Character.isValidCodePoint(unicode) || unicode == 0) return;
        KeyEvent[] keys = characters.getEvents(Character.toChars(unicode));
        // getEvents may start with Shift. Select the character key, preserving its metaState.
        if (keys == null) return;
        for (KeyEvent character : keys) {
            if (character.getAction() == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(character.getKeyCode())) {
                enqueue(owner, stroke(character.getKeyCode(), KeyEvent.normalizeMetaState(meta | character.getMetaState())));
            }
        }
    }

    private void offerText(int owner, String text) {
        if (text.isEmpty() || text.indexOf('\0') >= 0) return;
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 16384) return;
        // INJECT_TEXT uses the server's virtual key map. Unicode paste remains LocalAdbPaste's job.
        // scrcpy limits each text message to 300 UTF-8 bytes; split only on code-point boundaries.
        int offset = 0;
        while (offset < utf8.length) {
            int end = Math.min(offset + 300, utf8.length);
            if (end < utf8.length) while ((utf8[end] & 0xc0) == 0x80) end--;
            int size = end - offset;
            enqueue(owner, ByteBuffer.allocate(5 + size).put((byte) 1).putInt(size).put(utf8, offset, size).array());
            offset = end;
        }
    }

    private void enqueue(int owner, byte[] packet) {
        // Each item is a complete press or committed text, so a full queue cannot leave a key down.
        if (pending.size() < 128) pending.add(new Stroke(owner, packet));
    }

    synchronized boolean hasPending() { return !pending.isEmpty(); }

    void writeNext(AdbStream stream) throws IOException {
        Stroke next;
        synchronized (this) { next = pending.poll(); }
        if (next == null) return;
        stream.write(next.packet, 0, next.packet.length);
        stream.flush();
    }

    synchronized void release(int owner) {
        modifiers.remove(owner);
        Iterator<Stroke> strokes = pending.iterator();
        while (strokes.hasNext()) if (strokes.next().owner == owner) strokes.remove();
    }

    synchronized void stop() { pending.clear(); modifiers.clear(); }

    private static byte[] stroke(int code, int meta) {
        ByteBuffer packet = ByteBuffer.allocate(28);
        for (int action = KeyEvent.ACTION_DOWN; action <= KeyEvent.ACTION_UP; action++) {
            packet.put((byte) 0).put((byte) action).putInt(code).putInt(0).putInt(meta);
        }
        return packet.array();
    }

    private static int modifierBit(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_SHIFT_LEFT: return KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_CTRL_LEFT: return KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT: return KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT: return KeyEvent.META_ALT_RIGHT_ON;
            case KeyEvent.KEYCODE_META_LEFT: return KeyEvent.META_META_LEFT_ON;
            case KeyEvent.KEYCODE_META_RIGHT: return KeyEvent.META_META_RIGHT_ON;
            default: return 0;
        }
    }

    private static final class Stroke {
        final int owner;
        final byte[] packet;
        Stroke(int owner, byte[] packet) { this.owner = owner; this.packet = packet; }
    }
}
