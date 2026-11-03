package hn.hotel.service;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.db.Tx;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Reglas del restaurante: comandas, envío a cocina, cuenta, cargo a habitación. */
public final class RestauranteService {
    private RestauranteService() {}

    /** Devuelve la comanda abierta de la mesa, o crea una nueva. mesa == null → para llevar. */
    public static int abrirComanda(String tipo, Integer idMesa, int personas) {
        Long r = Db.transaccion(tx -> {
            if (idMesa != null) {
                Map<String, Object> m = tx.uno("SELECT estado FROM mesas WHERE id_mesa = ? FOR UPDATE", idMesa);
                if (m == null) throw new IllegalArgumentException("La mesa no existe.");
                if ("INACTIVA".equals(Db.str(m.get("estado")))) throw new IllegalArgumentException("La mesa está inactiva.");
                Object existente = tx.scalar("SELECT id_comanda FROM comandas WHERE id_mesa = ? AND estado IN ('ABIERTA','CERRADA') LIMIT 1", idMesa);
                if (existente != null) return Db.lng(existente);
                tx.exec("UPDATE mesas SET estado = 'OCUPADA' WHERE id_mesa = ?", idMesa);
            }
            long id = tx.insert("INSERT INTO comandas (tipo, id_mesa, num_personas, id_usuario) VALUES (?,?,?,?)",
                    tipo, idMesa, Math.max(1, personas), Sesion.idUsuario);
            Bitacora.registrar("ABRIR", "comanda", id, tipo);
            return id;
        });
        return r.intValue();
    }

    private static void exigirAbierta(Tx tx, int idComanda) throws Exception {
        Map<String, Object> c = tx.uno("SELECT estado, id_usuario FROM comandas WHERE id_comanda = ? FOR UPDATE", idComanda);
        if (c == null) throw new IllegalArgumentException("La comanda no existe.");
        String e = Db.str(c.get("estado"));
        if (!"ABIERTA".equals(e)) throw new IllegalArgumentException("La comanda ya no está abierta (estado " + e + ").");
        exigirPropia(tx, c.get("id_usuario"));
    }

    /** Un mesero solo trabaja sus propias mesas (se puede desactivar en Configuración). */
    private static void exigirPropia(Tx tx, Object idMesero) throws Exception {
        if (idMesero == null || Sesion.permite("pos.ver_todas")) return;
        Object v = tx.scalar("SELECT valor FROM configuracion WHERE clave = 'mesero_ve_solo_suyas'");
        if (v != null && "SI".equalsIgnoreCase(v.toString()) && Db.ent(idMesero) != Sesion.idUsuario)
            throw new IllegalArgumentException("Esta mesa la atiende otro mesero. Pida a un supervisor que se la transfiera.");
    }

    public static boolean puedeCobrar() {
        return Sesion.permite("pos.cobrar");
    }

    public static boolean esSupervisor() {
        return Sesion.permite("pos.anular_enviado");
    }

    public static void agregarPlato(int idComanda, int idProducto, BigDecimal cantidad, String notas) {
        agregarPlato(idComanda, idProducto, cantidad, notas, 1);
    }

    public static void agregarPlato(int idComanda, int idProducto, BigDecimal cantidad, String notas, int numCuenta) {
        agregarPlato(idComanda, idProducto, cantidad, notas, numCuenta, List.of());
    }

    /** Agrega un plato con las opciones elegidas (término, extras...). Valida mínimos y máximos de cada grupo y suma los recargos al precio. */
    public static void agregarPlato(int idComanda, int idProducto, BigDecimal cantidad, String notas, int numCuenta, List<Integer> opciones) {
        if (cantidad == null || cantidad.signum() <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
        Db.transaccion(tx -> {
            exigirAbierta(tx, idComanda);
            Map<String, Object> p = tx.uno("SELECT p.nombre, p.precio, p.tratamiento_fiscal, p.tasa_isv, p.activo, c.tipo AS tipo_linea "
                    + "FROM productos_menu p JOIN categorias_menu c ON c.id_categoria = p.id_categoria WHERE p.id_producto = ?", idProducto);
            if (p == null || Db.ent(p.get("activo")) == 0) throw new IllegalArgumentException("El producto no está disponible.");
            exigirCuentaLibre(tx, idComanda, numCuenta);
            tx.exec("UPDATE comandas SET cuenta_pedida_en = NULL WHERE id_comanda = ?", idComanda);   // si agregan algo, ya no está pidiendo la cuenta

            // opciones: cada grupo del plato respeta su mínimo y máximo
            List<Map<String, Object>> grupos = tx.query("SELECT g.id_grupo, g.nombre, g.min_sel, g.max_sel FROM producto_grupos pg JOIN grupos_opciones g ON g.id_grupo = pg.id_grupo "
                    + "WHERE pg.id_producto = ? AND g.activo = 1 AND EXISTS (SELECT 1 FROM opciones o WHERE o.id_grupo = g.id_grupo AND o.activa = 1)", idProducto);
            List<Map<String, Object>> elegidas = new java.util.ArrayList<>();
            for (Integer idOp : opciones) {
                Map<String, Object> o = tx.uno("SELECT o.id_opcion, o.id_grupo, o.nombre, o.recargo, o.id_insumo, o.cantidad_insumo FROM opciones o "
                        + "JOIN producto_grupos pg ON pg.id_grupo = o.id_grupo AND pg.id_producto = ? WHERE o.id_opcion = ? AND o.activa = 1", idProducto, idOp);
                if (o == null) throw new IllegalArgumentException("Una opción elegida ya no está disponible para este plato.");
                elegidas.add(o);
            }
            for (Map<String, Object> g : grupos) {
                long n = elegidas.stream().filter(o -> Db.ent(o.get("id_grupo")) == Db.ent(g.get("id_grupo"))).count();
                if (n < Db.ent(g.get("min_sel")))
                    throw new IllegalArgumentException("Falta elegir: " + g.get("nombre") + ".");
                if (n > Db.ent(g.get("max_sel")))
                    throw new IllegalArgumentException(g.get("nombre") + ": puede elegir máximo " + g.get("max_sel") + ".");
            }
            BigDecimal precio = Db.dec(p.get("precio"));
            Object lista = tx.scalar("SELECT COALESCE(c.id_lista, a.id_lista) FROM comandas c LEFT JOIN mesas m ON m.id_mesa = c.id_mesa "
                    + "LEFT JOIN areas_restaurante a ON a.id_area = m.id_area WHERE c.id_comanda = ?", idComanda);
            if (lista != null) {
                Object especial = tx.scalar("SELECT precio FROM precios_lista WHERE id_lista = ? AND id_producto = ?", lista, idProducto);
                if (especial != null) precio = Db.dec(especial);
            }
            StringBuilder txtOpc = new StringBuilder();
            for (Map<String, Object> o : elegidas) {
                precio = precio.add(Db.dec(o.get("recargo")));
                if (txtOpc.length() > 0) txtOpc.append(", ");
                txtOpc.append(o.get("nombre"));
            }
            String nota = notas == null ? "" : notas.trim();
            String notaFinal = txtOpc.length() == 0 ? nota : nota.isEmpty() ? txtOpc.toString() : txtOpc + " | " + nota;
            if (notaFinal.length() > 150) notaFinal = notaFinal.substring(0, 150);
            long idDet = tx.insert("INSERT INTO comanda_detalle (id_comanda, num_cuenta, id_producto, descripcion, cantidad, precio_unitario, tratamiento_fiscal, tasa_isv, tipo_linea, notas, id_usuario) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?)", idComanda, numCuenta, idProducto, p.get("nombre"), cantidad, precio,
                    p.get("tratamiento_fiscal"), p.get("tasa_isv"), p.get("tipo_linea"), notaFinal.isEmpty() ? null : notaFinal, Sesion.idUsuario);
            for (Map<String, Object> o : elegidas)
                tx.exec("INSERT INTO comanda_detalle_opciones (id_detalle, id_opcion, nombre, recargo, id_insumo, cantidad_insumo) VALUES (?,?,?,?,?,?)",
                        idDet, o.get("id_opcion"), o.get("nombre"), o.get("recargo"), o.get("id_insumo"), o.get("cantidad_insumo"));
            return null;
        });
    }

    /** Pasa los platos pendientes a cocina/barra y descuenta el inventario. Devuelve la cantidad de platos. */
    public static int enviarACocina(int idComanda) {
        return enviar(idComanda).platos();
    }

    public record Envio(int platos, List<Integer> tickets) {}

    /**
     * Envía lo pendiente: crea un ticket por estación (cocina, barra, postres...), como las comandas de Odoo.
     * La estación sale del plato o, si no la tiene, de su categoría. Lo que no tiene estación se da por servido.
     */
    public static Envio enviar(int idComanda) {
        return Db.transaccion(tx -> {
            exigirAbierta(tx, idComanda);
            List<Map<String, Object>> pend = tx.query("SELECT d.id_detalle, COALESCE(p.id_estacion, c.id_estacion) AS est FROM comanda_detalle d "
                    + "JOIN productos_menu p ON p.id_producto = d.id_producto JOIN categorias_menu c ON c.id_categoria = p.id_categoria "
                    + "WHERE d.id_comanda = ? AND d.estado = 'PENDIENTE' AND p.area_preparacion <> 'NINGUNA'", idComanda);
            int total = Db.ent(tx.scalar("SELECT COUNT(*) FROM comanda_detalle WHERE id_comanda = ? AND estado = 'PENDIENTE'", idComanda));
            if (total == 0) throw new IllegalArgumentException("No hay platos pendientes por enviar.");
            java.util.LinkedHashMap<Integer, List<Object>> porEstacion = new java.util.LinkedHashMap<>();
            for (Map<String, Object> d : pend)
                if (d.get("est") != null) porEstacion.computeIfAbsent(Db.ent(d.get("est")), k -> new java.util.ArrayList<>()).add(d.get("id_detalle"));
            List<Integer> tickets = new java.util.ArrayList<>();
            for (Map.Entry<Integer, List<Object>> e : porEstacion.entrySet()) {
                Object nombre = tx.scalar("SELECT nombre FROM estaciones WHERE id_estacion = ?", e.getKey());
                if (nombre == null) continue;
                int numero = Db.ent(tx.scalar("SELECT COALESCE(MAX(numero_dia),0) + 1 FROM tickets_cocina WHERE DATE(creado_en) = CURDATE()"));
                long idTicket = tx.insert("INSERT INTO tickets_cocina (id_comanda, destino, id_estacion, numero_dia, id_usuario) VALUES (?,?,?,?,?)",
                        idComanda, nombre.toString(), e.getKey(), numero, Sesion.idUsuario);
                for (Object id : e.getValue())
                    tx.exec("UPDATE comanda_detalle SET estado = 'ENVIADO', enviado_en = NOW(), id_ticket = ? WHERE id_detalle = ?", idTicket, id);
                tickets.add((int) idTicket);
            }
            // lo que no pasa por ninguna estación (bebidas embotelladas, postres de vitrina...) se da por servido
            tx.exec("UPDATE comanda_detalle SET estado = 'SERVIDO', enviado_en = NOW() WHERE id_comanda = ? AND estado = 'PENDIENTE'", idComanda);
            tx.call("{CALL sp_descontar_inventario_comanda(?)}", idComanda);
            descontarOpciones(tx, idComanda);
            Bitacora.registrar("ENVIAR", "comanda", idComanda, total + " plato(s)");
            return new Envio(total, tickets);
        });
    }

    /** Cambia la lista de precios de la comanda (los platos ya agregados conservan su precio). null = la de la zona / general. */
    public static void cambiarLista(int idComanda, Integer idLista) {
        Db.exec("UPDATE comandas SET id_lista = ? WHERE id_comanda = ? AND estado = 'ABIERTA'", idLista, idComanda);
        Bitacora.registrar("LISTA_PRECIOS", "comanda", idComanda, "lista " + idLista);
    }

    /** "Tirar la cuenta": marca que la mesa ya pidió la cuenta (queda la hora para controlar la espera). */
    public static void pedirCuenta(int idComanda) {
        Db.transaccion(tx -> {
            exigirAbierta(tx, idComanda);
            if (tx.scalar("SELECT 1 FROM comanda_detalle WHERE id_comanda = ? AND estado <> 'ANULADO' LIMIT 1", idComanda) == null)
                throw new IllegalArgumentException("La cuenta no tiene platos.");
            tx.exec("UPDATE comandas SET cuenta_pedida_en = NOW() WHERE id_comanda = ?", idComanda);
            Bitacora.registrar("PEDIR_CUENTA", "comanda", idComanda, "");
            return null;
        });
    }

    public static void quitarPedidoCuenta(int idComanda) {
        Db.exec("UPDATE comandas SET cuenta_pedida_en = NULL WHERE id_comanda = ? AND estado = 'ABIERTA'", idComanda);
    }

    public static void cambiarEstadoPlato(int idDetalle, String nuevo) {
        String requerido = nuevo.equals("LISTO") ? "ENVIADO" : "LISTO";
        if (nuevo.equals("SERVIDO")) requerido = "LISTO";
        int n = Db.exec("UPDATE comanda_detalle SET estado = ? WHERE id_detalle = ? AND estado IN (?, 'ENVIADO')", nuevo, idDetalle, requerido);
        if (n == 0) throw new IllegalArgumentException("El plato no está en el estado esperado.");
    }

    /** Ajusta una línea de la cuenta: «DESCUENTO» (monto en L), «PRECIO» (unitario) o «CANTIDAD» (solo si aún no se envió a cocina). */
    public static void ajustarPlato(int idDetalle, String que, BigDecimal valor, String motivo) {
        if (valor == null || valor.signum() < 0) throw new IllegalArgumentException("El valor no puede ser negativo.");
        if (motivo == null || motivo.isBlank()) throw new IllegalArgumentException("Escriba el motivo.");
        Db.transaccion(tx -> {
            Map<String, Object> d = tx.uno("SELECT d.estado, d.num_cuenta, d.id_comanda, d.cantidad, d.precio_unitario, d.descuento, c.id_usuario FROM comanda_detalle d "
                    + "JOIN comandas c ON c.id_comanda = d.id_comanda WHERE d.id_detalle = ? FOR UPDATE", idDetalle);
            if (d == null) throw new IllegalArgumentException("El plato no existe.");
            exigirPropia(tx, d.get("id_usuario"));
            exigirCuentaLibre(tx, Db.ent(d.get("id_comanda")), Db.ent(d.get("num_cuenta")));
            String estado = Db.str(d.get("estado"));
            if (estado.equals("ANULADO")) throw new IllegalArgumentException("Ese plato está anulado.");
            BigDecimal cant = Db.dec(d.get("cantidad")), precio = Db.dec(d.get("precio_unitario")), desc = Db.dec(d.get("descuento"));
            switch (que) {
                case "DESCUENTO" -> { desc = valor; }
                case "PRECIO" -> { precio = valor; }
                case "CANTIDAD" -> {
                    if (!estado.equals("PENDIENTE")) throw new IllegalArgumentException("Solo se cambia la cantidad de platos que aún no se envían a cocina.");
                    if (valor.signum() <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
                    cant = valor;
                }
                default -> throw new IllegalArgumentException("Ajuste no válido.");
            }
            if (desc.compareTo(cant.multiply(precio)) > 0) throw new IllegalArgumentException("El descuento no puede ser mayor que el importe del plato.");
            tx.exec("UPDATE comanda_detalle SET cantidad = ?, precio_unitario = ?, descuento = ? WHERE id_detalle = ?", cant, precio, desc, idDetalle);
            Bitacora.registrar("AJUSTE_PLATO", "comanda_detalle", idDetalle, que + " → " + valor + " (" + motivo + ")");
            return null;
        });
    }

    /** Anula un plato. Si aún no estaba listo, regresa los ingredientes al inventario. */
    public static void anularPlato(int idDetalle, String motivo) {
        Db.transaccion(tx -> {
            Map<String, Object> det = tx.uno("SELECT d.estado, d.id_ticket, d.num_cuenta, d.id_comanda, c.id_usuario FROM comanda_detalle d "
                    + "JOIN comandas c ON c.id_comanda = d.id_comanda WHERE d.id_detalle = ?", idDetalle);
            if (det == null) throw new IllegalArgumentException("El plato no existe.");
            exigirPropia(tx, det.get("id_usuario"));
            exigirCuentaLibre(tx, Db.ent(det.get("id_comanda")), Db.ent(det.get("num_cuenta")));
            String estado = Db.str(det.get("estado"));
            if (!estado.equals("PENDIENTE") && !esSupervisor())
                throw new IllegalArgumentException("El plato ya fue enviado a cocina. Un supervisor debe autorizar la anulación.");
            boolean devolver = estado.equals("PENDIENTE") || estado.equals("ENVIADO");
            tx.call("{CALL sp_anular_detalle_comanda(?,?,?)}", idDetalle, motivo, devolver ? 1 : 0);
            if (devolver) devolverOpciones(tx, idDetalle);
            if (det.get("id_ticket") != null)
                tx.exec("UPDATE tickets_cocina t SET t.estado = 'CANCELADO' WHERE t.id_ticket = ? AND t.estado IN ('NUEVO','PREPARANDO') "
                      + "AND NOT EXISTS (SELECT 1 FROM comanda_detalle d WHERE d.id_ticket = t.id_ticket AND d.estado <> 'ANULADO')", det.get("id_ticket"));
            Bitacora.registrar("ANULAR_PLATO", "comanda_detalle", idDetalle, motivo);
            return null;
        });
    }

    public static void cerrarCuenta(int idComanda, BigDecimal propina) {
        if (propina == null || propina.signum() < 0) throw new IllegalArgumentException("La propina no puede ser negativa.");
        Db.transaccion(tx -> {
            exigirAbierta(tx, idComanda);
            if (tx.scalar("SELECT 1 FROM comanda_detalle WHERE id_comanda = ? AND estado <> 'ANULADO' LIMIT 1", idComanda) == null)
                throw new IllegalArgumentException("La comanda no tiene platos.");
            tx.call("{CALL sp_descontar_inventario_comanda(?)}", idComanda);
            descontarOpciones(tx, idComanda);
            tx.exec("UPDATE comandas SET estado = 'CERRADA', cerrada_en = NOW(), propina = ? WHERE id_comanda = ?", propina, idComanda);
            return null;
        });
    }

    /**
     * Autoconsumo: la cuenta NO genera venta ni factura, pero descarga todo su inventario (platos y opciones).
     * Los movimientos quedan como tipo AUTOCONSUMO en el kardex y el costo se guarda para el reporte.
     */
    public static void autoconsumo(int idComanda, int idMotivo, String notas) {
        Db.transaccion(tx -> {
            exigirAbierta(tx, idComanda);
            if (tx.scalar("SELECT 1 FROM comanda_detalle WHERE id_comanda = ? AND estado <> 'ANULADO' LIMIT 1", idComanda) == null)
                throw new IllegalArgumentException("La cuenta no tiene platos.");
            if (tx.scalar("SELECT 1 FROM motivos_autoconsumo WHERE id_motivo = ? AND activo = 1", idMotivo) == null)
                throw new IllegalArgumentException("Elija un motivo de autoconsumo válido.");
            tx.call("{CALL sp_descontar_inventario_comanda(?)}", idComanda);
            descontarOpciones(tx, idComanda);
            String ref = "Autoconsumo #" + idComanda;
            tx.exec("UPDATE movimientos_inventario SET tipo = 'AUTOCONSUMO', referencia = ? WHERE tipo = 'SALIDA_VENTA' AND "
                    + "(id_detalle_comanda IN (SELECT id_detalle FROM comanda_detalle WHERE id_comanda = ?) OR referencia IN (?, ?))",
                    ref, idComanda, "Comanda #" + idComanda, "Comanda #" + idComanda + " (opción)");
            Object costo = tx.scalar("SELECT COALESCE(SUM(-cantidad * costo_unitario), 0) FROM movimientos_inventario WHERE tipo = 'AUTOCONSUMO' AND referencia = ?", ref);
            tx.exec("INSERT INTO autoconsumos (id_comanda, id_motivo, id_usuario, id_autoriza, costo_total, notas) VALUES (?,?,?,?,?,?)",
                    idComanda, idMotivo, Sesion.idUsuario, Sesion.autorizadoPor > 0 ? Sesion.autorizadoPor : null, costo,
                    notas == null || notas.isBlank() ? null : notas.trim());
            tx.exec("UPDATE comandas SET estado = 'AUTOCONSUMO', cerrada_en = NOW() WHERE id_comanda = ?", idComanda);
            liberarMesaSiCorresponde(tx, idComanda);
            Bitacora.registrar("AUTOCONSUMO", "comanda", idComanda, "motivo " + idMotivo + ", costo " + costo);
            return null;
        });
    }

    public static void reabrir(int idComanda) {
        int n = Db.exec("UPDATE comandas SET estado = 'ABIERTA', cerrada_en = NULL WHERE id_comanda = ? AND estado = 'CERRADA'", idComanda);
        if (n == 0) throw new IllegalArgumentException("Solo se reabren cuentas cerradas que no se han cobrado.");
    }

    public static void cargarAHabitacion(int idComanda, int idEstancia) {
        Db.transaccion(tx -> {
            if (tx.scalar("SELECT 1 FROM comanda_cuentas WHERE id_comanda = ? AND id_factura IS NOT NULL LIMIT 1", idComanda) != null)
                throw new IllegalArgumentException("Esta mesa ya tiene cuentas cobradas por separado; cobre el resto en caja.");
            descontarOpciones(tx, idComanda);
            tx.call("{CALL sp_cargar_comanda_a_habitacion(?,?,?)}", idComanda, idEstancia, Sesion.idUsuario);
            Bitacora.registrar("CARGO_HABITACION", "comanda", idComanda, "estancia " + idEstancia);
            return null;
        });
    }

    public static void anularComanda(int idComanda, String motivo) {
        if (motivo == null || motivo.trim().length() < 3) throw new IllegalArgumentException("Escriba el motivo de la anulación.");
        Db.transaccion(tx -> {
            Object e = tx.scalar("SELECT estado FROM comandas WHERE id_comanda = ? FOR UPDATE", idComanda);
            if (e == null || !(e.toString().equals("ABIERTA") || e.toString().equals("CERRADA")))
                throw new IllegalArgumentException("Solo se anulan comandas abiertas o cerradas sin cobrar.");
            if (e.toString().equals("CERRADA"))
                tx.exec("UPDATE comandas SET estado = 'ABIERTA' WHERE id_comanda = ?", idComanda);
            List<Map<String, Object>> platos = tx.query("SELECT id_detalle, estado FROM comanda_detalle WHERE id_comanda = ? AND estado <> 'ANULADO'", idComanda);
            for (Map<String, Object> d : platos) {
                String est = Db.str(d.get("estado"));
                boolean devolver = est.equals("PENDIENTE") || est.equals("ENVIADO");
                tx.call("{CALL sp_anular_detalle_comanda(?,?,?)}", d.get("id_detalle"), motivo.trim(), devolver ? 1 : 0);
                if (devolver) devolverOpciones(tx, Db.ent(d.get("id_detalle")));
            }
            tx.exec("UPDATE comandas SET estado = 'ANULADA', motivo_anulacion = ?, cerrada_en = NOW() WHERE id_comanda = ?", motivo.trim(), idComanda);
            liberarMesaSiCorresponde(tx, idComanda);
            Bitacora.registrar("ANULAR", "comanda", idComanda, motivo);
            return null;
        });
    }

    // ------------------------------------------------------------------ dividir cuentas
    private static void exigirCuentaLibre(Tx tx, int idComanda, int numCuenta) throws Exception {
        if (numCuenta < 1 || numCuenta > 20) throw new IllegalArgumentException("Número de cuenta no válido (1 a 20).");
        if (tx.scalar("SELECT 1 FROM comanda_cuentas WHERE id_comanda = ? AND num_cuenta = ? AND id_factura IS NOT NULL", idComanda, numCuenta) != null)
            throw new IllegalArgumentException("La cuenta " + numCuenta + " ya fue cobrada; no se puede modificar.");
    }

    /** Mueve platos completos a otra subcuenta (cada subcuenta se cobra y factura por separado). */
    public static void moverPlatos(int idComanda, List<Integer> idDetalles, int numCuentaDestino) {
        if (idDetalles.isEmpty()) throw new IllegalArgumentException("Seleccione al menos un plato.");
        Db.transaccion(tx -> {
            exigirAbierta(tx, idComanda);
            exigirCuentaLibre(tx, idComanda, numCuentaDestino);
            for (int id : idDetalles) {
                Map<String, Object> d = tx.uno("SELECT num_cuenta, estado FROM comanda_detalle WHERE id_detalle = ? AND id_comanda = ? FOR UPDATE", id, idComanda);
                if (d == null || "ANULADO".equals(Db.str(d.get("estado")))) throw new IllegalArgumentException("Un plato seleccionado ya no existe o está anulado.");
                exigirCuentaLibre(tx, idComanda, Db.ent(d.get("num_cuenta")));
                tx.exec("UPDATE comanda_detalle SET num_cuenta = ? WHERE id_detalle = ?", numCuentaDestino, id);
            }
            tx.exec("INSERT IGNORE INTO comanda_cuentas (id_comanda, num_cuenta) VALUES (?,?)", idComanda, numCuentaDestino);
            Bitacora.registrar("DIVIDIR", "comanda", idComanda, idDetalles.size() + " plato(s) a cuenta " + numCuentaDestino);
            return null;
        });
    }

    /** Parte un renglón: p. ej. 2 pizzas -> mueve 1 (o 0.5 si se comparte) a otra subcuenta. */
    public static void dividirPlato(int idDetalle, BigDecimal cantidadMover, int numCuentaDestino) {
        if (cantidadMover == null || cantidadMover.signum() <= 0) throw new IllegalArgumentException("La cantidad a mover debe ser mayor que cero.");
        Db.transaccion(tx -> {
            Map<String, Object> d = tx.uno("SELECT * FROM comanda_detalle WHERE id_detalle = ? FOR UPDATE", idDetalle);
            if (d == null || "ANULADO".equals(Db.str(d.get("estado")))) throw new IllegalArgumentException("El plato no existe o está anulado.");
            int idComanda = Db.ent(d.get("id_comanda"));
            exigirAbierta(tx, idComanda);
            exigirCuentaLibre(tx, idComanda, Db.ent(d.get("num_cuenta")));
            exigirCuentaLibre(tx, idComanda, numCuentaDestino);
            BigDecimal total = Db.dec(d.get("cantidad"));
            if (cantidadMover.compareTo(total) >= 0)
                throw new IllegalArgumentException("Para mover todo el renglón use «Mover a cuenta». Aquí indique una cantidad menor que " + total.stripTrailingZeros().toPlainString() + ".");
            BigDecimal resto = total.subtract(cantidadMover);
            // el descuento (si hay) se reparte proporcional
            BigDecimal desc = Db.dec(d.get("descuento"));
            BigDecimal descMov = desc.multiply(cantidadMover).divide(total, 2, java.math.RoundingMode.HALF_UP);
            tx.exec("UPDATE comanda_detalle SET cantidad = ?, descuento = ? WHERE id_detalle = ?", resto, desc.subtract(descMov), idDetalle);
            long idNuevo = tx.insert("INSERT INTO comanda_detalle (id_comanda, num_cuenta, id_producto, descripcion, cantidad, precio_unitario, descuento, tratamiento_fiscal, tasa_isv, "
                    + "tipo_linea, notas, estado, id_ticket, inventario_descontado, enviado_en, id_usuario) SELECT id_comanda, ?, id_producto, descripcion, ?, precio_unitario, ?, "
                    + "tratamiento_fiscal, tasa_isv, tipo_linea, notas, estado, id_ticket, inventario_descontado, enviado_en, id_usuario FROM comanda_detalle WHERE id_detalle = ?",
                    numCuentaDestino, cantidadMover, descMov, idDetalle);
            tx.exec("INSERT INTO comanda_detalle_opciones (id_detalle, id_opcion, nombre, recargo, id_insumo, cantidad_insumo, descontado) "
                  + "SELECT ?, id_opcion, nombre, recargo, id_insumo, cantidad_insumo, descontado FROM comanda_detalle_opciones WHERE id_detalle = ?", idNuevo, idDetalle);
            tx.exec("INSERT IGNORE INTO comanda_cuentas (id_comanda, num_cuenta) VALUES (?,?)", idComanda, numCuentaDestino);
            Bitacora.registrar("DIVIDIR", "comanda_detalle", idDetalle, cantidadMover + " a cuenta " + numCuentaDestino);
            return null;
        });
    }

    public static void ponerEtiquetaCuenta(int idComanda, int numCuenta, String etiqueta) {
        Db.exec("INSERT INTO comanda_cuentas (id_comanda, num_cuenta, etiqueta) VALUES (?,?,?) ON DUPLICATE KEY UPDATE etiqueta = VALUES(etiqueta)",
                idComanda, numCuenta, etiqueta == null || etiqueta.isBlank() ? null : etiqueta.trim());
    }

    /** Suma una propina voluntaria a la comanda (cada subcuenta puede dejar la suya). */
    public static void sumarPropina(int idComanda, BigDecimal propina) {
        if (propina == null || propina.signum() < 0) throw new IllegalArgumentException("La propina no puede ser negativa.");
        if (propina.signum() > 0) Db.exec("UPDATE comandas SET propina = propina + ? WHERE id_comanda = ?", propina, idComanda);
    }

    // ------------------------------------------------------------------ mesas y meseros
    public static void moverAMesa(int idComanda, int idMesaDestino) {
        Db.transaccion(tx -> {
            exigirAbierta(tx, idComanda);
            Map<String, Object> m = tx.uno("SELECT estado FROM mesas WHERE id_mesa = ? FOR UPDATE", idMesaDestino);
            if (m == null || !"LIBRE".equals(Db.str(m.get("estado")))) throw new IllegalArgumentException("La mesa destino no está libre.");
            Object origen = tx.scalar("SELECT id_mesa FROM comandas WHERE id_comanda = ?", idComanda);
            tx.exec("UPDATE comandas SET id_mesa = ?, tipo = 'SALON' WHERE id_comanda = ?", idMesaDestino, idComanda);
            tx.exec("UPDATE mesas SET estado = 'OCUPADA' WHERE id_mesa = ?", idMesaDestino);
            if (origen != null) liberarMesaSiCorresponde(tx, Db.ent(origen), idComanda);
            Bitacora.registrar("MOVER_MESA", "comanda", idComanda, "a mesa " + idMesaDestino);
            return null;
        });
    }

    private static void liberarMesaSiCorresponde(Tx tx, int idMesa, int idComandaExcluida) throws Exception {
        tx.exec("UPDATE mesas SET estado = 'LIBRE' WHERE id_mesa = ? AND estado = 'OCUPADA' "
              + "AND NOT EXISTS (SELECT 1 FROM comandas c WHERE c.id_mesa = ? AND c.id_comanda <> ? AND c.estado IN ('ABIERTA','CERRADA'))", idMesa, idMesa, idComandaExcluida);
    }

    /** Solo supervisor/administrador: pasa la mesa a otro mesero. */
    public static void transferirMesero(int idComanda, int idMeseroNuevo) {
        if (!Sesion.permite("pos.transferir")) throw new IllegalArgumentException("Su usuario no puede transferir mesas entre meseros.");
        int n = Db.exec("UPDATE comandas SET id_usuario = ? WHERE id_comanda = ? AND estado IN ('ABIERTA','CERRADA') "
                + "AND EXISTS (SELECT 1 FROM usuarios WHERE id_usuario = ? AND activo = 1)", idMeseroNuevo, idComanda, idMeseroNuevo);
        if (n == 0) throw new IllegalArgumentException("No se pudo transferir (comanda cerrada o usuario inactivo).");
        Bitacora.registrar("TRANSFERIR", "comanda", idComanda, "a usuario " + idMeseroNuevo);
    }

    public static void liberarMesaSiCorresponde(Tx tx, int idComanda) throws Exception {
        Object mesa = tx.scalar("SELECT id_mesa FROM comandas WHERE id_comanda = ?", idComanda);
        if (mesa != null)
            tx.exec("UPDATE mesas SET estado = 'LIBRE' WHERE id_mesa = ? AND estado = 'OCUPADA' "
                  + "AND NOT EXISTS (SELECT 1 FROM comandas c WHERE c.id_mesa = ? AND c.estado IN ('ABIERTA','CERRADA'))", mesa, mesa);
    }

    // ------------------------------------------------------------------ inventario de las opciones
    /** Descuenta del inventario los ingredientes de las opciones elegidas (p. ej. queso extra = 30 g). */
    private static void descontarOpciones(Tx tx, int idComanda) throws Exception {
        List<Map<String, Object>> filas = tx.query("SELECT o.id_det_opcion, o.id_insumo, o.cantidad_insumo * d.cantidad AS uso, i.costo_promedio FROM comanda_detalle_opciones o "
                + "JOIN comanda_detalle d ON d.id_detalle = o.id_detalle JOIN insumos i ON i.id_insumo = o.id_insumo "
                + "WHERE d.id_comanda = ? AND d.estado <> 'ANULADO' AND o.descontado = 0 AND o.cantidad_insumo > 0", idComanda);
        for (Map<String, Object> f : filas) {
            tx.exec("INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, costo_unitario, referencia) VALUES (?,'SALIDA_VENTA',?,?,?)",
                    f.get("id_insumo"), Db.dec(f.get("uso")).negate(), f.get("costo_promedio"), "Comanda #" + idComanda + " (opción)");
            tx.exec("UPDATE insumos SET stock_actual = stock_actual - ? WHERE id_insumo = ?", f.get("uso"), f.get("id_insumo"));
            tx.exec("UPDATE comanda_detalle_opciones SET descontado = 1 WHERE id_det_opcion = ?", f.get("id_det_opcion"));
        }
    }

    private static void devolverOpciones(Tx tx, int idDetalle) throws Exception {
        List<Map<String, Object>> filas = tx.query("SELECT o.id_det_opcion, o.id_insumo, o.cantidad_insumo * d.cantidad AS uso, i.costo_promedio FROM comanda_detalle_opciones o "
                + "JOIN comanda_detalle d ON d.id_detalle = o.id_detalle JOIN insumos i ON i.id_insumo = o.id_insumo "
                + "WHERE o.id_detalle = ? AND o.descontado = 1 AND o.cantidad_insumo > 0", idDetalle);
        for (Map<String, Object> f : filas) {
            tx.exec("INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, costo_unitario, referencia, id_detalle_comanda) VALUES (?,'DEVOLUCION',?,?,?,?)",
                    f.get("id_insumo"), f.get("uso"), f.get("costo_promedio"), "Anulación (opción)", idDetalle);
            tx.exec("UPDATE insumos SET stock_actual = stock_actual + ? WHERE id_insumo = ?", f.get("uso"), f.get("id_insumo"));
            tx.exec("UPDATE comanda_detalle_opciones SET descontado = 0 WHERE id_det_opcion = ?", f.get("id_det_opcion"));
        }
    }
}
