package hn.hotel.vpos;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** Imita al PinPad para probar todo el sistema sin el equipo ni el DLL. Montos de 99,999 o más se rechazan. */
public final class Simulador implements Terminal {
    private final AtomicInteger secuencia = new AtomicInteger(1000);
    private final AtomicInteger lote = new AtomicInteger(1);

    @Override public Resultado autorizar(String entrada) {
        pausa(1500);
        String pid = valor(entrada, "PID");
        LocalDateTime n = LocalDateTime.now();
        String fecha = n.format(DateTimeFormatter.ofPattern("yyyyMMdd")), hora = n.format(DateTimeFormatter.ofPattern("HHmmss"));
        if ("VPSALE0002".equals(pid)) {
            double tot = numero(valor(entrada, "TOT")) + numero(valor(entrada, "TAX"));
            if (tot >= 99999) return new Resultado(true, "RSP=05;MSG=TRANSACCION RECHAZADA;");
            String aut = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            return new Resultado(true, "RSP=00;CRD=VISA ****4242;AUT=" + aut + ";LTD=" + fecha + ";BAT=" + String.format("%06d", lote.get())
                    + ";LTT=" + hora + ";SYS=" + secuencia.incrementAndGet() + ";");
        }
        if ("VPSETTL001".equals(pid)) {
            int l = lote.getAndIncrement();
            return new Resultado(true, "RSP=00;BAT=" + String.format("%06d", l) + ";LTD=" + fecha + ";LTT=" + hora + ";");
        }
        return new Resultado(true, "RSP=00;LTD=" + fecha + ";LTT=" + hora + ";");   // anulación u otro
    }

    @Override public boolean imprimirReporte(String entrada) { pausa(500); return true; }

    @Override public String nombre() { return "SIMULADOR (sin PinPad real)"; }

    static String valor(String entrada, String clave) {
        for (String p : entrada.split(";")) {
            int i = p.indexOf('=');
            if (i > 0 && p.substring(0, i).trim().equals(clave)) return p.substring(i + 1).trim();
        }
        return "";
    }

    private static double numero(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    private static void pausa(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
