package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.util.Map;

/** Autoconsumos: motivos y reporte. El autoconsumo se registra desde el POS (no genera venta, descarga inventario). */
public class AutoconsumoPanel extends JPanel implements Actualizable {
    private final DefaultTableModel mRep = Ui.modelo("Id", "Fecha", "Cuenta", "Motivo", "Platos", "Registró", "Autorizó", "Costo", "Notas");
    private final DefaultTableModel mMotivo = Ui.modelo("Motivo", "Autoconsumos", "Costo");
    private final DefaultTableModel mProd = Ui.modelo("Producto", "Cantidad", "Costo aprox.");
    private final DefaultTableModel mMotivos = Ui.modelo("Id", "Motivo", "Activo");
    private final JTable tRep = Ui.tabla(mRep), tMotivos = Ui.tabla(mMotivos);
    private final JSpinner desde = Campos.fecha(LocalDate.now().withDayOfMonth(1)), hasta = Campos.fecha(LocalDate.now());
    private final JLabel lblTotal = new JLabel(" ");

    public AutoconsumoPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Autoconsumos"), BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();

        JPanel rep = new JPanel(new BorderLayout(6, 6));
        rep.add(Ui.fila(new JLabel("Desde:"), desde, new JLabel("hasta:"), hasta, Ui.boton("Mostrar", this::alMostrar),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, mRep, "autoconsumos_" + LocalDate.now())), lblTotal), BorderLayout.NORTH);
        tRep.getColumnModel().getColumn(0).setMaxWidth(50);
        Ui.alinearDerecha(tRep, 7);
        rep.add(new JScrollPane(tRep), BorderLayout.CENTER);
        rep.add(new JLabel("<html><i>El autoconsumo no genera venta ni factura: solo descarga el inventario. El costo es el del inventario descargado (costo promedio).</i></html>"), BorderLayout.SOUTH);
        tabs.addTab("Reporte", rep);

        JPanel porMotivo = new JPanel(new BorderLayout());
        JTable t1 = Ui.tabla(mMotivo);
        Ui.alinearDerecha(t1, 1, 2);
        porMotivo.add(new JScrollPane(t1));
        tabs.addTab("Por motivo", porMotivo);

        JPanel porProd = new JPanel(new BorderLayout());
        JTable t2 = Ui.tabla(mProd);
        Ui.alinearDerecha(t2, 1, 2);
        porProd.add(new JScrollPane(t2));
        tabs.addTab("Por producto", porProd);

        JPanel mot = new JPanel(new BorderLayout(6, 6));
        tMotivos.getColumnModel().getColumn(0).setMaxWidth(50);
        mot.add(new JScrollPane(tMotivos), BorderLayout.CENTER);
        mot.add(Ui.fila(Ui.botonPrimario("+ Nuevo motivo", () -> formMotivo(null)),
                Ui.boton("Editar / desactivar", () -> { int id = Ui.idSeleccionado(tMotivos); if (id > 0) formMotivo(id); else Ui.info(this, "Seleccione un motivo."); }),
                new JLabel("<html><i>Estos motivos se eligen en el POS al registrar un autoconsumo.</i></html>")), BorderLayout.SOUTH);
        tabs.addTab("Motivos", mot);
        add(tabs, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        java.sql.Date d = java.sql.Date.valueOf(Campos.valor(desde)), h = java.sql.Date.valueOf(Campos.valor(hasta));
        try {
            var filas = Db.query("SELECT a.id_autoconsumo, DATE_FORMAT(a.fecha, '%d/%m/%Y %H:%i') AS fecha, COALESCE(m.nombre, CONCAT('Pedido #', c.id_comanda)) AS cuenta, "
                    + "mo.nombre AS motivo, (SELECT GROUP_CONCAT(CONCAT(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM cd.cantidad)), ' ', cd.descripcion) SEPARATOR ', ') FROM comanda_detalle cd "
                    + "WHERE cd.id_comanda = a.id_comanda AND cd.estado <> 'ANULADO') AS platos, u.nombre AS quien, ua.nombre AS autorizo, FORMAT(a.costo_total, 2) AS costo, a.notas "
                    + "FROM autoconsumos a JOIN comandas c ON c.id_comanda = a.id_comanda LEFT JOIN mesas m ON m.id_mesa = c.id_mesa "
                    + "JOIN motivos_autoconsumo mo ON mo.id_motivo = a.id_motivo LEFT JOIN usuarios u ON u.id_usuario = a.id_usuario LEFT JOIN usuarios ua ON ua.id_usuario = a.id_autoriza "
                    + "WHERE DATE(a.fecha) BETWEEN ? AND ? ORDER BY a.fecha DESC", d, h);
            Ui.llenar(mRep, filas, "id_autoconsumo", "fecha", "cuenta", "motivo", "platos", "quien", "autorizo", "costo", "notas");
            Object total = Db.scalar("SELECT COALESCE(SUM(costo_total),0) FROM autoconsumos WHERE DATE(fecha) BETWEEN ? AND ?", d, h);
            lblTotal.setText("<html>Autoconsumos: <b>" + filas.size() + "</b> · Costo total: <b>" + Ui.lempiras(total) + "</b></html>");
            Ui.llenar(mMotivo, Db.query("SELECT mo.nombre, COUNT(*) AS n, FORMAT(SUM(a.costo_total), 2) AS costo FROM autoconsumos a JOIN motivos_autoconsumo mo ON mo.id_motivo = a.id_motivo "
                    + "WHERE DATE(a.fecha) BETWEEN ? AND ? GROUP BY mo.nombre ORDER BY SUM(a.costo_total) DESC", d, h), "nombre", "n", "costo");
            Ui.llenar(mProd, Db.query("SELECT cd.descripcion, SUM(cd.cantidad) AS cant, FORMAT(SUM(cd.cantidad * COALESCE((SELECT SUM(rd.cantidad * (1 + rd.merma_pct/100) * i.costo_promedio) "
                    + "FROM receta_detalle rd JOIN insumos i ON i.id_insumo = rd.id_insumo WHERE rd.id_producto = cd.id_producto), 0)), 2) AS costo "
                    + "FROM autoconsumos a JOIN comanda_detalle cd ON cd.id_comanda = a.id_comanda AND cd.estado <> 'ANULADO' WHERE DATE(a.fecha) BETWEEN ? AND ? "
                    + "GROUP BY cd.descripcion ORDER BY SUM(cd.cantidad) DESC", d, h), "descripcion", "cant", "costo");
            Ui.llenar(mMotivos, Db.query("SELECT id_motivo, nombre, IF(activo=1,'Sí','No') AS activo FROM motivos_autoconsumo ORDER BY nombre"), "id_motivo", "nombre", "activo");
        } catch (RuntimeException e) { Ui.error(this, "Falta ejecutar el script 15_autoconsumo.sql.\n" + e.getMessage()); }
    }

    private void formMotivo(Integer id) {
        Map<String, Object> m = id == null ? null : Db.uno("SELECT * FROM motivos_autoconsumo WHERE id_motivo = ?", id);
        JTextField nombre = new JTextField(m == null ? "" : Db.str(m.get("nombre")), 24);
        JCheckBox activo = new JCheckBox("Activo (se puede elegir en el POS)", m == null || Db.ent(m.get("activo")) == 1);
        Campos.Formulario f = new Campos.Formulario().agregar("Motivo:", nombre).agregar("", activo)
                .nota("Ejemplos: consumo de empleados, cortesía de la gerencia, degustación, error de preparación.");
        if (Campos.dialogo(this, id == null ? "Nuevo motivo" : "Editar motivo", f, () -> {
            String n = nombre.getText().trim();
            if (n.length() < 3) throw new IllegalArgumentException("Escriba el nombre del motivo.");
            if (id == null) Db.insert("INSERT INTO motivos_autoconsumo (nombre, activo) VALUES (?,?)", n, activo.isSelected() ? 1 : 0);
            else Db.exec("UPDATE motivos_autoconsumo SET nombre = ?, activo = ? WHERE id_motivo = ?", n, activo.isSelected() ? 1 : 0, id);
            Bitacora.registrar("MOTIVO_AUTOCONSUMO", "motivos_autoconsumo", id == null ? n : id, n);
            return true;
        })) alMostrar();
    }
}
