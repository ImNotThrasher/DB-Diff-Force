package studio.dbdiff;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.imageio.ImageIO;

/** Scalable application mark used in the header and native window chrome. */
final class BrandMark implements Icon {
    private static final int VIEW_SIZE = 64;
    private static final BufferedImage LOGO = loadLogo();
    private final int size;

    private BrandMark(int size) { this.size = size; }

    static Icon icon(int size) { return new BrandMark(size); }

    static List<Image> windowImages() {
        return List.of(16, 24, 32, 48, 64, 128).stream().map(BrandMark::image).map(Image.class::cast).toList();
    }

    private static BufferedImage image(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        paintMark(graphics, size, size);
        graphics.dispose();
        return image;
    }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        canvas.translate(x, y);
        paintMark(canvas, size, size);
        canvas.dispose();
    }

    private static void paintMark(Graphics2D graphics, int width, int height) {
        if (LOGO != null) {
            double scale = Math.min(width / (double) LOGO.getWidth(), height / (double) LOGO.getHeight());
            int drawWidth = (int) Math.round(LOGO.getWidth() * scale);
            int drawHeight = (int) Math.round(LOGO.getHeight() * scale);
            int x = (width - drawWidth) / 2;
            int y = (height - drawHeight) / 2;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(LOGO, x, y, drawWidth, drawHeight, null);
            g.dispose();
            return;
        }
        paintFallback(graphics, width, height);
    }

    /** Keep the source canvas intact so its original aspect ratio is never altered. */
    private static BufferedImage loadLogo() {
        try (var input = BrandMark.class.getResourceAsStream("/studio/dbdiff/brand-mark.png")) {
            return input == null ? null : ImageIO.read(input);
        } catch (java.io.IOException ignored) {
            return null;
        }
    }

    private static void paintFallback(Graphics2D graphics, int width, int height) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.scale(width / (double) VIEW_SIZE, height / (double) VIEW_SIZE);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        g.setPaint(new GradientPaint(3, 2, new Color(20, 50, 100), 61, 62, new Color(5, 14, 31)));
        g.fill(new RoundRectangle2D.Float(2, 2, 60, 60, 17, 17));
        g.setColor(new Color(45, 111, 211, 150));
        g.draw(new RoundRectangle2D.Float(2.5f, 2.5f, 59, 59, 17, 17));

        Color blueTop = new Color(43, 165, 255);
        Color blueBody = new Color(11, 99, 231);
        Color silverTop = new Color(239, 245, 255);
        Color silverBody = new Color(154, 175, 205);
        for (int tier = 0; tier < 3; tier++) {
            float y = 19 + tier * 10.5f;
            drawCylinder(g, 8, y, 25, 12, blueTop, blueBody, new Color(7, 58, 161));
            drawCylinder(g, 31, y, 25, 12, silverTop, silverBody, new Color(92, 116, 151));
        }

        g.setColor(new Color(4, 16, 36));
        g.fillRoundRect(26, 16, 14, 38, 5, 5);
        g.setPaint(new GradientPaint(26, 16, Color.WHITE, 40, 54, new Color(218, 230, 248)));
        g.fillRoundRect(25, 14, 14, 38, 5, 5);
        g.setColor(new Color(255, 255, 255, 190));
        g.drawRoundRect(25, 14, 14, 38, 5, 5);
        g.setColor(new Color(13, 190, 132)); g.fillRoundRect(28, 22, 8, 3, 2, 2);
        g.setColor(new Color(103, 130, 166)); g.fillRoundRect(28, 29, 6, 3, 2, 2);
        g.setColor(new Color(238, 70, 93)); g.fillRoundRect(28, 36, 8, 3, 2, 2);
        g.setColor(new Color(13, 190, 132)); g.fillRoundRect(28, 43, 7, 3, 2, 2);
        g.dispose();
    }

    private static void drawCylinder(Graphics2D g, float x, float y, float width, float height,
                                     Color top, Color body, Color edge) {
        g.setColor(body);
        g.fillRoundRect(Math.round(x), Math.round(y + 3), Math.round(width), Math.round(height - 4), 5, 5);
        g.setColor(top);
        g.fill(new Ellipse2D.Float(x, y, width, height * .52f));
        g.setColor(edge);
        g.drawArc(Math.round(x), Math.round(y + height * .48f), Math.round(width), Math.round(height * .45f), 180, 180);
    }
}
