package hn.hotel.ui;

/** Un reporte del catálogo: categoría (para el árbol), nombre y consulta. */
public record ReporteDef(String categoria, String nombre, String sql, boolean soloAdmin) {
    public boolean usaPeriodo() { return sql.contains("@d"); }
    public boolean usaCliente() { return sql.contains("@cliente"); }
    public boolean usaProveedor() { return sql.contains("@proveedor"); }
    @Override public String toString() { return nombre; }
}
