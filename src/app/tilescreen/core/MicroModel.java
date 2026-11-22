package app.tilescreen.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs a microWakeWord streaming model (.tflite) the way TensorFlow Lite Micro does on an ESP32: int8 tensors, the
 * reference integer kernels (per-channel conv and depthwise conv, fully connected, gemmlowp's fixed-point logistic,
 * requantize) and the resource variables that carry each layer's history from one call to the next. Only the
 * operations microWakeWord's models use are here; anything else fails at load with its name, so a model that needs
 * more says so instead of giving wrong numbers. test/wake_test.py checks the outputs against TFLite Micro itself.
 * Apache 2.0 (TensorFlow, gemmlowp): see NOTICE.
 */
public final class MicroModel {
    // TFLite builtin operators.
    static final int CONCATENATION = 2, CONV_2D = 3, DEPTHWISE_CONV_2D = 4, FULLY_CONNECTED = 9, LOGISTIC = 14,
            RESHAPE = 22, STRIDED_SLICE = 45, SPLIT_V = 102, QUANTIZE = 114, CALL_ONCE = 129, VAR_HANDLE = 142,
            READ_VARIABLE = 143, ASSIGN_VARIABLE = 144;
    static final int INT32 = 2, UINT8 = 3, INT8 = 9, RESOURCE = 13;

    /** The model's input: [1, stride, 40] int8 features. */
    public final int stride, features;
    private final Graph main;
    private final Graph[] graphs;
    private final Map<String, byte[]> variables = new HashMap<>();
    private boolean initialised;

    public MicroModel(byte[] model) {
        Fb fb = new Fb(model);
        int root = fb.root();
        int[] codes = codes(fb, root);
        int buffers = fb.vector(root, 4);
        int subgraphs = fb.vector(root, 2);
        int count = fb.length(subgraphs);
        graphs = new Graph[count];
        for (int i = 0; i < count; i++) graphs[i] = new Graph(fb, fb.table(subgraphs, i), codes, buffers);
        main = graphs[0];
        Tensor in = main.tensors[main.inputs[0]];
        Tensor out = main.tensors[main.outputs[0]];
        if (in.shape.length != 3 || in.type != INT8) throw new IllegalArgumentException("model input isn't int8 [1, n, 40]");
        if (out.type != UINT8 || out.size != 1) throw new IllegalArgumentException("model output isn't one uint8");
        stride = in.shape[1];
        features = in.shape[2];
    }

    /** The input tensor: write `stride` rows of int8 features here, then call {@link #invoke()}. */
    public byte[] input() { return main.tensors[main.inputs[0]].data; }

    /** Runs the model once; returns its probability, 0..255. */
    public int invoke() {
        main.run();
        return main.tensors[main.outputs[0]].data[0] & 0xFF;
    }

    /** Forget everything heard: the next call starts from the model's initial state, as after loading. */
    public void reset() {
        initialised = false;
        variables.clear();
    }

    private static int[] codes(Fb fb, int root) {
        int v = fb.vector(root, 1);
        int[] codes = new int[fb.length(v)];
        for (int i = 0; i < codes.length; i++) {
            int t = fb.table(v, i);
            codes[i] = Math.max(fb.int8(t, 0, 0), fb.int32(t, 3, 0));
        }
        return codes;
    }

    // ---- the graph ----

    static final class Tensor {
        int[] shape;
        int type, size;
        byte[] data;      // int8/uint8 values (constants or activations)
        int[] ints;       // int32 constants (bias, shapes, slice bounds)
        float[] scale;
        int zeroPoint;
        String variable;  // a resource tensor's variable, once VAR_HANDLE has run

        float scale() { return scale == null || scale.length == 0 ? 0 : scale[0]; }
    }

    private abstract static class Op { abstract void run(); }

    private final class Graph {
        final Tensor[] tensors;
        final int[] inputs, outputs;
        final Op[] ops;

        Graph(Fb fb, int g, int[] codes, int buffers) {
            int tv = fb.vector(g, 0);
            tensors = new Tensor[fb.length(tv)];
            for (int i = 0; i < tensors.length; i++) tensors[i] = tensor(fb, fb.table(tv, i), buffers);
            inputs = fb.ints(fb.vector(g, 1));
            outputs = fb.ints(fb.vector(g, 2));
            int ov = fb.vector(g, 3);
            ops = new Op[fb.length(ov)];
            for (int i = 0; i < ops.length; i++) ops[i] = op(fb, fb.table(ov, i), codes);
        }

        void run() { for (Op op : ops) op.run(); }

        private Tensor tensor(Fb fb, int t, int buffers) {
            Tensor x = new Tensor();
            x.shape = fb.ints(fb.vector(t, 0));
            x.type = fb.int8(t, 1, 0);
            int size = 1;
            for (int d : x.shape) size *= d;
            x.size = size;
            int q = fb.tableField(t, 4);
            if (q != 0) {
                x.scale = fb.floats(fb.vector(q, 2));
                int zp = fb.vector(q, 3);
                if (zp != 0 && fb.length(zp) > 0) x.zeroPoint = (int) fb.int64At(fb.element(zp, 0, 8));
            }
            int buffer = fb.int32(t, 2, 0);
            int data = 0;
            if (buffer > 0) {
                int b = fb.table(buffers, buffer);
                data = fb.vector(b, 0);
                if (data != 0 && fb.length(data) == 0) data = 0;
                if (data == 0 && fb.int64(b, 2, 0) != 0) throw new IllegalArgumentException("models with outside data aren't supported");
            }
            if (x.type == INT32) {
                x.ints = data != 0 ? fb.intsOfBytes(data) : new int[size];
            } else if (x.type == INT8 || x.type == UINT8) {
                x.data = data != 0 ? fb.bytes(data) : new byte[size];
            } else if (x.type != RESOURCE) {
                throw new IllegalArgumentException("tensor type " + x.type + " isn't supported");
            }
            return x;
        }

        private Op op(Fb fb, int o, int[] codes) {
            int code = codes[fb.int32(o, 0, 0)];
            final int[] in = fb.ints(fb.vector(o, 1));
            final int[] out = fb.ints(fb.vector(o, 2));
            int opt = fb.tableField(o, 4);
            switch (code) {
                case CALL_ONCE: {
                    final int init = fb.int32(opt, 0, 0);
                    return new Op() { void run() {
                        if (initialised) return;
                        initialised = true;
                        graphs[init].run();
                    } };
                }
                case VAR_HANDLE: {
                    final String name = fb.string(opt, 0) + "/" + fb.string(opt, 1);
                    final Tensor t = tensors[out[0]];
                    return new Op() { void run() { t.variable = name; } };
                }
                case READ_VARIABLE: {
                    final Tensor h = tensors[in[0]], t = tensors[out[0]];
                    return new Op() { void run() {
                        byte[] v = variables.get(h.variable);
                        if (v == null) java.util.Arrays.fill(t.data, (byte) 0); else System.arraycopy(v, 0, t.data, 0, t.data.length);
                    } };
                }
                case ASSIGN_VARIABLE: {
                    final Tensor h = tensors[in[0]], t = tensors[in[1]];
                    return new Op() { void run() {
                        byte[] v = variables.get(h.variable);
                        if (v == null || v.length != t.data.length) variables.put(h.variable, v = new byte[t.data.length]);
                        System.arraycopy(t.data, 0, v, 0, v.length);
                    } };
                }
                case RESHAPE: {
                    final Tensor a = tensors[in[0]], t = tensors[out[0]];
                    return new Op() { void run() { System.arraycopy(a.data, 0, t.data, 0, t.size); } };
                }
                case CONCATENATION: return concatenation(in, out, fb.int32(opt, 0, 0));
                case STRIDED_SLICE: return stridedSlice(fb, opt, in, out);
                case SPLIT_V: return splitV(in, out);
                case CONV_2D: return conv(in, out, fb.int8(opt, 0, 0), fb.int32(opt, 1, 0), fb.int32(opt, 2, 0),
                        fb.int8(opt, 3, 0), fb.int32(opt, 4, 1), fb.int32(opt, 5, 1), false, 1);
                case DEPTHWISE_CONV_2D: return conv(in, out, fb.int8(opt, 0, 0), fb.int32(opt, 1, 0), fb.int32(opt, 2, 0),
                        fb.int8(opt, 4, 0), fb.int32(opt, 5, 1), fb.int32(opt, 6, 1), true, fb.int32(opt, 3, 0));
                case FULLY_CONNECTED: return fullyConnected(in, out, fb.int8(opt, 0, 0));
                case LOGISTIC: return logistic(in, out);
                case QUANTIZE: return quantize(in, out);
                default: throw new IllegalArgumentException("operator " + code + " isn't supported");
            }
        }

        // -- copies --

        private Op concatenation(int[] in, int[] out, int axis) {
            final Tensor t = tensors[out[0]];
            final int rank = t.shape.length;
            final int ax = axis < 0 ? axis + rank : axis;
            int outer = 1;
            for (int i = 0; i < ax; i++) outer *= t.shape[i];
            final int outerF = outer;
            final Tensor[] parts = new Tensor[in.length];
            final int[] chunk = new int[in.length];
            for (int p = 0; p < in.length; p++) {
                parts[p] = tensors[in[p]];
                chunk[p] = parts[p].size / outer;
            }
            return new Op() { void run() {
                int at = 0;
                for (int o = 0; o < outerF; o++)
                    for (int p = 0; p < parts.length; p++) {
                        System.arraycopy(parts[p].data, o * chunk[p], t.data, at, chunk[p]);
                        at += chunk[p];
                    }
            } };
        }

        private Op stridedSlice(Fb fb, int opt, int[] in, int[] out) {
            final Tensor a = tensors[in[0]], t = tensors[out[0]];
            int[] begin = tensors[in[1]].ints, end = tensors[in[2]].ints, strides = tensors[in[3]].ints;
            int beginMask = fb.int32(opt, 0, 0), endMask = fb.int32(opt, 1, 0);
            if (fb.int32(opt, 2, 0) != 0 || fb.int32(opt, 3, 0) != 0 || fb.int32(opt, 4, 0) != 0 || fb.bool(opt, 5))
                throw new IllegalArgumentException("strided slice with ellipsis, new or shrunk axes isn't supported");
            final int rank = a.shape.length;
            final int[] start = new int[rank], step = new int[rank], count = new int[rank];
            for (int d = 0; d < rank; d++) {
                int dim = a.shape[d], s = strides[d];
                if (s <= 0) throw new IllegalArgumentException("strided slice needs positive strides");
                int b = (beginMask & (1 << d)) != 0 ? 0 : clampIndex(begin[d], dim);
                int e = (endMask & (1 << d)) != 0 ? dim : clampIndex(end[d], dim);
                start[d] = b;
                step[d] = s;
                count[d] = Math.max(0, (e - b + s - 1) / s);
            }
            final int[] stridesIn = rowStrides(a.shape);
            // The usual slice keeps the last rows of the time axis whole (stride 1, every later axis complete): one copy.
            int first = 0;
            while (first < rank && count[first] == 1 && step[first] == 1) first++;
            boolean block = true;
            for (int d = 0; d < rank; d++) {
                if (step[d] != 1) block = false;
                if (d > first && (start[d] != 0 || count[d] != a.shape[d])) block = false;
            }
            if (block) {
                int from = 0;
                for (int d = 0; d < rank; d++) from += start[d] * stridesIn[d];
                final int src = from;
                return new Op() { void run() { System.arraycopy(a.data, src, t.data, 0, t.size); } };
            }
            return new Op() { void run() {
                int[] idx = new int[rank];
                for (int i = 0; i < t.size; i++) {
                    int src = 0;
                    for (int d = 0; d < rank; d++) src += (start[d] + idx[d] * step[d]) * stridesIn[d];
                    t.data[i] = a.data[src];
                    for (int d = rank - 1; d >= 0; d--) { if (++idx[d] < count[d]) break; idx[d] = 0; }
                }
            } };
        }

        private Op splitV(int[] in, int[] out) {
            final Tensor a = tensors[in[0]];
            int[] sizes = tensors[in[1]].ints;
            int axis = tensors[in[2]].ints[0];
            final int rank = a.shape.length;
            final int ax = axis < 0 ? axis + rank : axis;
            int outer = 1, inner = 1;
            for (int i = 0; i < ax; i++) outer *= a.shape[i];
            for (int i = ax + 1; i < rank; i++) inner *= a.shape[i];
            final int outerF = outer, rowIn = a.shape[ax] * inner;
            final Tensor[] parts = new Tensor[out.length];
            final int[] width = new int[out.length];
            for (int p = 0; p < out.length; p++) {
                parts[p] = tensors[out[p]];
                width[p] = parts[p].shape[ax] * inner;
            }
            if (sizes.length != out.length) throw new IllegalArgumentException("split sizes don't match");
            return new Op() { void run() {
                for (int o = 0; o < outerF; o++) {
                    int at = o * rowIn;
                    for (int p = 0; p < parts.length; p++) {
                        System.arraycopy(a.data, at, parts[p].data, o * width[p], width[p]);
                        at += width[p];
                    }
                }
            } };
        }

        // -- arithmetic --

        private Op conv(int[] in, int[] out, int padding, int strideW, int strideH, int activation, int dilationW,
                        int dilationH, final boolean depthwise, final int depthMultiplier) {
            final Tensor a = tensors[in[0]], f = tensors[in[1]], t = tensors[out[0]];
            final int[] bias = in.length > 2 && in[2] >= 0 ? tensors[in[2]].ints : null;
            final int H = a.shape[1], W = a.shape[2], C = a.shape[3];
            final int KH = f.shape[1], KW = f.shape[2];
            final int OH = t.shape[1], OW = t.shape[2], O = t.shape[3];
            final int padH = padding == 0 ? pad(H, KH, strideH, dilationH, OH) : 0;
            final int padW = padding == 0 ? pad(W, KW, strideW, dilationW, OW) : 0;
            final int sh = strideH, sw = strideW, dh = dilationH, dw = dilationW;
            final int[] mult = new int[O], shift = new int[O];
            for (int c = 0; c < O; c++) {
                float fs = f.scale.length > 1 ? f.scale[c] : f.scale[0];
                double effective = (double) a.scale() * (double) fs / (double) t.scale();
                long[] q = quantizeMultiplier(effective);
                mult[c] = (int) q[0];
                shift[c] = (int) q[1];
            }
            final int inOffset = -a.zeroPoint, outOffset = t.zeroPoint;
            final int[] range = activationRange(activation, t);
            final byte[] x = a.data, w = f.data, y = t.data;
            // Without padding every tap is inside, so the input's offset comes out of the sum once per channel:
            // sum(w * (x + offset)) = sum(w * x) + offset * sum(w). Same integers, a third of the work in the loop.
            boolean unpadded = padH == 0 && padW == 0 && (OH - 1) * sh + (KH - 1) * dh < H && (OW - 1) * sw + (KW - 1) * dw < W;
            if (unpadded && !depthwise && KW == 1 && dw == 1 && W == 1 && OW == 1) {
                final int[] wsum = new int[O];
                final int span = KH * C;
                for (int oc = 0; oc < O; oc++) {
                    int sum = 0;
                    for (int k = 0; k < span; k++) sum += w[oc * span + k];
                    wsum[oc] = sum * inOffset + (bias != null ? bias[oc] : 0);
                }
                // A column of rows (microWakeWord's layers are all [1, H, 1, C]): the taps of an output are KH rows of
                // C values in a row, dh rows apart; with dh 1 one run of KH * C.
                return new Op() { void run() {
                    int lo = range[0], hi = range[1];
                    for (int oy = 0; oy < OH; oy++) {
                        int base = oy * sh * C;
                        for (int oc = 0; oc < O; oc++) {
                            int acc = wsum[oc];
                            int wi = oc * span;
                            if (dh == 1) {
                                for (int k = 0; k < span; k++) acc += w[wi + k] * x[base + k];
                            } else {
                                for (int ky = 0; ky < KH; ky++) {
                                    int xi = base + ky * dh * C, wk = wi + ky * C;
                                    for (int ic = 0; ic < C; ic++) acc += w[wk + ic] * x[xi + ic];
                                }
                            }
                            acc = multiply(acc, mult[oc], shift[oc]) + outOffset;
                            y[oy * O + oc] = (byte) (acc < lo ? lo : acc > hi ? hi : acc);
                        }
                    }
                } };
            }
            if (unpadded && depthwise && KW == 1 && W == 1 && OW == 1 && depthMultiplier == 1) {
                final int[] wsum = new int[O];
                for (int oc = 0; oc < O; oc++) {
                    int sum = 0;
                    for (int ky = 0; ky < KH; ky++) sum += w[ky * O + oc];
                    wsum[oc] = sum * inOffset + (bias != null ? bias[oc] : 0);
                }
                return new Op() { void run() {
                    int lo = range[0], hi = range[1];
                    for (int oy = 0; oy < OH; oy++) {
                        for (int oc = 0; oc < O; oc++) {
                            int acc = wsum[oc];
                            int xi = oy * sh * C + oc, step = dh * C;
                            for (int ky = 0; ky < KH; ky++, xi += step) acc += w[ky * O + oc] * x[xi];
                            acc = multiply(acc, mult[oc], shift[oc]) + outOffset;
                            y[oy * O + oc] = (byte) (acc < lo ? lo : acc > hi ? hi : acc);
                        }
                    }
                } };
            }
            return new Op() { void run() {
                for (int oy = 0; oy < OH; oy++)
                    for (int ox = 0; ox < OW; ox++)
                        for (int oc = 0; oc < O; oc++) {
                            int acc = 0;
                            int ic0 = depthwise ? oc / depthMultiplier : 0;
                            for (int ky = 0; ky < KH; ky++) {
                                int iy = oy * sh - padH + dh * ky;
                                if (iy < 0 || iy >= H) continue;
                                for (int kx = 0; kx < KW; kx++) {
                                    int ix = ox * sw - padW + dw * kx;
                                    if (ix < 0 || ix >= W) continue;
                                    int inBase = (iy * W + ix) * C;
                                    if (depthwise) {
                                        acc += w[(ky * KW + kx) * O + oc] * (x[inBase + ic0] + inOffset);
                                    } else {
                                        int wBase = ((oc * KH + ky) * KW + kx) * C;
                                        for (int ic = 0; ic < C; ic++) acc += w[wBase + ic] * (x[inBase + ic] + inOffset);
                                    }
                                }
                            }
                            if (bias != null) acc += bias[oc];
                            acc = multiply(acc, mult[oc], shift[oc]) + outOffset;
                            y[(oy * OW + ox) * O + oc] = (byte) Math.max(range[0], Math.min(range[1], acc));
                        }
            } };
        }

        private Op fullyConnected(int[] in, int[] out, int activation) {
            final Tensor a = tensors[in[0]], f = tensors[in[1]], t = tensors[out[0]];
            final int[] bias = in.length > 2 && in[2] >= 0 ? tensors[in[2]].ints : null;
            final int depth = f.shape[f.shape.length - 1], units = f.shape[0];
            final int batches = a.size / depth;
            double real = (double) (a.scale() * f.scale()) / (double) t.scale();
            long[] q = quantizeMultiplier(real);
            final int mult = (int) q[0], shift = (int) q[1];
            final int inOffset = -a.zeroPoint, fOffset = -f.zeroPoint, outOffset = t.zeroPoint;
            final int[] range = activationRange(activation, t);
            return new Op() { void run() {
                for (int b = 0; b < batches; b++)
                    for (int u = 0; u < units; u++) {
                        int acc = 0;
                        for (int d = 0; d < depth; d++) acc += (f.data[u * depth + d] + fOffset) * (a.data[b * depth + d] + inOffset);
                        if (bias != null) acc += bias[u];
                        acc = multiply(acc, mult, shift) + outOffset;
                        t.data[b * units + u] = (byte) Math.max(range[0], Math.min(range[1], acc));
                    }
            } };
        }

        /** reference_integer_ops::Logistic: int8 in, int8 out with zero point -128 and scale 1/256. */
        private Op logistic(int[] in, int[] out) {
            final Tensor a = tensors[in[0]], t = tensors[out[0]];
            if (a.type != INT8 || t.zeroPoint != -128) throw new IllegalArgumentException("logistic must be int8");
            double real = (double) a.scale() * (double) (1 << (31 - 4));
            int exp = Math.getExponent(real) + 1;
            double frac = real / Math.pow(2, exp);
            final int multiplier = (int) Math.round(frac * (1L << 31));
            final int leftShift = exp;
            final int radius = (int) Math.floor(1.0 * ((1 << 4) - 1) * (1L << (31 - 4)) / (1L << leftShift));
            final int zp = a.zeroPoint;
            // 256 inputs: work them out once.
            final byte[] table = new byte[256];
            for (int v = -128; v <= 127; v++) {
                int input = v - zp, o;
                if (input <= -radius) o = -128;
                else if (input >= radius) o = 127;
                else {
                    int q4 = multiply(input, multiplier, leftShift);
                    int q0 = Gemm.logistic(q4);
                    o = Math.max(-128, Math.min(127, Gemm.roundingDivideByPot(q0, 31 - 8) - 128));
                }
                table[v & 0xFF] = (byte) o;
            }
            return new Op() { void run() { for (int i = 0; i < t.size; i++) t.data[i] = table[a.data[i] & 0xFF]; } };
        }

        /** reference_ops::Requantize: int8 to uint8 (or int8). */
        private Op quantize(int[] in, int[] out) {
            final Tensor a = tensors[in[0]], t = tensors[out[0]];
            if (a.type != INT8) throw new IllegalArgumentException("quantize needs an int8 input");
            long[] q = quantizeMultiplier((double) a.scale() / (double) t.scale());
            final int mult = (int) q[0], shift = (int) q[1];
            final int inZp = a.zeroPoint, outZp = t.zeroPoint;
            final int lo = t.type == UINT8 ? 0 : -128, hi = t.type == UINT8 ? 255 : 127;
            return new Op() { void run() {
                for (int i = 0; i < t.size; i++) {
                    int v = multiply(a.data[i] - inZp, mult, shift) + outZp;
                    t.data[i] = (byte) Math.max(lo, Math.min(hi, v));
                }
            } };
        }
    }

    static int clampIndex(int i, int dim) {
        if (i < 0) i += dim;
        return Math.max(0, Math.min(dim, i));
    }

    static int[] rowStrides(int[] shape) {
        int[] s = new int[shape.length];
        int acc = 1;
        for (int d = shape.length - 1; d >= 0; d--) { s[d] = acc; acc *= shape[d]; }
        return s;
    }

    /** SAME padding before the first element (ComputePaddingHeightWidth). */
    static int pad(int in, int k, int stride, int dilation, int out) {
        int effective = (k - 1) * dilation + 1;
        int total = (out - 1) * stride + effective - in;
        return Math.max(0, total / 2);
    }

    static int[] activationRange(int activation, Tensor t) {
        int lo = t.type == UINT8 ? 0 : -128, hi = t.type == UINT8 ? 255 : 127;
        float s = t.scale();
        int zp = t.zeroPoint;
        switch (activation) {
            case 0: return new int[] {lo, hi};
            case 1: return new int[] {Math.max(lo, zp), hi};                                                 // RELU
            case 2: return new int[] {Math.max(lo, zp + Math.round(-1f / s)), Math.min(hi, zp + Math.round(1f / s))};
            case 3: return new int[] {Math.max(lo, zp), Math.min(hi, zp + Math.round(6f / s))};             // RELU6
            default: throw new IllegalArgumentException("activation " + activation + " isn't supported");
        }
    }

    /** QuantizeMultiplier: {multiplier, shift}. */
    static long[] quantizeMultiplier(double m) {
        if (m == 0) return new long[] {0, 0};
        int shift = Math.getExponent(m) + 1;
        double q = m / Math.pow(2, shift);
        long fixed = Math.round(q * (1L << 31));
        if (fixed == (1L << 31)) { fixed /= 2; shift++; }
        if (shift < -31) { shift = 0; fixed = 0; }
        return new long[] {fixed, shift};
    }

    /** MultiplyByQuantizedMultiplier (TFLite Micro, double rounding). */
    static int multiply(int x, int multiplier, int shift) {
        int left = shift > 0 ? shift : 0, right = shift > 0 ? 0 : -shift;
        return Gemm.roundingDivideByPot(Gemm.doublingHighMul(x * (1 << left), multiplier), right);
    }

    /** gemmlowp's fixed-point helpers, int32 raw values. */
    static final class Gemm {
        static int doublingHighMul(int a, int b) {
            if (a == b && a == Integer.MIN_VALUE) return Integer.MAX_VALUE;
            long ab = (long) a * b;
            long nudge = ab >= 0 ? (1 << 30) : (1 - (1 << 30));
            return (int) ((ab + nudge) / (1L << 31));
        }

        static int roundingDivideByPot(int x, int exponent) {
            int mask = (1 << exponent) - 1;
            int remainder = x & mask;
            int threshold = (mask >> 1) + (x < 0 ? 1 : 0);
            return (x >> exponent) + (remainder > threshold ? 1 : 0);
        }

        static int mulByPot(int x, int exponent) {
            if (exponent < 0) return roundingDivideByPot(x, -exponent);
            if (exponent == 0) return x;
            int threshold = (1 << (31 - exponent)) - 1;
            if (x > threshold) return Integer.MAX_VALUE;
            if (x < -threshold) return Integer.MIN_VALUE;
            return x << exponent;
        }

        /** exp(a) for a in [-1/4, 0), Q0.31. */
        static int expQuarter(int a) {
            int constantTerm = 1895147668, oneThird = 715827883;
            int x = a + (1 << 28);
            int x2 = doublingHighMul(x, x);
            int x3 = doublingHighMul(x2, x);
            int x4 = doublingHighMul(x2, x2);
            int x4over4 = mulByPot(x4, -2);
            int poly = mulByPot(doublingHighMul(x4over4 + x3, oneThird) + x2, -1);
            return constantTerm + doublingHighMul(constantTerm, x + poly);
        }

        /** exp(a) for a <= 0, a with 4 integer bits (Q4.27); result Q0.31. */
        static int expNegative(int a) {
            final int fractional = 27, integerBits = 4;
            int quarter = 1 << (fractional - 2);
            int aModQuarterMinusQuarter = (a & (quarter - 1)) - quarter;
            int result = expQuarter(mulByPot(aModQuarterMinusQuarter, integerBits)); // Rescale<0> from 4 integer bits
            int remainder = aModQuarterMinusQuarter - a;
            int[] multipliers = {1672461947, 1302514674, 790015084, 290630308, 39332535, 720401, 242};
            for (int e = -2; e <= 4; e++) {
                if (integerBits <= e) continue;
                int shiftAmount = fractional + e;
                if ((remainder & (1 << shiftAmount)) != 0) result = doublingHighMul(result, multipliers[e + 2]);
            }
            if (a == 0) result = Integer.MAX_VALUE;
            return result;
        }

        /** 1 / (1 + a) for a in [0, 1], Q0.31 in and out. */
        static int oneOverOnePlus(int a) {
            long sum = (long) a + Integer.MAX_VALUE;
            int half = (int) ((sum + (sum >= 0 ? 1 : -1)) / 2);
            int c48over17 = 1515870810, cNeg32over17 = -1010580540;
            int x = c48over17 + doublingHighMul(half, cNeg32over17);   // Q2.29
            int oneQ2 = 1 << 29;
            for (int i = 0; i < 3; i++) {
                int hx = doublingHighMul(half, x);                       // Q2
                int oneMinus = oneQ2 - hx;                               // Q2
                x = x + mulByPot(doublingHighMul(x, oneMinus), 2);      // Q4 -> Q2
            }
            return mulByPot(x, 1);  // ExactMulByPot<-1> keeps the raw value (Q2 -> Q1), then Rescale<0>
        }

        /** gemmlowp::logistic for a Q4.27 input; Q0.31 result. */
        static int logistic(int a) {
            if (a == 0) return 1 << 30;
            int abs = a > 0 ? a : -a;
            int positive = oneOverOnePlus(expNegative(-abs));
            return a > 0 ? positive : Integer.MAX_VALUE - positive;
        }
    }

    // ---- a small FlatBuffers reader (only what the TFLite schema needs) ----

    static final class Fb {
        final ByteBuffer b;

        Fb(byte[] data) { b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }

        int root() { return b.getInt(0); }

        /** The position of field `i`'s value in table `t`, or 0 when absent. */
        int field(int t, int i) {
            int vt = t - b.getInt(t);
            int vtSize = b.getShort(vt) & 0xFFFF;
            int at = 4 + 2 * i;
            if (at >= vtSize) return 0;
            int off = b.getShort(vt + at) & 0xFFFF;
            return off == 0 ? 0 : t + off;
        }

        int int8(int t, int i, int def) { int f = t == 0 ? 0 : field(t, i); return f == 0 ? def : b.get(f); }
        int int32(int t, int i, int def) { int f = t == 0 ? 0 : field(t, i); return f == 0 ? def : b.getInt(f); }
        long int64(int t, int i, long def) { int f = t == 0 ? 0 : field(t, i); return f == 0 ? def : b.getLong(f); }
        boolean bool(int t, int i) { return int8(t, i, 0) != 0; }
        long int64At(int at) { return b.getLong(at); }

        private int indirect(int f) { return f + b.getInt(f); }

        int tableField(int t, int i) { int f = t == 0 ? 0 : field(t, i); return f == 0 ? 0 : indirect(f); }
        /** A vector's position (its length word), or 0. */
        int vector(int t, int i) { return tableField(t, i); }
        int length(int v) { return v == 0 ? 0 : b.getInt(v); }
        int element(int v, int i, int size) { return v + 4 + i * size; }
        int table(int v, int i) { return indirect(element(v, i, 4)); }

        String string(int t, int i) {
            int s = tableField(t, i);
            if (s == 0) return "";
            int n = b.getInt(s);
            byte[] out = new byte[n];
            for (int k = 0; k < n; k++) out[k] = b.get(s + 4 + k);
            try { return new String(out, "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { return ""; }
        }

        int[] ints(int v) {
            int[] out = new int[length(v)];
            for (int i = 0; i < out.length; i++) out[i] = b.getInt(element(v, i, 4));
            return out;
        }

        float[] floats(int v) {
            float[] out = new float[length(v)];
            for (int i = 0; i < out.length; i++) out[i] = b.getFloat(element(v, i, 4));
            return out;
        }

        byte[] bytes(int v) {
            byte[] out = new byte[length(v)];
            for (int i = 0; i < out.length; i++) out[i] = b.get(v + 4 + i);
            return out;
        }

        int[] intsOfBytes(int v) {
            int[] out = new int[length(v) / 4];
            for (int i = 0; i < out.length; i++) out[i] = b.getInt(v + 4 + 4 * i);
            return out;
        }
    }
}
