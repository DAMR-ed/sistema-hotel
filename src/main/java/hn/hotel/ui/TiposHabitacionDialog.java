package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.util.Map;

/** Catálogo de tipos de habitación: nombre, precio por noche, capacidad y color. */
public final class TiposHabitacionDialog {
    private TiposHabitacionDialog() {}

    public static void mostrar(Component padre) {
        DefaultTableModel m = Ui.modelo("Id", "Tipo", "Precio/noche", "Capacidad", "Color", "Activo");
        JTable t = Ui.tabla(m);
        t.getColumnModel().getColumn(0).setMaxWidth(50);
        Runnable cargar = () -> Ui.llenar(m, Db.query("SELECT id_tipo, nombre, precio_noche, capacidad, color, IF(activo=1,'Sí','No') AS act "
                + "FROM tipos_habitacion ORDER BY nombre"), "id_tipo", "nombre", "precio_noche", "capacidad", "color", "act");
        cargar.run();
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(560, 260));
        p.add(sp, BorderLayout.CENTER);
        p.add(Ui.fila(
                Ui.boton("Nuevo", () -> { if (editar(padre, null)) cargar.run(); }),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(t); if (id > 0 && editar(padre, id)) cargar.run(); })),
              BorderLayout.SOUTH);
        Window w = padre == null ? null : SwingUtilities.getWindowAncestor(padre);
        JDialog d = new JDialog(w, "Tipos de habitación", Dialog.ModalityType.APPLICATION_MODAL);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
    }

    private static boolean editar(Component padre, Integer id) {
        Map<String, Object> r = id == null ? null : Db.uno("SELECT * FROM tipos_habitacion WHERE id_tipo = ?", id);
        JTextField nombre = new JTextField(r == null ? "" : Db.str(r.get("nombre")), 20);
        JTextField desc = new JTextField(r == null ? "" : Db.str(r.get("descripcion")), 24);
        JTextField precio = new JTextField(r == null ? "" : Db.dec(r.get("precio_noche")).toPlainString(), 10);
        JSpinner cap = Campos.entero(r == null ? 2 : Db.ent(r.get("capacidad")), 1, 20);
        JCheckBox activo = new JCheckBox("Activo", r == null || Db.ent(r.get("activo")) == 1);
        String[] color = {r == null ? "#4A90D9" : Db.str(r.get("color"))};
        JButton btnColor = new JButton("   Elegir color   ");
        btnColor.setBackground(Ui.colorHex(color[0], Color.LIGHT_GRAY));
        btnColor.addActionListener(e -> {
            Color c = JColorChooser.showDialog(padre, "Color del tipo", Ui.colorHex(color[0], Color.LIGHT_GRAY));
            if (c != null) {
                color[0] = String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
                btnColor.setBackground(c);
            }
        });
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre:", nombre).agregar("Descripción:", desc).agregar("Precio por noche (L):", precio)
         .agregar("Capacidad (personas):", cap).agregar("Color en el plano:", btnColor).agregar("", activo);
        return Campos.dialogo(padre, id == null ? "Nuevo tipo" : "Editar tipo", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre del tipo.");
            BigDecimal pr = Ui.leerDinero(precio.getText(), "precio");
            if (pr.signum() < 0) throw new IllegalArgumentException("El precio no puede ser negativo.");
            String d = desc.getText().isBlank() ? null : desc.getText().trim();
            if (id == null) {
                long nid = Db.insert("INSERT INTO tipos_habitacion (nombre, descripcion, precio_noche, capacidad, color, activo) VALUES (?,?,?,?,?,?)",
                        nombre.getText().trim(), d, pr, Campos.valorEntero(cap), color[0], activo.isSelected() ? 1 : 0);
                Bitacora.registrar("CREAR", "tipo_habitacion", nid, nombre.getText().trim());
            } else {
                Db.exec("UPDATE tipos_habitacion SET nombre=?, descripcion=?, precio_noche=?, capacidad=?, color=?, activo=? WHERE id_tipo=?",
                        nombre.getText().trim(), d, pr, Campos.valorEntero(cap), color[0], activo.isSelected() ? 1 : 0, id);
                Bitacora.registrar("EDITAR", "tipo_habitacion", id, nombre.getText().trim() + " L" + pr);
            }
            return true;
        });
    }
}
