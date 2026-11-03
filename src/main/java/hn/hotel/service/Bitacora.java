package hn.hotel.service;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;

/** Registro de auditoría: quién hizo qué. Nunca debe romper la operación principal. */
public final class Bitacora {
    private Bitacora() {}

    public static void registrar(String accion, String entidad, Object idEntidad, String detalle) {
        try {
            Db.exec("INSERT INTO bitacora (id_usuario, accion, entidad, id_entidad, detalle) VALUES (?,?,?,?,?)",
                    Sesion.idUsuario == 0 ? null : Sesion.idUsuario, accion, entidad,
                    idEntidad == null ? null : idEntidad.toString(),
                    detalle == null ? null : (detalle.length() > 500 ? detalle.substring(0, 500) : detalle));
        } catch (RuntimeException ignorar) {
            // la auditoría no debe impedir trabajar
        }
    }
}
