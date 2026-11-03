package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.service.VposService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Pagos con tarjeta por el vPOS: operaciones, anulaciones, cierre de lote y reportes. */
public class VposPanel extends JPanel implements Actualizable {
    private final JSpinner desde = Campos.fecha(LocalDate.now()), hasta = Campos.fecha(LocalDate.now());
    private final JComboBox<String> cmbEstado = new JComboBox<>(new String[]{"TODOS", "APROBADA", "RECHAZADA", "ANULADA", "ERROR", "PENDIENTE"});
    private final JComboBox<String> cmbTipo = new JComboBox<>(new String[]{"TODOS", "VENTA", "ANULACION"});
    private final DefaultTableModel mOps = Ui.modelo("Id", "Fecha", "Tipo", "Origen", "Referencia", "Factura", "Monto", "Tarjeta", "Autorización", "Lote", "Estado", "Cajero", "Cierre", "Detalle");
    private final JTable tOps = Ui.tabla(mOps);
    private final JLabel lblTotales = new JLabel(" ");
    private final DefaultTableModel mTarjeta = Ui.modelo("Tarjeta", "Operaciones", "Monto"), mCajero = Ui.modelo("Cajero", "Operaciones", "Monto");
    private final DefaultTableModel mCierres = Ui.modelo("Id", "Fecha", "Cajero", "Ventas", "Monto ventas", "Anuladas", "Monto anuladas", "Resultado");
    private final JLabel lblPendiente = new JLabel(" ");

    public VposPanel() {
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        add(new JLabel("<html><span style='font-size:20px'><b>Pagos con tarjeta (vPOS)</b></span></html>"), BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Operaciones", pestanaOperaciones());
        tabs.addTab("Cierre de lote", pestanaCierre());
        tabs.addTab("Resúmenes", pestanaResumenes());
        tabs.addTab("Terminal", pestanaTerminal());
        add(tabs, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------ operaciones
    private JComponent pestanaOperaciones() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        p.add(Ui.fila(new JLabel("Desde:"), desde, new JLabel("Hasta:"), hasta, new JLabel("Estado:"), cmbEstado, new JLabel("Tipo:"), cmbTipo,
                Ui.botonPrimario("Mostrar", this::alMostrar)), BorderLayout.NORTH);
        tOps.getColumnModel().getColumn(0).setMaxWidth(55);
        Ui.alinearDerecha(tOps, 6);
        p.add(new JScrollPane(tOps), BorderLayout.CENTER);
        JPanel sur = new JPanel(new BorderLayout());
        sur.add(lblTotales, BorderLayout.CENTER);
        sur.add(Ui.fila(Ui.boton("Anular cobro seleccionado...", this::anular), Ui.boton("Anular otro cobro (sin registro)...", () -> anularConId(null)),
                Ui.boton("Exportar a Excel (CSV)", () -> Exportar.csv(this, mOps, "vpos_operaciones_" + LocalDate.now()))), BorderLayout.SOUTH);
        p.add(sur, BorderLayout.SOUTH);
        return p;
    }

    private void anular() {
        int id = Ui.idSeleccionado(tOps);
        if (id < 0) { Ui.info(this, "Seleccione en la lista el cobro que quiere anular."); return; }
        int fila = tOps.convertRowIndexToModel(tOps.getSelectedRow());
        if (!"VENTA".equals(mOps.getValueAt(fila, 2)) || !"APROBADA".equals(mOps.getValueAt(fila, 10))) { Ui.info(this, "Solo se anulan ventas APROBADAS."); return; }
        if (!"".equals(String.valueOf(mOps.getValueAt(fila, 12))))
            if (!Ui.confirmar(this, "Esta venta ya pertenece a un cierre de lote. Anularla ahora puede no ser posible en el banco. ¿Continuar?")) return;
        if (!"".equals(String.valueOf(mOps.getValueAt(fila, 5))))
            if (!Ui.confirmar(this, "Este cobro ya está en la factura " + mOps.getValueAt(fila, 5) + ".\nDespués deberá anular esa factura y emitir otra. ¿Continuar?")) return;
        anularConId((long) id);
    }

    private void anularConId(Long idOriginal) {
        Ui.seguro(this, () -> {
            long idAn = VposService.iniciarAnulacion(idOriginal);
            Ui.info(this, "Se abrirá la pantalla de anulación del PinPad.\nDigite ahí la referencia o autorización de la venta a anular.");
            String r = PinPadDialogo.esperar(this, "Anulación en PinPad", "<html><div style='text-align:center;width:300px'>Complete la anulación en el PinPad.<br>"
                    + "<span style='color:#64748B'>No cierre esta ventana.</span></div></html>", () -> VposService.ejecutarAnulacion(idAn), true);
            if (r == null) { alMostrar(); return; }
            boolean seAnulo = Ui.confirmar(this, "Respuesta del PinPad: " + r.substring(r.indexOf('|') + 1)
                    + "\n\n¿El PinPad confirmó que la venta fue ANULADA?");
            VposService.confirmarAnulacion(idAn, seAnulo);
            alMostrar();
        });
    }

    // ------------------------------------------------------------------ cierre
    private JComponent pestanaCierre() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        Tema.Tarjeta t = new Tema.Tarjeta(new BorderLayout(0, 8), Tema.ACENTO);
        lblPendiente.setFont(lblPendiente.getFont().deriveFont(14f));
        t.add(lblPendiente, BorderLayout.CENTER);
        JButton cerrar = Ui.botonPrimario("Realizar cierre de lote", this::cerrar);
        cerrar.setMargin(new Insets(10, 20, 10, 20));
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        b.setOpaque(false);
        b.add(cerrar);
        t.add(b, BorderLayout.SOUTH);
        p.add(t, BorderLayout.NORTH);
        JPanel hist = new JPanel(new BorderLayout(0, 6));
        hist.add(new JLabel("<html><b>Historial de cierres</b> (rango de fechas de la pestaña Operaciones)</html>"), BorderLayout.NORTH);
        JTable tc = Ui.tabla(mCierres);
        Ui.alinearDerecha(tc, 3, 4, 5, 6);
        hist.add(new JScrollPane(tc), BorderLayout.CENTER);
        hist.add(Ui.fila(Ui.boton("Exportar a Excel (CSV)", () -> Exportar.csv(this, mCierres, "vpos_cierres_" + LocalDate.now()))), BorderLayout.SOUTH);
        p.add(hist, BorderLayout.CENTER);
        return p;
    }

    private void cerrar() {
        Ui.seguro(this, () -> {
            VposService.Pendientes pe = VposService.pendientesDeCierre();
            if (pe.conError() > 0 && !Ui.confirmar(this, "Hay " + pe.conError() + " operación(es) con ERROR o PENDIENTES sin resolver.\n"
                    + "Revíselas en la pestaña Operaciones antes de cerrar. ¿Cerrar de todos modos?")) return;
            if (!Ui.confirmar(this, "Se hará el cierre de lote en el banco.\n\nVentas: " + pe.ventas() + " por " + Ui.lempiras(pe.montoVentas())
                    + "\nAnuladas: " + pe.anuladas() + " por " + Ui.lempiras(pe.montoAnuladas()) + "\n\n¿Continuar?")) return;
            VposService.Cierre c = PinPadDialogo.esperar(this, "Cierre de lote", "<html><div style='text-align:center;width:300px'>Realizando el cierre con el banco...<br>"
                    + "<span style='color:#64748B'>No cierre esta ventana.</span></div></html>", VposService::cerrar, true);
            if (c == null) return;
            if (c.exito()) Ui.info(this, "Cierre #" + c.idCierre() + " realizado.\nRespuesta del banco: " + c.raw());
            else Ui.error(this, "El banco no aceptó el cierre. Respuesta: " + c.raw());
            alMostrar();
        });
    }

    // ------------------------------------------------------------------ resúmenes
    private JComponent pestanaResumenes() {
        JPanel p = new JPanel(new GridLayout(1, 2, 16, 0));
        p.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        JTable a = Ui.tabla(mTarjeta), b = Ui.tabla(mCajero);
        Ui.alinearDerecha(a, 1, 2);
        Ui.alinearDerecha(b, 1, 2);
        JPanel pa = new JPanel(new BorderLayout(0, 6)), pb = new JPanel(new BorderLayout(0, 6));
        pa.add(new JLabel("<html><b>Ventas aprobadas por tarjeta</b></html>"), BorderLayout.NORTH);
        pa.add(new JScrollPane(a));
        pb.add(new JLabel("<html><b>Ventas aprobadas por cajero</b></html>"), BorderLayout.NORTH);
        pb.add(new JScrollPane(b));
        p.add(pa);
        p.add(pb);
        return p;
    }

    // ------------------------------------------------------------------ terminal
    private JComponent pestanaTerminal() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 14));
        p.add(new JLabel("<html><div style='width:420px'>Reportes que imprime el propio PinPad del banco.</div></html>"));
        p.add(Ui.boton("Reporte detallado", () -> reporte("DET")));
        p.add(Ui.boton("Reporte de totales", () -> reporte("TOT")));
        p.add(Ui.boton("Probar conexión con el puente", () -> Ui.seguro(this, () -> Ui.info(this, "Puente conectado: " + VposService.probar()))));
        return p;
    }

    private void reporte(String tipo) {
        Ui.seguro(this, () -> {
            Boolean ok = PinPadDialogo.esperar(this, "Reporte", "Enviando al PinPad...", () -> VposService.reporteTerminal(tipo), true);
            if (ok != null) { if (ok) Ui.info(this, "Reporte enviado al PinPad."); else Ui.error(this, "El PinPad no pudo imprimir el reporte."); }
        });
    }

    // ------------------------------------------------------------------ datos
    @Override public void alMostrar() {
        LocalDate d = Campos.valor(desde), h = Campos.valor(hasta);
        String est = (String) cmbEstado.getSelectedItem(), tipo = (String) cmbTipo.getSelectedItem();
        Ui.enSegundoPlano(this, () -> new Object[]{
                VposService.operaciones(d, h, est, tipo), VposService.resumenPorTarjeta(d, h), VposService.resumenPorCajero(d, h),
                VposService.cierres(d, h), VposService.pendientesDeCierre()}, r -> {
            @SuppressWarnings("unchecked") List<Map<String, Object>> ops = (List<Map<String, Object>>) r[0];
            Ui.llenar(mOps, ops, "id_vpos", "fecha", "tipo", "origen", "origen_ref", "factura", "monto_total", "tarjeta", "autorizacion", "lote", "estado", "cajero", "id_cierre", "detalle");
            BigDecimal apr = BigDecimal.ZERO, anu = BigDecimal.ZERO;
            int nApr = 0, nAnu = 0, nRech = 0, nErr = 0;
            for (Map<String, Object> o : ops) {
                if (!"VENTA".equals(o.get("tipo"))) continue;
                switch (String.valueOf(o.get("estado"))) {
                    case "APROBADA" -> { apr = apr.add((BigDecimal) o.get("monto_total")); nApr++; }
                    case "ANULADA" -> { anu = anu.add((BigDecimal) o.get("monto_total")); nAnu++; }
                    case "RECHAZADA" -> nRech++;
                    case "ERROR", "PENDIENTE" -> nErr++;
                    default -> { }
                }
            }
            lblTotales.setText("<html>Aprobadas: <b>" + nApr + "</b> por <b>" + Ui.lempiras(apr) + "</b> &nbsp;|&nbsp; Anuladas: " + nAnu + " por " + Ui.lempiras(anu)
                    + " &nbsp;|&nbsp; Rechazadas: " + nRech + " &nbsp;|&nbsp; Con error/pendientes: " + nErr + "</html>");
            @SuppressWarnings("unchecked") List<Map<String, Object>> tar = (List<Map<String, Object>>) r[1];
            Ui.llenar(mTarjeta, tar, "marca", "operaciones", "monto");
            @SuppressWarnings("unchecked") List<Map<String, Object>> caj = (List<Map<String, Object>>) r[2];
            Ui.llenar(mCajero, caj, "cajero", "operaciones", "monto");
            @SuppressWarnings("unchecked") List<Map<String, Object>> cie = (List<Map<String, Object>>) r[3];
            Ui.llenar(mCierres, cie, "id_cierre", "fecha", "cajero", "num_ventas", "monto_ventas", "num_anuladas", "monto_anuladas", "resultado");
            VposService.Pendientes pe = (VposService.Pendientes) r[4];
            lblPendiente.setText("<html><b>Pendiente de cierre</b><br>Ventas aprobadas: <b>" + pe.ventas() + "</b> por <b>" + Ui.lempiras(pe.montoVentas())
                    + "</b><br>Anuladas: " + pe.anuladas() + " por " + Ui.lempiras(pe.montoAnuladas())
                    + (pe.conError() > 0 ? "<br><span style='color:#DC2626'>Operaciones con error o pendientes: " + pe.conError() + "</span>" : "") + "</html>");
        });
    }
}
