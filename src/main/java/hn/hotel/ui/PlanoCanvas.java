package hn.hotel.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Lienzo con cuadrícula para diseñar planos (habitaciones de un piso, mesas de un salón).
 * Cada elemento ocupa celdas enteras; en modo edición se arrastra y se acomoda a la cuadrícula
 * sin salirse del plano ni encimarse con otro elemento.
 */
public class PlanoCanvas extends JPanel {

    public static class Item {
        public int id;
        public String texto = "";
        public String subtexto = "";
        public int x, y, ancho = 2, alto = 2;
        public Color color = Color.LIGHT_GRAY;
        public boolean redondo = false;

        public Item(int id) { this.id = id; }
    }

    private int columnas = 20, filas = 12;
    private List<Item> items = new ArrayList<>();
    private boolean editable = false;
    private Item arrastrado, seleccionado;
    private int offsetX, offsetY;      // en celdas, dentro del elemento
    private int fantasmaX, fantasmaY;  // posición provisional al arrastrar
    private int origenX, origenY;
    private boolean moviendo;

    /** Se llama al soltar un elemento movido: (item, nuevaX, nuevaY) -> true si se aceptó (se guardó). */
    public BiPredicate<Item, int[]> alMover = (it, pos) -> true;
    public Consumer<Item> alClic = it -> { };
    public Consumer<Item> alDobleClic = it -> { };
    public Consumer<Item> alClicDerecho = it -> { };
    public Consumer<int[]> alClicVacio = pos -> { };

    public PlanoCanvas() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(900, 540));
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int cx = celdaX(e.getX()), cy = celdaY(e.getY());
                Item it = itemEn(cx, cy);
                if (SwingUtilities.isRightMouseButton(e)) {
                    seleccionado = it; repaint();
                    if (it != null) alClicDerecho.accept(it);
                    return;
                }
                seleccionado = it;
                if (it == null) {
                    repaint();
                    if (cx >= 0 && cy >= 0 && cx < columnas && cy < filas) alClicVacio.accept(new int[]{cx, cy});
                    return;
                }
                if (e.getClickCount() == 2) { alDobleClic.accept(it); return; }
                if (editable) {
                    arrastrado = it;
                    offsetX = cx - it.x; offsetY = cy - it.y;
                    origenX = it.x; origenY = it.y;
                    fantasmaX = it.x; fantasmaY = it.y;
                    moviendo = false;
                } else {
                    alClic.accept(it);
                }
                repaint();
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (arrastrado == null) return;
                moviendo = true;
                int nx = celdaX(e.getX()) - offsetX, ny = celdaY(e.getY()) - offsetY;
                nx = Math.max(0, Math.min(columnas - arrastrado.ancho, nx));
                ny = Math.max(0, Math.min(filas - arrastrado.alto, ny));
                fantasmaX = nx; fantasmaY = ny;
                repaint();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (arrastrado == null) return;
                Item it = arrastrado;
                arrastrado = null;
                if (moviendo && (fantasmaX != origenX || fantasmaY != origenY)) {
                    if (libre(it, fantasmaX, fantasmaY) && alMover.test(it, new int[]{fantasmaX, fantasmaY})) {
                        it.x = fantasmaX; it.y = fantasmaY;
                    }
                } else {
                    alClic.accept(it);
                }
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    public void setCuadricula(int columnas, int filas) {
        this.columnas = Math.max(1, columnas);
        this.filas = Math.max(1, filas);
        repaint();
    }

    public void setItems(List<Item> lista) { this.items = new ArrayList<>(lista); seleccionado = null; repaint(); }
    public void setEditable(boolean e) { this.editable = e; repaint(); }
    public boolean isEditable() { return editable; }
    public Item getSeleccionado() { return seleccionado; }
    public int getColumnas() { return columnas; }
    public int getFilas() { return filas; }

    /** ¿Cabe el elemento en (x, y) sin salirse ni chocar con otros? */
    public boolean libre(Item it, int x, int y) {
        if (x < 0 || y < 0 || x + it.ancho > columnas || y + it.alto > filas) return false;
        for (Item o : items) {
            if (o == it) continue;
            if (x < o.x + o.ancho && x + it.ancho > o.x && y < o.y + o.alto && y + it.alto > o.y) return false;
        }
        return true;
    }

    /** Primera posición libre para un elemento de ese tamaño, o null si no hay lugar. */
    public int[] buscarLugar(int ancho, int alto) {
        Item prueba = new Item(0);
        prueba.ancho = ancho; prueba.alto = alto;
        for (int y = 0; y + alto <= filas; y++)
            for (int x = 0; x + ancho <= columnas; x++)
                if (libre(prueba, x, y)) return new int[]{x, y};
        return null;
    }

    private int celda() {
        int w = getWidth() - 20, h = getHeight() - 20;
        return Math.max(24, Math.min(w / columnas, h / filas));
    }
    private int celdaX(int px) { return (px - 10) / celda() - (px < 10 ? 1 : 0); }
    private int celdaY(int py) { return (py - 10) / celda() - (py < 10 ? 1 : 0); }

    private Item itemEn(int cx, int cy) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (cx >= it.x && cx < it.x + it.ancho && cy >= it.y && cy < it.y + it.alto) return it;
        }
        return null;
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int c = celda(), ox = 10, oy = 10;

        if (editable) {
            g.setColor(new Color(0xE3E3E3));
            for (int i = 0; i <= columnas; i++) g.drawLine(ox + i * c, oy, ox + i * c, oy + filas * c);
            for (int j = 0; j <= filas; j++) g.drawLine(ox, oy + j * c, ox + columnas * c, oy + j * c);
        }
        g.setColor(new Color(0xBBBBBB));
        g.drawRect(ox, oy, columnas * c, filas * c);

        for (Item it : items) {
            if (it == arrastrado) continue;
            dibujar(g, it, ox + it.x * c, oy + it.y * c, c, it == seleccionado, false);
        }
        if (arrastrado != null) {
            boolean ok = libre(arrastrado, fantasmaX, fantasmaY);
            int px = ox + fantasmaX * c, py = oy + fantasmaY * c;
            dibujar(g, arrastrado, px, py, c, true, true);
            if (!ok) {
                g.setColor(new Color(255, 0, 0, 90));
                g.fillRect(px, py, arrastrado.ancho * c, arrastrado.alto * c);
            }
        }
        g.dispose();
    }

    private void dibujar(Graphics2D g, Item it, int px, int py, int c, boolean sel, boolean fantasma) {
        int w = it.ancho * c - 4, h = it.alto * c - 4;
        int x = px + 2, y = py + 2;
        Composite prev = g.getComposite();
        if (fantasma) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
        g.setColor(it.color);
        if (it.redondo) g.fillOval(x, y, w, h); else g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(sel ? Color.BLACK : it.color.darker());
        g.setStroke(new BasicStroke(sel ? 3f : 1.2f));
        if (it.redondo) g.drawOval(x, y, w, h); else g.drawRoundRect(x, y, w, h, 12, 12);
        g.setComposite(prev);

        g.setColor(Color.WHITE);
        Font f = g.getFont().deriveFont(Font.BOLD, Math.max(11f, Math.min(18f, c * 0.38f)));
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        String t = recortar(it.texto, fm, w - 6);
        int ty = y + h / 2 + (it.subtexto.isEmpty() ? fm.getAscent() / 2 - 2 : -2);
        g.drawString(t, x + (w - fm.stringWidth(t)) / 2, ty);
        if (!it.subtexto.isEmpty()) {
            g.setFont(f.deriveFont(Font.PLAIN, Math.max(9f, f.getSize2D() - 4f)));
            fm = g.getFontMetrics();
            String s = recortar(it.subtexto, fm, w - 6);
            g.drawString(s, x + (w - fm.stringWidth(s)) / 2, y + h / 2 + fm.getAscent());
        }
    }

    private static String recortar(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
