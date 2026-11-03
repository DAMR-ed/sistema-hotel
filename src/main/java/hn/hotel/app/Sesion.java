package hn.hotel.app;

import java.util.*;

/** Usuario que inició sesión y lo que puede hacer según su rol. */
public final class Sesion {
    private Sesion() {}

    public static int idUsuario;
    public static String username = "";
    public static String nombre = "";
    public static String rol = "";
    /** Punto de venta asignado al usuario (null = el de la computadora). */
    public static Integer idPuntoVenta = null;

    public static boolean esAdmin() { return "ADMIN".equals(rol); }

    /** Módulos que ve cada rol. ADMIN ve todo. */
    private static final Map<String, Set<String>> PERMISOS = Map.of(
            "SUPERVISOR", Set.of("inicio", "autoconsumo", "pos_retail", "cortes", "pos_rest", "cocina", "restaurante", "menu", "estaciones", "inventario", "manufactura", "compras", "almacenes", "facturacion", "credito", "vpos", "alarmas", "reportes"),
            "RECEPCION", Set.of("inicio", "pos_retail", "cortes", "plano", "reservas", "estancias", "huespedes", "facturacion", "credito", "alarmas", "reportes", "pos_rest"),
            "AMA_LLAVES", Set.of("inicio", "plano", "alarmas"),
            "CAJERO", Set.of("inicio", "pos_retail", "cortes", "facturacion", "credito", "vpos", "pos_rest", "reportes", "alarmas"),
            "MESERO", Set.of("inicio", "pos_rest"),
            "COCINA", Set.of("inicio", "cocina"),
            "BODEGA", Set.of("inicio", "autoconsumo", "inventario", "manufactura", "compras", "almacenes", "alarmas"),
            "CONTADOR", Set.of("inicio", "autoconsumo", "cortes", "compras", "facturacion", "credito", "vpos", "reportes", "fiscal", "impuestos")
    );

    /** Tipo de negocio (configuración «modulo_hotel» y «modulo_restaurante»): permite usar el sistema solo para restaurante o solo para hotel. */
    public static boolean hotelActivo = true, restauranteActivo = true, retailActivo = false;

    private static final Set<String> MODULOS_HOTEL = Set.of("plano", "reservas", "estancias", "huespedes");
    private static final Set<String> MODULOS_RESTAURANTE = Set.of("pos_rest", "cocina", "restaurante", "estaciones", "autoconsumo");
    /** Se usan en restaurante y también en tienda (productos y su receta/inventario). */
    private static final Set<String> MODULOS_PRODUCTOS = Set.of("menu", "manufactura");

    public static void cargarNegocio() {
        try {
            hotelActivo = !"NO".equalsIgnoreCase(hn.hotel.app.Config.texto("modulo_hotel", "SI").trim());
            restauranteActivo = !"NO".equalsIgnoreCase(hn.hotel.app.Config.texto("modulo_restaurante", "SI").trim());
            retailActivo = "SI".equalsIgnoreCase(hn.hotel.app.Config.texto("modulo_retail", "NO").trim());
        } catch (RuntimeException e) { hotelActivo = true; restauranteActivo = true; retailActivo = false; }
    }

    // ------------------------------------------------------------------ acciones del POS que se pueden permitir o negar por rol
    public static final Map<String, String> ACCIONES = new java.util.LinkedHashMap<>();
    static {
        ACCIONES.put("pos.cobrar", "Cobrar cuentas");
        ACCIONES.put("pos.precuenta", "Imprimir la pre-cuenta");
        ACCIONES.put("pos.dividir", "Dividir cuenta y partir platos");
        ACCIONES.put("pos.cambiar_mesa", "Cambiar la cuenta de mesa");
        ACCIONES.put("pos.cargar_habitacion", "Cargar la cuenta a una habitación");
        ACCIONES.put("pos.cambiar_lista", "Cambiar la lista de precios de una cuenta");
        ACCIONES.put("pos.ver_todas", "Ver y atender mesas de otros meseros");
        ACCIONES.put("pos.anular_enviado", "Quitar platos ya enviados a cocina");
        ACCIONES.put("pos.anular_comanda", "Anular una comanda completa");
        ACCIONES.put("pos.transferir", "Transferir mesas a otro mesero");
        ACCIONES.put("pos.disenar_mesas", "Diseñar mesas y zonas");
        ACCIONES.put("pos.autoconsumo", "Registrar autoconsumos (cuentas sin venta que descargan inventario)");
        ACCIONES.put("pos.corte_x", "Hacer corte X (cambio de turno)");
        ACCIONES.put("pos.corte_z", "Hacer corte Z (cierre del día)");
        ACCIONES.put("pos.descuento", "Aplicar descuentos a un plato");
        ACCIONES.put("pos.cambiar_precio", "Cambiar el precio de un plato");
        ACCIONES.put("pos.cambiar_cantidad", "Cambiar la cantidad de un plato (antes de enviarlo)");
        ACCIONES.put("pos.borrar_linea", "Quitar platos que aún no se envían a cocina");
        ACCIONES.put("inventario.carga_masiva", "Cargar o ajustar el inventario en masa (Excel)");
        ACCIONES.put("pos.traspasos", "Hacer traspasos entre almacenes");
        ACCIONES.put("compras.pagar", "Pagar a proveedores (cuentas por pagar)");
    }

    private static final Set<String> TODAS_POS = new java.util.LinkedHashSet<>(ACCIONES.keySet());
    private static final Map<String, Set<String>> ACCIONES_DEF = Map.of(
            "SUPERVISOR", TODAS_POS,
            "RECEPCION", Set.of("pos.cobrar", "pos.precuenta", "pos.dividir", "pos.cambiar_mesa", "pos.cargar_habitacion", "pos.ver_todas", "pos.cambiar_lista", "pos.corte_x", "pos.borrar_linea", "pos.cambiar_cantidad"),
            "CAJERO", Set.of("pos.cobrar", "pos.precuenta", "pos.dividir", "pos.cambiar_mesa", "pos.cargar_habitacion", "pos.ver_todas", "pos.cambiar_lista", "pos.corte_x", "pos.borrar_linea", "pos.cambiar_cantidad"),
            "MESERO", Set.of("pos.precuenta", "pos.dividir", "pos.cambiar_mesa", "pos.borrar_linea", "pos.cambiar_cantidad"),
            "CONTADOR", Set.of("compras.pagar"),
            "BODEGA", Set.of("pos.traspasos", "inventario.carga_masiva")
    );

    /** Permisos de fábrica de un rol (módulos y acciones). */
    public static Set<String> porDefecto(String r) {
        Set<String> s = new java.util.HashSet<>(PERMISOS.getOrDefault(r, Set.of("inicio")));
        s.addAll(ACCIONES_DEF.getOrDefault(r, Set.of()));
        return s;
    }

    private static final Map<String, Set<String>> PERSONALIZADOS = new java.util.HashMap<>();
    /** Por rol: acciones bloqueadas que NO admiten autorización con clave (quedan negadas del todo). */
    private static final Map<String, Set<String>> SIN_CLAVE = new java.util.HashMap<>();

    /** ¿Esta acción, estando bloqueada para el rol actual, queda negada sin posibilidad de pedir clave de supervisor? */
    public static synchronized boolean negadaSinClave(String accion) {
        return !esAdmin() && SIN_CLAVE.getOrDefault(rol, Set.of()).contains(accion);
    }

    public static synchronized boolean pideClave(String rolx, String accion) {
        return !SIN_CLAVE.getOrDefault(rolx, Set.of()).contains(accion);
    }

    /** Lo que realmente puede hacer el rol: lo guardado en «Roles y permisos» o, si no se ha tocado, lo de fábrica. */
    public static synchronized Set<String> efectivos(String r) {
        Set<String> p = PERSONALIZADOS.get(r);
        return p != null ? p : porDefecto(r);
    }

    /** Lee de la base de datos los permisos personalizados (si el script 09 aún no se ejecutó, se usan los de fábrica). */
    public static synchronized void cargarPermisos() {
        PERSONALIZADOS.clear();
        SIN_CLAVE.clear();
        try {
            for (Map<String, Object> f : hn.hotel.db.Db.query("SELECT rol, permiso FROM permisos_sin_clave"))
                SIN_CLAVE.computeIfAbsent(String.valueOf(f.get("rol")), k -> new java.util.HashSet<>()).add(String.valueOf(f.get("permiso")));
        } catch (RuntimeException e) { SIN_CLAVE.clear(); }
        try {
            for (Map<String, Object> f : hn.hotel.db.Db.query("SELECT rol, permiso FROM permisos_rol"))
                PERSONALIZADOS.computeIfAbsent(String.valueOf(f.get("rol")), k -> new java.util.HashSet<>()).add(String.valueOf(f.get("permiso")));
        } catch (RuntimeException e) { PERSONALIZADOS.clear(); }
    }

    /** Usuario supervisor que autorizó, por una sola operación, algo que el rol actual no puede hacer. */
    public static volatile int autorizadoPor = 0;

    /** ¿Puede el usuario actual hacer esta acción? (o la autorizó un supervisor en este momento) */
    public static boolean permite(String accion) {
        if (esAdmin() || autorizadoPor > 0) return true;
        return efectivos(rol).contains(accion);
    }

    /** ¿Su rol tiene este permiso por sí mismo? (sin contar autorizaciones de un supervisor). Sirve para ocultar botones que no le corresponden. */
    public static boolean tiene(String accion) {
        return esAdmin() || efectivos(rol).contains(accion);
    }

    public static boolean puede(String modulo) {
        if (!hotelActivo && MODULOS_HOTEL.contains(modulo)) return false;
        if (!restauranteActivo && MODULOS_RESTAURANTE.contains(modulo)) return false;
        if (!restauranteActivo && !retailActivo && MODULOS_PRODUCTOS.contains(modulo)) return false;
        if (!retailActivo && modulo.equals("pos_retail")) return false;
        if (esAdmin()) return true;
        return efectivos(rol).contains(modulo);
    }

    public static void cerrar() {
        idUsuario = 0; username = ""; nombre = ""; rol = ""; idPuntoVenta = null;
    }
}
