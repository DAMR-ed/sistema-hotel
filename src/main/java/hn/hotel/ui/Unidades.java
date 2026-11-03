package hn.hotel.ui;

import java.math.BigDecimal;
import java.util.List;

/** Conversión de unidades de compra/uso a la unidad base del insumo (G, ML o UND). */
public final class Unidades {
    private Unidades() {}

    public record Un(String nombre, BigDecimal factor) {
        @Override public String toString() { return nombre; }
    }

    public static List<Un> para(String base) {
        return switch (base) {
            case "G" -> List.of(un("gramos (g)", "1"), un("kilogramos (kg)", "1000"), un("libras (lb)", "453.592"), un("onzas (oz)", "28.3495"));
            case "ML" -> List.of(un("mililitros (ml)", "1"), un("litros (l)", "1000"), un("galones", "3785.41"), un("onzas líquidas (fl oz)", "29.5735"));
            default -> List.of(un("unidades", "1"), un("docenas", "12"));
        };
    }

    private static Un un(String n, String f) { return new Un(n, new BigDecimal(f)); }
}
