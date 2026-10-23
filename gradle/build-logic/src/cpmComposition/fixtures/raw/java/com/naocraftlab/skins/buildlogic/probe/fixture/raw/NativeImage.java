package com.naocraftlab.skins.buildlogic.probe.fixture.raw;
public final class NativeImage implements AutoCloseable {
 private final int width,height; private final int[] pixels;
 public NativeImage(int w,int h,boolean clear){width=w;height=h;pixels=new int[w*h];}
 public static NativeImage read(java.io.InputStream in) throws java.io.IOException {
  var png=javax.imageio.ImageIO.read(in); var out=new NativeImage(png.getWidth(),png.getHeight(),false);
  for(int y=0;y<out.height;y++)for(int x=0;x<out.width;x++)out.setPixelRGBA(x,y,Integer.reverseBytes(png.getRGB(x,y))>>>8 | (png.getRGB(x,y)&0xff000000));
  return out;
 }
 public int getWidth(){return width;} public int getHeight(){return height;}
 public int getPixelRGBA(int x,int y){return pixels[y*width+x];}
 public void setPixelRGBA(int x,int y,int value){pixels[y*width+x]=value;}
 public void close(){}
}
