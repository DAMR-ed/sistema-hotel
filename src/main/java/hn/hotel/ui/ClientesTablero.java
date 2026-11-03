package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tablero de clientes: mejores clientes, ventas por zona y antigüedad de la cartera por cobrar. */
public final class ClientesTablero {
    private ClientesTablero() {}

    public static void mostrar(Component padre) {
        JSpinner desde = Campos.fecha(LocalDate.now().withDayOfYear(1)), hasta = Campos.fecha(LocalDate.now());
        Graficos.Barras mejores = new Graficos.Barras(true, Graficos.MONEDA, Graficos.PALETA[0]);
        Graficos.Dona zonas = new Graficos.Dona(Graficos.MONEDA);
        Graficos.Barras cartera = new Graficos.Barras(false, Graficos.MONEDA, Graficos.PALETA[4]);
        JLabel resumen = new JLabel(" ");

        Runnable cargar = () -> {
            LocalDate d = Campos.valor(desde), h = Campos.valor(hasta);
            Ui.enSegundoPlano(padre, () -> Db.sin(tx -> new Object[]{
                    tx.query("SELECT COALESCE(c.nombre, f.cliente_nombre) AS nombre, SUM(f.total) AS total FROM facturas f LEFT JOIN clientes_credito c ON c.id_cliente_credito = f.id_cliente_credito "
                            + "WHERE f.estado = 'EMITIDA' AND f.id_cliente_credito IS NOT NULL AND DATE(f.fecha_emision) BETWEEN ? AND ? GROUP BY f.id_cliente_credito, COALESCE(c.nombre, f.cliente_nombre) "
                            + "ORDER BY SUM(f.total) DESC LIMIT 10", d, h),
                    tx.query("SELECT COALESCE(z.nombre, '(sin zona)') AS zona, SUM(f.total) AS total FROM facturas f LEFT JOIN clientes_credito c ON c.id_cliente_credito = f.id_cliente_credito "
                            + "LEFT JOIN zonas z ON z.id_zona = c.id_zona WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN ? AND ? GROUP BY z.nombre ORDER BY SUM(f.total) DESC", d, h),
                    tx.query("SELECT COALESCE(SUM(IF(dias_vencido = 0, saldo, 0)), 0) AS a, COALESCE(SUM(IF(dias_vencido BETWEEN 1 AND 30, saldo, 0)), 0) AS b, "
                            + "COALESCE(SUM(IF(dias_vencido BETWEEN 31 AND 60, saldo, 0)), 0) AS c, COALESCE(SUM(IF(dias_vencido BETWEEN 61 AND 90, saldo, 0)), 0) AS d, "
                            + "COALESCE(SUM(IF(dias_vencido > 90, saldo, 0)), 0) AS e FROM v_cuentas_por_cobrar WHERE estado IN ('PENDIENTE','PARCIAL')")}),
                r -> {
                    @SuppressWarnings("unchecked") var a = (List<Map<String, Object>>) r[0];
                    @SuppressWarnings("unchecked") var b = (List<Map<String, Object>>) r[1];
                    @SuppressWarnings("unchecked") var c = (List<Map<String, Object>>) r[2];
                    List<String> et = new ArrayList<>(); List<BigDecimal> v = new ArrayList<>();
                    for (var f : a) { et.add(Db.str(f.get("nombre"))); v.add(Db.dec(f.get("total"))); }
                    mejores.datos(et, v);
                    et = new ArrayList<>(); v = new ArrayList<>();
                    for (var f : b) { et.add(Db.str(f.get("zona"))); v.add(Db.dec(f.get("total"))); }
                    zonas.datos(et, v);
                    var k = c.get(0);
                    List<BigDecimal> cv = List.of(Db.dec(k.get("a")), Db.dec(k.get("b")), Db.dec(k.get("c")), Db.dec(k.get("d")), Db.dec(k.get("e")));
                    cartera.datos(List.of("Por vencer", "1-30", "31-60", "61-90", "+90"), cv);
                    resumen.setText("Cartera por cobrar total: " + Ui.lempiras(cv.stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
                });
        };

        JPanel graficos = new JPanel(new GridLayout(1, 3, 12, 0));
        graficos.add(tarjeta("Mejores clientes del período", mejores));
        graficos.add(tarjeta("Ventas por zona", zonas));
        graficos.add(tarjeta("Cartera por cobrar según vencimiento (días)", cartera));
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.add(Ui.fila(new JLabel("Ventas desde:"), desde, new JLabel("hasta:"), hasta, Ui.botonPrimario("Actualizar", cargar), resumen), BorderLayout.NORTH);
        p.add(graficos, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(1180, 420));
        cargar.run();
        JOptionPane.showMessageDialog(padre, p, "Tablero de clientes", JOptionPane.PLAIN_MESSAGE);
    }

    private static JComponent tarjeta(String titulo, JComponent g) {
        Tema.Tarjeta t = new Tema.Tarjeta(new BorderLayout(0, 6), null);
        t.add(new JLabel("<html><b>" + titulo + "</b></html>"), BorderLayout.NORTH);
        t.add(g, BorderLayout.CENTER);
        return t;
    }
}
