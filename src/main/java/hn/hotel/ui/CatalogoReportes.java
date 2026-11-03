package hn.hotel.ui;

import java.util.List;

/**
 * Catálogo de reportes. Para agregar un reporte basta una línea aquí: categoría, nombre y la consulta.
 * La consulta puede usar @d y @h (desde / hasta), @cliente y @proveedor (id elegido o NULL). Los filtros, el agrupado,
 * los totales, el gráfico y la exportación los pone la pantalla sola, para cualquier columna.
 */
public final class CatalogoReportes {
    private CatalogoReportes() {}

    /** Orden en que aparecen las categorías en el árbol. */
    public static final List<String> CATEGORIAS = List.of("Ventas", "Clientes", "Cuentas por cobrar y cobros", "Proveedores y compras",
            "Inventario", "Hotel", "Restaurante", "Fiscal (SAR)", "Auditoría");

    /** Facturas emitidas del período con los datos del cliente (alias v). */
    private static final String VENTAS = "(SELECT f.id_factura, f.fecha_emision, DATE(f.fecha_emision) AS fecha, f.numero_factura, COALESCE(c.nombre, f.cliente_nombre) AS cliente, "
            + "COALESCE(c.codigo, '') AS codigo, COALESCE(z.nombre, '(sin zona)') AS zona, COALESCE(c.tipo_cliente, 'EVENTUAL') AS tipo_cliente, f.origen, f.condicion_pago, f.total, "
            + "f.descuento_total, f.importe_gravado_15 + f.importe_gravado_18 AS gravado, f.importe_exento, f.importe_exonerado, f.isv_15 + f.isv_18 AS isv, f.id_usuario, f.id_cliente_credito "
            + "FROM facturas f LEFT JOIN clientes_credito c ON c.id_cliente_credito = f.id_cliente_credito LEFT JOIN zonas z ON z.id_zona = c.id_zona "
            + "WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN @d AND @h) v";

    public static List<ReporteDef> todos() { return REPORTES; }

    public static ReporteDef buscar(String nombre) {
        for (ReporteDef r : REPORTES) if (r.nombre().equals(nombre)) return r;
        return null;
    }

    private static final List<ReporteDef> REPORTES = List.of(
        new ReporteDef("Hotel", "Reservas del período (por fecha de llegada)",
            "SELECT r.id_reserva AS `N.º`, hu.nombre AS `Huésped`, ha.numero AS `Hab.`, t.nombre AS `Tipo`, r.fecha_llegada AS `Llegada`, r.fecha_salida AS `Salida`, "
          + "r.noches AS `Noches`, r.num_personas AS `Personas`, r.anticipo AS `Anticipo`, r.estado AS `Estado` FROM reservas r JOIN huespedes hu ON hu.id_huesped = r.id_huesped "
          + "JOIN habitaciones ha ON ha.id_habitacion = r.id_habitacion JOIN tipos_habitacion t ON t.id_tipo = ha.id_tipo WHERE r.fecha_llegada BETWEEN @d AND @h ORDER BY r.fecha_llegada", false),
        new ReporteDef("Hotel", "Ocupación por habitación",
            "SELECT ha.numero AS `Hab.`, t.nombre AS `Tipo`, COALESCE(SUM(GREATEST(0, DATEDIFF(LEAST(r.fecha_salida, DATE_ADD(@h, INTERVAL 1 DAY)), GREATEST(r.fecha_llegada, @d)))), 0) AS `Noches ocupada`, "
          + "DATEDIFF(@h, @d) + 1 AS `Días del período`, ROUND(100 * COALESCE(SUM(GREATEST(0, DATEDIFF(LEAST(r.fecha_salida, DATE_ADD(@h, INTERVAL 1 DAY)), GREATEST(r.fecha_llegada, @d)))), 0) / (DATEDIFF(@h, @d) + 1), 1) AS `% ocupación` "
          + "FROM habitaciones ha JOIN tipos_habitacion t ON t.id_tipo = ha.id_tipo LEFT JOIN reservas r ON r.id_habitacion = ha.id_habitacion AND r.estado IN ('EN_CASA','FINALIZADA') "
          + "AND r.fecha_llegada <= @h AND r.fecha_salida > @d WHERE ha.activa = 1 GROUP BY ha.id_habitacion, ha.numero, t.nombre ORDER BY ha.numero", false),
        new ReporteDef("Ventas", "Ventas por día",
            "SELECT fecha AS `Fecha`, COUNT(*) AS `Facturas`, SUM(IF(origen='HOSPEDAJE', total, 0)) AS `Hospedaje`, SUM(IF(origen='RESTAURANTE', total, 0)) AS `Restaurante`, "
          + "SUM(isv_15 + isv_18) AS `ISV`, SUM(tasa_turistica) AS `Tasa turística`, SUM(total) AS `Total`, SUM(IF(condicion_pago='CREDITO', total, 0)) AS `A crédito` "
          + "FROM v_libro_ventas WHERE estado = 'EMITIDA' AND fecha BETWEEN @d AND @h GROUP BY fecha ORDER BY fecha", false),
        new ReporteDef("Cuentas por cobrar y cobros", "Cobros por forma de pago (contado y abonos)",
            "SELECT forma AS `Forma de pago`, COUNT(*) AS `Movimientos`, SUM(monto) AS `Total` FROM (SELECT p.metodo AS forma, p.monto FROM pagos p JOIN facturas f ON f.id_factura = p.id_factura "
          + "WHERE f.estado = 'EMITIDA' AND DATE(p.fecha_pago) BETWEEN @d AND @h UNION ALL SELECT a.metodo, a.monto FROM abonos a JOIN cuentas_por_cobrar x ON x.id_cxc = a.id_cxc "
          + "WHERE x.estado <> 'ANULADA' AND DATE(a.fecha) BETWEEN @d AND @h) z GROUP BY forma ORDER BY forma", false),
        new ReporteDef("Restaurante", "Restaurante: platos más vendidos y margen",
            "SELECT d.descripcion AS `Plato`, SUM(d.cantidad) AS `Cantidad`, ROUND(SUM(d.cantidad * d.precio_unitario - d.descuento), 2) AS `Venta (sin ISV)`, "
          + "ROUND(SUM(d.cantidad * COALESCE(rc.costo, 0)), 2) AS `Costo estimado`, ROUND(SUM(d.cantidad * d.precio_unitario - d.descuento) - SUM(d.cantidad * COALESCE(rc.costo, 0)), 2) AS `Utilidad` "
          + "FROM comanda_detalle d JOIN comandas c ON c.id_comanda = d.id_comanda LEFT JOIN (SELECT rd.id_producto, SUM(rd.cantidad * (1 + rd.merma_pct/100) * i.costo_promedio) AS costo "
          + "FROM receta_detalle rd JOIN insumos i ON i.id_insumo = rd.id_insumo GROUP BY rd.id_producto) rc ON rc.id_producto = d.id_producto "
          + "WHERE d.estado <> 'ANULADO' AND c.estado IN ('FACTURADA','CARGADA_HABITACION') AND DATE(c.abierta_en) BETWEEN @d AND @h GROUP BY d.descripcion ORDER BY `Venta (sin ISV)` DESC", false),
        new ReporteDef("Inventario", "Inventario: consumo y mermas por insumo",
            "SELECT i.nombre AS `Insumo`, i.unidad_base AS `Unidad`, ROUND(-SUM(IF(m.tipo='SALIDA_VENTA', m.cantidad, 0)) - SUM(IF(m.tipo='DEVOLUCION', m.cantidad, 0)), 3) AS `Usado en ventas`, "
          + "ROUND(-SUM(IF(m.tipo='MERMA', m.cantidad, 0)), 3) AS `Merma`, ROUND(SUM(IF(m.tipo='COMPRA', m.cantidad, 0)), 3) AS `Comprado` FROM movimientos_inventario m JOIN insumos i ON i.id_insumo = m.id_insumo "
          + "WHERE DATE(m.fecha) BETWEEN @d AND @h GROUP BY i.id_insumo, i.nombre, i.unidad_base ORDER BY i.nombre", false),
        new ReporteDef("Inventario", "Inventario valorizado (hoy)",
            "SELECT nombre AS `Insumo`, unidad_base AS `Unidad`, stock_actual AS `Existencia`, stock_minimo AS `Mínimo`, costo_promedio AS `Costo/unidad`, valor_inventario AS `Valor`, situacion AS `Situación` FROM v_stock_insumos ORDER BY nombre", false),
        new ReporteDef("Proveedores y compras", "Compras por proveedor",
            "SELECT p.nombre AS `Proveedor`, COUNT(*) AS `Compras`, SUM(c.total) AS `Total` FROM compras c JOIN proveedores p ON p.id_proveedor = c.id_proveedor "
          + "WHERE c.estado = 'RECIBIDA' AND c.fecha BETWEEN @d AND @h GROUP BY p.id_proveedor, p.nombre ORDER BY SUM(c.total) DESC", false),
        new ReporteDef("Cuentas por cobrar y cobros", "Cuentas por cobrar (hoy)",
            "SELECT cliente AS `Cliente`, numero_factura AS `Factura`, fecha_emision AS `Emisión`, fecha_vencimiento AS `Vence`, monto AS `Monto`, saldo AS `Saldo`, dias_vencido AS `Días vencida` FROM v_cuentas_por_cobrar WHERE estado IN ('PENDIENTE','PARCIAL') ORDER BY fecha_vencimiento", false),
        new ReporteDef("Ventas", "Facturas anuladas",
            "SELECT numero_factura AS `Factura`, fecha_emision AS `Emitida`, cliente_nombre AS `Cliente`, total AS `Total`, anulada_en AS `Anulada`, motivo_anulacion AS `Motivo` FROM facturas WHERE estado = 'ANULADA' AND DATE(fecha_emision) BETWEEN @d AND @h ORDER BY id_factura", false),
        new ReporteDef("Ventas", "Ventas por producto o servicio (facturado)",
            "SELECT fd.descripcion AS `Producto / servicio`, fd.tipo AS `Tipo`, SUM(fd.cantidad) AS `Cantidad`, ROUND(SUM(fd.cantidad * fd.precio_unitario), 2) AS `Venta bruta`, "
          + "ROUND(SUM(fd.descuento), 2) AS `Descuentos`, ROUND(SUM(fd.subtotal), 2) AS `Venta neta`, ROUND(SUM(fd.isv), 2) AS `ISV` FROM factura_detalle fd JOIN facturas f ON f.id_factura = fd.id_factura "
          + "WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN @d AND @h GROUP BY fd.descripcion, fd.tipo ORDER BY SUM(fd.subtotal) DESC", false),
        new ReporteDef("Ventas", "Ventas por tipo (hospedaje, alimentos, bebidas, servicios)",
            "SELECT fd.tipo AS `Tipo`, SUM(fd.cantidad) AS `Cantidad`, ROUND(SUM(fd.subtotal), 2) AS `Venta neta`, ROUND(SUM(fd.isv), 2) AS `ISV` FROM factura_detalle fd JOIN facturas f ON f.id_factura = fd.id_factura "
          + "WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN @d AND @h GROUP BY fd.tipo ORDER BY SUM(fd.subtotal) DESC", false),
        new ReporteDef("Ventas", "Ventas por cajero o usuario",
            "SELECT COALESCE(u.nombre, '-') AS `Usuario`, COUNT(*) AS `Facturas`, ROUND(SUM(f.total), 2) AS `Total`, ROUND(SUM(f.descuento_total), 2) AS `Descuentos` FROM facturas f "
          + "LEFT JOIN usuarios u ON u.id_usuario = f.id_usuario WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN @d AND @h GROUP BY u.id_usuario, u.nombre ORDER BY SUM(f.total) DESC", false),
        new ReporteDef("Ventas", "Ventas por hora del día",
            "SELECT HOUR(fecha_emision) AS `Hora`, COUNT(*) AS `Facturas`, ROUND(SUM(total), 2) AS `Total` FROM facturas WHERE estado = 'EMITIDA' AND DATE(fecha_emision) BETWEEN @d AND @h GROUP BY HOUR(fecha_emision) ORDER BY HOUR(fecha_emision)", false),
        new ReporteDef("Ventas", "Descuentos aplicados en facturas",
            "SELECT f.numero_factura AS `Factura`, f.fecha_emision AS `Fecha`, fd.descripcion AS `Detalle`, fd.cantidad AS `Cant.`, ROUND(fd.cantidad * fd.precio_unitario, 2) AS `Importe`, fd.descuento AS `Descuento`, "
          + "COALESCE(u.username, '-') AS `Usuario` FROM factura_detalle fd JOIN facturas f ON f.id_factura = fd.id_factura LEFT JOIN usuarios u ON u.id_usuario = f.id_usuario "
          + "WHERE f.estado = 'EMITIDA' AND fd.descuento > 0 AND DATE(f.fecha_emision) BETWEEN @d AND @h ORDER BY f.id_factura", false),
        new ReporteDef("Auditoría", "Descuentos y cambios de precio en el POS (auditoría)",
            "SELECT b.fecha AS `Fecha`, COALESCE(u.username, '-') AS `Usuario`, b.accion AS `Acción`, b.id_entidad AS `Id`, b.detalle AS `Detalle` FROM bitacora b LEFT JOIN usuarios u ON u.id_usuario = b.id_usuario "
          + "WHERE b.accion IN ('AJUSTE_PLATO', 'PRECIO', 'AUTORIZACION') AND DATE(b.fecha) BETWEEN @d AND @h ORDER BY b.id_bitacora DESC", false),
        new ReporteDef("Proveedores y compras", "Compras detalladas por insumo",
            "SELECT c.fecha AS `Fecha`, p.nombre AS `Proveedor`, c.numero_documento AS `Factura`, i.nombre AS `Insumo`, d.cantidad AS `Cantidad`, i.unidad_base AS `Unidad`, d.costo_unitario AS `Costo unit.`, "
          + "ROUND(d.cantidad * d.costo_unitario, 2) AS `Importe`, c.condicion_pago AS `Pago` FROM compras c JOIN compra_detalle d ON d.id_compra = c.id_compra JOIN insumos i ON i.id_insumo = d.id_insumo "
          + "JOIN proveedores p ON p.id_proveedor = c.id_proveedor WHERE c.estado = 'RECIBIDA' AND c.fecha BETWEEN @d AND @h ORDER BY c.fecha, c.id_compra", false),
        new ReporteDef("Proveedores y compras", "Cuentas por pagar a proveedores (hoy)",
            "SELECT p.nombre AS `Proveedor`, c.numero_documento AS `Factura`, x.fecha_emision AS `Emisión`, x.fecha_vencimiento AS `Vence`, x.monto AS `Monto`, x.saldo AS `Saldo`, "
          + "GREATEST(0, DATEDIFF(CURRENT_DATE, x.fecha_vencimiento)) AS `Días vencida` FROM cuentas_por_pagar x JOIN proveedores p ON p.id_proveedor = x.id_proveedor JOIN compras c ON c.id_compra = x.id_compra "
          + "WHERE x.estado = 'PENDIENTE' ORDER BY x.fecha_vencimiento", false),
        new ReporteDef("Proveedores y compras", "Pagos realizados a proveedores",
            "SELECT g.fecha AS `Fecha`, p.nombre AS `Proveedor`, c.numero_documento AS `Factura`, g.monto AS `Monto`, g.metodo AS `Método`, g.referencia AS `Referencia`, COALESCE(u.username, '-') AS `Registró` "
          + "FROM pagos_proveedor g JOIN cuentas_por_pagar x ON x.id_cxp = g.id_cxp JOIN proveedores p ON p.id_proveedor = x.id_proveedor JOIN compras c ON c.id_compra = x.id_compra "
          + "LEFT JOIN usuarios u ON u.id_usuario = g.id_usuario WHERE g.fecha BETWEEN @d AND @h ORDER BY g.fecha, g.id_pago", false),
        new ReporteDef("Auditoría", "Bitácora de auditoría (solo administrador)",
            "SELECT b.fecha AS `Fecha`, COALESCE(u.username, '-') AS `Usuario`, b.accion AS `Acción`, b.entidad AS `Sobre`, b.id_entidad AS `Id`, b.detalle AS `Detalle` FROM bitacora b LEFT JOIN usuarios u ON u.id_usuario = b.id_usuario "
          + "WHERE DATE(b.fecha) BETWEEN @d AND @h ORDER BY b.id_bitacora DESC LIMIT 5000", true),

        // ------------------------------------------------------------ CLIENTES
        new ReporteDef("Clientes", "Listado general de clientes",
            "SELECT c.codigo AS `Código`, c.nombre AS `Cliente`, COALESCE(c.rtn, '') AS `RTN`, c.tipo_cliente AS `Tipo`, COALESCE(z.nombre, '(sin zona)') AS `Zona`, "
          + "COALESCE(c.ciudad, '') AS `Ciudad`, COALESCE(c.telefono, c.celular, '') AS `Teléfono`, COALESCE(c.email, '') AS `Correo`, "
          + "c.limite_credito AS `Límite`, COALESCE(s.saldo, 0) AS `Saldo`, c.limite_credito - COALESCE(s.saldo, 0) AS `Disponible`, "
          + "IF(c.limite_credito > 0, ROUND(100 * COALESCE(s.saldo, 0) / c.limite_credito, 1), 0) AS `% del límite usado`, "
          + "IF(c.es_exento = 1, 'Sí', 'No') AS `Exento`, IF(c.activo = 1, 'Activo', 'Inactivo') AS `Estado`, "
          + "COALESCE(v.fac, 0) AS `Facturas`, COALESCE(v.tot, 0) AS `Total comprado`, v.ultima AS `Última compra` "
          + "FROM clientes_credito c LEFT JOIN zonas z ON z.id_zona = c.id_zona "
          + "LEFT JOIN (SELECT id_cliente_credito, SUM(saldo) AS saldo FROM cuentas_por_cobrar WHERE estado IN ('PENDIENTE','PARCIAL') GROUP BY id_cliente_credito) s ON s.id_cliente_credito = c.id_cliente_credito "
          + "LEFT JOIN (SELECT id_cliente_credito, COUNT(*) AS fac, SUM(total) AS tot, MAX(DATE(fecha_emision)) AS ultima FROM facturas WHERE estado = 'EMITIDA' AND id_cliente_credito IS NOT NULL GROUP BY id_cliente_credito) v "
          + "ON v.id_cliente_credito = c.id_cliente_credito ORDER BY c.nombre", false),
        new ReporteDef("Clientes", "Ventas por cliente",
            "SELECT v.cliente AS `Cliente`, v.codigo AS `Código`, v.zona AS `Zona`, v.tipo_cliente AS `Tipo de cliente`, COUNT(*) AS `Facturas`, ROUND(SUM(v.total), 2) AS `Total`, "
          + "ROUND(AVG(v.total), 2) AS `Ticket promedio`, MAX(v.fecha) AS `Última compra` FROM " + VENTAS
          + " GROUP BY v.cliente, v.codigo, v.zona, v.tipo_cliente ORDER BY SUM(v.total) DESC", false),
        new ReporteDef("Clientes", "Mejores clientes (análisis ABC)",
            "SELECT t.cliente AS `Cliente`, t.zona AS `Zona`, t.facturas AS `Facturas`, t.total AS `Total`, t.pct AS `% del total`, t.acum AS `% acumulado`, "
          + "CASE WHEN t.acum - t.pct < 80 THEN 'A' WHEN t.acum - t.pct < 95 THEN 'B' ELSE 'C' END AS `Clase` FROM ("
          + "SELECT v.cliente, v.zona, COUNT(*) AS facturas, ROUND(SUM(v.total), 2) AS total, "
          + "ROUND(100 * SUM(v.total) / SUM(SUM(v.total)) OVER (), 2) AS pct, "
          + "ROUND(100 * SUM(SUM(v.total)) OVER (ORDER BY SUM(v.total) DESC, v.cliente) / SUM(SUM(v.total)) OVER (), 2) AS acum "
          + "FROM " + VENTAS + " WHERE v.id_cliente_credito IS NOT NULL GROUP BY v.cliente, v.zona) t ORDER BY t.total DESC", false),
        new ReporteDef("Clientes", "Ventas por zona",
            "SELECT v.zona AS `Zona`, COUNT(DISTINCT v.id_cliente_credito) AS `Clientes registrados`, COUNT(*) AS `Facturas`, ROUND(SUM(v.total), 2) AS `Total`, "
          + "ROUND(AVG(v.total), 2) AS `Ticket promedio` FROM " + VENTAS + " GROUP BY v.zona ORDER BY SUM(v.total) DESC", false),
        new ReporteDef("Clientes", "Clientes sin compras en el período",
            "SELECT c.codigo AS `Código`, c.nombre AS `Cliente`, COALESCE(z.nombre, '(sin zona)') AS `Zona`, COALESCE(c.telefono, c.celular, '') AS `Teléfono`, "
          + "MAX(DATE(f.fecha_emision)) AS `Última compra` FROM clientes_credito c LEFT JOIN zonas z ON z.id_zona = c.id_zona "
          + "LEFT JOIN facturas f ON f.id_cliente_credito = c.id_cliente_credito AND f.estado = 'EMITIDA' WHERE c.activo = 1 "
          + "GROUP BY c.id_cliente_credito, c.codigo, c.nombre, z.nombre, c.telefono, c.celular "
          + "HAVING MAX(DATE(f.fecha_emision)) IS NULL OR MAX(DATE(f.fecha_emision)) < @d ORDER BY MAX(DATE(f.fecha_emision))", false),
        new ReporteDef("Clientes", "Estado de cuenta de cliente",
            "SELECT t.cliente AS `Cliente`, t.fecha AS `Fecha`, t.tipo AS `Movimiento`, t.documento AS `Documento`, t.cargo AS `Cargos`, t.abono AS `Abonos`, t.saldo AS `Saldo` FROM ("
          + "SELECT m.id_c, m.cliente, m.fecha, m.tipo, m.documento, m.cargo, m.abono, m.orden, m.ref, "
          + "SUM(m.cargo - m.abono) OVER (PARTITION BY m.id_c ORDER BY m.fecha, m.orden, m.ref) AS saldo FROM ("
          + "SELECT c.id_cliente_credito AS id_c, c.nombre AS cliente, x.fecha_emision AS fecha, 'Factura a crédito' AS tipo, f.numero_factura AS documento, "
          + "x.monto AS cargo, 0 AS abono, 1 AS orden, x.id_cxc AS ref FROM cuentas_por_cobrar x JOIN facturas f ON f.id_factura = x.id_factura "
          + "JOIN clientes_credito c ON c.id_cliente_credito = x.id_cliente_credito WHERE x.estado <> 'ANULADA' "
          + "UNION ALL "
          + "SELECT c.id_cliente_credito, c.nombre, DATE(a.fecha), CONCAT('Abono ', a.metodo), COALESCE(a.referencia, f.numero_factura), 0, a.monto, 2, a.id_abono "
          + "FROM abonos a JOIN cuentas_por_cobrar x ON x.id_cxc = a.id_cxc JOIN facturas f ON f.id_factura = x.id_factura "
          + "JOIN clientes_credito c ON c.id_cliente_credito = x.id_cliente_credito WHERE x.estado <> 'ANULADA') m "
          + "WHERE (@cliente IS NULL OR m.id_c = @cliente)) t WHERE t.fecha BETWEEN @d AND @h ORDER BY t.cliente, t.fecha, t.orden, t.ref", false),

        // ------------------------------------------------------------ CUENTAS POR COBRAR Y COBROS
        new ReporteDef("Cuentas por cobrar y cobros", "Antigüedad de saldos por cobrar (análisis de vencimiento)",
            "SELECT x.cliente AS `Cliente`, COALESCE(z.nombre, '(sin zona)') AS `Zona`, SUM(IF(x.dias_vencido = 0, x.saldo, 0)) AS `Por vencer`, "
          + "SUM(IF(x.dias_vencido BETWEEN 1 AND 30, x.saldo, 0)) AS `1-30 días`, SUM(IF(x.dias_vencido BETWEEN 31 AND 60, x.saldo, 0)) AS `31-60 días`, "
          + "SUM(IF(x.dias_vencido BETWEEN 61 AND 90, x.saldo, 0)) AS `61-90 días`, SUM(IF(x.dias_vencido > 90, x.saldo, 0)) AS `Más de 90`, SUM(x.saldo) AS `Total` "
          + "FROM v_cuentas_por_cobrar x JOIN clientes_credito c ON c.id_cliente_credito = x.id_cliente_credito LEFT JOIN zonas z ON z.id_zona = c.id_zona "
          + "WHERE x.estado IN ('PENDIENTE','PARCIAL') GROUP BY x.cliente, z.nombre ORDER BY SUM(x.saldo) DESC", false),
        new ReporteDef("Cuentas por cobrar y cobros", "Relación de cobros (contado y abonos)",
            "SELECT r.fecha AS `Fecha`, r.cliente AS `Cliente`, r.factura AS `Factura`, r.tipo AS `Tipo de cobro`, r.metodo AS `Forma de pago`, COALESCE(r.referencia, '') AS `Referencia`, "
          + "r.monto AS `Monto`, COALESCE(u.username, '-') AS `Cobró` FROM ("
          + "SELECT p.fecha_pago AS fecha, f.cliente_nombre AS cliente, f.numero_factura AS factura, 'Venta de contado' AS tipo, CAST(p.metodo AS CHAR) AS metodo, p.referencia AS referencia, p.monto AS monto, p.id_usuario AS id_usuario "
          + "FROM pagos p JOIN facturas f ON f.id_factura = p.id_factura WHERE f.estado = 'EMITIDA' AND DATE(p.fecha_pago) BETWEEN @d AND @h "
          + "UNION ALL "
          + "SELECT a.fecha, c.nombre, f.numero_factura, 'Abono a crédito', CAST(a.metodo AS CHAR), a.referencia, a.monto, a.id_usuario "
          + "FROM abonos a JOIN cuentas_por_cobrar x ON x.id_cxc = a.id_cxc JOIN facturas f ON f.id_factura = x.id_factura "
          + "JOIN clientes_credito c ON c.id_cliente_credito = x.id_cliente_credito WHERE x.estado <> 'ANULADA' AND DATE(a.fecha) BETWEEN @d AND @h) r "
          + "LEFT JOIN usuarios u ON u.id_usuario = r.id_usuario ORDER BY r.fecha", false),

        // ------------------------------------------------------------ VENTAS (nuevos)
        new ReporteDef("Ventas", "Ventas generales (por factura)",
            "SELECT v.fecha_emision AS `Fecha`, v.numero_factura AS `Factura`, v.cliente AS `Cliente`, v.zona AS `Zona`, v.tipo_cliente AS `Tipo de cliente`, v.origen AS `Origen`, "
          + "v.condicion_pago AS `Condición`, v.gravado AS `Gravado`, v.importe_exento AS `Exento`, v.importe_exonerado AS `Exonerado`, v.isv AS `ISV`, v.descuento_total AS `Descuento`, "
          + "v.total AS `Total`, COALESCE(u.username, '-') AS `Usuario` FROM " + VENTAS + " LEFT JOIN usuarios u ON u.id_usuario = v.id_usuario ORDER BY v.fecha_emision", false),

        // ------------------------------------------------------------ FISCAL
        new ReporteDef("Fiscal (SAR)", "Exoneraciones de ISV (constancias)",
            "SELECT f.fecha_emision AS `Fecha`, f.numero_factura AS `Factura`, f.cliente_nombre AS `Cliente`, COALESCE(f.cliente_rtn, '') AS `RTN`, "
          + "COALESCE(f.orden_compra_exenta, '') AS `Orden de compra exenta`, f.constancia_exoneracion AS `Constancia de registro exonerado`, COALESCE(f.registro_sag, '') AS `Registro SAG`, "
          + "f.importe_exonerado AS `Importe exonerado`, f.total AS `Total`, f.estado AS `Estado`, COALESCE(u.username, '-') AS `Usuario` "
          + "FROM facturas f LEFT JOIN usuarios u ON u.id_usuario = f.id_usuario WHERE f.constancia_exoneracion IS NOT NULL AND DATE(f.fecha_emision) BETWEEN @d AND @h ORDER BY f.fecha_emision", false),
        new ReporteDef("Fiscal (SAR)", "Libro de ventas ISV (detalle)",
            "SELECT fecha AS `Fecha`, numero_factura AS `Factura`, cliente_nombre AS `Cliente`, COALESCE(cliente_rtn, '') AS `RTN`, origen AS `Origen`, condicion_pago AS `Condición`, "
          + "importe_gravado_15 AS `Gravado 15 %`, importe_gravado_18 AS `Gravado 18 %`, importe_exento AS `Exento`, importe_exonerado AS `Exonerado`, isv_15 AS `ISV 15 %`, isv_18 AS `ISV 18 %`, "
          + "tasa_turistica AS `Tasa turística`, total AS `Total`, estado AS `Estado` FROM v_libro_ventas WHERE fecha BETWEEN @d AND @h ORDER BY numero_factura", false),

        // ------------------------------------------------------------ PROVEEDORES
        new ReporteDef("Proveedores y compras", "Listado general de proveedores",
            "SELECT p.codigo AS `Código`, p.nombre AS `Proveedor`, COALESCE(p.rtn, '') AS `RTN`, COALESCE(z.nombre, '(sin zona)') AS `Zona`, COALESCE(p.ciudad, '') AS `Ciudad`, "
          + "COALESCE(p.telefono, p.celular, '') AS `Teléfono`, p.dias_credito AS `Días de crédito`, p.limite_credito AS `Límite`, COALESCE(s.saldo, 0) AS `Saldo por pagar`, "
          + "IF(p.es_exento = 1, 'Sí', 'No') AS `Exento`, IF(p.activo = 1, 'Activo', 'Inactivo') AS `Estado`, COALESCE(v.n, 0) AS `Compras`, COALESCE(v.t, 0) AS `Total comprado`, v.u AS `Última compra` "
          + "FROM proveedores p LEFT JOIN zonas z ON z.id_zona = p.id_zona "
          + "LEFT JOIN (SELECT id_proveedor, SUM(saldo) AS saldo FROM cuentas_por_pagar WHERE estado = 'PENDIENTE' GROUP BY id_proveedor) s ON s.id_proveedor = p.id_proveedor "
          + "LEFT JOIN (SELECT id_proveedor, COUNT(*) AS n, SUM(total) AS t, MAX(fecha) AS u FROM compras WHERE estado = 'RECIBIDA' GROUP BY id_proveedor) v ON v.id_proveedor = p.id_proveedor ORDER BY p.nombre", false),
        new ReporteDef("Proveedores y compras", "Antigüedad de saldos por pagar (análisis de vencimiento)",
            "SELECT p.nombre AS `Proveedor`, COALESCE(z.nombre, '(sin zona)') AS `Zona`, SUM(IF(DATEDIFF(CURDATE(), x.fecha_vencimiento) <= 0, x.saldo, 0)) AS `Por vencer`, "
          + "SUM(IF(DATEDIFF(CURDATE(), x.fecha_vencimiento) BETWEEN 1 AND 30, x.saldo, 0)) AS `1-30 días`, SUM(IF(DATEDIFF(CURDATE(), x.fecha_vencimiento) BETWEEN 31 AND 60, x.saldo, 0)) AS `31-60 días`, "
          + "SUM(IF(DATEDIFF(CURDATE(), x.fecha_vencimiento) BETWEEN 61 AND 90, x.saldo, 0)) AS `61-90 días`, SUM(IF(DATEDIFF(CURDATE(), x.fecha_vencimiento) > 90, x.saldo, 0)) AS `Más de 90`, SUM(x.saldo) AS `Total` "
          + "FROM cuentas_por_pagar x JOIN proveedores p ON p.id_proveedor = x.id_proveedor LEFT JOIN zonas z ON z.id_zona = p.id_zona "
          + "WHERE x.estado = 'PENDIENTE' GROUP BY p.nombre, z.nombre ORDER BY SUM(x.saldo) DESC", false),
        new ReporteDef("Proveedores y compras", "Estado de cuenta de proveedor",
            "SELECT t.proveedor AS `Proveedor`, t.fecha AS `Fecha`, t.tipo AS `Movimiento`, t.documento AS `Documento`, t.cargo AS `Compras a crédito`, t.abono AS `Pagos`, t.saldo AS `Saldo` FROM ("
          + "SELECT m.id_p, m.proveedor, m.fecha, m.tipo, m.documento, m.cargo, m.abono, m.orden, m.ref, "
          + "SUM(m.cargo - m.abono) OVER (PARTITION BY m.id_p ORDER BY m.fecha, m.orden, m.ref) AS saldo FROM ("
          + "SELECT p.id_proveedor AS id_p, p.nombre AS proveedor, x.fecha_emision AS fecha, 'Compra a crédito' AS tipo, COALESCE(c.numero_documento, CONCAT('Compra #', c.id_compra)) AS documento, "
          + "x.monto AS cargo, 0 AS abono, 1 AS orden, x.id_cxp AS ref FROM cuentas_por_pagar x JOIN compras c ON c.id_compra = x.id_compra JOIN proveedores p ON p.id_proveedor = x.id_proveedor "
          + "WHERE c.estado <> 'ANULADA' "
          + "UNION ALL "
          + "SELECT p.id_proveedor, p.nombre, g.fecha, CONCAT('Pago ', g.metodo), COALESCE(g.referencia, c.numero_documento, CONCAT('Compra #', c.id_compra)), 0, g.monto, 2, g.id_pago "
          + "FROM pagos_proveedor g JOIN cuentas_por_pagar x ON x.id_cxp = g.id_cxp JOIN compras c ON c.id_compra = x.id_compra JOIN proveedores p ON p.id_proveedor = x.id_proveedor "
          + "WHERE c.estado <> 'ANULADA') m WHERE (@proveedor IS NULL OR m.id_p = @proveedor)) t WHERE t.fecha BETWEEN @d AND @h ORDER BY t.proveedor, t.fecha, t.orden, t.ref", false),
        new ReporteDef("Ventas", "Ventas por cliente (por nombre en la factura)",
            "SELECT cliente_nombre AS `Cliente`, COALESCE(cliente_rtn, '') AS `RTN`, COUNT(*) AS `Facturas`, ROUND(SUM(total), 2) AS `Total` FROM facturas "
          + "WHERE estado = 'EMITIDA' AND DATE(fecha_emision) BETWEEN @d AND @h GROUP BY cliente_nombre, cliente_rtn ORDER BY SUM(total) DESC", false)
    );
}
