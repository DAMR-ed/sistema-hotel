package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.util.Map;

/** Cuentas por cobrar, abonos y cobranza. Los clientes se administran en el módulo «Clientes». */
public class CreditoPanel extends JPanel implements Actualizable {
    private final DefaultTableModel mCxc = Ui.modelo("Id", "Factura", "Cliente", "Emisión", "Vence", "Monto", "Saldo", "Días vencida", "Estado");
    private final JTable tCxc = Ui.tabla(mCxc);
    private final JCheckBox soloPendientes = new JCheckBox("Solo con saldo", true);

    public CreditoPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Crédito y cobros"), BorderLayout.NORTH);

        JPanel cxc = new JPanel(new BorderLayout(4, 4));
        tCxc.getColumnModel().getColumn(0).setMaxWidth(45);
        Ui.alinearDerecha(tCxc, 5, 6, 7);
        cxc.add(new JScrollPane(tCxc), BorderLayout.CENTER);
        cxc.add(Ui.fila(soloPendientes, Ui.boton("Registrar abono", this::abonar), Ui.boton("Ver factura", this::verFactura),
                Ui.boton("Abonos de la cuenta", this::verAbonos), Ui.boton("Antigüedad de saldos", () -> ReporteDialog.mostrar(this, "Antigüedad de saldos por cobrar (análisis de vencimiento)", null)),
                Ui.boton("Estado de cuenta del cliente", this::estadoCuenta), Ui.boton("Relación de cobros", () -> ReporteDialog.mostrar(this, "Relación de cobros (contado y abonos)", null)),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, mCxc, "cuentas_por_cobrar"))), BorderLayout.SOUTH);
        soloPendientes.addActionListener(e -> alMostrar());

        add(cxc, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        boolean pend = soloPendientes.isSelected();
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> new Object[]{
                tx.query("SELECT id_cxc, numero_factura, cliente, fecha_emision, fecha_vencimiento, monto, saldo, dias_vencido, estado FROM v_cuentas_por_cobrar "
                        + (pend ? "WHERE estado IN ('PENDIENTE','PARCIAL') " : "") + "ORDER BY fecha_vencimiento LIMIT 1000")
                }),
            r -> {
                @SuppressWarnings("unchecked") var a = (java.util.List<Map<String, Object>>) r[0];
                Ui.llenar(mCxc, a, "id_cxc", "numero_factura", "cliente", "fecha_emision", "fecha_vencimiento", "monto", "saldo", "dias_vencido", "estado");
            });
    }

    private void abonar() {
        int id = Ui.idSeleccionado(tCxc);
        if (id < 0) { Ui.info(this, "Seleccione una cuenta."); return; }
        Map<String, Object> x = Db.uno("SELECT saldo, estado, numero_factura, cliente FROM v_cuentas_por_cobrar WHERE id_cxc = ?", id);
        BigDecimal saldo = Db.dec(x.get("saldo"));
        JTextField monto = new JTextField(saldo.toPlainString(), 10), ref = new JTextField(14);
        JComboBox<String> metodo = new JComboBox<>(new String[]{"EFECTIVO", "TRANSFERENCIA", "CHEQUE", "TARJETA", "OTRO"});
        Campos.Formulario f = new Campos.Formulario();
        f.nota("Factura " + x.get("numero_factura") + " - " + x.get("cliente") + ". Saldo: " + Ui.lempiras(saldo))
         .agregar("Monto del abono (L):", monto).agregar("Forma de pago:", metodo).agregar("Referencia (cheque, transferencia...):", ref);
        if (Campos.dialogo(this, "Registrar abono", f, () -> {
            BigDecimal m = Ui.leerDinero(monto.getText(), "monto");
            if (m.signum() <= 0) throw new IllegalArgumentException("El abono debe ser mayor que cero.");
            long ida = Db.insert("INSERT INTO abonos (id_cxc, metodo, monto, referencia, id_usuario) VALUES (?,?,?,?,?)",
                    id, metodo.getSelectedItem(), m, ref.getText().isBlank() ? null : ref.getText().trim(), Sesion.idUsuario);
            Bitacora.registrar("ABONO", "cxc", id, "abono #" + ida + " L" + m);
            return true;
        })) alMostrar();
    }

    private void verFactura() {
        int id = Ui.idSeleccionado(tCxc);
        if (id < 0) { Ui.info(this, "Seleccione una cuenta."); return; }
        FacturaVista.mostrar(this, Db.ent(Db.scalar("SELECT id_factura FROM cuentas_por_cobrar WHERE id_cxc = ?", id)));
    }

    private void verAbonos() {
        int id = Ui.idSeleccionado(tCxc);
        if (id < 0) { Ui.info(this, "Seleccione una cuenta."); return; }
        DefaultTableModel m = Ui.modelo("Fecha", "Forma de pago", "Monto", "Referencia");
        Ui.llenar(m, Db.query("SELECT fecha, metodo, monto, referencia FROM abonos WHERE id_cxc = ? ORDER BY fecha", id), "fecha", "metodo", "monto", "referencia");
        JTable t = Ui.tabla(m);
        Ui.alinearDerecha(t, 2);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(520, 220));
        JOptionPane.showMessageDialog(this, sp, "Abonos", JOptionPane.PLAIN_MESSAGE);
    }

    private void antiguedad() {
        DefaultTableModel m = Ui.modelo("Cliente", "Por vencer", "1-30 días", "31-60 días", "61-90 días", "Más de 90", "Total");
        Ui.llenar(m, Db.query("SELECT cliente, SUM(IF(dias_vencido=0, saldo, 0)) AS vigente, SUM(IF(dias_vencido BETWEEN 1 AND 30, saldo, 0)) AS d30, "
                + "SUM(IF(dias_vencido BETWEEN 31 AND 60, saldo, 0)) AS d60, SUM(IF(dias_vencido BETWEEN 61 AND 90, saldo, 0)) AS d90, "
                + "SUM(IF(dias_vencido > 90, saldo, 0)) AS d91, SUM(saldo) AS total FROM v_cuentas_por_cobrar WHERE estado IN ('PENDIENTE','PARCIAL') "
                + "GROUP BY cliente ORDER BY total DESC"), "cliente", "vigente", "d30", "d60", "d90", "d91", "total");
        JTable t = Ui.tabla(m);
        Ui.alinearDerecha(t, 1, 2, 3, 4, 5, 6);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(760, 280));
        Object[] opciones = {"Exportar (CSV)", "Cerrar"};
        if (JOptionPane.showOptionDialog(this, sp, "Antigüedad de saldos", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opciones, opciones[1]) == 0)
            Exportar.csv(this, m, "antiguedad_saldos");
    }

    private void estadoCuenta() {
        int id = Ui.idSeleccionado(tCxc);
        if (id < 0) { Ui.info(this, "Seleccione una cuenta del cliente."); return; }
        Object cli = Db.scalar("SELECT id_cliente_credito FROM cuentas_por_cobrar WHERE id_cxc = ?", id);
        ReporteDialog.mostrar(this, "Estado de cuenta de cliente", Map.of("cliente", Db.ent(cli)));
    }
}
