package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reportes del hotel y el restaurante con período y exportación a Excel (CSV). */
public class ReportesPanel extends JPanel implements Actualizable {
    private record Rep(String nombre, String sql, boolean soloAdmin) {
        @Override public String toString() { return nombre; }
    }

    // Las consultas usan @d (desde) y @h (hasta)
    private static final List<Rep> REPORTES = List.of(
        new Rep("Reservas del período (por fecha de llegada)",
            "SELECT r.id_reserva AS `N.º`, hu.nombre AS `Huésped`, ha.numero AS `Hab.`, t.nombre AS `Tipo`, r.fecha_llegada AS `Llegada`, r.fecha_salida AS `Salida`, "
          + "r.noches AS `Noches`, r.num_personas AS `Personas`, r.anticipo AS `Anticipo`, r.estado AS `Estado` FROM reservas r JOIN huespedes hu ON hu.id_huesped = r.id_huesped "
          + "JOIN habitaciones ha ON ha.id_habitacion = r.id_habitacion JOIN tipos_habitacion t ON t.id_tipo = ha.id_tipo WHERE r.fecha_llegada BETWEEN @d AND @h ORDER BY r.fecha_llegada", false),
        new Rep("Ocupación por habitación",
            "SELECT ha.numero AS `Hab.`, t.nombre AS `Tipo`, COALESCE(SUM(GREATEST(0, DATEDIFF(LEAST(r.fecha_salida, DATE_ADD(@h, INTERVAL 1 DAY)), GREATEST(r.fecha_llegada, @d)))), 0) AS `Noches ocupada`, "
          + "DATEDIFF(@h, @d) + 1 AS `Días del período`, ROUND(100 * COALESCE(SUM(GREATEST(0, DATEDIFF(LEAST(r.fecha_salida, DATE_ADD(@h, INTERVAL 1 DAY)), GREATEST(r.fecha_llegada, @d)))), 0) / (DATEDIFF(@h, @d) + 1), 1) AS `% ocupación` "
          + "FROM habitaciones ha JOIN tipos_habitacion t ON t.id_tipo = ha.id_tipo LEFT JOIN reservas r ON r.id_habitacion = ha.id_habitacion AND r.estado IN ('EN_CASA','FINALIZADA') "
          + "AND r.fecha_llegada <= @h AND r.fecha_salida > @d WHERE ha.activa = 1 GROUP BY ha.id_habitacion, ha.numero, t.nombre ORDER BY ha.numero", false),
        new Rep("Ventas por día",
            "SELECT fecha AS `Fecha`, COUNT(*) AS `Facturas`, SUM(IF(origen='HOSPEDAJE', total, 0)) AS `Hospedaje`, SUM(IF(origen='RESTAURANTE', total, 0)) AS `Restaurante`, "
          + "SUM(isv_15 + isv_18) AS `ISV`, SUM(tasa_turistica) AS `Tasa turística`, SUM(total) AS `Total`, SUM(IF(condicion_pago='CREDITO', total, 0)) AS `A crédito` "
          + "FROM v_libro_ventas WHERE estado = 'EMITIDA' AND fecha BETWEEN @d AND @h GROUP BY fecha ORDER BY fecha", false),
        new Rep("Cobros por forma de pago (contado y abonos)",
            "SELECT forma AS `Forma de pago`, COUNT(*) AS `Movimientos`, SUM(monto) AS `Total` FROM (SELECT p.metodo AS forma, p.monto FROM pagos p JOIN facturas f ON f.id_factura = p.id_factura "
          + "WHERE f.estado = 'EMITIDA' AND DATE(p.fecha_pago) BETWEEN @d AND @h UNION ALL SELECT a.metodo, a.monto FROM abonos a JOIN cuentas_por_cobrar x ON x.id_cxc = a.id_cxc "
          + "WHERE x.estado <> 'ANULADA' AND DATE(a.fecha) BETWEEN @d AND @h) z GROUP BY forma ORDER BY forma", false),
        new Rep("Restaurante: platos más vendidos y margen",
            "SELECT d.descripcion AS `Plato`, SUM(d.cantidad) AS `Cantidad`, ROUND(SUM(d.cantidad * d.precio_unitario - d.descuento), 2) AS `Venta (sin ISV)`, "
          + "ROUND(SUM(d.cantidad * COALESCE(rc.costo, 0)), 2) AS `Costo estimado`, ROUND(SUM(d.cantidad * d.precio_unitario - d.descuento) - SUM(d.cantidad * COALESCE(rc.costo, 0)), 2) AS `Utilidad` "
          + "FROM comanda_detalle d JOIN comandas c ON c.id_comanda = d.id_comanda LEFT JOIN (SELECT rd.id_producto, SUM(rd.cantidad * (1 + rd.merma_pct/100) * i.costo_promedio) AS costo "
          + "FROM receta_detalle rd JOIN insumos i ON i.id_insumo = rd.id_insumo GROUP BY rd.id_producto) rc ON rc.id_producto = d.id_producto "
          + "WHERE d.estado <> 'ANULADO' AND c.estado IN ('FACTURADA','CARGADA_HABITACION') AND DATE(c.abierta_en) BETWEEN @d AND @h GROUP BY d.descripcion ORDER BY `Venta (sin ISV)` DESC", false),
        new Rep("Inventario: consumo y mermas por insumo",
            "SELECT i.nombre AS `Insumo`, i.unidad_base AS `Unidad`, ROUND(-SUM(IF(m.tipo='SALIDA_VENTA', m.cantidad, 0)) - SUM(IF(m.tipo='DEVOLUCION', m.cantidad, 0)), 3) AS `Usado en ventas`, "
          + "ROUND(-SUM(IF(m.tipo='MERMA', m.cantidad, 0)), 3) AS `Merma`, ROUND(SUM(IF(m.tipo='COMPRA', m.cantidad, 0)), 3) AS `Comprado` FROM movimientos_inventario m JOIN insumos i ON i.id_insumo = m.id_insumo "
          + "WHERE DATE(m.fecha) BETWEEN @d AND @h GROUP BY i.id_insumo, i.nombre, i.unidad_base ORDER BY i.nombre", false),
        new Rep("Inventario valorizado (hoy)",
            "SELECT nombre AS `Insumo`, unidad_base AS `Unidad`, stock_actual AS `Existencia`, stock_minimo AS `Mínimo`, costo_promedio AS `Costo/unidad`, valor_inventario AS `Valor`, situacion AS `Situación` FROM v_stock_insumos ORDER BY nombre", false),
        new Rep("Compras por proveedor",
            "SELECT p.nombre AS `Proveedor`, COUNT(*) AS `Compras`, SUM(c.total) AS `Total` FROM compras c JOIN proveedores p ON p.id_proveedor = c.id_proveedor "
          + "WHERE c.estado = 'RECIBIDA' AND c.fecha BETWEEN @d AND @h GROUP BY p.id_proveedor, p.nombre ORDER BY SUM(c.total) DESC", false),
        new Rep("Cuentas por cobrar (hoy)",
            "SELECT cliente AS `Cliente`, numero_factura AS `Factura`, fecha_emision AS `Emisión`, fecha_vencimiento AS `Vence`, monto AS `Monto`, saldo AS `Saldo`, dias_vencido AS `Días vencida` FROM v_cuentas_por_cobrar WHERE estado IN ('PENDIENTE','PARCIAL') ORDER BY fecha_vencimiento", false),
        new Rep("Facturas anuladas",
            "SELECT numero_factura AS `Factura`, fecha_emision AS `Emitida`, cliente_nombre AS `Cliente`, total AS `Total`, anulada_en AS `Anulada`, motivo_anulacion AS `Motivo` FROM facturas WHERE estado = 'ANULADA' AND DATE(fecha_emision) BETWEEN @d AND @h ORDER BY id_factura", false),
        new Rep("Ventas por producto o servicio (facturado)",
            "SELECT fd.descripcion AS `Producto / servicio`, fd.tipo AS `Tipo`, SUM(fd.cantidad) AS `Cantidad`, ROUND(SUM(fd.cantidad * fd.precio_unitario), 2) AS `Venta bruta`, "
          + "ROUND(SUM(fd.descuento), 2) AS `Descuentos`, ROUND(SUM(fd.subtotal), 2) AS `Venta neta`, ROUND(SUM(fd.isv), 2) AS `ISV` FROM factura_detalle fd JOIN facturas f ON f.id_factura = fd.id_factura "
          + "WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN @d AND @h GROUP BY fd.descripcion, fd.tipo ORDER BY SUM(fd.subtotal) DESC", false),
        new Rep("Ventas por tipo (hospedaje, alimentos, bebidas, servicios)",
            "SELECT fd.tipo AS `Tipo`, SUM(fd.cantidad) AS `Cantidad`, ROUND(SUM(fd.subtotal), 2) AS `Venta neta`, ROUND(SUM(fd.isv), 2) AS `ISV` FROM factura_detalle fd JOIN facturas f ON f.id_factura = fd.id_factura "
          + "WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN @d AND @h GROUP BY fd.tipo ORDER BY SUM(fd.subtotal) DESC", false),
        new Rep("Ventas por cajero o usuario",
            "SELECT COALESCE(u.nombre, '-') AS `Usuario`, COUNT(*) AS `Facturas`, ROUND(SUM(f.total), 2) AS `Total`, ROUND(SUM(f.descuento_total), 2) AS `Descuentos` FROM facturas f "
          + "LEFT JOIN usuarios u ON u.id_usuario = f.id_usuario WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN @d AND @h GROUP BY u.id_usuario, u.nombre ORDER BY SUM(f.total) DESC", false),
        new Rep("Ventas por hora del día",
            "SELECT HOUR(fecha_emision) AS `Hora`, COUNT(*) AS `Facturas`, ROUND(SUM(total), 2) AS `Total` FROM facturas WHERE estado = 'EMITIDA' AND DATE(fecha_emision) BETWEEN @d AND @h GROUP BY HOUR(fecha_emision) ORDER BY HOUR(fecha_emision)", false),
        new Rep("Ventas por cliente",
            "SELECT cliente_nombre AS `Cliente`, COALESCE(cliente_rtn, '') AS `RTN`, COUNT(*) AS `Facturas`, ROUND(SUM(total), 2) AS `Total` FROM facturas "
          + "WHERE estado = 'EMITIDA' AND DATE(fecha_emision) BETWEEN @d AND @h GROUP BY cliente_nombre, cliente_rtn ORDER BY SUM(total) DESC", false),
        new Rep("Descuentos aplicados en facturas",
            "SELECT f.numero_factura AS `Factura`, f.fecha_emision AS `Fecha`, fd.descripcion AS `Detalle`, fd.cantidad AS `Cant.`, ROUND(fd.cantidad * fd.precio_unitario, 2) AS `Importe`, fd.descuento AS `Descuento`, "
          + "COALESCE(u.username, '-') AS `Usuario` FROM factura_detalle fd JOIN facturas f ON f.id_factura = fd.id_factura LEFT JOIN usuarios u ON u.id_usuario = f.id_usuario "
          + "WHERE f.estado = 'EMITIDA' AND fd.descuento > 0 AND DATE(f.fecha_emision) BETWEEN @d AND @h ORDER BY f.id_factura", false),
        new Rep("Descuentos y cambios de precio en el POS (auditoría)",
            "SELECT b.fecha AS `Fecha`, COALESCE(u.username, '-') AS `Usuario`, b.accion AS `Acción`, b.id_entidad AS `Id`, b.detalle AS `Detalle` FROM bitacora b LEFT JOIN usuarios u ON u.id_usuario = b.id_usuario "
          + "WHERE b.accion IN ('AJUSTE_PLATO', 'PRECIO', 'AUTORIZACION') AND DATE(b.fecha) BETWEEN @d AND @h ORDER BY b.id_bitacora DESC", false),
        new Rep("Compras detalladas por insumo",
            "SELECT c.fecha AS `Fecha`, p.nombre AS `Proveedor`, c.numero_documento AS `Factura`, i.nombre AS `Insumo`, d.cantidad AS `Cantidad`, i.unidad_base AS `Unidad`, d.costo_unitario AS `Costo unit.`, "
          + "ROUND(d.cantidad * d.costo_unitario, 2) AS `Importe`, c.condicion_pago AS `Pago` FROM compras c JOIN compra_detalle d ON d.id_compra = c.id_compra JOIN insumos i ON i.id_insumo = d.id_insumo "
          + "JOIN proveedores p ON p.id_proveedor = c.id_proveedor WHERE c.estado = 'RECIBIDA' AND c.fecha BETWEEN @d AND @h ORDER BY c.fecha, c.id_compra", false),
        new Rep("Cuentas por pagar a proveedores (hoy)",
            "SELECT p.nombre AS `Proveedor`, c.numero_documento AS `Factura`, x.fecha_emision AS `Emisión`, x.fecha_vencimiento AS `Vence`, x.monto AS `Monto`, x.saldo AS `Saldo`, "
          + "GREATEST(0, DATEDIFF(CURRENT_DATE, x.fecha_vencimiento)) AS `Días vencida` FROM cuentas_por_pagar x JOIN proveedores p ON p.id_proveedor = x.id_proveedor JOIN compras c ON c.id_compra = x.id_compra "
          + "WHERE x.estado = 'PENDIENTE' ORDER BY x.fecha_vencimiento", false),
        new Rep("Pagos realizados a proveedores",
            "SELECT g.fecha AS `Fecha`, p.nombre AS `Proveedor`, c.numero_documento AS `Factura`, g.monto AS `Monto`, g.metodo AS `Método`, g.referencia AS `Referencia`, COALESCE(u.username, '-') AS `Registró` "
          + "FROM pagos_proveedor g JOIN cuentas_por_pagar x ON x.id_cxp = g.id_cxp JOIN proveedores p ON p.id_proveedor = x.id_proveedor JOIN compras c ON c.id_compra = x.id_compra "
          + "LEFT JOIN usuarios u ON u.id_usuario = g.id_usuario WHERE g.fecha BETWEEN @d AND @h ORDER BY g.fecha, g.id_pago", false),
        new Rep("Bitácora de auditoría (solo administrador)",
            "SELECT b.fecha AS `Fecha`, COALESCE(u.username, '-') AS `Usuario`, b.accion AS `Acción`, b.entidad AS `Sobre`, b.id_entidad AS `Id`, b.detalle AS `Detalle` FROM bitacora b LEFT JOIN usuarios u ON u.id_usuario = b.id_usuario "
          + "WHERE DATE(b.fecha) BETWEEN @d AND @h ORDER BY b.id_bitacora DESC LIMIT 5000", true)
    );

    private final JComboBox<Rep> cmb = new JComboBox<>();
    private final JSpinner desde = Campos.fecha(LocalDate.now().withDayOfMonth(1)), hasta = Campos.fecha(LocalDate.now());
    private final DefaultTableModel modelo = new DefaultTableModel() {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable tabla = Ui.tabla(modelo);
    private final JLabel lblInfo = new JLabel(" ");

    public ReportesPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        for (Rep r : REPORTES) if (!r.soloAdmin() || Sesion.esAdmin()) cmb.addItem(r);
        JPanel norte = new JPanel(new BorderLayout());
        norte.add(Ui.titulo("Reportes"), BorderLayout.NORTH);
        norte.add(Ui.fila(new JLabel("Reporte:"), cmb, new JLabel("Desde:"), desde, new JLabel("hasta:"), hasta,
                Ui.boton("Generar", this::generar), Ui.boton("Exportar a Excel (CSV)", () -> Exportar.csv(this, modelo, "reporte_" + LocalDate.now()))), BorderLayout.CENTER);
        add(norte, BorderLayout.NORTH);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        add(lblInfo, BorderLayout.SOUTH);
        tabla.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    }

    @Override public void alMostrar() { }

    private void generar() {
        Rep r = (Rep) cmb.getSelectedItem();
        if (r == null) return;
        LocalDate d = Campos.valor(desde), h = Campos.valor(hasta);
        if (h.isBefore(d)) { Ui.error(this, "La fecha final es anterior a la inicial."); return; }
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> {
            tx.exec("SET @d = ?, @h = ?", d, h);
            return tx.query(r.sql());
        }), filas -> {
            modelo.setRowCount(0);
            modelo.setColumnCount(0);
            if (filas.isEmpty()) { lblInfo.setText("Sin datos para ese período."); return; }
            List<String> cols = new ArrayList<>(filas.get(0).keySet());
            for (String c : cols) modelo.addColumn(c);
            for (Map<String, Object> f : filas) {
                Object[] fila = new Object[cols.size()];
                for (int i = 0; i < fila.length; i++) fila[i] = Ui.formatear(f.get(cols.get(i)));
                modelo.addRow(fila);
            }
            for (int i = 0; i < tabla.getColumnCount(); i++) tabla.getColumnModel().getColumn(i).setPreferredWidth(Math.max(90, Math.min(320, cols.get(i).length() * 11 + 30)));
            lblInfo.setText(filas.size() + " fila(s).");
        });
    }
}
