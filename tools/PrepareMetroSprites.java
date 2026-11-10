import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Run from the repository root: java -Djava.awt.headless=true tools/PrepareMetroSprites.java. No external libraries. */
class PrepareMetroSprites {
    static final Path SOURCE = Path.of("assets/sprites/metro");
    static final Path OUTPUT = Path.of("client/src/main/resources/city/client/metro");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        for (int mask = 0; mask < 16; mask++) {
            final String prefix = String.format("tunnel-%02d-", mask);
            Path source;
            try (var files = Files.list(SOURCE)) {
                source = files.filter(p -> p.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow();
            }
            BufferedImage input = crop(ImageIO.read(source.toFile()));
            BufferedImage tile = new BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tile.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            Polygon diamond = new Polygon(new int[]{128,256,128,0}, new int[]{0,64,128,64}, 4);
            g.setClip(diamond);
            g.setColor(new Color(0x253e47));
            g.fill(diamond);
            // Map the ground plane, excluding the raised back wall and transparent margins.
            double w = input.getWidth(), h = input.getHeight();
            g.drawImage(input, new AffineTransform(256 / w, 0, 0, 128 / (.84*h), 0, -.16*h*128/(.84*h)), null);
            // Identical open-end geometry on every variant. Work in square ground coordinates.
            g.transform(new AffineTransform(128,64,-128,64,128,0));
            g.setColor(new Color(0x253e47));
            g.fill(new Rectangle2D.Double(.12, .12, .76, .76));
            for (int side = 0; side < 4; side++) {
                if ((mask & (1 << side)) == 0) continue;
                Graphics2D port = (Graphics2D) g.create();
                port.rotate(side * Math.PI / 2, .5, .5);
                port.fill(new Rectangle2D.Double(.30, 0, .40, .5));
                port.dispose();
            }
            var routes = new java.util.ArrayList<double[]>();
            double[][] ends = {{.5,.125},{.875,.5},{.5,.875},{.125,.5}};
            int count = Integer.bitCount(mask);
            if (count == 0) routes.add(new double[]{.5,.30,.5,.70});
            for (int a=0; a<4; a++) if ((mask & (1<<a)) != 0) {
                if (count == 1) routes.add(new double[]{ends[a][0],ends[a][1],.5,.5});
                for (int b=a+1; b<4; b++) if ((mask & (1<<b)) != 0) {
                    if (count == 4 && b-a != 2) continue;
                    routes.add(new double[]{ends[a][0],ends[a][1],ends[b][0],ends[b][1]});
                }
            }
            for (double[] route : routes) track(g, route, false);
            for (double[] route : routes) track(g, route, true);
            // The last eighth of each opening is identical, including sleeper phase.
            for (int side=0; side<4; side++) if ((mask & (1<<side)) != 0) {
                Graphics2D port=(Graphics2D)g.create();
                port.rotate(side*Math.PI/2,.5,.5);
                port.setColor(new Color(0x253e47));
                port.fill(new Rectangle2D.Double(.30,0,.40,.125));
                port.setColor(new Color(0x75636a));
                for (double y=.025; y<.125; y+=.075) port.fill(new Rectangle2D.Double(.355,y,.29,.025));
                port.setColor(new Color(0xd1d7e0));
                port.setStroke(new BasicStroke(.018f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                port.draw(new Line2D.Double(.40,0,.40,.125));
                port.draw(new Line2D.Double(.60,0,.60,.125));
                port.dispose();
            }
            if (count <= 1) {
                Graphics2D buffer = (Graphics2D) g.create();
                if (count == 1) buffer.rotate(Integer.numberOfTrailingZeros(mask)*Math.PI/2, .5, .5);
                buffer.setColor(new Color(0xe1c15b));
                buffer.fill(new Rectangle2D.Double(.35, count==0 ? .70 : .48, .30, .035));
                buffer.dispose();
            }
            g.dispose();
            ImageIO.write(tile, "png", OUTPUT.resolve(String.format("tunnel-%02d.png",mask)).toFile());
        }
        BufferedImage station = crop(ImageIO.read(SOURCE.resolve("station-v1.png").toFile()));
        BufferedImage compact = new BufferedImage(256, 208, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = compact.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(station, 0, 0, 256, 208, null);
        g.dispose();
        ImageIO.write(compact, "png", OUTPUT.resolve("station.png").toFile());
    }

    // Offset both rails in the ground plane; every opening has the same gauge and tangent.
    static void track(Graphics2D g, double[] route, boolean rails) {
        Path2D left = new Path2D.Double(), right = new Path2D.Double();
        for (int step=0; step<=80; step++) {
            double t=step/80.0, q=1-t;
            double x=q*q*route[0]+q*t+t*t*route[2];
            double y=q*q*route[1]+q*t+t*t*route[3];
            double dx=2*q*(.5-route[0])+2*t*(route[2]-.5);
            double dy=2*q*(.5-route[1])+2*t*(route[3]-.5);
            double length=Math.hypot(dx,dy);
            if (length<1e-8) { dx=route[2]-route[0]; dy=route[3]-route[1]; length=Math.hypot(dx,dy); }
            double nx=-dy/length, ny=dx/length;
            if (!rails && step%8==4) {
                g.setColor(new Color(0x75636a));
                g.setStroke(new BasicStroke(.025f));
                g.draw(new Line2D.Double(x+nx*.145,y+ny*.145,x-nx*.145,y-ny*.145));
            }
            if (step==0) { left.moveTo(x+nx*.10,y+ny*.10); right.moveTo(x-nx*.10,y-ny*.10); }
            else { left.lineTo(x+nx*.10,y+ny*.10); right.lineTo(x-nx*.10,y-ny*.10); }
        }
        if (rails) {
            g.setColor(new Color(0xd1d7e0));
            g.setStroke(new BasicStroke(.018f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.draw(left); g.draw(right);
        }
    }

    static BufferedImage crop(BufferedImage input) {
        int left=input.getWidth(), top=input.getHeight(), right=0, bottom=0;
        // Ignore faint generated halos when finding the artwork bounds.
        for (int y=0; y<input.getHeight(); y++) for (int x=0; x<input.getWidth(); x++) {
            if ((input.getRGB(x,y) >>> 24) < 192) continue;
            left=Math.min(left,x); top=Math.min(top,y); right=Math.max(right,x); bottom=Math.max(bottom,y);
        }
        if (right < left || bottom < top) throw new IllegalArgumentException("Empty sprite");
        BufferedImage result = new BufferedImage(right-left+1,bottom-top+1,BufferedImage.TYPE_INT_ARGB);
        for (int y=0; y<result.getHeight(); y++) for (int x=0; x<result.getWidth(); x++) {
            int pixel=input.getRGB(x+left,y+top);
            result.setRGB(x,y,(pixel >>> 24) < 96 ? 0 : pixel);
        }
        return result;
    }
}
