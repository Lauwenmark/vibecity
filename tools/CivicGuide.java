import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
class CivicGuide {
 public static void main(String[] args) throws Exception {
  var im=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB); var g=im.createGraphics();
  g.setColor(new Color(120,140,100)); g.fillPolygon(new int[]{64,512,960,512},new int[]{640,416,640,864},4);
  g.setColor(new Color(190,145,105)); g.fillPolygon(new int[]{192,512,512,192},new int[]{640,800,520,360},4);
  g.setColor(new Color(150,110,85)); g.fillPolygon(new int[]{512,832,832,512},new int[]{800,640,360,520},4);
  g.setColor(new Color(90,100,110)); g.fillPolygon(new int[]{192,512,832,512},new int[]{360,200,360,520},4);
  g.setColor(Color.BLACK); g.setStroke(new BasicStroke(3));
  g.drawPolyline(new int[]{64,512,960},new int[]{640,864,640},3);
  g.drawPolyline(new int[]{192,512,832},new int[]{640,800,640},3);
  g.dispose(); ImageIO.write(im,"png",new File("assets/sprites/civic/geometry-guide.png"));
 }
}
