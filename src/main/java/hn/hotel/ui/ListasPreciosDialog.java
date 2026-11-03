package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Listas de precios: la «General» es el precio del menú; las demás guardan solo los precios distintos. */
public final class ListasPreciosDialog {
    private ListasPreciosDialog() {}

    public static void mostrar(Component padre) {
        DefaultTableModel mL = Ui.modelo("Id", "Lista", "Activa", "Precios propios");
        JTable tL = Ui.tabla(mL);
        tL.getColumnModel().getColumn(0).setMaxWidth(40);
        DefaultTableModel mP = Ui.modelo("Id", "Plato", "Precio general", "Precio en la lista");
        JTable tP = Ui.tabla(mP);
        tP.getColumnModel().getColumn(0).setMaxWidth(40);
        Ui.alinearDerecha(tP, 2, 3);

        Runnable cargarP = () -> {
            int l = Ui.idSeleccionado(tL);
            mP.setRowCount(0);
            if (l < 0) return;
            Ui.llenar(mP, Db.query("SELECT p.id_producto, p.nombre, p.precio, COALESCE(CAST(pl.precio AS CHAR), '') AS esp FROM productos_menu p "
                    + "LEFT JOIN precios_lista pl ON pl.id_producto = p.id_producto AND pl.id_lista = ? WHERE p.activo = 1 ORDER BY p.nombre", l),
                    "id_producto", "nombre", "precio", "esp");
        };
        Runnable cargarL = () -> {
            int sel = Ui.idSeleccionado(tL);
            Ui.llenar(mL, Db.query("SELECT l.id_lista, l.nombre, IF(l.activa=1,'Sí','No') AS act, (SELECT COUNT(*) FROM precios_lista pl WHERE pl.id_lista = l.id_lista) AS n "
                    + "FROM listas_precios l ORDER BY l.nombre"), "id_lista", "nombre", "act", "n");
            for (int i = 0; i < mL.getRowCount(); i++) if (Integer.parseInt(mL.getValueAt(i, 0).toString()) == sel) { tL.setRowSelectionInterval(i, i); break; }
        };
        tL.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) cargarP.run(); });
        cargarL.run();

        JPanel izq = new JPanel(new BorderLayout(4, 4));
        izq.setBorder(BorderFactory.createTitledBorder("Listas"));
        izq.add(new JScrollPane(tL), BorderLayout.CENTER);
        izq.add(Ui.fila(Ui.botonPrimario("+ Lista", () -> { if (formLista(padre, null)) cargarL.run(); }),
                Ui.boton("Renombrar / activar", () -> { int l = Ui.idSeleccionado(tL); if (l > 0 && formLista(padre, l)) cargarL.run(); })), BorderLayout.SOUTH);

        JPanel der = new JPanel(new BorderLayout(4, 4));
        der.setBorder(BorderFactory.createTitledBorder("Precios de la lista seleccionada"));
        der.add(new JScrollPane(tP), BorderLayout.CENTER);
        der.add(Ui.fila(Ui.botonPrimario("Poner precio", () -> {
                    int l = Ui.idSeleccionado(tL), p = Ui.idSeleccionado(tP);
                    if (l < 0 || p < 0) { Ui.info(padre, "Seleccione una lista y un plato."); return; }
                    if (precio(padre, l, p, String.valueOf(tP.getValueAt(tP.getSelectedRow(), 1)))) { cargarP.run(); cargarL.run(); }
                }),
                Ui.boton("Quitar (usar precio general)", () -> {
                    int l = Ui.idSeleccionado(tL), p = Ui.idSeleccionado(tP);
                    if (l < 0 || p < 0) return;
                    Db.exec("DELETE FROM precios_lista WHERE id_lista = ? AND id_producto = ?", l, p);
                    cargarP.run(); cargarL.run();
                }),
                Ui.boton("Aplicar % a todos...", () -> {
                    int l = Ui.idSeleccionado(tL);
                    if (l < 0) { Ui.info(padre, "Seleccione una lista."); return; }
                    if (porcentaje(padre, l)) { cargarP.run(); cargarL.run(); }
                })), BorderLayout.SOUTH);

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, izq, der);
        sp.setResizeWeight(0.35);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        p.add(new JLabel("<html>El <b>precio general</b> es el de «Menú y recetas». Cada lista guarda solo los platos que cambian de precio. "
                + "Una zona del restaurante puede usar una lista por defecto, y el POS puede cambiarla por cuenta.</html>"), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(860, 480));
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(padre), "Listas de precios", Dialog.ModalityType.APPLICATION_MODAL);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
    }

    private static boolean formLista(Component padre, Integer id) {
        Map<String, Object> l = id == null ? null : Db.uno("SELECT * FROM listas_precios WHERE id_lista = ?", id);
        JTextField nombre = new JTextField(l == null ? "" : Db.str(l.get("nombre")), 18);
        JCheckBox activa = new JCheckBox("Activa", l == null || Db.ent(l.get("activa")) == 1);
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre:", nombre).agregar("", activa);
        return Campos.dialogo(padre, id == null ? "Nueva lista de precios" : "Editar lista", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            if (id == null) Db.insert("INSERT INTO listas_precios (nombre, activa) VALUES (?,?)", nombre.getText().trim(), activa.isSelected() ? 1 : 0);
            else Db.exec("UPDATE listas_precios SET nombre=?, activa=? WHERE id_lista=?", nombre.getText().trim(), activa.isSelected() ? 1 : 0, id);
            return true;
        });
    }

    private static boolean precio(Component padre, int lista, int producto, String plato) {
        JTextField pr = new JTextField(10);
        Campos.Formulario f = new Campos.Formulario().agregar("Precio de " + plato + " (L, sin ISV):", pr);
        return Campos.dialogo(padre, "Precio en la lista", f, () -> {
            BigDecimal v = Ui.leerDinero(pr.getText(), "precio");
            if (v.signum() < 0) throw new IllegalArgumentException("El precio no puede ser negativo.");
            Db.exec("INSERT INTO precios_lista (id_lista, id_producto, precio) VALUES (?,?,?) ON DUPLICATE KEY UPDATE precio = VALUES(precio)", lista, producto, v);
            return true;
        });
    }

    /** Ej.: -20 para happy hour (20 % menos), 10 para delivery (10 % más). Reemplaza los precios de esa lista. */
    private static boolean porcentaje(Component padre, int lista) {
        JTextField pct = new JTextField("-20", 6);
        Campos.Formulario f = new Campos.Formulario().agregar("Porcentaje (+ sube, − baja):", pct)
                .nota("Calcula el precio de TODOS los platos activos a partir del precio general y reemplaza los precios actuales de esta lista.");
        return Campos.dialogo(padre, "Aplicar porcentaje", f, () -> {
            BigDecimal v = Ui.leerDinero(pct.getText(), "porcentaje");
            if (v.compareTo(BigDecimal.valueOf(-100)) <= 0) throw new IllegalArgumentException("El descuento no puede ser 100 % o más.");
            BigDecimal factor = BigDecimal.ONE.add(v.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            List<Map<String, Object>> prods = Db.query("SELECT id_producto, precio FROM productos_menu WHERE activo = 1");
            Db.transaccion(tx -> {
                for (Map<String, Object> p : prods)
                    tx.exec("INSERT INTO precios_lista (id_lista, id_producto, precio) VALUES (?,?,?) ON DUPLICATE KEY UPDATE precio = VALUES(precio)",
                            lista, p.get("id_producto"), Db.dec(p.get("precio")).multiply(factor).setScale(2, RoundingMode.HALF_UP));
                return null;
            });
            return true;
        });
    }
}
