package hn.hotel.service;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import org.mindrot.jbcrypt.BCrypt;

import java.time.LocalDateTime;
import java.util.Map;

/** Inicio de sesión con contraseñas cifradas (BCrypt) y bloqueo por intentos fallidos. */
public final class Autenticacion {
    private Autenticacion() {}

    public static final int MAX_INTENTOS = 5;
    public static final int MINUTOS_BLOQUEO = 15;
    public static final int CLAVE_MINIMA = 8;

    public static boolean hayUsuarios() {
        return Db.ent(Db.scalar("SELECT COUNT(*) FROM usuarios WHERE activo = 1")) > 0;
    }

    /** Devuelve null si entró bien, o el mensaje de error para mostrar. */
    public static String iniciar(String username, char[] clave) {
        try {
            Map<String, Object> u = Db.uno(
                    "SELECT id_usuario, username, nombre, password_hash, rol, activo, intentos_fallidos, bloqueado_hasta "
                  + "FROM usuarios WHERE username = ?", username.trim());
            // Mensaje genérico para no revelar si el usuario existe
            String generico = "Usuario o contraseña incorrectos.";
            if (u == null) { BCrypt.checkpw("x", BCrypt.hashpw("y", BCrypt.gensalt(4))); return generico; }
            if (Db.ent(u.get("activo")) == 0) return "Este usuario está desactivado.";

            Object bloq = u.get("bloqueado_hasta");
            if (bloq != null) {
                LocalDateTime hasta = aFecha(bloq);
                if (hasta.isAfter(LocalDateTime.now()))
                    return "Usuario bloqueado por intentos fallidos. Intente de nuevo después de las "
                            + String.format("%02d:%02d", hasta.getHour(), hasta.getMinute()) + ".";
            }

            boolean ok;
            try { ok = BCrypt.checkpw(new String(clave), Db.str(u.get("password_hash"))); }
            catch (IllegalArgumentException e) { ok = false; }   // hash inválido (ej. usuario del sistema)

            int id = Db.ent(u.get("id_usuario"));
            if (!ok) {
                int n = Db.ent(u.get("intentos_fallidos")) + 1;
                if (n >= MAX_INTENTOS) {
                    Db.exec("UPDATE usuarios SET intentos_fallidos = 0, bloqueado_hasta = ? WHERE id_usuario = ?",
                            LocalDateTime.now().plusMinutes(MINUTOS_BLOQUEO), id);
                    return "Demasiados intentos. Usuario bloqueado " + MINUTOS_BLOQUEO + " minutos.";
                }
                Db.exec("UPDATE usuarios SET intentos_fallidos = ? WHERE id_usuario = ?", n, id);
                return generico + " (Intento " + n + " de " + MAX_INTENTOS + ")";
            }
            Db.exec("UPDATE usuarios SET intentos_fallidos = 0, bloqueado_hasta = NULL, ultimo_acceso = NOW() WHERE id_usuario = ?", id);
            Sesion.idUsuario = id;
            Sesion.username = Db.str(u.get("username"));
            Sesion.nombre = Db.str(u.get("nombre"));
            Sesion.rol = Db.str(u.get("rol"));
            Sesion.cargarNegocio();
            Sesion.cargarPermisos();
            Bitacora.registrar("LOGIN", "usuario", id, "Inicio de sesión");
            return null;
        } catch (RuntimeException e) {
            return "No se pudo conectar con la base de datos:\n" + e.getMessage()
                    + "\n\nVerifique que el contenedor MySQL esté encendido y los datos de hotel.properties.";
        }
    }

    private static int fallosAutorizacion = 0;

    /**
     * Verifica a un supervisor (usuario y contraseña) para autorizar una acción puntual.
     * Devuelve su id; lanza IllegalArgumentException con el motivo si no se puede autorizar.
     */
    public static int autorizar(String username, char[] clave, String permiso) {
        if (fallosAutorizacion >= MAX_INTENTOS) throw new IllegalArgumentException("Demasiados intentos fallidos. Cierre la ventana y espere un momento.");
        Map<String, Object> u = Db.uno("SELECT id_usuario, password_hash, rol, activo FROM usuarios WHERE username = ?", username == null ? "" : username.trim());
        boolean ok = false;
        if (u != null && Db.ent(u.get("activo")) == 1) {
            try { ok = BCrypt.checkpw(new String(clave), Db.str(u.get("password_hash"))); } catch (IllegalArgumentException e) { ok = false; }
        }
        if (!ok) {
            fallosAutorizacion++;
            throw new IllegalArgumentException("Usuario o contraseña incorrectos.");
        }
        String r = Db.str(u.get("rol"));
        if (!"ADMIN".equals(r) && !Sesion.efectivos(r).contains(permiso))
            throw new IllegalArgumentException("Ese usuario no tiene permiso para autorizar esta acción. Pida a un supervisor o administrador.");
        fallosAutorizacion = 0;
        return Db.ent(u.get("id_usuario"));
    }

    public static String validarClave(String clave) {
        if (clave == null || clave.length() < CLAVE_MINIMA) return "La contraseña debe tener al menos " + CLAVE_MINIMA + " caracteres.";
        boolean letra = false, numero = false;
        for (char c : clave.toCharArray()) { if (Character.isLetter(c)) letra = true; if (Character.isDigit(c)) numero = true; }
        if (!letra || !numero) return "La contraseña debe tener letras y números.";
        return null;
    }

    public static String cifrar(String clave) { return BCrypt.hashpw(clave, BCrypt.gensalt(12)); }

    public static long crearUsuario(String username, String nombre, String clave, String rol) {
        return Db.insert("INSERT INTO usuarios (username, nombre, password_hash, rol) VALUES (?,?,?,?)",
                username.trim(), nombre.trim(), cifrar(clave), rol);
    }

    public static void cambiarClave(int idUsuario, String clave) {
        Db.exec("UPDATE usuarios SET password_hash = ?, intentos_fallidos = 0, bloqueado_hasta = NULL WHERE id_usuario = ?",
                cifrar(clave), idUsuario);
        Bitacora.registrar("CAMBIO_CLAVE", "usuario", idUsuario, null);
    }

    /** El propio usuario cambia su contraseña (debe conocer la actual). Devuelve null si salió bien, o el error. */
    public static String cambiarMiClave(String actual, String nueva) {
        String err = validarClave(nueva);
        if (err != null) return err;
        Object hash = Db.scalar("SELECT password_hash FROM usuarios WHERE id_usuario = ?", Sesion.idUsuario);
        boolean ok;
        try { ok = hash != null && BCrypt.checkpw(actual, hash.toString()); } catch (IllegalArgumentException e) { ok = false; }
        if (!ok) return "La contraseña actual no es correcta.";
        cambiarClave(Sesion.idUsuario, nueva);
        return null;
    }

    static LocalDateTime aFecha(Object o) {
        if (o instanceof LocalDateTime l) return l;
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(o.toString().replace(' ', 'T'));
    }
}
