package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.Map;

/** Tablero con lo más importante del día. */
public class InicioPanel extends JPanel implements Actualizable {
    private final JLabel lblLibres = tarjeta(), lblOcupadas = tarjeta(), lblLlegadas = tarjeta(),
            lblSalidas = tarjeta(), lblVentas = tarjeta(), lblComandas = tarjeta();
    private final DefaultTableModel modeloAlertas = Ui.modelo("Tipo", "Prioridad", "Detalle");

    public InicioPanel() {
        setLayout(new BorderLayout(10, 16));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        JLabel saludo = new JLabel("<html><span style='font-size:20px'><b>Resumen del día</b></span><br>"
                + "<span style='color:#64748B'>" + java.time.LocalDate.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", new java.util.Locale("es", "HN")))
                + "</span></html>");
        JPanel norte = new JPanel(new BorderLayout());
        norte.setOpaque(false);
        norte.add(saludo, BorderLayout.WEST);
        JPanel rapidas = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rapidas.setOpaque(false);
        if (hn.hotel.app.Sesion.puede("reservas"))
            rapidas.add(Ui.botonPrimario("+ Nueva reserva", () -> NuevaReservaDialog.mostrar(this)));
        if (hn.hotel.app.Sesion.puede("estancias"))
            rapidas.add(Ui.boton("Check-in / Check-out", () -> ir("estancias")));
        if (hn.hotel.app.Sesion.puede("pos_rest"))
            rapidas.add(Ui.boton("Punto de venta", () -> ir("pos_rest")));
        norte.add(rapidas, BorderLayout.EAST);
        add(norte, BorderLayout.NORTH);

        JPanel centro = new JPanel(new BorderLayout(10, 18));
        centro.setOpaque(false);
        JPanel tarjetas = new JPanel(new GridLayout(2, 3, 16, 16));
        tarjetas.setOpaque(false);
        tarjetas.add(envolver("Habitaciones libres", lblLibres, Ui.COLOR_LIBRE));
        tarjetas.add(envolver("Habitaciones ocupadas", lblOcupadas, Ui.COLOR_OCUPADA));
        tarjetas.add(envolver("Llegadas de hoy", lblLlegadas, Ui.COLOR_RESERVADA));
        tarjetas.add(envolver("Salidas pendientes", lblSalidas, Ui.COLOR_LIMPIEZA));
        tarjetas.add(envolver("Facturado hoy", lblVentas, Tema.ACENTO));
        tarjetas.add(envolver("Comandas abiertas", lblComandas, new Color(0x8B5CF6)));
        centro.add(tarjetas, BorderLayout.NORTH);

        Tema.Tarjeta alertas = new Tema.Tarjeta(new BorderLayout(0, 8), null);
        JLabel ta = new JLabel("Alertas activas");
        ta.setFont(ta.getFont().deriveFont(Font.BOLD, 14f));
        alertas.add(ta, BorderLayout.NORTH);
        JTable t = Ui.tabla(modeloAlertas);
        t.getColumnModel().getColumn(0).setMaxWidth(170);
        t.getColumnModel().getColumn(1).setMaxWidth(90);
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createEmptyBorder());
        alertas.add(sp, BorderLayout.CENTER);
        centro.add(alertas, BorderLayout.CENTER);
        add(centro, BorderLayout.CENTER);
    }

    private void ir(String modulo) {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w instanceof MainFrame mf) mf.mostrar(modulo);
    }

    private static JLabel tarjeta() {
        JLabel l = new JLabel("-");
        l.setFont(l.getFont().deriveFont(Font.BOLD, 32f));
        l.setForeground(Tema.TEXTO);
        return l;
    }

    private static JPanel envolver(String titulo, JLabel valor, Color franja) {
        Tema.Tarjeta p = new Tema.Tarjeta(new BorderLayout(0, 6), franja);
        JLabel t = new JLabel(titulo);
        t.setForeground(Tema.TEXTO_SUAVE);
        p.add(t, BorderLayout.NORTH);
        p.add(valor, BorderLayout.CENTER);
        return p;
    }

    @Override public void alMostrar() {
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> {
            Object[] r = new Object[7];
            r[0] = tx.scalar("SELECT COUNT(*) FROM habitaciones WHERE activa = 1 AND estado = 'LIBRE'");
            r[1] = tx.scalar("SELECT COUNT(*) FROM habitaciones WHERE activa = 1 AND estado = 'OCUPADA'");
            r[2] = tx.scalar("SELECT COUNT(*) FROM reservas WHERE estado IN ('PENDIENTE','CONFIRMADA') AND fecha_llegada = CURDATE()");
            r[3] = tx.scalar("SELECT COUNT(*) FROM estancias WHERE estado = 'ACTIVA' AND salida_prevista <= CURDATE()");
            r[4] = tx.scalar("SELECT COALESCE(SUM(total),0) FROM facturas WHERE estado = 'EMITIDA' AND DATE(fecha_emision) = CURDATE()");
            r[5] = tx.scalar("SELECT COUNT(*) FROM comandas WHERE estado IN ('ABIERTA','CERRADA')");
            r[6] = tx.query("SELECT tipo, severidad, mensaje FROM v_alertas ORDER BY FIELD(severidad,'ALTA','MEDIA'), fecha LIMIT 100");
            return r;
        }), r -> {
            lblLibres.setText(String.valueOf(r[0]));
            lblOcupadas.setText(String.valueOf(r[1]));
            lblLlegadas.setText(String.valueOf(r[2]));
            lblSalidas.setText(String.valueOf(r[3]));
            lblVentas.setText(Ui.lempiras(r[4]));
            lblComandas.setText(String.valueOf(r[5]));
            @SuppressWarnings("unchecked") List<Map<String, Object>> filas = (List<Map<String, Object>>) r[6];
            Ui.llenar(modeloAlertas, filas, "tipo", "severidad", "mensaje");
        });
    }
}
