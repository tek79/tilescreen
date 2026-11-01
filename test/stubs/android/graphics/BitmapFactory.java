package android.graphics;
public class BitmapFactory {
    public static Bitmap decodeStream(java.io.InputStream in) {
        try { java.awt.image.BufferedImage i = javax.imageio.ImageIO.read(in); return i == null ? null : new Bitmap(i); }
        catch (java.io.IOException e) { return null; }
    }
    public static Bitmap decodeByteArray(byte[] b, int off, int len) {
        return decodeStream(new java.io.ByteArrayInputStream(b, off, len));
    }
}
