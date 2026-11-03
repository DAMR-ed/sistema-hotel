package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.FacturacionService;
import hn.hotel.service.VposService;
import hn.hotel.service.FacturacionService.*;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Cuadro de cobro: calcula la factura, elige contado o crédito, registra los pagos y emite. */
public final class FacturarDialog {
    private FacturarDialog() {}

    private record Vista(Totales totales, String nombre, String rtn, Integer idHuesped, BigDecimal anticipo,
                         BigDecimal pctElegible, Integer creditoSugerido) {}

    private interface Fuente {
        Vista vista(Integer idHuespedElegido, boolean aplicarDesc, boolean exonerado);
        long emitir(Opciones o);
    }

    public static boolean estancia(Component padre, int idEstancia) {
        return mostrar(padre, "Check-out y factura", false, "HOTEL", null, idEstancia, new Fuente() {
            @Override public Vista vista(Integer idH, boolean desc, boolean exo) {
                return Db.sin(tx -> {
                    CuentaEstancia c = FacturacionService.cuentaEstancia(tx, idEstancia, false);
                    BigDecimal elegible = FacturacionService.porcentajeAdultoMayor(tx, c.idHuesped(), LocalDate.now());
                    Totales t = FacturacionService.calcularConConfig(tx, c.lineas(), desc ? elegible : BigDecimal.ZERO, exo);
                    return new Vista(t, c.huesped(), c.rtnHuesped(), c.idHuesped(), c.anticipo().min(t.total), elegible, c.idClienteCreditoReserva());
                });
            }
            @Override public long emitir(Opciones o) { return FacturacionService.facturarEstancia(idEstancia, o); }
        });
    }

    public static boolean comanda(Component padre, int idComanda) { return comanda(padre, idComanda, null); }

    /** numCuenta != null: cobra solo esa subcuenta de la mesa (cuentas divididas). */
    public static boolean comanda(Component padre, int idComanda, Integer numCuenta) {
        return mostrar(padre, "Cobrar y facturar cuenta", true, "RESTAURANTE", idComanda, null, new Fuente() {
            @Override public Vista vista(Integer idH, boolean desc, boolean exo) {
                return Db.sin(tx -> {
                    CuentaComanda c = FacturacionService.cuentaComanda(tx, idComanda, false, numCuenta);
                    BigDecimal elegible = FacturacionService.porcentajeAdultoMayor(tx, idH, LocalDate.now());
                    Totales t = FacturacionService.calcularConConfig(tx, c.lineas(), desc ? elegible : BigDecimal.ZERO, exo);
                    return new Vista(t, "", "", idH, BigDecimal.ZERO, elegible, null);
                });
            }
            @Override public long emitir(Opciones o) { return FacturacionService.facturarCuenta(idComanda, numCuenta, o); }
        });
    }

    private static long ultimoAvisoCai = 0;

    private static boolean mostrar(Component padre, String titulo, boolean permiteElegirHuesped, String origen, Integer idComandaOrigen,
                                   Integer idEstanciaOrigen, Fuente fuente) {
        Integer punto = hn.hotel.service.Almacenes.puntoLocal();
        String bloqueo = hn.hotel.service.CaiService.bloqueo(punto);
        if (bloqueo != null) { Ui.error(padre, bloqueo); return false; }
        String aviso = hn.hotel.service.CaiService.aviso(punto);
        if (aviso != null && System.currentTimeMillis() - ultimoAvisoCai > 30 * 60 * 1000L) {   // máximo una vez cada 30 minutos
            ultimoAvisoCai = System.currentTimeMillis();
            Ui.info(padre, "Aviso del CAI:\n" + aviso);
        }
        Vista inicial;
        try { inicial = fuente.vista(null, true, false); }
        catch (RuntimeException e) { Ui.error(padre, e.getMessage()); return false; }

        JTextField nombre = new JTextField(inicial.nombre(), 24), rtn = new JTextField(inicial.rtn() == null ? "" : inicial.rtn(), 14);
        Integer[] idHuesped = {inicial.idHuesped()};
        JCheckBox chkDesc = new JCheckBox("Aplicar descuento de adulto mayor", true);
        JCheckBox chkExo = new JCheckBox("Venta exonerada de ISV (SAR)");
        JTextField ordenExenta = new JTextField(16), constancia = new JTextField(16), registroSag = new JTextField(16);
        for (JTextField t : new JTextField[]{ordenExenta, constancia, registroSag}) t.setEnabled(false);
        chkExo.addActionListener(e -> {
            for (JTextField t : new JTextField[]{ordenExenta, constancia, registroSag}) t.setEnabled(chkExo.isSelected());
            if (chkExo.isSelected()) ordenExenta.requestFocusInWindow();
        });
        DefaultTableModel mDet = Ui.modelo("Descripción", "Cant.", "P. unit.", "Desc.", "Neto", "Impuesto");
        JTable tDet = Ui.tabla(mDet);
        Ui.alinearDerecha(tDet, 1, 2, 3, 4);
        JLabel lblTotales = new JLabel();
        JRadioButton rContado = new JRadioButton("Contado", true), rCredito = new JRadioButton("Crédito");
        ButtonGroup bg = new ButtonGroup(); bg.add(rContado); bg.add(rCredito);
        java.util.Map<Integer, java.util.Map<String, Object>> fichas = new java.util.HashMap<>();
        JComboBox<AccionesHotel.Opcion> cliente = new JComboBox<>();
        JLabel infoCredito = new JLabel(" ");
        Runnable cargarClientes = () -> {
            int antes = cliente.getItemCount() == 0 ? 0 : AccionesHotel.idDe(cliente);
            cliente.removeAllItems();
            fichas.clear();
            cliente.addItem(new AccionesHotel.Opcion(0, "(Consumidor final / cliente eventual)"));
            for (var c : Db.query("SELECT c.id_cliente_credito, c.nombre, c.rtn, c.es_exento, c.exo_constancia, c.exo_registro_sag, c.limite_credito, "
                    + "COALESCE((SELECT SUM(x.saldo) FROM cuentas_por_cobrar x WHERE x.id_cliente_credito = c.id_cliente_credito AND x.estado IN ('PENDIENTE','PARCIAL')),0) AS saldo "
                    + "FROM clientes_credito c WHERE c.activo = 1 ORDER BY c.nombre")) {
                int id = Db.ent(c.get("id_cliente_credito"));
                fichas.put(id, c);
                cliente.addItem(new AccionesHotel.Opcion(id, Db.str(c.get("nombre"))));
            }
            AccionesHotel.seleccionar(cliente, antes);
        };
        cargarClientes.run();
        String[] metodos = {"EFECTIVO", "TARJETA", "TRANSFERENCIA", "CHEQUE", "OTRO"};
        JComboBox<String> m1 = new JComboBox<>(metodos), m2 = new JComboBox<>(metodos);
        JTextField p1 = new JTextField(9), p2 = new JTextField("0", 9), ref1 = new JTextField(10), ref2 = new JTextField(10);
        JLabel lblCambio = new JLabel(" ");
        Vista[] actual = {inicial};
        List<Long> vposIds = new ArrayList<>();
        JButton v1 = new JButton("Cobrar en PinPad"), v2 = new JButton("Cobrar en PinPad");

        Runnable recalcular = () -> {
            try {
                Vista v = fuente.vista(idHuesped[0], chkDesc.isSelected(), chkExo.isSelected());
                actual[0] = v;
                mDet.setRowCount(0);
                for (Detalle d : v.totales().detalles)
                    mDet.addRow(new Object[]{d.descripcion(), Ui.numero(d.cantidad()), Ui.numero(d.precio()),
                            d.descuento().signum() == 0 ? "" : Ui.numero(d.descuento()), Ui.numero(d.subtotal()),
                            d.tratamiento().equals("GRAVADO") ? "ISV " + Ui.numero(d.isv()) : d.tratamiento()});
                Totales t = v.totales();
                BigDecimal pagar = t.total.subtract(v.anticipo());
                lblTotales.setText("<html>Gravado " + Ui.numero(t.gravado15.add(t.gravado18)) + " | Exento " + Ui.numero(t.exento) + " | Exonerado " + Ui.numero(t.exonerado)
                        + " | Descuento " + Ui.numero(t.descuento) + (v.pctElegible().signum() > 0 ? " (" + v.pctElegible().stripTrailingZeros().toPlainString() + " %)" : "")
                        + "<br>ISV " + Ui.numero(t.isv15) + (t.isv18.signum() > 0 ? " | ISV 18 % " + Ui.numero(t.isv18) : "") + " | Tasa turística " + Ui.numero(t.tasaTuristica)
                        + "<br><b>TOTAL " + Ui.lempiras(t.total) + "</b>"
                        + (v.anticipo().signum() > 0 ? " &nbsp; Anticipo " + Ui.lempiras(v.anticipo()) + " &nbsp; <b>A pagar " + Ui.lempiras(pagar) + "</b>" : "") + "</html>");
                chkDesc.setEnabled(v.pctElegible().signum() > 0);
                if (rContado.isSelected() && (p1.getText().isBlank() || p1.getText().equals("0"))) p1.setText(pagar.toPlainString());
                actualizarCambio(lblCambio, p1, p2, pagar);
            } catch (RuntimeException e) { Ui.error(padre, e.getMessage()); }
        };
        p1.getDocument().addDocumentListener(simple(() -> actualizarCambio(lblCambio, p1, p2, actual[0].totales().total.subtract(actual[0].anticipo()))));
        p2.getDocument().addDocumentListener(simple(() -> actualizarCambio(lblCambio, p1, p2, actual[0].totales().total.subtract(actual[0].anticipo()))));
        chkDesc.addActionListener(e -> recalcular.run());
        chkExo.addActionListener(e -> recalcular.run());
        Runnable alElegirCliente = () -> {
            int id = AccionesHotel.idDe(cliente);
            var f = fichas.get(id);
            if (f == null) { infoCredito.setText(" "); return; }
            nombre.setText(Db.str(f.get("nombre")));
            rtn.setText(f.get("rtn") == null ? "" : f.get("rtn").toString());
            BigDecimal lim = Db.dec(f.get("limite_credito")), sal = Db.dec(f.get("saldo"));
            infoCredito.setText("<html>Límite " + Ui.lempiras(lim) + " | Saldo " + Ui.lempiras(sal) + " | <b>Disponible " + Ui.lempiras(lim.subtract(sal)) + "</b></html>");
            if (Db.ent(f.get("es_exento")) == 1) {
                chkExo.setSelected(true);
                for (JTextField t : new JTextField[]{ordenExenta, constancia, registroSag}) t.setEnabled(true);
                constancia.setText(Db.str(f.get("exo_constancia")));
                registroSag.setText(Db.str(f.get("exo_registro_sag")));
            }
            recalcular.run();
        };
        cliente.addActionListener(e -> alElegirCliente.run());

        JButton nuevoCliente = Ui.boton("Nuevo cliente...", () -> {
            if (ClienteForm.editar(padre, null)) {
                cargarClientes.run();
                Object ultimo = Db.scalar("SELECT MAX(id_cliente_credito) FROM clientes_credito");
                if (ultimo != null) AccionesHotel.seleccionar(cliente, Db.ent(ultimo));
            }
        });
        JPanel filaCliente = new JPanel(new BorderLayout(6, 0));
        filaCliente.add(cliente, BorderLayout.CENTER);
        filaCliente.add(nuevoCliente, BorderLayout.EAST);
        JPanel datosCliente = new Campos.Formulario().agregar("Cliente registrado:", filaCliente).agregar("", infoCredito)
                .agregar("Nombre en la factura:", nombre).agregar("RTN o identidad (opcional):", rtn)
                .agregar("", RtnValidacion.panel(padre, rtn, nombre))
                .agregar("", chkExo)
                .agregar("N.º correlativo de orden de compra exenta:", ordenExenta)
                .agregar("N.º correlativo de constancia de registro exonerado:", constancia)
                .agregar("N.º identificativo del registro de la SAG:", registroSag)
                .agregar("", chkDesc);
        if (permiteElegirHuesped) {
            JButton b = Ui.boton("Asociar huésped (para descuento)", () -> {
                int id = AccionesHotel.elegirHuesped(padre);
                if (id > 0) {
                    idHuesped[0] = id;
                    nombre.setText(AccionesHotel.nombreHuesped(id));
                    Object r = Db.scalar("SELECT rtn FROM huespedes WHERE id_huesped = ?", id);
                    rtn.setText(r == null ? "" : r.toString());
                    recalcular.run();
                }
            });
            datosCliente.add(b);
        }

        JPanel pagoContado = new JPanel(new GridLayout(0, 1, 2, 2));
        pagoContado.add(Ui.fila(new JLabel("Pago 1:"), m1, new JLabel("L"), p1, new JLabel("Ref.:"), ref1, v1));
        pagoContado.add(Ui.fila(new JLabel("Pago 2:"), m2, new JLabel("L"), p2, new JLabel("Ref.:"), ref2, v2));
        Runnable[] cobrarPinPad = new Runnable[2];
        for (int k = 0; k < 2; k++) {
            final int fila = k;
            JComboBox<String> m = fila == 0 ? m1 : m2;
            JTextField pp = fila == 0 ? p1 : p2, rr = fila == 0 ? ref1 : ref2, po = fila == 0 ? p2 : p1;
            JButton bt = fila == 0 ? v1 : v2;
            bt.setToolTipText("Cobra con el PinPad del banco. Puede ser un pago parcial: escriba el monto antes.");
            Runnable refrescarBoton = () -> bt.setVisible(VposService.activo() && "TARJETA".equals(m.getSelectedItem()));
            m.addActionListener(e -> refrescarBoton.run());
            refrescarBoton.run();
            bt.addActionListener(e -> {
                try {
                    Totales t = actual[0].totales();
                    BigDecimal pagar = t.total.subtract(actual[0].anticipo());
                    BigDecimal monto = vacioCero(pp);
                    if (monto.signum() <= 0) throw new IllegalArgumentException("Escriba primero el monto a cobrar con la tarjeta.");
                    BigDecimal otro = vacioCero(po);
                    if (monto.add(otro).compareTo(pagar) > 0 && otro.signum() > 0) po.setText("0");
                    VposService.Resultado r = PinPadDialogo.cobrar(padre, monto, t.total, t.isv15.add(t.isv18), origen, idComandaOrigen, idEstanciaOrigen);
                    if (r == null) return;
                    if (!r.aprobada()) { Ui.error(padre, "La tarjeta no fue aprobada: " + r.mensaje()); return; }
                    vposIds.add(r.idVpos());
                    pp.setText(r.monto().toPlainString());
                    rr.setText(("AUT " + Db.str(r.autorizacion()) + " " + Db.str(r.tarjeta())).trim());
                    m.setEnabled(false); pp.setEnabled(false); rr.setEnabled(false); bt.setEnabled(false);
                    chkDesc.setEnabled(false); chkExo.setEnabled(false); rCredito.setEnabled(false);
                    BigDecimal falta = pagar.subtract(vacioCero(p1)).subtract(vacioCero(p2));
                    if (falta.signum() > 0 && po.isEnabled()) po.setText(falta.toPlainString());
                    Ui.info(padre, "Tarjeta aprobada. Autorización " + Db.str(r.autorizacion()) + (falta.signum() > 0 ? "\nFalta por cobrar: " + Ui.lempiras(falta) : ""));
                } catch (RuntimeException ex) { Ui.error(padre, ex.getMessage()); }
            });
            cobrarPinPad[fila] = refrescarBoton;
        }
        pagoContado.add(lblCambio);
        JPanel pagoCredito = Ui.fila(new JLabel("<html>Se carga al cliente registrado elegido arriba. El sistema revisa su límite de crédito y sus facturas vencidas.</html>"));
        if (inicial.creditoSugerido() != null) { AccionesHotel.seleccionar(cliente, inicial.creditoSugerido()); rCredito.setSelected(true); alElegirCliente.run(); }
        JPanel pagos = new JPanel(new CardLayout());
        pagos.add(pagoContado, "C"); pagos.add(pagoCredito, "R");
        Runnable cambioCondicion = () -> ((CardLayout) pagos.getLayout()).show(pagos, rCredito.isSelected() ? "R" : "C");
        rContado.addActionListener(e -> cambioCondicion.run());
        rCredito.addActionListener(e -> cambioCondicion.run());
        cambioCondicion.run();

        JPanel p = new JPanel(new BorderLayout(6, 6));
        JPanel norte = new JPanel(new BorderLayout());
        norte.add(datosCliente, BorderLayout.CENTER);
        p.add(norte, BorderLayout.NORTH);
        JPanel centro = new JPanel(new BorderLayout(4, 4));
        JScrollPane sp = new JScrollPane(tDet);
        sp.setPreferredSize(new Dimension(720, 190));
        centro.add(sp, BorderLayout.CENTER);
        centro.add(lblTotales, BorderLayout.SOUTH);
        p.add(centro, BorderLayout.CENTER);
        JPanel sur = new JPanel(new BorderLayout());
        sur.setBorder(BorderFactory.createTitledBorder("Forma de pago"));
        sur.add(Ui.fila(rContado, rCredito), BorderLayout.NORTH);
        sur.add(pagos, BorderLayout.CENTER);
        p.add(sur, BorderLayout.SOUTH);
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        recalcular.run();

        long[] idFactura = {0};
        boolean ok = Campos.dialogo(padre, titulo, p, () -> {
            List<Pago> lista = new ArrayList<>();
            boolean credit = rCredito.isSelected();
            if (!credit) {
                BigDecimal a = vacioCero(p1), b = vacioCero(p2);
                if (a.signum() > 0) lista.add(new Pago((String) m1.getSelectedItem(), a, ref1.getText().isBlank() ? null : ref1.getText().trim()));
                if (b.signum() > 0) lista.add(new Pago((String) m2.getSelectedItem(), b, ref2.getText().isBlank() ? null : ref2.getText().trim()));
            }
            int idCli = AccionesHotel.idDe(cliente);
            if (credit && idCli == 0) throw new IllegalArgumentException("Para vender a crédito elija un cliente registrado (o créelo con «Nuevo cliente»).");
            boolean exo = chkExo.isSelected();
            Opciones o = new Opciones(idHuesped[0], nombre.getText(), rtn.getText(), credit, credit ? idCli : null,
                    lista, exo ? constancia.getText() : null, chkDesc.isSelected() && chkDesc.isEnabled(),
                    idCli == 0 ? null : idCli, exo ? ordenExenta.getText() : null, exo ? registroSag.getText() : null);
            if (exo && !o.exonerado()) throw new IllegalArgumentException("Para exonerar el ISV escriba los tres datos del SAR.");
            o.validarExoneracion();
            String problema = credit ? FacturacionService.verificarCredito(idCli, actual[0].totales().total) : null;
            if (problema != null) {
                if (!Autorizacion.ejecutar(padre, "credito.exceder", "vender a crédito: " + problema, () -> idFactura[0] = fuente.emitir(o))) return false;
            } else idFactura[0] = fuente.emitir(o);
            if (!vposIds.isEmpty()) VposService.vincularFactura(vposIds, idFactura[0]);
            return true;
        });
        if (!ok && !vposIds.isEmpty())
            Ui.error(padre, "ATENCIÓN: se cobraron " + vposIds.size() + " pago(s) con tarjeta en el PinPad pero la cuenta NO se facturó.\n"
                    + "Vuelva a cobrar la cuenta (los cobros quedan registrados en «Pagos con tarjeta») o anúlelos en ese módulo.");
        if (ok && idFactura[0] > 0) FacturaVista.mostrar(padre, (int) idFactura[0]);
        return ok;
    }

    private static BigDecimal vacioCero(JTextField t) {
        return t.getText().isBlank() ? BigDecimal.ZERO : Ui.leerDinero(t.getText(), "pago");
    }

    private static void actualizarCambio(JLabel lbl, JTextField p1, JTextField p2, BigDecimal pagar) {
        try {
            BigDecimal pagado = vacioCero(p1).add(vacioCero(p2));
            BigDecimal dif = pagado.subtract(pagar);
            lbl.setText(dif.signum() >= 0 ? "Cambio a entregar: " + Ui.lempiras(dif) : "Falta por cobrar: " + Ui.lempiras(dif.negate()));
        } catch (IllegalArgumentException e) { lbl.setText("Escriba montos válidos."); }
    }

    private static javax.swing.event.DocumentListener simple(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        };
    }
}
