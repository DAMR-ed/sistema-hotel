package hn.hotel.ui;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/** Campos de formulario reutilizables y un armador de formularios simple. */
public final class Campos {
    private Campos() {}

    public static JSpinner fecha(LocalDate inicial) {
        Date d = Date.from((inicial == null ? LocalDate.now() : inicial).atStartOfDay(ZoneId.systemDefault()).toInstant());
        JSpinner s = new JSpinner(new SpinnerDateModel(d, null, null, java.util.Calendar.DAY_OF_MONTH));
        s.setEditor(new JSpinner.DateEditor(s, "dd/MM/yyyy"));
        return s;
    }

    public static LocalDate valor(JSpinner s) {
        return ((Date) s.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public static void poner(JSpinner s, LocalDate f) {
        s.setValue(Date.from(f.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    public static JSpinner entero(int valor, int min, int max) {
        return new JSpinner(new SpinnerNumberModel(valor, min, max, 1));
    }

    public static int valorEntero(JSpinner s) { return ((Number) s.getValue()).intValue(); }

    /** Formulario de dos columnas: etiqueta / campo. */
    public static final class Formulario extends JPanel {
        private final GridBagConstraints g = new GridBagConstraints();
        private int fila = 0;

        public Formulario() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
            g.insets = new Insets(4, 4, 4, 4);
        }

        public Formulario agregar(String etiqueta, JComponent campo) {
            g.gridy = fila++;
            g.gridx = 0; g.anchor = GridBagConstraints.EAST; g.fill = GridBagConstraints.NONE; g.weightx = 0;
            add(new JLabel(etiqueta), g);
            g.gridx = 1; g.anchor = GridBagConstraints.WEST; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
            add(campo, g);
            return this;
        }

        public Formulario nota(String texto) {
            g.gridy = fila++; g.gridx = 0; g.gridwidth = 2; g.anchor = GridBagConstraints.WEST;
            add(new JLabel("<html><i>" + texto + "</i></html>"), g);
            g.gridwidth = 1;
            return this;
        }
    }

    /** Muestra el formulario en un cuadro con Aceptar/Cancelar. Devuelve true si aceptó. */
    public static boolean dialogo(Component padre, String titulo, JComponent contenido, java.util.function.BooleanSupplier alAceptar) {
        Window w = padre == null ? null : SwingUtilities.getWindowAncestor(padre);
        JDialog d = new JDialog(w, titulo, Dialog.ModalityType.APPLICATION_MODAL);
        boolean[] ok = {false};
        JButton aceptar = new JButton("Aceptar");
        JButton cancelar = new JButton("Cancelar");
        aceptar.addActionListener(e -> {
            try {
                if (alAceptar.getAsBoolean()) { ok[0] = true; d.dispose(); }
            } catch (IllegalArgumentException ex) {
                Ui.error(d, ex.getMessage());
            } catch (RuntimeException ex) {
                Ui.error(d, ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
        });
        cancelar.addActionListener(e -> d.dispose());
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botones.add(cancelar); botones.add(aceptar);
        d.setLayout(new BorderLayout());
        d.add(contenido, BorderLayout.CENTER);
        d.add(botones, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(aceptar);
        d.pack();
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
        return ok[0];
    }
}
