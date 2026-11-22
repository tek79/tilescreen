package android.graphics;
public class Bitmap {
    public final java.awt.image.BufferedImage img;
    public Bitmap(java.awt.image.BufferedImage i) { img = i; }
    public static Bitmap createBitmap(Bitmap b, int x, int y, int w, int h) { return new Bitmap(b.img.getSubimage(x, y, w, h)); }
    public void recycle() {}
    public int getWidth() { return img.getWidth(); }
    public int getHeight() { return img.getHeight(); }
}
