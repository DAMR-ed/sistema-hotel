package hn.hotel.service;

import hn.hotel.app.Config;
import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.db.Tx;
import hn.hotel.ui.Ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Reglas del hotel: disponibilidad, reservas, check-in, extensiones, consumos. */
public final class HotelService {
    private HotelService() {}

    /** Habitaciones que se pueden reservar entre llegada (incl.) y salida (excl.). */
    public static List<Map<String, Object>> disponibles(LocalDate llegada, LocalDate salida, Integer idTipo, Integer excluirReserva) {
        return Db.query(
            "SELECT v.id_habitacion, v.numero, v.piso, v.tipo, v.capacidad, v.precio_noche, v.estado "
          + "FROM v_habitaciones v WHERE v.activa = 1 AND v.estado <> 'REPARACION' "
          + "AND (? IS NULL OR v.id_tipo = ?) "
          + "AND NOT EXISTS (SELECT 1 FROM reservas r WHERE r.id_habitacion = v.id_habitacion "
          + "  AND r.estado IN ('PENDIENTE','CONFIRMADA','EN_CASA') AND (? IS NULL OR r.id_reserva <> ?) "
          + "  AND ? < r.fecha_salida AND ? > r.fecha_llegada) "
          + "ORDER BY v.numero",
            idTipo, idTipo, excluirReserva, excluirReserva, llegada, salida);
    }

    private static void validarFechas(LocalDate llegada, LocalDate salida) {
        if (llegada == null || salida == null) throw new IllegalArgumentException("Indique las fechas de llegada y salida.");
        if (!salida.isAfter(llegada)) throw new IllegalArgumentException("La salida debe ser después de la llegada.");
        if (ChronoUnit.DAYS.between(llegada, salida) > 365) throw new IllegalArgumentException("La estadía no puede pasar de 365 noches.");
    }

    public static BigDecimal recargoPorPersonas(int personas, int capacidad, long noches) {
        int extra = Math.max(0, personas - capacidad);
        return Config.numero("recargo_persona_extra", "0").multiply(BigDecimal.valueOf((long) extra * noches));
    }

    // ---------------------------------------------------------------- reservas
    public static long crearReserva(int idHuesped, int idHabitacion, LocalDate llegada, LocalDate salida,
                                    int personas, BigDecimal anticipo, Integer idCredito, String obs, boolean confirmada) {
        validarFechas(llegada, salida);
        if (llegada.isBefore(LocalDate.now())) throw new IllegalArgumentException("La fecha de llegada ya pasó.");
        if (personas < 1) throw new IllegalArgumentException("Debe haber al menos 1 persona.");
        if (anticipo == null || anticipo.signum() < 0) throw new IllegalArgumentException("El anticipo no puede ser negativo.");
        return Db.transaccion(tx -> {
            Map<String, Object> h = tx.uno("SELECT h.id_habitacion, h.activa, t.capacidad FROM habitaciones h "
                    + "JOIN tipos_habitacion t ON t.id_tipo = h.id_tipo WHERE h.id_habitacion = ? FOR UPDATE", idHabitacion);
            if (h == null || Db.ent(h.get("activa")) == 0) throw new IllegalArgumentException("La habitación no existe o está inactiva.");
            long noches = ChronoUnit.DAYS.between(llegada, salida);
            BigDecimal recargo = recargoPorPersonas(personas, Db.ent(h.get("capacidad")), noches);
            long id = tx.insert("INSERT INTO reservas (id_huesped, id_habitacion, fecha_llegada, fecha_salida, num_personas, "
                    + "recargo, anticipo, id_cliente_credito, estado, observaciones, id_usuario) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    idHuesped, idHabitacion, llegada, salida, personas, recargo, anticipo, idCredito,
                    confirmada ? "CONFIRMADA" : "PENDIENTE", vacioANull(obs), Sesion.idUsuario);
            Bitacora.registrar("CREAR", "reserva", id, "Hab " + idHabitacion + " " + llegada + " a " + salida);
            return id;
        });
    }

    public static void confirmarReserva(int idReserva) {
        int n = Db.exec("UPDATE reservas SET estado = 'CONFIRMADA' WHERE id_reserva = ? AND estado = 'PENDIENTE'", idReserva);
        if (n == 0) throw new IllegalArgumentException("Solo se confirman reservas pendientes.");
        Bitacora.registrar("CONFIRMAR", "reserva", idReserva, null);
    }

    public static void cancelarReserva(int idReserva, String motivo) {
        if (motivo == null || motivo.trim().length() < 3) throw new IllegalArgumentException("Escriba el motivo de la cancelación.");
        int n = Db.exec("UPDATE reservas SET estado = 'CANCELADA', observaciones = LEFT(CONCAT(COALESCE(observaciones,''),' [CANCELADA: ', ?, ']'),255) "
                + "WHERE id_reserva = ? AND estado IN ('PENDIENTE','CONFIRMADA')", motivo.trim(), idReserva);
        if (n == 0) throw new IllegalArgumentException("Solo se cancelan reservas pendientes o confirmadas.");
        Bitacora.registrar("CANCELAR", "reserva", idReserva, motivo);
    }

    public static void marcarNoShow(int idReserva) {
        int n = Db.exec("UPDATE reservas SET estado = 'NO_SHOW' WHERE id_reserva = ? AND estado IN ('PENDIENTE','CONFIRMADA') AND fecha_llegada < CURDATE()", idReserva);
        if (n == 0) throw new IllegalArgumentException("Solo se marca 'no llegó' una reserva vencida (llegada anterior a hoy).");
        Bitacora.registrar("NO_SHOW", "reserva", idReserva, null);
    }

    // ---------------------------------------------------------------- check-in
    /** Convierte una reserva en estadía activa. Devuelve el id de la estadía. */
    public static long checkInReserva(int idReserva) {
        return Db.transaccion(tx -> {
            Map<String, Object> r = tx.uno("SELECT * FROM reservas WHERE id_reserva = ? FOR UPDATE", idReserva);
            if (r == null) throw new IllegalArgumentException("La reserva no existe.");
            String estado = Db.str(r.get("estado"));
            if (!estado.equals("PENDIENTE") && !estado.equals("CONFIRMADA"))
                throw new IllegalArgumentException("La reserva está en estado " + estado + "; no se puede hacer check-in.");
            LocalDate llegada = Ui.fecha(r.get("fecha_llegada"));
            if (llegada.isAfter(LocalDate.now()))
                throw new IllegalArgumentException("La reserva es para el " + Ui.texto(llegada) + ". Aún no es la fecha de llegada.");
            long id = abrirEstancia(tx, idReserva, Db.ent(r.get("id_huesped")), Db.ent(r.get("id_habitacion")),
                    Ui.fecha(r.get("fecha_salida")), Db.ent(r.get("num_personas")));
            tx.exec("UPDATE reservas SET estado = 'EN_CASA' WHERE id_reserva = ?", idReserva);
            return id;
        });
    }

    /** Check-in sin reserva previa (el huésped llega al mostrador). */
    public static long checkInDirecto(int idHuesped, int idHabitacion, LocalDate salida, int personas, Integer idCredito) {
        LocalDate hoy = LocalDate.now();
        validarFechas(hoy, salida);
        return Db.transaccion(tx -> {
            Map<String, Object> h = tx.uno("SELECT h.activa, t.capacidad FROM habitaciones h "
                    + "JOIN tipos_habitacion t ON t.id_tipo = h.id_tipo WHERE h.id_habitacion = ? FOR UPDATE", idHabitacion);
            if (h == null || Db.ent(h.get("activa")) == 0) throw new IllegalArgumentException("La habitación no existe o está inactiva.");
            BigDecimal recargo = recargoPorPersonas(personas, Db.ent(h.get("capacidad")), ChronoUnit.DAYS.between(hoy, salida));
            long idReserva = tx.insert("INSERT INTO reservas (id_huesped, id_habitacion, fecha_llegada, fecha_salida, num_personas, "
                    + "recargo, id_cliente_credito, estado, observaciones, id_usuario) VALUES (?,?,?,?,?,?,?,'EN_CASA','Check-in directo',?)",
                    idHuesped, idHabitacion, hoy, salida, personas, recargo, idCredito, Sesion.idUsuario);
            return abrirEstancia(tx, (int) idReserva, idHuesped, idHabitacion, salida, personas);
        });
    }

    private static long abrirEstancia(Tx tx, int idReserva, int idHuesped, int idHabitacion, LocalDate salida, int personas) throws Exception {
        Map<String, Object> h = tx.uno("SELECT estado, COALESCE(precio_especial, (SELECT precio_noche FROM tipos_habitacion t WHERE t.id_tipo = habitaciones.id_tipo)) AS precio, numero "
                + "FROM habitaciones WHERE id_habitacion = ? FOR UPDATE", idHabitacion);
        String estado = Db.str(h.get("estado"));
        if (!estado.equals("LIBRE") && !estado.equals("RESERVADA"))
            throw new IllegalArgumentException("La habitación " + h.get("numero") + " está en estado " + estado + "; debe estar LIBRE para el check-in.");
        long id = tx.insert("INSERT INTO estancias (id_reserva, id_huesped, id_habitacion, salida_prevista, precio_noche, num_personas, id_usuario) "
                + "VALUES (?,?,?,?,?,?,?)", idReserva, idHuesped, idHabitacion, salida, h.get("precio"), personas, Sesion.idUsuario);
        tx.exec("UPDATE habitaciones SET estado = 'OCUPADA' WHERE id_habitacion = ?", idHabitacion);
        Bitacora.registrar("CHECKIN", "estancia", id, "Hab " + h.get("numero"));
        return id;
    }

    public static void extenderEstancia(int idEstancia, LocalDate nuevaSalida) {
        Db.transaccion(tx -> {
            Map<String, Object> e = tx.uno("SELECT id_reserva, estado, checkin_real FROM estancias WHERE id_estancia = ? FOR UPDATE", idEstancia);
            if (e == null || !"ACTIVA".equals(Db.str(e.get("estado")))) throw new IllegalArgumentException("La estadía no está activa.");
            LocalDate ingreso = Ui.fecha(e.get("checkin_real"));
            if (!nuevaSalida.isAfter(ingreso)) throw new IllegalArgumentException("La salida debe ser posterior al ingreso (" + Ui.texto(ingreso) + ").");
            if (e.get("id_reserva") != null)
                tx.exec("UPDATE reservas SET fecha_salida = ? WHERE id_reserva = ?", nuevaSalida, e.get("id_reserva"));  // el trigger revisa choques
            tx.exec("UPDATE estancias SET salida_prevista = ? WHERE id_estancia = ?", nuevaSalida, idEstancia);
            Bitacora.registrar("EXTENDER", "estancia", idEstancia, "Nueva salida " + nuevaSalida);
            return null;
        });
    }

    // ---------------------------------------------------------------- habitaciones
    public static void cambiarEstadoHabitacion(int idHabitacion, String nuevo) {
        Db.transaccion(tx -> {
            Map<String, Object> h = tx.uno("SELECT estado, numero FROM habitaciones WHERE id_habitacion = ? FOR UPDATE", idHabitacion);
            String actual = Db.str(h.get("estado"));
            if (actual.equals("OCUPADA") || nuevo.equals("OCUPADA"))
                throw new IllegalArgumentException("El estado OCUPADA lo controla el check-in y el check-out.");
            tx.exec("UPDATE habitaciones SET estado = ? WHERE id_habitacion = ?", nuevo, idHabitacion);
            Bitacora.registrar("ESTADO_HAB", "habitacion", idHabitacion, actual + " -> " + nuevo);
            return null;
        });
    }

    // ---------------------------------------------------------------- consumos
    public static void agregarConsumo(int idEstancia, Integer idServicio, String descripcion, BigDecimal cantidad,
                                      BigDecimal precio, String tratamiento) {
        if (descripcion == null || descripcion.isBlank()) throw new IllegalArgumentException("Escriba la descripción.");
        if (cantidad.signum() <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
        if (precio.signum() < 0) throw new IllegalArgumentException("El precio no puede ser negativo.");
        Object activa = Db.scalar("SELECT 1 FROM estancias WHERE id_estancia = ? AND estado = 'ACTIVA'", idEstancia);
        if (activa == null) throw new IllegalArgumentException("La estadía no está activa.");
        long id = Db.insert("INSERT INTO consumos (id_estancia, id_servicio, descripcion, cantidad, precio_unitario, tratamiento_fiscal, id_usuario) "
                + "VALUES (?,?,?,?,?,?,?)", idEstancia, idServicio, descripcion.trim(), cantidad, precio, tratamiento, Sesion.idUsuario);
        Bitacora.registrar("CONSUMO", "estancia", idEstancia, descripcion + " x" + cantidad);
    }

    private static String vacioANull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
