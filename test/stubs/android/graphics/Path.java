package android.graphics;
public class Path {
    final java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
    public void moveTo(float x, float y) { p.moveTo(x, y); }
    public void lineTo(float x, float y) { p.lineTo(x, y); }
    public void close() { p.closePath(); }
    public enum Direction { CW, CCW }
    public void addRoundRect(RectF r, float rx, float ry, Direction d) {
        p.append(new java.awt.geom.RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), rx * 2, ry * 2), false);
    }
}
