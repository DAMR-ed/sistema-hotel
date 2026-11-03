package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.util.Map;

/** Catálogo de servicios del hotel (lavandería, transporte, minibar...). */
public final class ServiciosDialog {
    private ServiciosDialog() {}

    public static void mostrar(Component padre) {
        DefaultTableModel m = Ui.modelo("Id", "Servicio", "Precio", "Impuesto", "Activo");
        JTable t = Ui.tabla(m);
        t.getColumnModel().getColumn(0).setMaxWidth(50);
        Runnable cargar = () -> Ui.llenar(m, Db.query("SELECT id_servicio, nombre, precio, tratamiento_fiscal, IF(activo=1,'Sí','No') AS act FROM servicios ORDER BY nombre"),
                "id_servicio", "nombre", "precio", "tratamiento_fiscal", "act");
        cargar.run();
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(520, 260));
        p.add(sp, BorderLayout.CENTER);
        p.add(Ui.fila(Ui.boton("Nuevo", () -> { if (editar(padre, null)) cargar.run(); }),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(t); if (id > 0 && editar(padre, id)) cargar.run(); })), BorderLayout.SOUTH);
        Window w = padre == null ? null : SwingUtilities.getWindowAncestor(padre);
        JDialog d = new JDialog(w, "Catálogo de servicios", Dialog.ModalityType.APPLICATION_MODAL);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
    }

    private static boolean editar(Component padre, Integer id) {
        Map<String, Object> r = id == null ? null : Db.uno("SELECT * FROM servicios WHERE id_servicio = ?", id);
        JTextField nombre = new JTextField(r == null ? "" : Db.str(r.get("nombre")), 20);
        JTextField precio = new JTextField(r == null ? "" : Db.dec(r.get("precio")).toPlainString(), 10);
        JComboBox<String> trat = new JComboBox<>(new String[]{"GRAVADO", "EXENTO"});
        if (r != null) trat.setSelectedItem(Db.str(r.get("tratamiento_fiscal")));
        JCheckBox activo = new JCheckBox("Activo", r == null || Db.ent(r.get("activo")) == 1);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre:", nombre).agregar("Precio (L, sin ISV):", precio).agregar("Tratamiento fiscal:", trat).agregar("", activo)
         .nota("GRAVADO paga ISV. EXENTO solo si la ley lo exime; consulte a su contador.");
        return Campos.dialogo(padre, id == null ? "Nuevo servicio" : "Editar servicio", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            BigDecimal pr = Ui.leerDinero(precio.getText(), "precio");
            if (pr.signum() < 0) throw new IllegalArgumentException("El precio no puede ser negativo.");
            if (id == null) Db.insert("INSERT INTO servicios (nombre, precio, tratamiento_fiscal, activo) VALUES (?,?,?,?)",
                    nombre.getText().trim(), pr, trat.getSelectedItem(), activo.isSelected() ? 1 : 0);
            else Db.exec("UPDATE servicios SET nombre=?, precio=?, tratamiento_fiscal=?, activo=? WHERE id_servicio=?",
                    nombre.getText().trim(), pr, trat.getSelectedItem(), activo.isSelected() ? 1 : 0, id);
            return true;
        });
    }
}
