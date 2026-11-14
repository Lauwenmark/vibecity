import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import javax.imageio.ImageIO;

/** Square ground geometry first, exact 2:1 raster projection last. No generated camera geometry. */
class PrepareTransportSprites {
    static final Path SOURCE = Path.of("assets/sprites/transport");
    static final Path OUTPUT = Path.of("client/src/main/resources/city/client/transport");
    static final int SIZE = 512;
    static final double[][] ENDS = {{.5, -.1}, {1.1, .5}, {.5, 1.1}, {-.1, .5}};

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        TexturePaint asphalt = texture("asphalt-v2.png");
        TexturePaint ballast = texture("ballast-v2.png");
        BufferedImage gallery = new BufferedImage(1024, 1216, BufferedImage.TYPE_INT_RGB);
        Graphics2D gg = gallery.createGraphics();
        gg.setColor(new Color(0x365741));
        gg.fillRect(0, 0, gallery.getWidth(), gallery.getHeight());
        for (String kind : new String[]{"road", "rail", "rail-overlay"}) {
            for (int mask = 0; mask < 16; mask++) {
                BufferedImage ground = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = ground.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.scale(SIZE, SIZE);
                if (kind.equals("road")) road(g, mask, asphalt); else rail(g, mask, ballast, kind.equals("rail-overlay"));
                g.dispose();
                BufferedImage tile = new BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB);
                g = tile.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                // (u,v) -> (128 + 128u - 128v, 64u + 64v): exact map projection.
                g.drawImage(ground, new AffineTransform(.25, .125, -.25, .125, 128, 0), null);
                g.dispose();
                ImageIO.write(tile, "png", OUTPUT.resolve(String.format("%s-%02d.png", kind, mask)).toFile());
                if (!kind.equals("rail-overlay")) {
                    int x = mask % 4 * 256, y = mask / 4 * 152 + (kind.equals("rail") ? 608 : 0);
                    gg.drawImage(tile, x + 16, y + 24, 224, 112, null);
                    gg.setColor(new Color(0xe0e7df));
                    gg.drawString(kind + " " + mask, x + 8, y + 14);
                }
            }
        }
        gg.dispose();
        ImageIO.write(gallery, "png", SOURCE.resolve("gallery.png").toFile());
    }

    static TexturePaint texture(String name) throws Exception {
        BufferedImage source = ImageIO.read(SOURCE.resolve(name).toFile());
        BufferedImage periodic = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        // Mirrored edges make every texture continuous across both square tile boundaries.
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            int sx = Math.min(x, 255 - x) * (source.getWidth() - 1) / 127;
            int sy = Math.min(y, 255 - y) * (source.getHeight() - 1) / 127;
            periodic.setRGB(x, y, source.getRGB(sx, sy));
        }
        return new TexturePaint(periodic, new Rectangle2D.Double(0, 0, 1, 1));
    }

    static ArrayList<Path2D> routes(int mask) {
        ArrayList<Path2D> result = new ArrayList<>();
        int count = Integer.bitCount(mask);
        if (count == 0) {
            Path2D p = new Path2D.Double(); p.moveTo(.5, .28); p.lineTo(.5, .72); result.add(p);
        }
        for (int a = 0; a < 4; a++) if ((mask & (1 << a)) != 0) {
            if (count == 1) {
                Path2D p = new Path2D.Double(); p.moveTo(ENDS[a][0], ENDS[a][1]); p.lineTo(.5, .5); result.add(p);
            }
            for (int b = a + 1; b < 4; b++) if ((mask & (1 << b)) != 0) {
                if (count == 4 && b - a != 2) continue;
                // Straight common approaches; tangent curves only begin inside the tile.
                double ax = .5 + (ENDS[a][0] - .5) * .5, ay = .5 + (ENDS[a][1] - .5) * .5;
                double bx = .5 + (ENDS[b][0] - .5) * .5, by = .5 + (ENDS[b][1] - .5) * .5;
                Path2D p = new Path2D.Double(); p.moveTo(ENDS[a][0], ENDS[a][1]); p.lineTo(ax, ay);
                p.quadTo(.5, .5, bx, by); p.lineTo(ENDS[b][0], ENDS[b][1]); result.add(p);
            }
        }
        return result;
    }

    static Area corridor(ArrayList<Path2D> routes, double width) {
        Area area = new Area();
        for (Path2D p : routes) area.add(new Area(new BasicStroke((float)width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND).createStrokedShape(p)));
        return area;
    }

    static void road(Graphics2D g, int mask, TexturePaint asphalt) {
        ArrayList<Path2D> routes = routes(mask);
        Area paving = corridor(routes, .86), curb = corridor(routes, .66), surface = corridor(routes, .62);
        g.setColor(new Color(0xa5a69d)); g.fill(paving);
        Graphics2D joints = (Graphics2D)g.create();
        Area sidewalks = new Area(paving); sidewalks.subtract(curb); joints.clip(sidewalks);
        joints.setColor(new Color(0x93988e)); joints.setStroke(new BasicStroke(.003f));
        for (double v = 0; v < 1; v += .125) {
            joints.draw(new Line2D.Double(v, 0, v, 1)); joints.draw(new Line2D.Double(0, v, 1, v));
        }
        joints.dispose();
        g.setColor(new Color(0xd1d0bc)); g.fill(curb);
        g.setPaint(asphalt); g.fill(surface);
        // White centre dashes follow the actual curve; intersections have clear centres.
        g.setColor(new Color(0xe5e2ca));
        g.setStroke(new BasicStroke(.014f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[]{.09f, .07f}, .025f));
        if (Integer.bitCount(mask) == 2) for (Path2D p : routes) g.draw(p);
        else for (int side = 0; side < 4; side++) if ((mask & (1 << side)) != 0) {
            Graphics2D port = port(g, side);
            port.draw(new Line2D.Double(.5, -.1, .5, .24)); port.dispose();
        }
        // Enforce identical geometry and marking phase at every opening, including bends.
        for (int side = 0; side < 4; side++) if ((mask & (1 << side)) != 0) {
            Graphics2D port = port(g, side);
            port.setPaint(asphalt); port.fill(new Rectangle2D.Double(.19, 0, .62, .16));
            port.setColor(new Color(0xe5e2ca)); port.fill(new Rectangle2D.Double(.493, .025, .014, .09));
            port.dispose();
        }
    }

    static Graphics2D port(Graphics2D g, int side) {
        Graphics2D result = (Graphics2D)g.create(); result.rotate(side * Math.PI / 2, .5, .5); return result;
    }

    static void rail(Graphics2D g, int mask, TexturePaint ballast, boolean overlay) {
        ArrayList<Path2D> routes = routes(mask);
        if (!overlay) {
            g.setColor(new Color(0x7b7b6d)); g.fill(corridor(routes, .52));
            g.setPaint(ballast); g.fill(corridor(routes, .48));
        }
        if (!overlay) for (Path2D p : routes) track(g, p, false);
        for (Path2D p : routes) track(g, p, true);
        // Redraw an identical straight port after the curved switches.
        for (int side = 0; side < 4; side++) if ((mask & (1 << side)) != 0) {
            Graphics2D port = port(g, side);
            if (overlay) {
                // Remove antialiased curved rails before laying down the canonical port.
                port.setComposite(AlphaComposite.Clear);
                port.fill(new Rectangle2D.Double(.26, 0, .48, .16));
                port.setComposite(AlphaComposite.SrcOver);
            }
            if (!overlay) {
                port.setPaint(ballast); port.fill(new Rectangle2D.Double(.26, 0, .48, .16));
                port.setColor(new Color(0x655d50));
                for (double y = .0375; y < .16; y += .075) port.fill(new Rectangle2D.Double(.33, y, .34, .033));
            }
            for (double x : new double[]{.405, .595}) {
                port.setColor(new Color(0x53616a)); port.fill(new Rectangle2D.Double(x - .016, 0, .032, .16));
                port.setColor(new Color(0xbec9ca)); port.fill(new Rectangle2D.Double(x - .008, 0, .016, .16));
            }
            port.dispose();
        }
        if (!overlay && Integer.bitCount(mask) <= 1) {
            Graphics2D buffer = port(g, mask == 0 ? 0 : Integer.numberOfTrailingZeros(mask));
            double y = mask == 0 ? .72 : .49;
            buffer.setColor(new Color(0x3e3631)); buffer.fill(new Rectangle2D.Double(.32, y, .36, .04));
            buffer.setColor(new Color(0xc3a46a)); buffer.fill(new Rectangle2D.Double(.34, y, .32, .012));
            buffer.dispose();
        }
    }

    static void track(Graphics2D g, Path2D route, boolean rails) {
        // Flatten the ground-space curve; offset by a constant physical gauge.
        PathIterator it = route.getPathIterator(null, .001);
        double[] xy = new double[6];
        ArrayList<Point2D.Double> points = new ArrayList<>();
        while (!it.isDone()) { it.currentSegment(xy); points.add(new Point2D.Double(xy[0], xy[1])); it.next(); }
        Path2D left = new Path2D.Double(), right = new Path2D.Double();
        double distance = 0, nextSleeper = .025;
        for (int i = 1; i < points.size(); i++) {
            Point2D.Double a = points.get(i - 1), b = points.get(i);
            double dx = b.x - a.x, dy = b.y - a.y, length = Math.hypot(dx, dy);
            if (length < 1e-9) continue;
            double nx = -dy / length, ny = dx / length;
            if (i == 1) { left.moveTo(a.x + nx * .095, a.y + ny * .095); right.moveTo(a.x - nx * .095, a.y - ny * .095); }
            left.lineTo(b.x + nx * .095, b.y + ny * .095); right.lineTo(b.x - nx * .095, b.y - ny * .095);
            while (nextSleeper <= distance + length) {
                double t = (nextSleeper - distance) / length, x = a.x + t * dx, y = a.y + t * dy;
                if (!rails) {
                    g.setColor(new Color(0x655d50)); g.setStroke(new BasicStroke(.033f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(x - nx * .17, y - ny * .17, x + nx * .17, y + ny * .17));
                    g.setColor(new Color(0x998b70)); g.setStroke(new BasicStroke(.006f));
                    g.draw(new Line2D.Double(x - nx * .17, y - ny * .17 - .008, x + nx * .17, y + ny * .17 - .008));
                }
                nextSleeper += .075;
            }
            distance += length;
        }
        if (rails) for (Path2D p : new Path2D[]{left, right}) {
            g.setColor(new Color(0x53616a)); g.setStroke(new BasicStroke(.032f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)); g.draw(p);
            g.setColor(new Color(0xbec9ca)); g.setStroke(new BasicStroke(.016f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)); g.draw(p);
        }
    }
}
