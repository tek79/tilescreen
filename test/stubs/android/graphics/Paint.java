package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public enum Align { LEFT, CENTER, RIGHT }
    public enum Style { FILL, STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    Cap cap = Cap.BUTT;
    public void setStrokeCap(Cap c) { cap = c; }
    int color = 0xFF000000; float size = 12; Typeface face = Typeface.DEFAULT; Align align = Align.LEFT; Style style = Style.FILL; float stroke = 1;
    public Paint() {}
    public Paint(int flags) {}
    public void setColor(int c) { color = c; }
    public int getColor() { return color; }
    public void setAlpha(int a) { color = (color & 0xFFFFFF) | ((a & 0xFF) << 24); }
    public void setTextSize(float s) { size = s; }
    public float getTextSize() { return size; }
    public void setTypeface(Typeface t) { face = t; }
    public void setTextAlign(Align a) { align = a; }
    public void setStyle(Style s) { style = s; }
    public Style getStyle() { return style; }
    public enum Join { MITER, ROUND, BEVEL }
    public void setStrokeJoin(Join j) {}
    public void setStrokeWidth(float w) { stroke = w; }
    java.awt.Font font() { return face.font.deriveFont(size); }
    static final java.awt.image.BufferedImage SCRATCH = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    public float measureText(String s) {
        java.awt.Graphics2D g = SCRATCH.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        float w = (float) font().getStringBounds(s, g.getFontRenderContext()).getWidth();
        g.dispose();
        return w;
    }
}
