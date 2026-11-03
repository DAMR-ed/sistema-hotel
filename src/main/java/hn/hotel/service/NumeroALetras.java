package hn.hotel.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** "CUATRO MIL TRESCIENTOS CINCUENTA LEMPIRAS CON 25/100" (la factura lo pide en letras). */
public final class NumeroALetras {
    private NumeroALetras() {}

    private static final String[] UNIDADES = {"", "UN", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE", "OCHO", "NUEVE", "DIEZ",
            "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE", "DIECISEIS", "DIECISIETE", "DIECIOCHO", "DIECINUEVE", "VEINTE",
            "VEINTIUN", "VEINTIDOS", "VEINTITRES", "VEINTICUATRO", "VEINTICINCO", "VEINTISEIS", "VEINTISIETE", "VEINTIOCHO", "VEINTINUEVE"};
    private static final String[] DECENAS = {"", "", "", "TREINTA", "CUARENTA", "CINCUENTA", "SESENTA", "SETENTA", "OCHENTA", "NOVENTA"};
    private static final String[] CENTENAS = {"", "CIENTO", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS", "QUINIENTOS",
            "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS"};

    public static String lempiras(BigDecimal monto) {
        BigDecimal m = monto.setScale(2, RoundingMode.HALF_UP);
        long enteros = m.toBigInteger().longValueExact();
        int centavos = m.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String letras = enteros == 0 ? "CERO" : convertir(enteros);
        String moneda = enteros == 1 ? "LEMPIRA" : (enteros != 0 && enteros % 1_000_000 == 0 ? "DE LEMPIRAS" : "LEMPIRAS");
        return letras + " " + moneda + " CON " + String.format("%02d", centavos) + "/100";
    }

    private static String convertir(long n) {
        if (n >= 1_000_000_000_000L) return String.valueOf(n);
        if (n >= 1_000_000) {
            long millones = n / 1_000_000, resto = n % 1_000_000;
            String m = millones == 1 ? "UN MILLON" : convertir(millones) + " MILLONES";
            return resto == 0 ? m : m + " " + convertir(resto);
        }
        if (n >= 1000) {
            long miles = n / 1000, resto = n % 1000;
            String m = miles == 1 ? "MIL" : convertir(miles) + " MIL";
            return resto == 0 ? m : m + " " + convertir(resto);
        }
        return menorMil((int) n);
    }

    private static String menorMil(int n) {
        if (n == 100) return "CIEN";
        StringBuilder sb = new StringBuilder();
        if (n >= 100) { sb.append(CENTENAS[n / 100]); n %= 100; if (n > 0) sb.append(' '); }
        if (n < 30) sb.append(UNIDADES[n]);
        else {
            sb.append(DECENAS[n / 10]);
            if (n % 10 > 0) sb.append(" Y ").append(UNIDADES[n % 10]);
        }
        return sb.toString().trim();
    }
}
