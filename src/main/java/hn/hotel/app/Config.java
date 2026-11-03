package hn.hotel.app;

import hn.hotel.db.Db;
import java.math.BigDecimal;
import java.util.Map;

/** Parámetros de la tabla "configuracion" (ISV, tasa turística, datos del hotel...). */
public final class Config {
    private Config() {}

    public static String texto(String clave, String defecto) {
        Object v = Db.scalar("SELECT valor FROM configuracion WHERE clave = ?", clave);
        return v == null ? defecto : v.toString();
    }

    public static BigDecimal numero(String clave, String defecto) {
        try { return new BigDecimal(texto(clave, defecto).trim()); }
        catch (NumberFormatException e) { return new BigDecimal(defecto); }
    }

    public static int entero(String clave, int defecto) {
        try { return Integer.parseInt(texto(clave, String.valueOf(defecto)).trim()); }
        catch (NumberFormatException e) { return defecto; }
    }

    public static void guardar(String clave, String valor) {
        Db.exec("UPDATE configuracion SET valor = ? WHERE clave = ?", valor, clave);
    }

    public static Map<String, Object> todas() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (Map<String, Object> f : Db.query("SELECT clave, valor FROM configuracion ORDER BY clave"))
            m.put(Db.str(f.get("clave")), f.get("valor"));
        return m;
    }
}
