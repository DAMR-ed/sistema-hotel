package hn.hotel.service;

import hn.hotel.db.Db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tickets de cocina y barra (las "comandas" que ve el cocinero), con su ciclo de estados. */
public final class TicketService {
    private TicketService() {}

    public record LineaTicket(int idDetalle, String cantidad, String descripcion, String notas, String estado) {}

    public record Ticket(int id, int numero, String destino, String donde, String mesero, String hora, int minutos,
                         String estado, boolean impreso, List<LineaTicket> lineas) {}

    public record Estacion(int id, String nombre, String impresora, boolean pantalla, boolean imprimeAuto) {}

    /** Estaciones activas que tienen pantalla (las pestañas de la pantalla de cocina). */
    public static List<Estacion> estacionesConPantalla() {
        List<Estacion> r = new ArrayList<>();
        for (Map<String, Object> e : Db.query("SELECT id_estacion, nombre, impresora, usa_pantalla, imprime_auto FROM estaciones WHERE activa = 1 AND usa_pantalla = 1 ORDER BY orden, nombre"))
            r.add(new Estacion(Db.ent(e.get("id_estacion")), Db.str(e.get("nombre")), e.get("impresora") == null ? null : Db.str(e.get("impresora")),
                    true, Db.ent(e.get("imprime_auto")) == 1));
        return r;
    }

    /** Tickets activos (no entregados ni cancelados) de un destino; destino null = todos. */
    public static List<Ticket> activos(String destino) {
        return cargar("t.estado IN ('NUEVO','PREPARANDO','LISTO') AND (? IS NULL OR t.destino = ?)", destino, destino);
    }

    public static Ticket uno(int idTicket) {
        List<Ticket> l = cargar("t.id_ticket = ? AND ? IS NOT NULL", idTicket, idTicket);
        if (l.isEmpty()) throw new IllegalArgumentException("El ticket no existe.");
        return l.get(0);
    }

    private static List<Ticket> cargar(String where, Object p1, Object p2) {
        List<Map<String, Object>> cab = Db.query(
                "SELECT t.id_ticket, t.numero_dia, t.destino, t.estado, t.impreso, DATE_FORMAT(t.creado_en, '%H:%i') AS hora, "
              + "TIMESTAMPDIFF(MINUTE, t.creado_en, NOW()) AS minutos, "
              + "COALESCE(m.nombre, IF(c.tipo = 'LLEVAR', CONCAT('Para llevar #', c.id_comanda), CONCAT('Servicio #', c.id_comanda))) AS donde, "
              + "COALESCE(u.nombre, '') AS mesero "
              + "FROM tickets_cocina t JOIN comandas c ON c.id_comanda = t.id_comanda "
              + "LEFT JOIN mesas m ON m.id_mesa = c.id_mesa LEFT JOIN usuarios u ON u.id_usuario = t.id_usuario "
              + "WHERE " + where + " ORDER BY t.creado_en", p1, p2);
        List<Ticket> r = new ArrayList<>();
        for (Map<String, Object> c : cab) {
            int id = Db.ent(c.get("id_ticket"));
            List<LineaTicket> ls = new ArrayList<>();
            for (Map<String, Object> d : Db.query("SELECT id_detalle, cantidad, descripcion, notas, estado FROM comanda_detalle WHERE id_ticket = ? ORDER BY id_detalle", id))
                ls.add(new LineaTicket(Db.ent(d.get("id_detalle")), Db.dec(d.get("cantidad")).stripTrailingZeros().toPlainString(),
                        Db.str(d.get("descripcion")), d.get("notas") == null ? "" : Db.str(d.get("notas")), Db.str(d.get("estado"))));
            r.add(new Ticket(id, Db.ent(c.get("numero_dia")), Db.str(c.get("destino")), Db.str(c.get("donde")), Db.str(c.get("mesero")),
                    Db.str(c.get("hora")), Db.ent(c.get("minutos")), Db.str(c.get("estado")), Db.ent(c.get("impreso")) == 1, ls));
        }
        return r;
    }

    /** NUEVO → PREPARANDO → LISTO → ENTREGADO (también se puede regresar un paso). */
    public static void cambiarEstado(int idTicket, String nuevo) {
        Db.transaccion(tx -> {
            Object actual = tx.scalar("SELECT estado FROM tickets_cocina WHERE id_ticket = ? FOR UPDATE", idTicket);
            if (actual == null) throw new IllegalArgumentException("El ticket no existe.");
            if ("CANCELADO".equals(actual.toString())) throw new IllegalArgumentException("El ticket fue cancelado.");
            tx.exec("UPDATE tickets_cocina SET estado = ?, listo_en = IF(? = 'LISTO', NOW(), listo_en) WHERE id_ticket = ?", nuevo, nuevo, idTicket);
            String det = switch (nuevo) {
                case "LISTO" -> "LISTO";
                case "ENTREGADO" -> "SERVIDO";
                default -> "ENVIADO";
            };
            tx.exec("UPDATE comanda_detalle SET estado = ? WHERE id_ticket = ? AND estado <> 'ANULADO'", det, idTicket);
            return null;
        });
    }

    public static void marcarImpreso(int idTicket) {
        Db.exec("UPDATE tickets_cocina SET impreso = 1 WHERE id_ticket = ?", idTicket);
    }
}
