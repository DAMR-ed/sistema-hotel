package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.util.Map;

/** Clientes con crédito, cuentas por cobrar, abonos y antigüedad de saldos. */
public class CreditoPanel extends JPanel implements Actualizable {
    private final DefaultTableModel mCxc = Ui.modelo("Id", "Factura", "Cliente", "Emisión", "Vence", "Monto", "Saldo", "Días vencida", "Estado");
    private final JTable tCxc = Ui.tabla(mCxc);
    private final DefaultTableModel mCli = Ui.modelo("Id", "Cliente", "RTN", "Contacto", "Teléfono", "Límite", "Días crédito", "Saldo actual", "Activo");
    private final JTable tCli = Ui.tabla(mCli);
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
                Ui.boton("Abonos de la cuenta", this::verAbonos), Ui.boton("Antigüedad de saldos", this::antiguedad),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, mCxc, "cuentas_por_cobrar"))), BorderLayout.SOUTH);
        soloPendientes.addActionListener(e -> alMostrar());

        JPanel cli = new JPanel(new BorderLayout(4, 4));
        tCli.getColumnModel().getColumn(0).setMaxWidth(45);
        Ui.alinearDerecha(tCli, 5, 6, 7);
        cli.add(new JScrollPane(tCli), BorderLayout.CENTER);
        cli.add(Ui.fila(Ui.boton("Nuevo cliente de crédito", () -> formulario(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(tCli); if (id > 0) formulario(id); else Ui.info(this, "Seleccione un cliente."); })), BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Cuentas por cobrar", cxc);
        tabs.addTab("Clientes de crédito", cli);
        add(tabs, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        boolean pend = soloPendientes.isSelected();
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> new Object[]{
                tx.query("SELECT id_cxc, numero_factura, cliente, fecha_emision, fecha_vencimiento, monto, saldo, dias_vencido, estado FROM v_cuentas_por_cobrar "
                        + (pend ? "WHERE estado IN ('PENDIENTE','PARCIAL') " : "") + "ORDER BY fecha_vencimiento LIMIT 1000"),
                tx.query("SELECT c.id_cliente_credito, c.nombre, c.rtn, c.contacto, c.telefono, c.limite_credito, c.dias_credito, "
                        + "COALESCE((SELECT SUM(x.saldo) FROM cuentas_por_cobrar x WHERE x.id_cliente_credito = c.id_cliente_credito AND x.estado IN ('PENDIENTE','PARCIAL')),0) AS saldo, "
                        + "IF(c.activo=1,'Sí','No') AS act FROM clientes_credito c ORDER BY c.nombre")}),
            r -> {
                @SuppressWarnings("unchecked") var a = (java.util.List<Map<String, Object>>) r[0];
                @SuppressWarnings("unchecked") var b = (java.util.List<Map<String, Object>>) r[1];
                Ui.llenar(mCxc, a, "id_cxc", "numero_factura", "cliente", "fecha_emision", "fecha_vencimiento", "monto", "saldo", "dias_vencido", "estado");
                Ui.llenar(mCli, b, "id_cliente_credito", "nombre", "rtn", "contacto", "telefono", "limite_credito", "dias_credito", "saldo", "act");
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

    private void formulario(Integer id) {
        Map<String, Object> c = id == null ? null : Db.uno("SELECT * FROM clientes_credito WHERE id_cliente_credito = ?", id);
        JTextField nombre = new JTextField(c == null ? "" : Db.str(c.get("nombre")), 24), rtn = new JTextField(c == null ? "" : Db.str(c.get("rtn")), 14),
                contacto = new JTextField(c == null ? "" : Db.str(c.get("contacto")), 18), tel = new JTextField(c == null ? "" : Db.str(c.get("telefono")), 14),
                email = new JTextField(c == null ? "" : Db.str(c.get("email")), 22), dir = new JTextField(c == null ? "" : Db.str(c.get("direccion")), 24),
                limite = new JTextField(c == null ? "0" : Db.dec(c.get("limite_credito")).toPlainString(), 12);
        JSpinner dias = Campos.entero(c == null ? 30 : Db.ent(c.get("dias_credito")), 1, 365);
        JCheckBox activo = new JCheckBox("Activo", c == null || Db.ent(c.get("activo")) == 1);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre / razón social:", nombre).agregar("RTN (14 dígitos):", rtn).agregar("Contacto:", contacto).agregar("Teléfono:", tel)
         .agregar("Correo:", email).agregar("Dirección:", dir).agregar("Límite de crédito (L):", limite).agregar("Días de crédito:", dias).agregar("", activo)
         .nota("Con límite 0 el cliente no puede comprar a crédito.");
        Campos.dialogo(this, id == null ? "Nuevo cliente de crédito" : "Editar cliente", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            String r = rtn.getText().trim();
            if (!r.isEmpty() && !r.matches("\\d{14}")) throw new IllegalArgumentException("El RTN debe tener 14 dígitos.");
            BigDecimal lim = Ui.leerDinero(limite.getText(), "límite");
            if (lim.signum() < 0) throw new IllegalArgumentException("El límite no puede ser negativo.");
            Object[] v = {nombre.getText().trim(), r.isEmpty() ? null : r, nv(contacto), nv(tel), nv(email), nv(dir), lim, Campos.valorEntero(dias), activo.isSelected() ? 1 : 0};
            if (id == null) Db.insert("INSERT INTO clientes_credito (nombre, rtn, contacto, telefono, email, direccion, limite_credito, dias_credito, activo) VALUES (?,?,?,?,?,?,?,?,?)", v);
            else Db.exec("UPDATE clientes_credito SET nombre=?, rtn=?, contacto=?, telefono=?, email=?, direccion=?, limite_credito=?, dias_credito=?, activo=? WHERE id_cliente_credito=?",
                    v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], id);
            Bitacora.registrar(id == null ? "CREAR" : "EDITAR", "cliente_credito", id, nombre.getText().trim() + " límite " + lim);
            return true;
        });
        alMostrar();
    }

    private static String nv(JTextField t) { return t.getText().isBlank() ? null : t.getText().trim(); }
}
