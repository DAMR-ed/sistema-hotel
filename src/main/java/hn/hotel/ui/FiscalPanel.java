package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Autorizaciones CAI del SAR y libro de ventas por período. */
public class FiscalPanel extends JPanel implements Actualizable {
    private final DefaultTableModel mCai = Ui.modelo("Id", "CAI", "Documento", "Establ.", "Punto", "Rango inicial", "Rango final", "Siguiente", "Disponibles", "Vence", "Activo");
    private final JTable tCai = Ui.tabla(mCai);
    private final DefaultTableModel mLibro = Ui.modelo("Fecha", "Factura", "Cliente", "RTN", "Origen", "Condición", "Exonerado", "Exento", "Gravado", "ISV", "Tasa tur.", "Total", "Estado");
    private final JTable tLibro = Ui.tabla(mLibro);
    private final JSpinner desde = Campos.fecha(LocalDate.now().withDayOfMonth(1)), hasta = Campos.fecha(LocalDate.now());
    private final JLabel lblResumen = new JLabel(" ");

    public FiscalPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("CAI y libro de ventas"), BorderLayout.NORTH);

        JPanel cai = new JPanel(new BorderLayout(4, 4));
        tCai.getColumnModel().getColumn(0).setMaxWidth(45);
        Ui.alinearDerecha(tCai, 5, 6, 7, 8);
        cai.add(new JScrollPane(tCai), BorderLayout.CENTER);
        cai.add(Ui.fila(Ui.boton("Registrar nuevo CAI", this::nuevoCai), Ui.boton("Activar / desactivar", this::alternar),
                new JLabel("<html><i>Los datos salen de la constancia que entrega el SAR.</i></html>")), BorderLayout.SOUTH);

        JPanel libro = new JPanel(new BorderLayout(4, 4));
        libro.add(Ui.fila(new JLabel("Desde:"), desde, new JLabel("hasta:"), hasta, Ui.boton("Mostrar", this::cargarLibro),
                Ui.boton("Exportar a Excel (CSV)", () -> Exportar.csv(this, mLibro, "libro_ventas_" + Campos.valor(desde) + "_" + Campos.valor(hasta)))), BorderLayout.NORTH);
        libro.add(new JScrollPane(tLibro), BorderLayout.CENTER);
        Ui.alinearDerecha(tLibro, 6, 7, 8, 9, 10, 11);
        libro.add(lblResumen, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Autorizaciones CAI", cai);
        tabs.addTab("Libro de ventas", libro);
        add(tabs, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        Ui.enSegundoPlano(this, () -> Db.query("SELECT id_cai, cai, tipo_documento, establecimiento, punto_emision, rango_inicial, rango_final, siguiente_correlativo, "
                + "(rango_final - siguiente_correlativo + 1) AS disponibles, fecha_limite_emision, IF(activo=1,'Sí','No') AS act FROM cai_autorizaciones ORDER BY id_cai DESC"),
            filas -> Ui.llenar(mCai, filas, "id_cai", "cai", "tipo_documento", "establecimiento", "punto_emision", "rango_inicial", "rango_final",
                    "siguiente_correlativo", "disponibles", "fecha_limite_emision", "act"));
        cargarLibro();
    }

    private void cargarLibro() {
        LocalDate d = Campos.valor(desde), h = Campos.valor(hasta);
        Ui.enSegundoPlano(this, () -> Db.query("SELECT fecha, numero_factura, cliente_nombre, cliente_rtn, origen, condicion_pago, importe_exonerado, importe_exento, "
                + "(importe_gravado_15 + importe_gravado_18) AS gravado, (isv_15 + isv_18) AS isv, tasa_turistica, total, estado FROM v_libro_ventas WHERE fecha BETWEEN ? AND ? ORDER BY numero_factura", d, h),
            filas -> {
                Ui.llenar(mLibro, filas, "fecha", "numero_factura", "cliente_nombre", "cliente_rtn", "origen", "condicion_pago", "importe_exonerado", "importe_exento", "gravado", "isv", "tasa_turistica", "total", "estado");
                BigDecimal g = BigDecimal.ZERO, i = BigDecimal.ZERO, t = BigDecimal.ZERO, tt = BigDecimal.ZERO;
                int n = 0, anuladas = 0;
                for (Map<String, Object> f : filas) {
                    if ("ANULADA".equals(Db.str(f.get("estado")))) { anuladas++; continue; }
                    n++; g = g.add(Db.dec(f.get("gravado"))); i = i.add(Db.dec(f.get("isv"))); t = t.add(Db.dec(f.get("total"))); tt = tt.add(Db.dec(f.get("tasa_turistica")));
                }
                lblResumen.setText("<html><b>" + n + " facturas válidas</b> (" + anuladas + " anuladas, no suman) &nbsp; Gravado " + Ui.lempiras(g)
                        + " &nbsp; ISV " + Ui.lempiras(i) + " &nbsp; Tasa turística " + Ui.lempiras(tt) + " &nbsp; <b>Total " + Ui.lempiras(t) + "</b></html>");
            });
    }

    private void nuevoCai() {
        JTextField cai = new JTextField(36), est = new JTextField("000", 4), pto = new JTextField("001", 4), td = new JTextField("01", 3);
        JTextField ini = new JTextField("1", 10), fin = new JTextField(10);
        JSpinner vence = Campos.fecha(LocalDate.now().plusYears(1));
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("CAI (como aparece en la constancia):", cai).agregar("Establecimiento (3 dígitos):", est).agregar("Punto de emisión (3 dígitos):", pto)
         .agregar("Tipo de documento (2 dígitos, 01 = factura):", td).agregar("Número inicial del rango:", ini).agregar("Número final del rango:", fin)
         .agregar("Fecha límite de emisión:", vence)
         .nota("El número de factura será establecimiento-punto-tipo-correlativo de 8 dígitos. Verifique el formato con su constancia del SAR.");
        Campos.dialogo(this, "Registrar CAI", f, () -> {
            if (cai.getText().isBlank()) throw new IllegalArgumentException("Escriba el CAI.");
            if (!est.getText().matches("\\d{3}") || !pto.getText().matches("\\d{3}") || !td.getText().matches("\\d{2}"))
                throw new IllegalArgumentException("Establecimiento y punto llevan 3 dígitos; el tipo de documento, 2.");
            long a, b;
            try { a = Long.parseLong(ini.getText().trim()); b = Long.parseLong(fin.getText().trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("El rango debe ser numérico."); }
            if (a < 1 || b < a || b > 99999999L) throw new IllegalArgumentException("Rango inválido (máximo 8 dígitos).");
            if (!Campos.valor(vence).isAfter(LocalDate.now())) throw new IllegalArgumentException("La fecha límite ya pasó.");
            long id = Db.insert("INSERT INTO cai_autorizaciones (cai, tipo_documento, establecimiento, punto_emision, rango_inicial, rango_final, siguiente_correlativo, fecha_limite_emision) VALUES (?,?,?,?,?,?,?,?)",
                    cai.getText().trim(), td.getText(), est.getText(), pto.getText(), a, b, a, Campos.valor(vence));
            Bitacora.registrar("CREAR", "cai", id, cai.getText().trim());
            return true;
        });
        alMostrar();
    }

    private void alternar() {
        int id = Ui.idSeleccionado(tCai);
        if (id < 0) { Ui.info(this, "Seleccione un CAI."); return; }
        Db.exec("UPDATE cai_autorizaciones SET activo = 1 - activo WHERE id_cai = ?", id);
        Bitacora.registrar("ACTIVAR_CAI", "cai", id, null);
        alMostrar();
    }
}
