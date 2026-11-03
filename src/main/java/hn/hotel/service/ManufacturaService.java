package hn.hotel.service;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Manufactura: producir lotes de preparaciones (salsas, masas, caldos...) que luego se usan como ingrediente. */
public final class ManufacturaService {
    private ManufacturaService() {}

    /** Costo de un lote según el costo promedio actual de sus ingredientes. */
    public static BigDecimal costoLote(int idPreparacion) {
        Object v = Db.scalar("SELECT COALESCE(SUM(d.cantidad * (1 + d.merma_pct/100) * i.costo_promedio), 0) FROM preparacion_detalle d "
                + "JOIN insumos i ON i.id_insumo = d.id_insumo WHERE d.id_preparacion = ?", idPreparacion);
        return Db.dec(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** Descuenta los ingredientes y suma lo producido al inventario. Devuelve la cantidad producida (unidad base). */
    public static BigDecimal producir(int idPreparacion, BigDecimal lotes, String notas) {
        if (lotes == null || lotes.signum() <= 0) throw new IllegalArgumentException("Indique cuántos lotes va a producir.");
        return Db.transaccion(tx -> {
            Map<String, Object> p = tx.uno("SELECT p.id_insumo, p.rendimiento, p.activa, i.nombre, i.stock_actual, i.costo_promedio FROM preparaciones p "
                    + "JOIN insumos i ON i.id_insumo = p.id_insumo WHERE p.id_preparacion = ? FOR UPDATE", idPreparacion);
            if (p == null || Db.ent(p.get("activa")) == 0) throw new IllegalArgumentException("La preparación no existe o está inactiva.");
            List<Map<String, Object>> ing = tx.query("SELECT d.id_insumo, d.cantidad, d.merma_pct, i.costo_promedio, i.nombre FROM preparacion_detalle d "
                    + "JOIN insumos i ON i.id_insumo = d.id_insumo WHERE d.id_preparacion = ?", idPreparacion);
            if (ing.isEmpty()) throw new IllegalArgumentException("La preparación no tiene ingredientes. Agréguelos primero.");
            String ref = "Producción: " + p.get("nombre");
            BigDecimal costo = BigDecimal.ZERO;
            for (Map<String, Object> d : ing) {
                BigDecimal uso = Db.dec(d.get("cantidad")).multiply(lotes).multiply(BigDecimal.ONE.add(Db.dec(d.get("merma_pct")).movePointLeft(2))).setScale(3, RoundingMode.HALF_UP);
                BigDecimal cu = Db.dec(d.get("costo_promedio"));
                costo = costo.add(uso.multiply(cu));
                tx.exec("INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, costo_unitario, referencia, id_usuario) VALUES (?,'PRODUCCION_SALIDA',?,?,?,?)",
                        d.get("id_insumo"), uso.negate(), cu, ref, Sesion.idUsuario);
                tx.exec("UPDATE insumos SET stock_actual = stock_actual - ? WHERE id_insumo = ?", uso, d.get("id_insumo"));
            }
            BigDecimal producido = Db.dec(p.get("rendimiento")).multiply(lotes).setScale(3, RoundingMode.HALF_UP);
            BigDecimal costoUnit = costo.divide(producido, 6, RoundingMode.HALF_UP);
            BigDecimal stock = Db.dec(p.get("stock_actual")), costoActual = Db.dec(p.get("costo_promedio"));
            BigDecimal nuevoCosto = stock.signum() <= 0 ? costoUnit
                    : stock.multiply(costoActual).add(costo).divide(stock.add(producido), 6, RoundingMode.HALF_UP);
            tx.exec("INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, costo_unitario, referencia, id_usuario) VALUES (?,'PRODUCCION_ENTRADA',?,?,?,?)",
                    p.get("id_insumo"), producido, costoUnit, ref, Sesion.idUsuario);
            tx.exec("UPDATE insumos SET stock_actual = stock_actual + ?, costo_promedio = ? WHERE id_insumo = ?", producido, nuevoCosto, p.get("id_insumo"));
            long id = tx.insert("INSERT INTO producciones (id_preparacion, lotes, cantidad, costo_total, id_usuario, notas) VALUES (?,?,?,?,?,?)",
                    idPreparacion, lotes, producido, costo.setScale(2, RoundingMode.HALF_UP), Sesion.idUsuario,
                    notas == null || notas.isBlank() ? null : notas.trim());
            Bitacora.registrar("PRODUCIR", "produccion", id, p.get("nombre") + " x" + lotes.stripTrailingZeros().toPlainString());
            return producido;
        });
    }
}
