package app.tilescreen.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Just enough protobuf to speak the ESPHome native API. */
public final class Pb {
    static final Charset UTF8 = Charset.forName("UTF-8");

    private Pb() {}

    /** Builds one message. Zero values are skipped, as proto3 does. */
    public static final class Out {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream();

        public Out varint(int field, long v) {
            if (v == 0) return this;
            tag(field, 0);
            raw(v);
            return this;
        }

        public Out bool(int field, boolean v) { return varint(field, v ? 1 : 0); }

        /** A varint written even when zero, for ESPHome's (force) fields such as a repeated UUID's halves. */
        public Out force(int field, long v) {
            tag(field, 0);
            raw(v);
            return this;
        }

        /** sint32 (zigzag), as a Bluetooth advertisement's RSSI. */
        public Out sint(int field, int v) { return varint(field, ((long) ((v << 1) ^ (v >> 31))) & 0xFFFFFFFFL); }

        public Out fixed32(int field, int v) {
            if (v == 0) return this;
            tag(field, 5);
            b.write(v & 0xff);
            b.write((v >>> 8) & 0xff);
            b.write((v >>> 16) & 0xff);
            b.write((v >>> 24) & 0xff);
            return this;
        }

        /** fixed32 that is written even when zero, for ESPHome's (force) fields such as keys. */
        public Out key(int field, int v) {
            tag(field, 5);
            b.write(v & 0xff);
            b.write((v >>> 8) & 0xff);
            b.write((v >>> 16) & 0xff);
            b.write((v >>> 24) & 0xff);
            return this;
        }

        public Out flt(int field, float v) {
            if (v == 0f) return this;
            return key(field, Float.floatToIntBits(v));
        }

        public Out string(int field, String v) {
            if (v == null || v.isEmpty()) return this;
            return bytes(field, v.getBytes(UTF8));
        }

        public Out bytes(int field, byte[] v) {
            if (v == null || v.length == 0) return this;
            tag(field, 2);
            raw(v.length);
            b.write(v, 0, v.length);
            return this;
        }

        public Out message(int field, Out m) {
            byte[] d = m.toBytes();
            tag(field, 2);
            raw(d.length);
            b.write(d, 0, d.length);
            return this;
        }

        private void tag(int field, int wire) { raw(((long) field << 3) | wire); }

        private void raw(long v) {
            while ((v & ~0x7FL) != 0) {
                b.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            b.write((int) v);
        }

        public byte[] toBytes() { return b.toByteArray(); }
    }

    /** One decoded field. */
    public static final class Field {
        public final int number, wire;
        public final long varint;
        public final byte[] data;

        Field(int number, int wire, long varint, byte[] data) {
            this.number = number;
            this.wire = wire;
            this.varint = varint;
            this.data = data;
        }

        public String text() { return data == null ? "" : new String(data, UTF8); }

        public float flt() { return Float.intBitsToFloat((int) varint); }

        public int sint32() { return (int) ((varint >>> 1) ^ -(varint & 1)); }
    }

    public static List<Field> parse(byte[] buf) {
        List<Field> out = new ArrayList<Field>();
        int[] at = {0};
        while (at[0] < buf.length) {
            long tag = readVarint(buf, at);
            int number = (int) (tag >>> 3), wire = (int) (tag & 7);
            switch (wire) {
                case 0:
                    out.add(new Field(number, wire, readVarint(buf, at), null));
                    break;
                case 1: {
                    long v = 0;
                    for (int k = 0; k < 8; k++) v |= (long) (buf[at[0] + k] & 0xff) << (8 * k);
                    at[0] += 8;
                    out.add(new Field(number, wire, v, null));
                    break;
                }
                case 2: {
                    int n = (int) readVarint(buf, at);
                    if (n < 0 || at[0] + n > buf.length) throw new IllegalArgumentException("short field");
                    byte[] d = new byte[n];
                    System.arraycopy(buf, at[0], d, 0, n);
                    at[0] += n;
                    out.add(new Field(number, wire, 0, d));
                    break;
                }
                case 5: {
                    long v = (buf[at[0]] & 0xffL) | (buf[at[0] + 1] & 0xffL) << 8
                            | (buf[at[0] + 2] & 0xffL) << 16 | (buf[at[0] + 3] & 0xffL) << 24;
                    at[0] += 4;
                    out.add(new Field(number, wire, v, null));
                    break;
                }
                default:
                    throw new IllegalArgumentException("wire type " + wire);
            }
        }
        return out;
    }

    static long readVarint(byte[] buf, int[] at) {
        long v = 0;
        int shift = 0;
        while (true) {
            if (at[0] >= buf.length) throw new IllegalArgumentException("short varint");
            int b = buf[at[0]++] & 0xff;
            v |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 63) throw new IllegalArgumentException("long varint");
        }
    }

    public static Field first(List<Field> fields, int number) {
        for (Field f : fields) if (f.number == number) return f;
        return null;
    }
}
