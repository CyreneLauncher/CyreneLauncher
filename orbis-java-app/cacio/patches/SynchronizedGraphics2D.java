package com.github.caciocavallosilano.cacio.ctc;

import java.awt.*;
import java.awt.font.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Map;
import sun.awt.ConstrainableGraphics;

/** A per-operation screen lock around a Graphics2D; clones retain the same lock. */
final class SynchronizedGraphics2D extends Graphics2D implements ConstrainableGraphics {
    private final Graphics2D delegate;
    private final Object screenLock;
    SynchronizedGraphics2D(Graphics2D delegate, Object screenLock) {
        this.delegate = delegate;
        this.screenLock = screenLock;
    }
    private Graphics2D wrap(Graphics2D g) { return new SynchronizedGraphics2D(g, screenLock); }
    public Graphics create() { synchronized(screenLock) { return wrap((Graphics2D)delegate.create()); } }
    public Graphics create(int x,int y,int w,int h) { synchronized(screenLock) { return wrap((Graphics2D)delegate.create(x,y,w,h)); } }
    public void dispose() { synchronized(screenLock) { delegate.dispose(); } }
    public void constrain(int x,int y,int w,int h) { synchronized(screenLock) { ((ConstrainableGraphics)delegate).constrain(x,y,w,h); } }
    public void translate(int x,int y) { synchronized(screenLock) { delegate.translate(x,y); } }
    public void translate(double x,double y) { synchronized(screenLock) { delegate.translate(x,y); } }
    public void rotate(double t) { synchronized(screenLock) { delegate.rotate(t); } }
    public void rotate(double t,double x,double y) { synchronized(screenLock) { delegate.rotate(t,x,y); } }
    public void scale(double x,double y) { synchronized(screenLock) { delegate.scale(x,y); } }
    public void shear(double x,double y) { synchronized(screenLock) { delegate.shear(x,y); } }
    public void transform(AffineTransform t) { synchronized(screenLock) { delegate.transform(t); } }
    public void setTransform(AffineTransform t) { synchronized(screenLock) { delegate.setTransform(t); } }
    public AffineTransform getTransform() { synchronized(screenLock) { return delegate.getTransform(); } }
    public void setColor(Color c) { synchronized(screenLock) { delegate.setColor(c); } }
    public Color getColor() { synchronized(screenLock) { return delegate.getColor(); } }
    public void setPaintMode() { synchronized(screenLock) { delegate.setPaintMode(); } }
    public void setXORMode(Color c) { synchronized(screenLock) { delegate.setXORMode(c); } }
    public void setFont(Font f) { synchronized(screenLock) { delegate.setFont(f); } }
    public Font getFont() { synchronized(screenLock) { return delegate.getFont(); } }
    public FontMetrics getFontMetrics(Font f) { synchronized(screenLock) { return delegate.getFontMetrics(f); } }
    public FontMetrics getFontMetrics() { synchronized(screenLock) { return delegate.getFontMetrics(); } }
    public Rectangle getClipBounds() { synchronized(screenLock) { return delegate.getClipBounds(); } }
    public Rectangle getClipBounds(Rectangle r) { synchronized(screenLock) { return delegate.getClipBounds(r); } }
    public Rectangle getClipRect() { synchronized(screenLock) { return delegate.getClipRect(); } }
    public boolean hitClip(int x,int y,int w,int h) { synchronized(screenLock) { return delegate.hitClip(x,y,w,h); } }
    public void clipRect(int x,int y,int w,int h) { synchronized(screenLock) { delegate.clipRect(x,y,w,h); } }
    public void setClip(int x,int y,int w,int h) { synchronized(screenLock) { delegate.setClip(x,y,w,h); } }
    public Shape getClip() { synchronized(screenLock) { return delegate.getClip(); } }
    public void setClip(Shape s) { synchronized(screenLock) { delegate.setClip(s); } }
    public void clip(Shape s) { synchronized(screenLock) { delegate.clip(s); } }
    public void copyArea(int x,int y,int w,int h,int dx,int dy) { synchronized(screenLock) { delegate.copyArea(x,y,w,h,dx,dy); } }
    public void draw(Shape s) { synchronized(screenLock) { delegate.draw(s); } }
    public void fill(Shape s) { synchronized(screenLock) { delegate.fill(s); } }
    public void drawLine(int x1,int y1,int x2,int y2) { synchronized(screenLock) { delegate.drawLine(x1,y1,x2,y2); } }
    public void fillRect(int x,int y,int w,int h) { synchronized(screenLock) { delegate.fillRect(x,y,w,h); } }
    public void drawRect(int x,int y,int w,int h) { synchronized(screenLock) { delegate.drawRect(x,y,w,h); } }
    public void clearRect(int x,int y,int w,int h) { synchronized(screenLock) { delegate.clearRect(x,y,w,h); } }
    public void draw3DRect(int x,int y,int w,int h,boolean raised) { synchronized(screenLock) { delegate.draw3DRect(x,y,w,h,raised); } }
    public void fill3DRect(int x,int y,int w,int h,boolean raised) { synchronized(screenLock) { delegate.fill3DRect(x,y,w,h,raised); } }
    public void drawRoundRect(int x,int y,int w,int h,int aw,int ah) { synchronized(screenLock) { delegate.drawRoundRect(x,y,w,h,aw,ah); } }
    public void fillRoundRect(int x,int y,int w,int h,int aw,int ah) { synchronized(screenLock) { delegate.fillRoundRect(x,y,w,h,aw,ah); } }
    public void drawOval(int x,int y,int w,int h) { synchronized(screenLock) { delegate.drawOval(x,y,w,h); } }
    public void fillOval(int x,int y,int w,int h) { synchronized(screenLock) { delegate.fillOval(x,y,w,h); } }
    public void drawArc(int x,int y,int w,int h,int sa,int aa) { synchronized(screenLock) { delegate.drawArc(x,y,w,h,sa,aa); } }
    public void fillArc(int x,int y,int w,int h,int sa,int aa) { synchronized(screenLock) { delegate.fillArc(x,y,w,h,sa,aa); } }
    public void drawPolyline(int[] x,int[] y,int n) { synchronized(screenLock) { delegate.drawPolyline(x,y,n); } }
    public void drawPolygon(int[] x,int[] y,int n) { synchronized(screenLock) { delegate.drawPolygon(x,y,n); } }
    public void drawPolygon(Polygon p) { synchronized(screenLock) { delegate.drawPolygon(p); } }
    public void fillPolygon(int[] x,int[] y,int n) { synchronized(screenLock) { delegate.fillPolygon(x,y,n); } }
    public void fillPolygon(Polygon p) { synchronized(screenLock) { delegate.fillPolygon(p); } }
    public void drawChars(char[] d,int o,int n,int x,int y) { synchronized(screenLock) { delegate.drawChars(d,o,n,x,y); } }
    public void drawBytes(byte[] d,int o,int n,int x,int y) { synchronized(screenLock) { delegate.drawBytes(d,o,n,x,y); } }
    public void drawString(String s,int x,int y) { synchronized(screenLock) { delegate.drawString(s,x,y); } }
    public void drawString(String s,float x,float y) { synchronized(screenLock) { delegate.drawString(s,x,y); } }
    public void drawString(AttributedCharacterIterator s,int x,int y) { synchronized(screenLock) { delegate.drawString(s,x,y); } }
    public void drawString(AttributedCharacterIterator s,float x,float y) { synchronized(screenLock) { delegate.drawString(s,x,y); } }
    public void drawGlyphVector(GlyphVector g,float x,float y) { synchronized(screenLock) { delegate.drawGlyphVector(g,x,y); } }
    public boolean drawImage(Image i,int x,int y,ImageObserver o) { synchronized(screenLock) { return delegate.drawImage(i,x,y,o); } }
    public boolean drawImage(Image i,int x,int y,int w,int h,ImageObserver o) { synchronized(screenLock) { return delegate.drawImage(i,x,y,w,h,o); } }
    public boolean drawImage(Image i,int x,int y,Color c,ImageObserver o) { synchronized(screenLock) { return delegate.drawImage(i,x,y,c,o); } }
    public boolean drawImage(Image i,int x,int y,int w,int h,Color c,ImageObserver o) { synchronized(screenLock) { return delegate.drawImage(i,x,y,w,h,c,o); } }
    public boolean drawImage(Image i,int dx1,int dy1,int dx2,int dy2,int sx1,int sy1,int sx2,int sy2,ImageObserver o) { synchronized(screenLock) { return delegate.drawImage(i,dx1,dy1,dx2,dy2,sx1,sy1,sx2,sy2,o); } }
    public boolean drawImage(Image i,int dx1,int dy1,int dx2,int dy2,int sx1,int sy1,int sx2,int sy2,Color c,ImageObserver o) { synchronized(screenLock) { return delegate.drawImage(i,dx1,dy1,dx2,dy2,sx1,sy1,sx2,sy2,c,o); } }
    public boolean drawImage(Image i,AffineTransform t,ImageObserver o) { synchronized(screenLock) { return delegate.drawImage(i,t,o); } }
    public void drawImage(BufferedImage i,BufferedImageOp op,int x,int y) { synchronized(screenLock) { delegate.drawImage(i,op,x,y); } }
    public void drawRenderedImage(RenderedImage i,AffineTransform t) { synchronized(screenLock) { delegate.drawRenderedImage(i,t); } }
    public void drawRenderableImage(RenderableImage i,AffineTransform t) { synchronized(screenLock) { delegate.drawRenderableImage(i,t); } }
    public void setComposite(Composite c) { synchronized(screenLock) { delegate.setComposite(c); } }
    public Composite getComposite() { synchronized(screenLock) { return delegate.getComposite(); } }
    public void setPaint(Paint p) { synchronized(screenLock) { delegate.setPaint(p); } }
    public Paint getPaint() { synchronized(screenLock) { return delegate.getPaint(); } }
    public void setStroke(Stroke s) { synchronized(screenLock) { delegate.setStroke(s); } }
    public Stroke getStroke() { synchronized(screenLock) { return delegate.getStroke(); } }
    public void setRenderingHint(RenderingHints.Key k,Object v) { synchronized(screenLock) { delegate.setRenderingHint(k,v); } }
    public Object getRenderingHint(RenderingHints.Key k) { synchronized(screenLock) { return delegate.getRenderingHint(k); } }
    public void setRenderingHints(Map<?,?> m) { synchronized(screenLock) { delegate.setRenderingHints(m); } }
    public void addRenderingHints(Map<?,?> m) { synchronized(screenLock) { delegate.addRenderingHints(m); } }
    public RenderingHints getRenderingHints() { synchronized(screenLock) { return delegate.getRenderingHints(); } }
    public void setBackground(Color c) { synchronized(screenLock) { delegate.setBackground(c); } }
    public Color getBackground() { synchronized(screenLock) { return delegate.getBackground(); } }
    public GraphicsConfiguration getDeviceConfiguration() { synchronized(screenLock) { return delegate.getDeviceConfiguration(); } }
    public FontRenderContext getFontRenderContext() { synchronized(screenLock) { return delegate.getFontRenderContext(); } }
    public boolean hit(Rectangle r,Shape s,boolean onStroke) { synchronized(screenLock) { return delegate.hit(r,s,onStroke); } }
    public String toString() { synchronized(screenLock) { return "SynchronizedGraphics2D["+delegate+"]"; } }
}
