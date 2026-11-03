package hn.hotel.vpos;

/** Lo que sabe hacer el PinPad. Hay dos implementaciones: la real (DLL del banco) y un simulador para pruebas. */
public interface Terminal {
    /** Ejecuta POSLinkAuthorize con la cadena de entrada ("PID=...;TAX=...;TOT=...;"). Devuelve el resultado de la función y el texto de salida. */
    Resultado autorizar(String entrada);

    /** Ejecuta POSLinkPrintReport ("REP=DET" o "REP=TOT"). */
    boolean imprimirReporte(String entrada);

    String nombre();

    record Resultado(boolean ok, String salida) {}
}
