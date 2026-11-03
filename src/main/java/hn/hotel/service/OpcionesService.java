package hn.hotel.service;

import hn.hotel.db.Db;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Opciones (variantes) de un plato: término, acompañante, extras... */
public final class OpcionesService {
    private OpcionesService() {}

    public record Opcion(int id, String nombre, BigDecimal recargo) {}

    public record Grupo(int id, String nombre, int min, int max, List<Opcion> opciones) {}

    /** Grupos activos del plato, con sus opciones activas. Vacío si el plato no tiene opciones. */
    public static List<Grupo> gruposDeProducto(int idProducto) {
        List<Grupo> r = new ArrayList<>();
        for (Map<String, Object> g : Db.query("SELECT g.id_grupo, g.nombre, g.min_sel, g.max_sel FROM producto_grupos pg JOIN grupos_opciones g ON g.id_grupo = pg.id_grupo "
                + "WHERE pg.id_producto = ? AND g.activo = 1 ORDER BY pg.orden, g.nombre", idProducto)) {
            List<Opcion> ops = new ArrayList<>();
            for (Map<String, Object> o : Db.query("SELECT id_opcion, nombre, recargo FROM opciones WHERE id_grupo = ? AND activa = 1 ORDER BY orden, nombre", g.get("id_grupo")))
                ops.add(new Opcion(Db.ent(o.get("id_opcion")), Db.str(o.get("nombre")), Db.dec(o.get("recargo"))));
            if (!ops.isEmpty()) r.add(new Grupo(Db.ent(g.get("id_grupo")), Db.str(g.get("nombre")), Db.ent(g.get("min_sel")), Db.ent(g.get("max_sel")), ops));
        }
        return r;
    }
}
