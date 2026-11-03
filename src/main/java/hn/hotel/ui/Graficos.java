package hn.hotel.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/** Gráficos sencillos dibujados con Swing (sin librerías): barras verticales, barras horizontales y dona. */
public final class Graficos {
    private Graficos() {}

    public static final Color[] PALETA = {new Color(0x2563EB), new Color(0x10B981), new Color(0xF59E0B), new Color(0x8B5CF6),
            new Color(0xEF4444), new Color(0x06B6D4), new Color(0xEC4899), new Color(0x64748B)};

    public static final DoubleFunction<String> MONEDA = v -> "L " + String.format("%,.2f", v);
    public static final DoubleFunction<String> ENTERO = v -> String.format("%,.0f", v);

    static String corto(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) return String.format("%.1fM", v / 1_000_000);
        if (a >= 1_000) return String.format("%.1fk", v / 1_000).replace(".0k", "k");
        return String.format("%.0f", v);
    }

    static double techoBonito(double max) {
        if (max <= 0) return 1;
        double exp = Math.pow(10, Math.floor(Math.log10(max)));
        double f = max / exp;
        double n = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
        return n * exp;
    }

    static String recortar(String s, FontMetrics fm, int ancho) {
        if (fm.stringWidth(s) <= ancho) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > ancho) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    static void vacio(Graphics2D g, int w, int h) {
        g.setColor(Tema.TEXTO_SUAVE);
        String t = "Sin datos en el período";
        g.drawString(t, (w - g.getFontMetrics().stringWidth(t)) / 2, h / 2);
    }

    // ------------------------------------------------------------------ barras
    public static class Barras extends JComponent {
        private List<String> etiquetas = List.of();
        private List<Double> valores = List.of();
        private final boolean horizontal;
        private final DoubleFunction<String> formato;
        private final Color color;

        public Barras(boolean horizontal, DoubleFunction<String> formato, Color color) {
            this.horizontal = horizontal; this.formato = formato; this.color = color;
            setPreferredSize(new Dimension(340, 230));
            setToolTipText("");
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { setToolTipText(textoEn(e.getX(), e.getY())); }
            });
        }

        public void datos(List<String> et, List<? extends Number> val) {
            etiquetas = new ArrayList<>(et);
            valores = new ArrayList<>();
            for (Number n : val) valores.add(n == null ? 0.0 : n.doubleValue());
            repaint();
        }

        private int izq() { return horizontal ? 118 : 46; }
        private int abajo() { return horizontal ? 8 : 26; }

        private String textoEn(int x, int y) {
            int n = valores.size();
            if (n == 0) return null;
            int w = getWidth() - izq() - 12, h = getHeight() - abajo() - 12;
            int i = horizontal ? (int) ((y - 6) / (h / (double) n)) : (int) ((x - izq()) / (w / (double) n));
            return i >= 0 && i < n ? etiquetas.get(i) + ": " + formato.apply(valores.get(i)) : null;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont((getFont() != null ? getFont() : new Font("SansSerif", Font.PLAIN, 12)).deriveFont(11f));
            FontMetrics fm = g.getFontMetrics();
            int n = valores.size();
            if (n == 0 || valores.stream().allMatch(v -> v == 0)) { vacio(g, getWidth(), getHeight()); g.dispose(); return; }
            double max = techoBonito(valores.stream().mapToDouble(Double::doubleValue).max().orElse(1));
            int x0 = izq(), y0 = 8, w = getWidth() - x0 - 12, h = getHeight() - abajo() - y0 - 4;
            if (!horizontal) {
                for (int k = 0; k <= 4; k++) {
                    int y = y0 + h - (int) (h * k / 4.0);
                    g.setColor(Tema.BORDE);
                    g.drawLine(x0, y, x0 + w, y);
                    g.setColor(Tema.TEXTO_SUAVE);
                    String t = corto(max * k / 4);
                    g.drawString(t, x0 - 6 - fm.stringWidth(t), y + 4);
                }
                double slot = w / (double) n;
                int bw = (int) Math.max(3, Math.min(46, slot * 0.62));
                int cada = (int) Math.ceil(n / Math.max(1.0, w / 44.0));
                for (int i = 0; i < n; i++) {
                    int bh = (int) (h * (valores.get(i) / max));
                    int bx = x0 + (int) (slot * i + (slot - bw) / 2);
                    g.setColor(color);
                    g.fillRoundRect(bx, y0 + h - bh, bw, Math.max(bh, 0), 5, 5);
                    if (i % cada == 0) {
                        g.setColor(Tema.TEXTO_SUAVE);
                        String e = recortar(etiquetas.get(i), fm, (int) (slot * cada));
                        g.drawString(e, bx + bw / 2 - fm.stringWidth(e) / 2, y0 + h + 16);
                    }
                }
            } else {
                double slot = h / (double) n;
                int bh = (int) Math.max(6, Math.min(24, slot * 0.6));
                for (int i = 0; i < n; i++) {
                    int by = y0 + (int) (slot * i + (slot - bh) / 2);
                    int bw = (int) (w * (valores.get(i) / max));
                    g.setColor(Tema.TEXTO);
                    g.drawString(recortar(etiquetas.get(i), fm, x0 - 10), 4, by + bh / 2 + 4);
                    g.setColor(color);
                    g.fillRoundRect(x0, by, Math.max(bw, 2), bh, 5, 5);
                    g.setColor(Tema.TEXTO_SUAVE);
                    String t = formato.apply(valores.get(i));
                    int tx = x0 + bw + 6;
                    if (tx + fm.stringWidth(t) > getWidth() - 2) { tx = x0 + bw - fm.stringWidth(t) - 6; g.setColor(Color.WHITE); }
                    g.drawString(t, tx, by + bh / 2 + 4);
                }
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ dona
    public static class Dona extends JComponent {
        private List<String> etiquetas = List.of();
        private List<Double> valores = List.of();
        private final DoubleFunction<String> formato;

        public Dona(DoubleFunction<String> formato) { this.formato = formato; setPreferredSize(new Dimension(340, 230)); }

        public void datos(List<String> et, List<? extends Number> val) {
            etiquetas = new ArrayList<>(); valores = new ArrayList<>();
            for (int i = 0; i < et.size(); i++) {
                double v = val.get(i) == null ? 0 : val.get(i).doubleValue();
                if (v > 0) { etiquetas.add(et.get(i)); valores.add(v); }
            }
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont((getFont() != null ? getFont() : new Font("SansSerif", Font.PLAIN, 12)).deriveFont(11f));
            FontMetrics fm = g.getFontMetrics();
            double total = valores.stream().mapToDouble(Double::doubleValue).sum();
            if (total <= 0) { vacio(g, getWidth(), getHeight()); g.dispose(); return; }
            int d = Math.min(getHeight() - 16, (int) (getWidth() * 0.46));
            int x = 8, y = (getHeight() - d) / 2;
            double ang = 90;
            for (int i = 0; i < valores.size(); i++) {
                double ext = -360.0 * valores.get(i) / total;
                g.setColor(PALETA[i % PALETA.length]);
                g.fill(new Arc2D.Double(x, y, d, d, ang, ext, Arc2D.PIE));
                ang += ext;
            }
            int hueco = (int) (d * 0.58);
            g.setColor(Tema.TARJETA);
            g.fillOval(x + (d - hueco) / 2, y + (d - hueco) / 2, hueco, hueco);
            g.setColor(Tema.TEXTO);
            Font f = g.getFont();
            g.setFont(f.deriveFont(Font.BOLD, 12f));
            String t = corto(total);
            g.drawString(t, x + d / 2 - g.getFontMetrics().stringWidth(t) / 2, y + d / 2 + 4);
            g.setFont(f);
            int lx = x + d + 16, ly = Math.max(14, (getHeight() - Math.min(8, valores.size()) * 29) / 2 + 12);
            for (int i = 0; i < valores.size() && i < 8; i++) {
                g.setColor(PALETA[i % PALETA.length]);
                g.fillRoundRect(lx, ly - 9, 11, 11, 3, 3);
                g.setColor(Tema.TEXTO);
                String pct = String.format("%.0f%%", 100 * valores.get(i) / total);
                g.drawString(recortar(etiquetas.get(i), fm, getWidth() - lx - 20 - 44), lx + 17, ly);
                g.setColor(Tema.TEXTO_SUAVE);
                g.drawString(pct, getWidth() - 8 - fm.stringWidth(pct), ly);
                g.drawString(formato.apply(valores.get(i)), lx + 17, ly + 12);
                ly += 29;
            }
            g.dispose();
        }
    }

    public static List<Double> comoDoubles(List<? extends Number> l) {
        List<Double> r = new ArrayList<>();
        for (Number n : l) r.add(n == null ? 0.0 : n.doubleValue());
        return r;
    }

    public static double aDouble(Object o) { return o instanceof BigDecimal b ? b.doubleValue() : o instanceof Number n ? n.doubleValue() : 0; }
}
