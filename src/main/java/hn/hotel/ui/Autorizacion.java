package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.service.Autenticacion;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import java.awt.*;

/**
 * Control de acceso del POS: si el rol del usuario no tiene el permiso, se pide el usuario y contraseña
 * de un supervisor (o administrador) que sí lo tenga; la autorización vale solo para esa operación.
 */
public final class Autorizacion {
    private Autorizacion() {}

    /** Ejecuta la acción si el usuario tiene permiso o si un supervisor la autoriza. Devuelve false si se canceló. */
    public static boolean ejecutar(Component padre, String permiso, String descripcion, Runnable accion) {
        if (Sesion.permite(permiso)) { accion.run(); return true; }
        if (Sesion.negadaSinClave(permiso)) {
            Ui.error(padre, "Su rol no puede " + descripcion + ". Esta acción está bloqueada y no admite autorización con clave.");
            return false;
        }
        JTextField usuario = new JTextField(14);
        JPasswordField clave = new JPasswordField(14);
        Campos.Formulario f = new Campos.Formulario()
                .nota("Su usuario no puede: " + descripcion + ".")
                .nota("Un supervisor o administrador puede autorizarlo escribiendo su usuario y contraseña.")
                .agregar("Usuario del supervisor:", usuario).agregar("Contraseña:", clave);
        int[] quien = {0};
        boolean ok = Campos.dialogo(padre, "Se requiere autorización", f, () -> {
            quien[0] = Autenticacion.autorizar(usuario.getText(), clave.getPassword(), permiso);
            java.util.Arrays.fill(clave.getPassword(), '\0');
            return true;
        });
        if (!ok) return false;
        Bitacora.registrar("AUTORIZACION", "usuario", quien[0], descripcion + " (pidió " + Sesion.username + ")");
        Sesion.autorizadoPor = quien[0];
        try { accion.run(); }
        finally { Sesion.autorizadoPor = 0; }
        return true;
    }
}
