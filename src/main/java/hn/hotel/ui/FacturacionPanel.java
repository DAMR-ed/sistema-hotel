package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.FacturacionService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;

/** Facturas emitidas: consulta, reimpresión, anulación y re-emisión; y cobro rápido de cuentas del restaurante. */
public class FacturacionPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Id", "Número", "Fecha", "Cliente", "Origen", "Condición", "Total", "Estado");
    private final JTable tabla = Ui.tabla(modelo);
    private final JSpinner desde = Campos.fecha(LocalDate.now().withDayOfMonth(1));
    private final JSpinner hasta = Campos.fecha(LocalDate.now());
    private final JTextField buscar = new JTextField(14);

    public FacturacionPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel norte = new JPanel(new BorderLayout());
        norte.add(Ui.titulo("Facturación"), BorderLayout.NORTH);
        norte.add(Ui.fila(new JLabel("Desde:"), desde, new JLabel("hasta:"), hasta, new JLabel("Cliente o número:"), buscar,
                Ui.boton("Mostrar", this::alMostrar)), BorderLayout.CENTER);
        add(norte, BorderLayout.NORTH);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        tabla.getColumnModel().getColumn(0).setMaxWidth(50);
        Ui.alinearDerecha(tabla, 6);
        buscar.addActionListener(e -> alMostrar());

        add(Ui.fila(
                Ui.boton("Ver / imprimir", () -> { int id = Ui.idSeleccionado(tabla); if (id > 0) FacturaVista.mostrar(this, id); else aviso(); }),
                Ui.boton("Cobrar cuenta del restaurante...", this::cobrarComanda),
                Ui.boton("Anular", () -> Autorizacion.ejecutar(this, "factura.anular", "anular facturas", this::anular)),
                Ui.boton("Re-emitir corregida", () -> Autorizacion.ejecutar(this, "factura.reemitir", "re-emitir facturas", this::reemitir)),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, modelo, "facturas_" + LocalDate.now()))), BorderLayout.SOUTH);
        add(new JLabel("<html><i>Las facturas no se borran ni se editan: se anulan con motivo y, si hubo error, se re-emiten con el siguiente número.</i></html>"), BorderLayout.PAGE_END);
    }

    private void aviso() { Ui.info(this, "Seleccione una factura de la tabla."); }

    private void cobrarComanda() {
        var filas = Db.query("SELECT c.id_comanda, CONCAT('#', c.id_comanda, ' ', COALESCE(m.nombre, IF(c.tipo='LLEVAR','Para llevar','Habitación')), ' - ', "
                + "DATE_FORMAT(c.abierta_en,'%H:%i'), ' - L ', FORMAT(COALESCE(t.subtotal,0),2)) AS txt FROM comandas c LEFT JOIN mesas m ON m.id_mesa = c.id_mesa "
                + "LEFT JOIN v_comandas_total t ON t.id_comanda = c.id_comanda WHERE c.estado IN ('ABIERTA','CERRADA') AND COALESCE(t.subtotal,0) > 0 ORDER BY c.id_comanda");
        if (filas.isEmpty()) { Ui.info(this, "No hay cuentas del restaurante pendientes de cobro."); return; }
        JComboBox<AccionesHotel.Opcion> c = AccionesHotel.combo(filas, "id_comanda", "txt", false);
        Campos.Formulario f = new Campos.Formulario().agregar("Cuenta:", c);
        if (Campos.dialogo(this, "Cobrar cuenta", f, () -> true) && FacturarDialog.comanda(this, AccionesHotel.idDe(c))) alMostrar();
    }

    private void anular() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { aviso(); return; }
        String motivo = JOptionPane.showInputDialog(this, "Motivo de la anulación (queda registrado):", "Anular factura", JOptionPane.QUESTION_MESSAGE);
        if (motivo == null) return;
        Ui.seguro(this, () -> { FacturacionService.anular(id, motivo); alMostrar(); });
    }

    private void reemitir() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { aviso(); return; }
        Ui.seguro(this, () -> {
            var f = Db.uno("SELECT estado, cliente_nombre, cliente_rtn FROM facturas WHERE id_factura = ?", id);
            if (!"ANULADA".equals(Db.str(f.get("estado")))) throw new IllegalArgumentException("Primero anule la factura incorrecta; luego se re-emite.");
            JTextField nombre = new JTextField(Db.str(f.get("cliente_nombre")), 24), rtn = new JTextField(Db.str(f.get("cliente_rtn")), 14);
            Campos.Formulario fm = new Campos.Formulario().agregar("Nombre correcto:", nombre).agregar("RTN correcto:", rtn)
                    .nota("Se emite una factura nueva (siguiente número) con las mismas líneas e importes.");
            long[] nid = {0};
            if (Campos.dialogo(this, "Re-emitir factura", fm, () -> { nid[0] = FacturacionService.reemitir(id, nombre.getText(), rtn.getText()); return true; })) {
                alMostrar();
                FacturaVista.mostrar(this, (int) nid[0]);
            }
        });
    }

    @Override public void alMostrar() {
        LocalDate d = Campos.valor(desde), h = Campos.valor(hasta);
        String q = "%" + buscar.getText().trim() + "%";
        Ui.enSegundoPlano(this, () -> Db.query("SELECT id_factura, numero_factura, fecha_emision, cliente_nombre, origen, condicion_pago, total, estado FROM facturas "
                + "WHERE DATE(fecha_emision) BETWEEN ? AND ? AND (cliente_nombre LIKE ? OR numero_factura LIKE ?) ORDER BY id_factura DESC LIMIT 1000", d, h, q, q),
            filas -> Ui.llenar(modelo, filas, "id_factura", "numero_factura", "fecha_emision", "cliente_nombre", "origen", "condicion_pago", "total", "estado"));
    }
}
