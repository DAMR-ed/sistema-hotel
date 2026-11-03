package hn.hotel.ui;

import javax.swing.*;
import java.awt.*;

/** FlowLayout que baja a la siguiente línea cuando no cabe más a lo ancho (así se desplaza hacia abajo, no a la derecha). */
public class WrapLayout extends FlowLayout {
    public WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

    @Override public Dimension preferredLayoutSize(Container target) { return medir(target, true); }

    @Override public Dimension minimumLayoutSize(Container target) {
        Dimension d = medir(target, false);
        d.width -= getHgap() + 1;
        return d;
    }

    private Dimension medir(Container target, boolean preferido) {
        synchronized (target.getTreeLock()) {
            int ancho = target.getSize().width;
            Container c = target;
            while (c.getSize().width == 0 && c.getParent() != null) c = c.getParent();
            ancho = c.getSize().width;
            if (ancho == 0) ancho = Integer.MAX_VALUE;
            Insets in = target.getInsets();
            int horizontal = getHgap() * 2 + in.left + in.right;
            int maxAncho = ancho - horizontal;
            Dimension dim = new Dimension(0, 0);
            int filaAncho = 0, filaAlto = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) continue;
                Dimension d = preferido ? m.getPreferredSize() : m.getMinimumSize();
                if (filaAncho + d.width > maxAncho) {
                    agregarFila(dim, filaAncho, filaAlto);
                    filaAncho = 0;
                    filaAlto = 0;
                }
                if (filaAncho != 0) filaAncho += getHgap();
                filaAncho += d.width;
                filaAlto = Math.max(filaAlto, d.height);
            }
            agregarFila(dim, filaAncho, filaAlto);
            dim.width += horizontal;
            dim.height += in.top + in.bottom + getVgap() * 2;
            Container padre = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (padre != null && target.isValid()) dim.width -= getHgap() + 1;
            return dim;
        }
    }

    private void agregarFila(Dimension dim, int filaAncho, int filaAlto) {
        dim.width = Math.max(dim.width, filaAncho);
        if (dim.height > 0) dim.height += getVgap();
        dim.height += filaAlto;
    }
}
