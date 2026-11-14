import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Standalone orthographic rasterizer; every vertex uses the same 2:1 projection.
 * javac -d /tmp/sc2000-airport tools/BuildAirportSprite.java
 * java -Djava.awt.headless=true -cp /tmp/sc2000-airport BuildAirportSprite
 */
class BuildAirportSprite {
    static final int SCALE=3, WIDTH=512*SCALE, HEIGHT=480*SCALE;
    static final Path ROOT=Path.of("assets/sprites/civic");
    record V(double u,double v,double z) {}
    record Screen(double x,double y,double depth) {}
    record UV(double u,double v) {}
    static BufferedImage image;
    static double[] depth;
    static BufferedImage front, side, slate, stone, brick, glass, paving, grass, clock;
    static final Random RANDOM=new Random(2000);
    static final Map<String,BufferedImage> MATERIALS=new HashMap<>();

    static Screen project(V p) {
        return new Screen((256+p.u-p.v)*SCALE,(220+(p.u+p.v)/2-p.z)*SCALE,p.u+p.v+p.z);
    }
    static int shade(int color,double light) {
        int r=(int)Math.min(255,((color>>16)&255)*light);
        int g=(int)Math.min(255,((color>>8)&255)*light);
        int b=(int)Math.min(255,(color&255)*light);
        return (color&0xff000000)|(r<<16)|(g<<8)|b;
    }
    static int pixel(BufferedImage texture,double u,double v) {
        int x=Math.max(0,Math.min(texture.getWidth()-1,(int)Math.round(u*(texture.getWidth()-1))));
        int y=Math.max(0,Math.min(texture.getHeight()-1,(int)Math.round(v*(texture.getHeight()-1))));
        return texture.getRGB(x,y);
    }
    static double edge(Screen a,Screen b,double x,double y) {
        return (b.x-a.x)*(y-a.y)-(b.y-a.y)*(x-a.x);
    }
    static void triangle(BufferedImage texture,double light,V av,V bv,V cv,UV at,UV bt,UV ct) {
        Screen a=project(av),b=project(bv),c=project(cv);
        double area=edge(a,b,c.x,c.y);
        if(Math.abs(area)<1e-8)return;
        int x0=Math.max(0,(int)Math.floor(Math.min(a.x,Math.min(b.x,c.x))));
        int x1=Math.min(WIDTH-1,(int)Math.ceil(Math.max(a.x,Math.max(b.x,c.x))));
        int y0=Math.max(0,(int)Math.floor(Math.min(a.y,Math.min(b.y,c.y))));
        int y1=Math.min(HEIGHT-1,(int)Math.ceil(Math.max(a.y,Math.max(b.y,c.y))));
        for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++) {
            double wa=edge(b,c,x+.5,y+.5)/area, wb=edge(c,a,x+.5,y+.5)/area,wc=1-wa-wb;
            if(wa< -1e-8||wb< -1e-8||wc< -1e-8)continue;
            double z=wa*a.depth+wb*b.depth+wc*c.depth;
            int index=y*WIDTH+x;
            if(z<depth[index]-1e-6)continue;
            int color=pixel(texture,wa*at.u+wb*bt.u+wc*ct.u,wa*at.v+wb*bt.v+wc*ct.v);
            if((color>>>24)<128)continue;
            depth[index]=z;image.setRGB(x,y,shade(color,light));
        }
    }
    static void quad(BufferedImage texture,double light,V a,V b,V c,V d) {
        triangle(texture,light,a,b,c,new UV(0,1),new UV(1,1),new UV(1,0));
        triangle(texture,light,a,c,d,new UV(0,1),new UV(1,0),new UV(0,0));
    }
    static void top(BufferedImage texture,double u0,double v0,double u1,double v1,double z) {
        quad(texture,1.04,new V(u0,v1,z),new V(u1,v1,z),new V(u1,v0,z),new V(u0,v0,z));
    }
    static void box(double u0,double v0,double u1,double v1,double z0,double z1,
                    BufferedImage left,BufferedImage right,BufferedImage roof) {
        quad(left,1,new V(u0,v1,z0),new V(u1,v1,z0),new V(u1,v1,z1),new V(u0,v1,z1));
        quad(right,.82,new V(u1,v1,z0),new V(u1,v0,z0),new V(u1,v0,z1),new V(u1,v1,z1));
        top(roof,u0,v0,u1,v1,z1);
    }
    static void solid(double u0,double v0,double u1,double v1,double z0,double z1,BufferedImage material) {
        box(u0,v0,u1,v1,z0,z1,material,material,material);
    }
    static BufferedImage crop(BufferedImage source,int x,int y,int w,int h) {
        BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=out.createGraphics();g.drawImage(source,0,0,w,h,x,y,x+w,y+h,null);g.dispose();return out;
    }
    static BufferedImage material(int base,int tw,int th,boolean masonry) {
        String key=base+":"+tw+":"+th+":"+masonry;
        if(MATERIALS.containsKey(key))return MATERIALS.get(key);
        int size=512;BufferedImage out=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        Random noise=new Random(base);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            double n=(noise.nextDouble()-.5)*.10;
            int row=y/th,shift=(row%2)*tw/2;
            boolean joint=y%th<2||(x+shift)%tw<2;
            double light=1+n+(masonry&&joint?-.14:0);
            if(masonry&&y%th==2)light+=.10;
            out.setRGB(x,y,shade(0xff000000|base,light));
        }
        MATERIALS.put(key,out);return out;
    }

    static BufferedImage white, asphalt, yellow, metal;
    static void plane(double u,double v) {
        // Small parked commuter aircraft, modeled in world coordinates, including swept wings.
        solid(u-2,v-17,u+2,v+15,3,7,white);
        quad(white,1.0,new V(u-2,v-6,6),new V(u+2,v-6,6),new V(u+4,v-14,6),new V(u,v-20,6));
        quad(white,1.0,new V(u-18,v+7,6),new V(u+18,v+7,6),new V(u+3,v-2,6),new V(u-3,v-2,6));
        top(metal,u-2,v-12,u+2,v-8,7.1);
        top(white,u-8,v+11,u+8,v+14,7);
        solid(u-.6,v+10,u+.6,v+16,7,14,metal);
        solid(u-10,v+1,u-7,v+8,3,6,metal);
        solid(u+7,v+1,u+10,v+8,3,6,metal);
    }
    public static void main(String[] args) throws Exception {
        white=material(0xe9e8dc,32,32,false);asphalt=material(0x4d575f,32,32,false);
        yellow=material(0xe4bd58,32,32,false);metal=material(0x536c7a,32,32,false);
        stone=material(0xb9b9ae,32,32,true);grass=material(0x70884c,32,32,false);
        slate=material(0x87939b,32,32,true);
        front=ImageIO.read(ROOT.resolve("textures/airport-front-v1.png").toFile());
        image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_ARGB);
        depth=new double[WIDTH*HEIGHT];Arrays.fill(depth,-Double.MAX_VALUE);
        top(grass,0,0,256,256,0);
        top(asphalt,16,8,64,248,.1);
        top(stone,80,14,240,194,.1);
        // Taxiway and turnoffs remain parallel to the runway and footprint axes.
        top(asphalt,70,8,86,248,.2);
        for(int v:new int[]{25,210})top(asphalt,55,v,85,v+13,.3);
        top(yellow,77,12,78,242,.4);
        for(int v=40;v<220;v+=23)top(white,39,v,41,v+12,.4);
        for(int u=22;u<60;u+=7)for(int v:new int[]{15,230})top(white,u,v,u+3,v+12,.4);
        // Terminal, hangar and control tower: explicit roof planes and wall volumes.
        box(125,158,236,203,0,29,front,front,slate);
        solid(123,156,238,205,29,31,white);
        for(int u=135;u<227;u+=22)solid(u,170,u+10,182,31,35,metal);
        box(185,22,239,72,0,33,front,front,slate);
        solid(183,20,241,74,33,35,white);
        box(205,119,220,134,0,65,white,white,slate);
        box(201,115,224,138,65,77,metal,metal,slate);
        // Geometric mullions guarantee exact facade directions independent of texture detail.
        for(int u=202;u<=222;u+=5)solid(u,137,u+1,138,65,77,white);
        for(int v=116;v<=136;v+=5)solid(223,v,224,v+1,65,77,white);
        solid(199,113,226,140,77,80,white);
        solid(211,125,212,126,80,91,metal);
        for(int u:new int[]{110,158}) {
            top(yellow,u,83,u+1,147,.4);
            top(yellow,u-16,145,u+16,146,.4);
            plane(u,112);
        }
        // Landside access and marked car parking.
        top(asphalt,102,211,245,244,.2);
        for(int u=107;u<240;u+=12) {
            top(white,u,214,u+1,225,.4);
            if(u%3==0)solid(u+3,215,u+8,224,.5,4,metal);
        }
        for(int u=108;u<240;u+=23)top(white,u,234,u+12,235,.4);
        int first=HEIGHT,last=-1;
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++)if((image.getRGB(x,y)>>>24)>0){first=Math.min(first,y);last=Math.max(last,y);}
        BufferedImage source=crop(image,0,first,WIDTH,last-first+1);
        ImageIO.write(source,"png",ROOT.resolve("airport-geometry-v1.png").toFile());
        BufferedImage compact=new BufferedImage(512,source.getHeight()/SCALE,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=compact.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source,0,0,compact.getWidth(),compact.getHeight(),null);g.dispose();
        ImageIO.write(compact,"png",Path.of("client/src/main/resources/city/client/civic/airport.png").toFile());
        System.out.println("airport: "+compact.getWidth()+"x"+compact.getHeight());
    }
}
