package hn.hotel.ui;

import hn.hotel.service.OpcionesService.Grupo;
import hn.hotel.service.OpcionesService.Opcion;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Ventana que aparece al tocar un plato con opciones: "¿Cómo lo arma?" (término, acompañante, extras...). */
public final class ElegirOpcionesDialog {
    private ElegirOpcionesDialog() {}

    /** Devuelve los ids de las opciones elegidas, o null si el mesero cancela. */
    public static List<Integer> mostrar(Component padre, String plato, List<Grupo> grupos) {
        JPanel cont = new JPanel();
        cont.setLayout(new BoxLayout(cont, BoxLayout.Y_AXIS));
        List<List<AbstractButton>> botones = new ArrayList<>();
        List<List<Opcion>> opcionesDe = new ArrayList<>();

        for (Grupo g : grupos) {
            JPanel caja = new JPanel(new GridLayout(0, 2, 8, 4));
            String regla = g.min() >= 1 && g.max() == 1 ? "elija una" : g.min() >= 1 ? "elija de " + g.min() + " a " + g.max()
                    : g.max() == 1 ? "opcional, una" : "opcional, hasta " + g.max();
            caja.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(g.nombre() + "  (" + regla + ")"),
                    BorderFactory.createEmptyBorder(2, 6, 6, 6)));
            ButtonGroup bg = new ButtonGroup();
            List<AbstractButton> bs = new ArrayList<>();
            boolean unica = g.max() == 1;
            for (Opcion o : g.opciones()) {
                String txt = o.nombre() + (o.recargo().signum() > 0 ? "   + " + Ui.lempiras(o.recargo()) : "");
                AbstractButton b = unica ? new JRadioButton(txt) : new JCheckBox(txt);
                b.setFont(b.getFont().deriveFont(14f));
                if (unica) bg.add(b);
                caja.add(b);
                bs.add(b);
            }
            if (unica && g.min() == 0) {   // opcional de una sola: permitir "ninguna"
                JRadioButton ninguna = new JRadioButton("Ninguna", true);
                bg.add(ninguna);
                caja.add(ninguna);
            } else if (unica && !bs.isEmpty()) bs.get(0).setSelected(true);
            botones.add(bs);
            opcionesDe.add(g.opciones());
            caja.setAlignmentX(Component.LEFT_ALIGNMENT);
            cont.add(caja);
        }
        JScrollPane sp = new JScrollPane(cont);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setPreferredSize(new Dimension(520, Math.min(460, 90 + grupos.size() * 110)));
        sp.getVerticalScrollBar().setUnitIncrement(20);

        List<Integer> resultado = new ArrayList<>();
        boolean ok = Campos.dialogo(padre, plato + " - ¿cómo lo desea?", sp, () -> {
            resultado.clear();
            for (int i = 0; i < grupos.size(); i++) {
                Grupo g = grupos.get(i);
                int n = 0;
                for (int j = 0; j < botones.get(i).size(); j++)
                    if (botones.get(i).get(j).isSelected()) { resultado.add(opcionesDe.get(i).get(j).id()); n++; }
                if (n < g.min()) throw new IllegalArgumentException("Falta elegir: " + g.nombre() + ".");
                if (n > g.max()) throw new IllegalArgumentException(g.nombre() + ": máximo " + g.max() + ".");
            }
            return true;
        });
        return ok ? resultado : null;
    }
}
