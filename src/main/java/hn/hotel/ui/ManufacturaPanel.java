package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;
import hn.hotel.service.ManufacturaService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Manufactura: recetas de los platos con sus medidas, preparaciones intermedias (salsas, masas, caldos...)
 * y producción de lotes. Los ingredientes se crean en Inventario.
 */
public class ManufacturaPanel extends JPanel implements Actualizable {
    // recetas de platos
    private final DefaultTableModel mPlatos = Ui.modelo("Id", "Plato", "Categoría", "Precio", "Costo", "Margen");
    private final JTable tPlatos = Ui.tabla(mPlatos);
    private final DefaultTableModel mReceta = Ui.modelo("Id insumo", "Ingrediente", "Cantidad", "Unidad", "Merma %", "Costo");
    private final JTable tReceta = Ui.tabla(mReceta);
    private final JLabel lblReceta = new JLabel("Seleccione un plato");
    private final JTextField buscarPlato = new JTextField(14);
    // preparaciones
    private final DefaultTableModel mPrep = Ui.modelo("Id", "Preparación", "Rinde por lote", "Costo del lote", "Costo por unidad", "Existencia", "Activa");
    private final JTable tPrep = Ui.tabla(mPrep);
    private final DefaultTableModel mPrepDet = Ui.modelo("Id insumo", "Ingrediente", "Cantidad", "Unidad", "Merma %", "Costo");
    private final JTable tPrepDet = Ui.tabla(mPrepDet);
    private final JLabel lblPrep = new JLabel("Seleccione una preparación");
    // historial
    private final DefaultTableModel mHist = Ui.modelo("Fecha", "Preparación", "Lotes", "Producido", "Unidad", "Costo total", "Usuario");

    public ManufacturaPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        add(new JLabel("<html><span style='font-size:20px'><b>Manufactura</b></span><br><span style='color:#64748B'>"
                + "1) Cree los ingredientes en <b>Inventario</b>. 2) Arme aquí la receta de cada plato con sus medidas. "
                + "3) Para salsas, masas o caldos que se preparan en lote, cree una <b>preparación</b> y produzca lotes.</span></html>"), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Recetas de platos", crearRecetas());
        tabs.addTab("Preparaciones", crearPreparaciones());
        tabs.addTab("Historial de producción", crearHistorial());
        tabs.addChangeListener(e -> alMostrar());
        add(tabs, BorderLayout.CENTER);
    }

    // =================================================================== recetas de platos
    private JComponent crearRecetas() {
        tPlatos.getColumnModel().getColumn(0).setMaxWidth(45);
        Ui.alinearDerecha(tPlatos, 3, 4, 5);
        JPanel izq = new JPanel(new BorderLayout(4, 4));
        izq.add(Ui.fila(new JLabel("Buscar plato:"), buscarPlato, Ui.boton("Buscar", this::cargarPlatos)), BorderLayout.NORTH);
        buscarPlato.addActionListener(e -> cargarPlatos());
        izq.add(new JScrollPane(tPlatos), BorderLayout.CENTER);

        tReceta.getColumnModel().getColumn(0).setMinWidth(0);
        tReceta.getColumnModel().getColumn(0).setMaxWidth(0);
        Ui.alinearDerecha(tReceta, 2, 4, 5);
        JPanel der = new JPanel(new BorderLayout(4, 4));
        lblReceta.setFont(lblReceta.getFont().deriveFont(Font.BOLD, 14f));
        der.add(lblReceta, BorderLayout.NORTH);
        der.add(new JScrollPane(tReceta), BorderLayout.CENTER);
        der.add(Ui.fila(Ui.botonPrimario("+ Ingrediente", () -> lineaReceta(false)),
                Ui.boton("Cambiar cantidad", () -> lineaReceta(true)),
                Ui.boton("Quitar", this::quitarLineaReceta)), BorderLayout.SOUTH);

        tPlatos.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) cargarReceta(); });
        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, izq, der);
        sp.setResizeWeight(0.5);
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private void cargarPlatos() {
        String q = "%" + buscarPlato.getText().trim() + "%";
        int sel = Ui.idSeleccionado(tPlatos);
        Ui.enSegundoPlano(this, () -> Db.query("SELECT p.id_producto, p.nombre, c.nombre AS categoria, p.precio, "
                + "COALESCE((SELECT SUM(rd.cantidad * (1 + rd.merma_pct/100) * i.costo_promedio) FROM receta_detalle rd JOIN insumos i ON i.id_insumo = rd.id_insumo WHERE rd.id_producto = p.id_producto), 0) AS costo "
                + "FROM productos_menu p JOIN categorias_menu c ON c.id_categoria = p.id_categoria WHERE p.nombre LIKE ? ORDER BY c.orden, p.nombre", q), filas -> {
            mPlatos.setRowCount(0);
            for (Map<String, Object> f : filas) {
                BigDecimal precio = Db.dec(f.get("precio")), costo = Db.dec(f.get("costo"));
                String margen = precio.signum() > 0 && costo.signum() > 0
                        ? precio.subtract(costo).multiply(BigDecimal.valueOf(100)).divide(precio, 0, RoundingMode.HALF_UP) + " %" : "-";
                mPlatos.addRow(new Object[]{f.get("id_producto"), f.get("nombre"), f.get("categoria"), Ui.numero(precio),
                        costo.signum() > 0 ? Ui.numero(costo) : "sin receta", margen});
            }
            for (int i = 0; i < mPlatos.getRowCount(); i++) if (Integer.parseInt(mPlatos.getValueAt(i, 0).toString()) == sel) { tPlatos.setRowSelectionInterval(i, i); break; }
        });
    }

    private void cargarReceta() {
        int id = Ui.idSeleccionado(tPlatos);
        mReceta.setRowCount(0);
        if (id < 0) { lblReceta.setText("Seleccione un plato"); return; }
        lblReceta.setText("Receta de: " + Db.scalar("SELECT nombre FROM productos_menu WHERE id_producto = ?", id) + "  (por 1 plato)");
        Ui.llenar(mReceta, Db.query("SELECT i.id_insumo, i.nombre, rd.cantidad, i.unidad_base, rd.merma_pct, ROUND(rd.cantidad * (1 + rd.merma_pct/100) * i.costo_promedio, 2) AS costo "
                + "FROM receta_detalle rd JOIN insumos i ON i.id_insumo = rd.id_insumo WHERE rd.id_producto = ? ORDER BY i.nombre", id),
                "id_insumo", "nombre", "cantidad", "unidad_base", "merma_pct", "costo");
    }

    private void lineaReceta(boolean editar) {
        int prod = Ui.idSeleccionado(tPlatos);
        if (prod < 0) { Ui.info(this, "Seleccione primero un plato."); return; }
        Integer insumo = null;
        if (editar) {
            int fila = tReceta.getSelectedRow();
            if (fila < 0) { Ui.info(this, "Seleccione el ingrediente a cambiar."); return; }
            insumo = Integer.parseInt(tReceta.getValueAt(fila, 0).toString());
        }
        if (dialogoIngrediente("receta_detalle", "id_producto", prod, insumo, null)) { cargarReceta(); cargarPlatos(); }
    }

    private void quitarLineaReceta() {
        int prod = Ui.idSeleccionado(tPlatos), fila = tReceta.getSelectedRow();
        if (prod < 0 || fila < 0) { Ui.info(this, "Seleccione el ingrediente a quitar."); return; }
        if (!Ui.confirmar(this, "¿Quitar este ingrediente de la receta?")) return;
        Db.exec("DELETE FROM receta_detalle WHERE id_producto = ? AND id_insumo = ?", prod, Integer.parseInt(tReceta.getValueAt(fila, 0).toString()));
        cargarReceta(); cargarPlatos();
    }

    /** Agrega o cambia un ingrediente (de una receta o de una preparación) con conversión de unidades. */
    private boolean dialogoIngrediente(String tabla, String colPadre, int idPadre, Integer insumoActual, Integer excluir) {
        List<Map<String, Object>> insumos = Db.query("SELECT id_insumo, CONCAT(nombre, ' (', unidad_base, ')') AS txt, unidad_base FROM insumos WHERE activo = 1 "
                + (excluir == null ? "" : "AND id_insumo <> " + excluir + " ") + "ORDER BY nombre");
        if (insumos.isEmpty()) { Ui.info(this, "Primero cree los ingredientes en el módulo Inventario."); return false; }
        java.util.Map<Integer, String> unidadDe = new java.util.HashMap<>();
        for (Map<String, Object> i : insumos) unidadDe.put(Db.ent(i.get("id_insumo")), Db.str(i.get("unidad_base")));
        JComboBox<AccionesHotel.Opcion> ins = AccionesHotel.combo(insumos, "id_insumo", "txt", false);
        JComboBox<Unidades.Un> un = new JComboBox<>();
        Runnable llenarUn = () -> {
            un.removeAllItems();
            for (Unidades.Un u : Unidades.para(unidadDe.getOrDefault(AccionesHotel.idDe(ins), "G"))) un.addItem(u);
        };
        ins.addActionListener(e -> llenarUn.run());
        JTextField cant = new JTextField("1", 10), merma = new JTextField("0", 6);
        if (insumoActual != null) {
            AccionesHotel.seleccionar(ins, insumoActual);
            ins.setEnabled(false);
            Map<String, Object> d = Db.uno("SELECT cantidad, merma_pct FROM " + tabla + " WHERE " + colPadre + " = ? AND id_insumo = ?", idPadre, insumoActual);
            if (d != null) { cant.setText(Db.dec(d.get("cantidad")).stripTrailingZeros().toPlainString()); merma.setText(Db.dec(d.get("merma_pct")).stripTrailingZeros().toPlainString()); }
        }
        llenarUn.run();
        Campos.Formulario f = new Campos.Formulario().agregar("Ingrediente:", ins).agregar("Cantidad:", cant).agregar("Medida:", un)
                .agregar("Merma esperada (%):", merma)
                .nota("Ejemplo: pollo a la plancha usa 250 gramos de pechuga con 8 % de merma. La cantidad se guarda en la unidad del ingrediente.");
        return Campos.dialogo(this, "Ingrediente", f, () -> {
            BigDecimal c = Ui.leerDinero(cant.getText(), "cantidad").multiply(((Unidades.Un) un.getSelectedItem()).factor()).setScale(3, RoundingMode.HALF_UP);
            BigDecimal m = Ui.leerDinero(merma.getText().isBlank() ? "0" : merma.getText(), "merma");
            if (c.signum() <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
            if (m.signum() < 0 || m.compareTo(BigDecimal.valueOf(99)) > 0) throw new IllegalArgumentException("La merma debe estar entre 0 y 99.");
            Db.exec("INSERT INTO " + tabla + " (" + colPadre + ", id_insumo, cantidad, merma_pct) VALUES (?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE cantidad = VALUES(cantidad), merma_pct = VALUES(merma_pct)", idPadre, AccionesHotel.idDe(ins), c, m);
            Bitacora.registrar("RECETA", tabla, idPadre, "insumo " + AccionesHotel.idDe(ins));
            return true;
        });
    }

    // =================================================================== preparaciones
    private JComponent crearPreparaciones() {
        tPrep.getColumnModel().getColumn(0).setMaxWidth(45);
        Ui.alinearDerecha(tPrep, 2, 3, 4, 5);
        JPanel izq = new JPanel(new BorderLayout(4, 4));
        izq.add(new JScrollPane(tPrep), BorderLayout.CENTER);
        izq.add(Ui.fila(Ui.botonPrimario("+ Nueva preparación", () -> formPreparacion(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(tPrep); if (id > 0) formPreparacion(id); else Ui.info(this, "Seleccione una preparación."); }),
                Ui.boton("Producir lote...", this::producir)), BorderLayout.SOUTH);

        tPrepDet.getColumnModel().getColumn(0).setMinWidth(0);
        tPrepDet.getColumnModel().getColumn(0).setMaxWidth(0);
        Ui.alinearDerecha(tPrepDet, 2, 4, 5);
        lblPrep.setFont(lblPrep.getFont().deriveFont(Font.BOLD, 14f));
        JPanel der = new JPanel(new BorderLayout(4, 4));
        der.add(lblPrep, BorderLayout.NORTH);
        der.add(new JScrollPane(tPrepDet), BorderLayout.CENTER);
        der.add(Ui.fila(Ui.botonPrimario("+ Ingrediente", () -> lineaPrep(false)),
                Ui.boton("Cambiar cantidad", () -> lineaPrep(true)),
                Ui.boton("Quitar", this::quitarLineaPrep)), BorderLayout.SOUTH);

        tPrep.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) cargarPrepDet(); });
        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, izq, der);
        sp.setResizeWeight(0.5);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        p.add(new JLabel("<html><span style='color:#64748B'>Una preparación se hace en lotes (ej. 1 lote de salsa = 2,000 ml). Al producir, se gastan sus ingredientes y "
                + "aumenta la existencia de la preparación, que luego se usa como ingrediente en las recetas de los platos.</span></html>"), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private void cargarPreparaciones() {
        int sel = Ui.idSeleccionado(tPrep);
        Ui.enSegundoPlano(this, () -> Db.query("SELECT p.id_preparacion, i.nombre, p.rendimiento, i.unidad_base, i.stock_actual, IF(p.activa=1,'Sí','No') AS act, "
                + "COALESCE((SELECT SUM(d.cantidad * (1 + d.merma_pct/100) * x.costo_promedio) FROM preparacion_detalle d JOIN insumos x ON x.id_insumo = d.id_insumo WHERE d.id_preparacion = p.id_preparacion), 0) AS costo_lote "
                + "FROM preparaciones p JOIN insumos i ON i.id_insumo = p.id_insumo ORDER BY i.nombre"), filas -> {
            mPrep.setRowCount(0);
            for (Map<String, Object> f : filas) {
                BigDecimal rend = Db.dec(f.get("rendimiento")), costo = Db.dec(f.get("costo_lote"));
                String u = Db.str(f.get("unidad_base"));
                mPrep.addRow(new Object[]{f.get("id_preparacion"), f.get("nombre"), rend.stripTrailingZeros().toPlainString() + " " + u,
                        Ui.numero(costo), rend.signum() > 0 ? Ui.numero(costo.divide(rend, 4, RoundingMode.HALF_UP)) + " / " + u : "-",
                        Db.dec(f.get("stock_actual")).stripTrailingZeros().toPlainString() + " " + u, f.get("act")});
            }
            for (int i = 0; i < mPrep.getRowCount(); i++) if (Integer.parseInt(mPrep.getValueAt(i, 0).toString()) == sel) { tPrep.setRowSelectionInterval(i, i); break; }
        });
    }

    private void cargarPrepDet() {
        int id = Ui.idSeleccionado(tPrep);
        mPrepDet.setRowCount(0);
        if (id < 0) { lblPrep.setText("Seleccione una preparación"); return; }
        lblPrep.setText("Ingredientes de: " + Db.scalar("SELECT i.nombre FROM preparaciones p JOIN insumos i ON i.id_insumo = p.id_insumo WHERE p.id_preparacion = ?", id) + "  (por 1 lote)");
        Ui.llenar(mPrepDet, Db.query("SELECT i.id_insumo, i.nombre, d.cantidad, i.unidad_base, d.merma_pct, ROUND(d.cantidad * (1 + d.merma_pct/100) * i.costo_promedio, 2) AS costo "
                + "FROM preparacion_detalle d JOIN insumos i ON i.id_insumo = d.id_insumo WHERE d.id_preparacion = ? ORDER BY i.nombre", id),
                "id_insumo", "nombre", "cantidad", "unidad_base", "merma_pct", "costo");
    }

    private void lineaPrep(boolean editar) {
        int prep = Ui.idSeleccionado(tPrep);
        if (prep < 0) { Ui.info(this, "Seleccione primero una preparación."); return; }
        Integer insumo = null;
        if (editar) {
            int fila = tPrepDet.getSelectedRow();
            if (fila < 0) { Ui.info(this, "Seleccione el ingrediente a cambiar."); return; }
            insumo = Integer.parseInt(tPrepDet.getValueAt(fila, 0).toString());
        }
        Object propio = Db.scalar("SELECT id_insumo FROM preparaciones WHERE id_preparacion = ?", prep);
        if (dialogoIngrediente("preparacion_detalle", "id_preparacion", prep, insumo, Db.ent(propio))) { cargarPrepDet(); cargarPreparaciones(); }
    }

    private void quitarLineaPrep() {
        int prep = Ui.idSeleccionado(tPrep), fila = tPrepDet.getSelectedRow();
        if (prep < 0 || fila < 0) { Ui.info(this, "Seleccione el ingrediente a quitar."); return; }
        if (!Ui.confirmar(this, "¿Quitar este ingrediente de la preparación?")) return;
        Db.exec("DELETE FROM preparacion_detalle WHERE id_preparacion = ? AND id_insumo = ?", prep, Integer.parseInt(tPrepDet.getValueAt(fila, 0).toString()));
        cargarPrepDet(); cargarPreparaciones();
    }

    private void formPreparacion(Integer id) {
        Map<String, Object> p = id == null ? null : Db.uno("SELECT p.rendimiento, p.activa, i.nombre, i.unidad_base FROM preparaciones p JOIN insumos i ON i.id_insumo = p.id_insumo WHERE p.id_preparacion = ?", id);
        JTextField nombre = new JTextField(p == null ? "" : Db.str(p.get("nombre")), 22);
        JComboBox<String> base = new JComboBox<>(new String[]{"G", "ML", "UND"});
        JComboBox<Unidades.Un> un = new JComboBox<>();
        JTextField rinde = new JTextField(p == null ? "1" : Db.dec(p.get("rendimiento")).stripTrailingZeros().toPlainString(), 10);
        JCheckBox activa = new JCheckBox("Activa", p == null || Db.ent(p.get("activa")) == 1);
        Runnable llenar = () -> { un.removeAllItems(); for (Unidades.Un u : Unidades.para((String) base.getSelectedItem())) un.addItem(u); };
        base.addActionListener(e -> llenar.run());
        if (p != null) {
            base.setSelectedItem(Db.str(p.get("unidad_base")));
            base.setEnabled(false);   // cambiar la unidad dañaría recetas e historial
        }
        llenar.run();
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre (ej. Salsa de la casa):", nombre)
                .agregar("Se mide en:", base).agregar("Un lote rinde:", rinde).agregar("Medida:", un).agregar("", activa)
                .nota("G = gramos, ML = mililitros, UND = unidades. Ejemplo: un lote de salsa rinde 2 litros. Después agregue sus ingredientes a la derecha.");
        Campos.dialogo(this, id == null ? "Nueva preparación" : "Editar preparación", f, () -> {
            String n = nombre.getText().trim();
            if (n.isEmpty()) throw new IllegalArgumentException("Escriba el nombre.");
            BigDecimal r = Ui.leerDinero(rinde.getText(), "rendimiento").multiply(((Unidades.Un) un.getSelectedItem()).factor()).setScale(3, RoundingMode.HALF_UP);
            if (r.signum() <= 0) throw new IllegalArgumentException("El rendimiento debe ser mayor que cero.");
            Db.transaccion(tx -> {
                if (id == null) {
                    if (tx.scalar("SELECT 1 FROM insumos WHERE nombre = ?", n) != null)
                        throw new IllegalArgumentException("Ya existe un insumo con ese nombre. Use otro nombre para la preparación.");
                    long ni = tx.insert("INSERT INTO insumos (nombre, unidad_base) VALUES (?,?)", n, base.getSelectedItem());
                    long np = tx.insert("INSERT INTO preparaciones (id_insumo, rendimiento, activa) VALUES (?,?,?)", ni, r, activa.isSelected() ? 1 : 0);
                    Bitacora.registrar("CREAR", "preparacion", np, n);
                } else {
                    Object ins = tx.scalar("SELECT id_insumo FROM preparaciones WHERE id_preparacion = ?", id);
                    if (tx.scalar("SELECT 1 FROM insumos WHERE nombre = ? AND id_insumo <> ?", n, ins) != null)
                        throw new IllegalArgumentException("Ya existe un insumo con ese nombre.");
                    tx.exec("UPDATE insumos SET nombre = ? WHERE id_insumo = ?", n, ins);
                    tx.exec("UPDATE preparaciones SET rendimiento = ?, activa = ? WHERE id_preparacion = ?", r, activa.isSelected() ? 1 : 0, id);
                    Bitacora.registrar("EDITAR", "preparacion", id, n);
                }
                return null;
            });
            return true;
        });
        cargarPreparaciones();
    }

    private void producir() {
        int id = Ui.idSeleccionado(tPrep);
        if (id < 0) { Ui.info(this, "Seleccione la preparación a producir."); return; }
        Map<String, Object> p = Db.uno("SELECT i.nombre, i.unidad_base, p.rendimiento FROM preparaciones p JOIN insumos i ON i.id_insumo = p.id_insumo WHERE p.id_preparacion = ?", id);
        BigDecimal costoLote = ManufacturaService.costoLote(id);
        JTextField lotes = new JTextField("1", 8), notas = new JTextField(22);
        JLabel resumen = new JLabel(" ");
        Runnable calc = () -> {
            try {
                BigDecimal l = Ui.leerDinero(lotes.getText(), "lotes");
                resumen.setText("Producirá " + Db.dec(p.get("rendimiento")).multiply(l).stripTrailingZeros().toPlainString() + " " + p.get("unidad_base")
                        + " · costo aprox. " + Ui.lempiras(costoLote.multiply(l)));
            } catch (RuntimeException e) { resumen.setText(" "); }
        };
        lotes.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { calc.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { calc.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { calc.run(); }
        });
        calc.run();
        Campos.Formulario f = new Campos.Formulario().agregar("Lotes a producir:", lotes).agregar("", resumen).agregar("Nota (opcional):", notas);
        boolean ok = Campos.dialogo(this, "Producir " + p.get("nombre"), f, () -> {
            BigDecimal l = Ui.leerDinero(lotes.getText(), "lotes");
            BigDecimal prod = ManufacturaService.producir(id, l, notas.getText());
            Ui.info(this, "Listo: se produjeron " + prod.stripTrailingZeros().toPlainString() + " " + p.get("unidad_base") + " de " + p.get("nombre") + ".");
            return true;
        });
        if (ok) { cargarPreparaciones(); cargarPrepDet(); }
    }

    // =================================================================== historial
    private JComponent crearHistorial() {
        JTable t = Ui.tabla(mHist);
        Ui.alinearDerecha(t, 2, 3, 5);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        p.add(new JScrollPane(t), BorderLayout.CENTER);
        p.add(Ui.fila(Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, mHist, "produccion_" + java.time.LocalDate.now()))), BorderLayout.SOUTH);
        return p;
    }

    private void cargarHistorial() {
        Ui.enSegundoPlano(this, () -> Db.query("SELECT pr.fecha, i.nombre, pr.lotes, pr.cantidad, i.unidad_base, pr.costo_total, COALESCE(u.nombre, '') AS usuario "
                + "FROM producciones pr JOIN preparaciones p ON p.id_preparacion = pr.id_preparacion JOIN insumos i ON i.id_insumo = p.id_insumo "
                + "LEFT JOIN usuarios u ON u.id_usuario = pr.id_usuario ORDER BY pr.id_produccion DESC LIMIT 300"),
            filas -> Ui.llenar(mHist, filas, "fecha", "nombre", "lotes", "cantidad", "unidad_base", "costo_total", "usuario"));
    }

    @Override public void alMostrar() {
        cargarPlatos();
        cargarPreparaciones();
        cargarHistorial();
    }
}
