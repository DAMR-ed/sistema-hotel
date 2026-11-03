package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Administración de las opciones (variantes) de los platos: grupos reutilizables y asignación a cada plato. */
public final class OpcionesDialog {
    private OpcionesDialog() {}

    // =================================================================== grupos y opciones
    public static void administrar(Component padre) {
        DefaultTableModel mG = Ui.modelo("Id", "Grupo", "Regla", "Opciones", "Activo");
        JTable tG = Ui.tabla(mG);
        DefaultTableModel mO = Ui.modelo("Id", "Opción", "Recargo", "Gasta del inventario", "Activa");
        JTable tO = Ui.tabla(mO);
        tG.getColumnModel().getColumn(0).setMaxWidth(40);
        tO.getColumnModel().getColumn(0).setMaxWidth(40);
        Ui.alinearDerecha(tO, 2);

        Runnable cargarO = () -> {
            int g = Ui.idSeleccionado(tG);
            if (g < 0) { mO.setRowCount(0); return; }
            Ui.llenar(mO, Db.query("SELECT o.id_opcion, o.nombre, o.recargo, "
                    + "IF(o.id_insumo IS NULL, '-', CONCAT(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM o.cantidad_insumo)), ' ', i.unidad_base, ' de ', i.nombre)) AS gasta, "
                    + "IF(o.activa=1,'Sí','No') AS act FROM opciones o LEFT JOIN insumos i ON i.id_insumo = o.id_insumo WHERE o.id_grupo = ? ORDER BY o.orden, o.nombre", g),
                    "id_opcion", "nombre", "recargo", "gasta", "act");
        };
        Runnable cargarG = () -> {
            int sel = Ui.idSeleccionado(tG);
            Ui.llenar(mG, Db.query("SELECT g.id_grupo, g.nombre, "
                    + "IF(g.min_sel >= 1, IF(g.max_sel = 1, 'Obligatorio, elegir una', CONCAT('Obligatorio, de ', g.min_sel, ' a ', g.max_sel)), "
                    + "IF(g.max_sel = 1, 'Opcional, una', CONCAT('Opcional, hasta ', g.max_sel))) AS regla, "
                    + "(SELECT COUNT(*) FROM opciones o WHERE o.id_grupo = g.id_grupo AND o.activa = 1) AS n, IF(g.activo=1,'Sí','No') AS act FROM grupos_opciones g ORDER BY g.nombre"),
                    "id_grupo", "nombre", "regla", "n", "act");
            for (int i = 0; i < mG.getRowCount(); i++) if (Integer.parseInt(mG.getValueAt(i, 0).toString()) == sel) { tG.setRowSelectionInterval(i, i); break; }
        };
        tG.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) cargarO.run(); });
        cargarG.run();

        JPanel izq = new JPanel(new BorderLayout(4, 4));
        izq.setBorder(BorderFactory.createTitledBorder("Grupos de opciones"));
        izq.add(new JScrollPane(tG), BorderLayout.CENTER);
        izq.add(Ui.fila(Ui.botonPrimario("+ Grupo", () -> { if (formGrupo(padre, null)) cargarG.run(); }),
                Ui.boton("Editar grupo", () -> { int g = Ui.idSeleccionado(tG); if (g > 0 && formGrupo(padre, g)) cargarG.run(); else if (g < 0) Ui.info(padre, "Seleccione un grupo."); })), BorderLayout.SOUTH);

        JPanel der = new JPanel(new BorderLayout(4, 4));
        der.setBorder(BorderFactory.createTitledBorder("Opciones del grupo"));
        der.add(new JScrollPane(tO), BorderLayout.CENTER);
        der.add(Ui.fila(Ui.botonPrimario("+ Opción", () -> {
                    int g = Ui.idSeleccionado(tG);
                    if (g < 0) { Ui.info(padre, "Seleccione primero un grupo."); return; }
                    if (formOpcion(padre, g, null)) { cargarO.run(); cargarG.run(); }
                }),
                Ui.boton("Editar opción", () -> {
                    int g = Ui.idSeleccionado(tG), o = Ui.idSeleccionado(tO);
                    if (g < 0 || o < 0) { Ui.info(padre, "Seleccione la opción a editar."); return; }
                    if (formOpcion(padre, g, o)) { cargarO.run(); cargarG.run(); }
                })), BorderLayout.SOUTH);

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, izq, der);
        sp.setResizeWeight(0.5);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        p.add(new JLabel("<html>Un <b>grupo</b> es una pregunta que se le hace al cliente (término de la carne, acompañante, extras). "
                + "Un mismo grupo se puede usar en varios platos.<br>Cada opción puede cobrar un recargo y gastar un ingrediente del inventario.</html>"), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(900, 480));
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(padre), "Opciones de los platos", Dialog.ModalityType.APPLICATION_MODAL);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
    }

    private static boolean formGrupo(Component padre, Integer id) {
        Map<String, Object> g = id == null ? null : Db.uno("SELECT * FROM grupos_opciones WHERE id_grupo = ?", id);
        JTextField nombre = new JTextField(g == null ? "" : Db.str(g.get("nombre")), 20);
        JSpinner min = Campos.entero(g == null ? 0 : Db.ent(g.get("min_sel")), 0, 10), max = Campos.entero(g == null ? 1 : Db.ent(g.get("max_sel")), 1, 20);
        JCheckBox activo = new JCheckBox("Activo", g == null || Db.ent(g.get("activo")) == 1);
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre del grupo:", nombre)
                .agregar("Debe elegir al menos:", min).agregar("Puede elegir como máximo:", max).agregar("", activo)
                .nota("Mínimo 0 = opcional. Mínimo 1 y máximo 1 = obligatorio, escoger una (ej. término). Máximo mayor = varias (ej. extras).");
        return Campos.dialogo(padre, id == null ? "Nuevo grupo de opciones" : "Editar grupo", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre del grupo.");
            int mn = Campos.valorEntero(min), mx = Campos.valorEntero(max);
            if (mx < mn) throw new IllegalArgumentException("El máximo no puede ser menor que el mínimo.");
            if (id == null) Db.insert("INSERT INTO grupos_opciones (nombre, min_sel, max_sel, activo) VALUES (?,?,?,?)", nombre.getText().trim(), mn, mx, activo.isSelected() ? 1 : 0);
            else Db.exec("UPDATE grupos_opciones SET nombre=?, min_sel=?, max_sel=?, activo=? WHERE id_grupo=?", nombre.getText().trim(), mn, mx, activo.isSelected() ? 1 : 0, id);
            return true;
        });
    }

    private static boolean formOpcion(Component padre, int idGrupo, Integer id) {
        Map<String, Object> o = id == null ? null : Db.uno("SELECT * FROM opciones WHERE id_opcion = ?", id);
        JTextField nombre = new JTextField(o == null ? "" : Db.str(o.get("nombre")), 20);
        JTextField recargo = new JTextField(o == null ? "0" : Db.dec(o.get("recargo")).toPlainString(), 8);
        List<Map<String, Object>> insumos = Db.query("SELECT id_insumo, CONCAT(nombre, ' (', unidad_base, ')') AS txt FROM insumos WHERE activo = 1 ORDER BY nombre");
        JComboBox<AccionesHotel.Opcion> ins = AccionesHotel.combo(insumos, "id_insumo", "txt", true);
        JTextField cant = new JTextField(o == null || o.get("cantidad_insumo") == null ? "" : Db.dec(o.get("cantidad_insumo")).stripTrailingZeros().toPlainString(), 8);
        JSpinner orden = Campos.entero(o == null ? 1 : Db.ent(o.get("orden")), 1, 99);
        JCheckBox activa = new JCheckBox("Activa", o == null || Db.ent(o.get("activa")) == 1);
        if (o != null && o.get("id_insumo") != null) AccionesHotel.seleccionar(ins, Db.ent(o.get("id_insumo")));
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre de la opción:", nombre).agregar("Recargo (L, sin ISV):", recargo)
                .agregar("Gasta del inventario:", ins).agregar("Cantidad que gasta:", cant).agregar("Orden:", orden).agregar("", activa)
                .nota("El ingrediente es opcional. Ejemplo: «Queso extra», recargo 15, gasta 30 de Queso mozzarella (en su unidad: G, ML o UND). Se descuenta por cada plato vendido.");
        return Campos.dialogo(padre, id == null ? "Nueva opción" : "Editar opción", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre de la opción.");
            BigDecimal r = Ui.leerDinero(recargo.getText().isBlank() ? "0" : recargo.getText(), "recargo");
            if (r.signum() < 0) throw new IllegalArgumentException("El recargo no puede ser negativo.");
            int in = AccionesHotel.idDe(ins);
            BigDecimal c = null;
            if (in > 0) {
                c = Ui.leerDinero(cant.getText(), "cantidad");
                if (c.signum() <= 0) throw new IllegalArgumentException("Indique cuánto gasta del ingrediente.");
            }
            Object idIn = in > 0 ? in : null;
            if (id == null) Db.insert("INSERT INTO opciones (id_grupo, nombre, recargo, id_insumo, cantidad_insumo, orden, activa) VALUES (?,?,?,?,?,?,?)",
                    idGrupo, nombre.getText().trim(), r, idIn, c, Campos.valorEntero(orden), activa.isSelected() ? 1 : 0);
            else Db.exec("UPDATE opciones SET nombre=?, recargo=?, id_insumo=?, cantidad_insumo=?, orden=?, activa=? WHERE id_opcion=?",
                    nombre.getText().trim(), r, idIn, c, Campos.valorEntero(orden), activa.isSelected() ? 1 : 0, id);
            return true;
        });
    }

    // =================================================================== asignar a un plato
    public static boolean asignar(Component padre, int idProducto, String plato) {
        List<Map<String, Object>> grupos = Db.query("SELECT g.id_grupo, g.nombre, "
                + "(SELECT GROUP_CONCAT(o.nombre ORDER BY o.orden SEPARATOR ', ') FROM opciones o WHERE o.id_grupo = g.id_grupo AND o.activa = 1) AS ops, "
                + "EXISTS (SELECT 1 FROM producto_grupos pg WHERE pg.id_grupo = g.id_grupo AND pg.id_producto = ?) AS asignado "
                + "FROM grupos_opciones g WHERE g.activo = 1 ORDER BY g.nombre", idProducto);
        if (grupos.isEmpty()) { Ui.info(padre, "Todavía no hay grupos de opciones. Cree uno en «Administrar opciones»."); return false; }
        JPanel lista = new JPanel();
        lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
        java.util.Map<JCheckBox, Integer> cajas = new java.util.LinkedHashMap<>();
        for (Map<String, Object> g : grupos) {
            JCheckBox c = new JCheckBox("<html><b>" + g.get("nombre") + "</b><br><span style='color:#64748B'>" + (g.get("ops") == null ? "(sin opciones)" : g.get("ops")) + "</span></html>",
                    Db.ent(g.get("asignado")) == 1);
            c.setBorder(BorderFactory.createEmptyBorder(4, 2, 6, 2));
            cajas.put(c, Db.ent(g.get("id_grupo")));
            lista.add(c);
        }
        JScrollPane sp = new JScrollPane(lista);
        sp.setPreferredSize(new Dimension(480, 300));
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JLabel("<html>Marque las preguntas que se hacen al pedir <b>" + plato + "</b>:</html>"), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return Campos.dialogo(padre, "Opciones de " + plato, p, () -> {
            Db.transaccion(tx -> {
                tx.exec("DELETE FROM producto_grupos WHERE id_producto = ?", idProducto);
                int orden = 1;
                for (Map.Entry<JCheckBox, Integer> e : cajas.entrySet())
                    if (e.getKey().isSelected()) tx.exec("INSERT INTO producto_grupos (id_producto, id_grupo, orden) VALUES (?,?,?)", idProducto, e.getValue(), orden++);
                return null;
            });
            return true;
        });
    }
}
