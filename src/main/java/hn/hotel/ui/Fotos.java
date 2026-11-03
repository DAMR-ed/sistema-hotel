package hn.hotel.ui;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** Fotos de platos: se reducen a una miniatura cuadrada JPEG (unos 20-40 KB) antes de guardarse en la base de datos. */
public final class Fotos {
    private Fotos() {}

    public static final int LADO = 360;

    /** Lee cualquier imagen (jpg, png, gif), la recorta al centro en cuadrado y la deja de 360x360 en JPEG. */
    public static byte[] desdeArchivo(File f) throws IOException {
        BufferedImage orig = ImageIO.read(f);
        if (orig == null) throw new IOException("El archivo no es una imagen válida (use JPG o PNG).");
        int lado = Math.min(orig.getWidth(), orig.getHeight());
        BufferedImage cuadro = orig.getSubimage((orig.getWidth() - lado) / 2, (orig.getHeight() - lado) / 2, lado, lado);
        int destino = Math.min(LADO, lado);
        BufferedImage rgb = new BufferedImage(destino, destino, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, destino, destino);
        g.drawImage(cuadro.getScaledInstance(destino, destino, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", o);
        return o.toByteArray();
    }

    /** Cuadro de diálogo para escoger la imagen; devuelve null si cancela. */
    public static byte[] elegir(Component padre) throws IOException {
        JFileChooser ch = new JFileChooser();
        ch.setDialogTitle("Foto del plato");
        ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Imágenes (jpg, png)", "jpg", "jpeg", "png", "gif"));
        if (ch.showOpenDialog(padre) != JFileChooser.APPROVE_OPTION) return null;
        return desdeArchivo(ch.getSelectedFile());
    }

    public static BufferedImage imagen(byte[] datos) {
        if (datos == null || datos.length == 0) return null;
        try { return ImageIO.read(new ByteArrayInputStream(datos)); }
        catch (IOException e) { return null; }
    }

    /** Valor de una columna BLOB como bytes (o null). */
    public static byte[] bytes(Object o) { return o instanceof byte[] b && b.length > 0 ? b : null; }

    /** Tarjeta de plato: foto cuadrada arriba (o una inicial si no tiene), nombre y precio. */
    public static JButton tarjeta(String nombre, String precio, BufferedImage foto, int ancho) {
        int alto = ancho + 52;
        JButton b = new JButton() {
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int m = 6, lado = Math.min(getWidth() - 2 * m, getHeight() - 56), x0 = (getWidth() - lado) / 2;
                Shape recorte = new java.awt.geom.RoundRectangle2D.Float(x0, m, lado, lado, 14, 14);
                g.setClip(recorte);
                if (foto != null) g.drawImage(foto, x0, m, lado, lado, null);
                else {
                    g.setColor(new Color(0xE8EDF5));
                    g.fillRect(x0, m, lado, lado);
                    g.setColor(new Color(0x94A3B8));
                    g.setFont(getFont().deriveFont(Font.BOLD, lado / 2.6f));
                    String ini = nombre.isBlank() ? "?" : nombre.substring(0, 1).toUpperCase();
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(ini, x0 + (lado - fm.stringWidth(ini)) / 2, m + (lado + fm.getAscent() - fm.getDescent()) / 2);
                }
                g.dispose();
            }
        };
        b.setLayout(new BorderLayout());
        JLabel txt = new JLabel("<html><div style='width:" + (ancho - 18) + "px;text-align:center'><b>" + escapar(nombre) + "</b><br>"
                + "<span style='color:#2563EB'>" + precio + "</span></div></html>", SwingConstants.CENTER);
        txt.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        txt.setPreferredSize(new Dimension(ancho, 50));
        b.add(txt, BorderLayout.SOUTH);
        b.setPreferredSize(new Dimension(ancho, alto));
        b.setFocusable(false);
        b.putClientProperty("FlatLaf.style", "arc: 18; background: #FFFFFF; borderColor: #E2E8F0; hoverBackground: #F1F5FF; pressedBackground: #DBEAFE");
        return b;
    }

    private static String escapar(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
