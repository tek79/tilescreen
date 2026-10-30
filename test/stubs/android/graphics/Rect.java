package android.graphics;
public class Rect {
    public int left, top, right, bottom;
    public Rect(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    @Override public boolean equals(Object o) {
        if (!(o instanceof Rect)) return false;
        Rect r = (Rect) o;
        return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
    }
    @Override public int hashCode() { return ((left * 31 + top) * 31 + right) * 31 + bottom; }
}
