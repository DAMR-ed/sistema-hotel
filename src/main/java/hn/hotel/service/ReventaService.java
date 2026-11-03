package hn.hotel.service;

import hn.hotel.db.Db;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Productos de reventa (bebidas, snacks, tienda): se venden tal cual y llevan su propio inventario.
 * Internamente son un insumo en unidades + un producto del menú con una receta de 1 unidad, así usan el mismo
 * inventario, kardex, compras y descuento automático que el resto del sistema.
 */
public final class ReventaService {
    private ReventaService() {}

    /** Al guardar un producto marcado «controlar inventario»: crea (o reutiliza) el insumo con su nombre y lo liga con receta de 1 unidad. */
    public static void controlarInventario(long idProducto, String nombre, BigDecimal costo) {
        Db.transaccion(tx -> {
            if (tx.scalar("SELECT 1 FROM receta_detalle WHERE id_producto = ? LIMIT 1", idProducto) != null)
                throw new IllegalArgumentException("Este producto ya tiene receta; edítela en «Menú y recetas».");
            Map<String, Object> ins = tx.uno("SELECT id_insumo, unidad_base FROM insumos WHERE nombre = ?", nombre);
            long idInsumo;
            if (ins != null) {
                if (!"UND".equals(Db.str(ins.get("unidad_base"))))
                    throw new IllegalArgumentException("Ya existe un insumo llamado «" + nombre + "» que no está en unidades. Cámbiele el nombre al producto.");
                idInsumo = Db.lng(ins.get("id_insumo"));
            } else {
                idInsumo = tx.insert("INSERT INTO insumos (nombre, unidad_base, stock_actual, stock_minimo, costo_promedio, activo) VALUES (?, 'UND', 0, 0, ?, 1)",
                        nombre, costo == null ? BigDecimal.ZERO : costo);
            }
            tx.exec("INSERT INTO receta_detalle (id_producto, id_insumo, cantidad, merma_pct) VALUES (?,?,1,0)", idProducto, idInsumo);
            Bitacora.registrar("REVENTA", "producto", idProducto, "inventario propio: insumo " + idInsumo);
            return null;
        });
    }

    /** Desde Inventario: publica un insumo en unidades como producto vendible en el POS. Devuelve el id del producto. */
    public static long venderEnPos(int idInsumo, int idCategoria, BigDecimal precio, BigDecimal tasaIsv, String codigoBarras) {
        if (precio == null || precio.signum() < 0) throw new IllegalArgumentException("Precio no válido.");
        return Db.transaccion(tx -> {
            Map<String, Object> ins = tx.uno("SELECT nombre, unidad_base FROM insumos WHERE id_insumo = ?", idInsumo);
            if (ins == null) throw new IllegalArgumentException("El insumo no existe.");
            if (!"UND".equals(Db.str(ins.get("unidad_base"))))
                throw new IllegalArgumentException("Solo los insumos en UNIDADES se pueden vender tal cual (ej. botella, lata). Los demás se usan en recetas de platos.");
            if (tx.scalar("SELECT 1 FROM productos_menu WHERE nombre = ?", ins.get("nombre")) != null)
                throw new IllegalArgumentException("Ya existe un producto llamado «" + ins.get("nombre") + "» en el menú.");
            if (tx.scalar("SELECT 1 FROM receta_detalle WHERE id_insumo = ? AND cantidad = 1 AND id_producto IN (SELECT id_producto FROM productos_menu WHERE area_preparacion = 'NINGUNA') LIMIT 1", idInsumo) != null)
                throw new IllegalArgumentException("Este insumo ya se vende en el POS como producto de reventa.");
            String fiscal = tasaIsv.signum() == 0 ? "EXENTO" : "GRAVADO";
            long id = tx.insert("INSERT INTO productos_menu (id_categoria, nombre, precio, tratamiento_fiscal, tasa_isv, area_preparacion, activo) VALUES (?,?,?,?,?,'NINGUNA',1)",
                    idCategoria, ins.get("nombre"), precio, fiscal, tasaIsv);
            tx.exec("INSERT INTO receta_detalle (id_producto, id_insumo, cantidad, merma_pct) VALUES (?,?,1,0)", id, idInsumo);
            if (codigoBarras != null && !codigoBarras.isBlank())
                tx.exec("UPDATE productos_menu SET codigo_barras = ? WHERE id_producto = ?", codigoBarras.trim(), id);
            Bitacora.registrar("REVENTA", "producto", id, "publicado desde inventario: insumo " + idInsumo);
            return id;
        });
    }
}
