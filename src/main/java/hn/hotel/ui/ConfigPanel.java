package hn.hotel.ui;

import hn.hotel.app.Config;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

/** Parámetros del hotel, impuestos y avisos. Solo el administrador. */
public class ConfigPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Clave", "Valor", "Descripción");
    private final JTable tabla = Ui.tabla(modelo);

    public ConfigPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Configuración"), BorderLayout.NORTH);
        tabla.getColumnModel().getColumn(0).setPreferredWidth(190);
        tabla.getColumnModel().getColumn(1).setPreferredWidth(200);
        tabla.getColumnModel().getColumn(2).setPreferredWidth(600);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        add(Ui.fila(Ui.boton("Cambiar valor", this::editar),
                new JLabel("<html><i>Los datos del hotel salen impresos en la factura. Los porcentajes de impuestos deben confirmarse con su contador / el SAR.</i></html>")), BorderLayout.SOUTH);
        tabla.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) editar(); }
        });
    }

    @Override public void alMostrar() {
        Ui.llenar(modelo, Db.query("SELECT clave, valor, descripcion FROM configuracion ORDER BY clave"), "clave", "valor", "descripcion");
    }

    private void editar() {
        int fila = tabla.getSelectedRow();
        if (fila < 0) { Ui.info(this, "Seleccione un parámetro."); return; }
        String clave = tabla.getValueAt(fila, 0).toString();
        String actual = tabla.getValueAt(fila, 1).toString();
        JTextField v = new JTextField(actual, 28);
        Campos.Formulario f = new Campos.Formulario().agregar(clave + ":", v).nota(tabla.getValueAt(fila, 2).toString());
        Campos.dialogo(this, "Cambiar parámetro", f, () -> {
            String nuevo = v.getText().trim();
            if (nuevo.isEmpty() && !clave.equals("hotel_rtn") && !clave.equals("hotel_direccion") && !clave.equals("hotel_telefono"))
                throw new IllegalArgumentException("El valor no puede quedar vacío.");
            boolean numerico = clave.startsWith("isv_") || clave.startsWith("tasa_turistica") && !clave.endsWith("recargo") || clave.startsWith("desc_tercera") || clave.startsWith("desc_cuarta")
                    || clave.startsWith("edad_") || clave.equals("recargo_persona_extra") || clave.equals("propina_sugerida") || clave.startsWith("dias_") || clave.startsWith("alerta_");
            if (numerico) {
                try { if (new java.math.BigDecimal(nuevo).signum() < 0) throw new NumberFormatException(); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Este parámetro debe ser un número positivo."); }
            }
            if (clave.startsWith("impresora_") && !nuevo.equalsIgnoreCase("NO") && !nuevo.matches("[A-Za-z0-9.\\-]+(:\\d{2,5})?"))
                throw new IllegalArgumentException("Escriba NO (desactivada) o la dirección de la impresora, por ejemplo 192.168.1.50:9100.");
            if (clave.startsWith("modulo_")) {
                if (!nuevo.equals("SI") && !nuevo.equals("NO")) throw new IllegalArgumentException("Use SI o NO.");
                if (nuevo.equals("NO")) {
                    boolean otroActivo = false;
                    for (String k : new String[]{"modulo_hotel", "modulo_restaurante", "modulo_retail"})
                        if (!k.equals(clave) && "SI".equalsIgnoreCase(Config.texto(k, k.equals("modulo_retail") ? "NO" : "SI").trim())) otroActivo = true;
                    if (!otroActivo) throw new IllegalArgumentException("Debe quedar activo al menos hotel, restaurante o tienda.");
                }
            }
            if (clave.equals("vpos_activo") && !nuevo.equals("SI") && !nuevo.equals("NO")) throw new IllegalArgumentException("Use SI o NO.");
            if (clave.equals("vpos_modo") && !java.util.Set.of("SIMULADOR", "LOCAL", "PUENTE").contains(nuevo))
                throw new IllegalArgumentException("Use SIMULADOR (pruebas), LOCAL (el DLL está en esta PC con Windows) o PUENTE (el DLL está en la PC de caja).");
            if (clave.equals("vpos_dll_ruta") && !nuevo.toLowerCase().endsWith(".dll")) throw new IllegalArgumentException("Escriba la ruta completa del archivo .dll, por ejemplo C:\\EDPvPOS\\EDPvPOSLink.dll");
            if (clave.equals("vpos_url") && !nuevo.matches("https?://[A-Za-z0-9.\\-]+(:\\d{2,5})?/?"))
                throw new IllegalArgumentException("Escriba la dirección del puente, por ejemplo http://192.168.1.20:8085");
            if (clave.equals("vpos_modo_tot") && !nuevo.equals("SIN_ISV") && !nuevo.equals("CON_ISV")) throw new IllegalArgumentException("Use SIN_ISV o CON_ISV.");
            if (clave.equals("vpos_timeout_seg") && !nuevo.matches("\\d{2,3}")) throw new IllegalArgumentException("Use segundos (por ejemplo 150).");
            if (clave.equals("ticket_caracteres") && !nuevo.matches("\\d{2}"))
                throw new IllegalArgumentException("Use un número de 2 cifras (42 o 48 para papel de 80 mm).");
            if ((clave.equals("precios_con_isv") || clave.equals("imprimir_auto") || clave.equals("mesero_ve_solo_suyas")) && !nuevo.equals("SI") && !nuevo.equals("NO"))
                throw new IllegalArgumentException("Use SI o NO.");
            if (clave.equals("hotel_rtn") && !nuevo.isEmpty() && !nuevo.matches("\\d{14}")) throw new IllegalArgumentException("El RTN debe tener 14 dígitos.");
            if (clave.equals("desc_adulto_mayor_aplica") && !nuevo.equals("HOSPEDAJE") && !nuevo.equals("TODO")) throw new IllegalArgumentException("Use HOSPEDAJE o TODO.");
            if (clave.equals("tasa_turistica_incluye_recargo") && !nuevo.equals("SI") && !nuevo.equals("NO")) throw new IllegalArgumentException("Use SI o NO.");
            Config.guardar(clave, nuevo);
            if (clave.startsWith("modulo_")) hn.hotel.app.Sesion.cargarNegocio();
            Bitacora.registrar("CONFIG", "configuracion", clave, actual + " -> " + nuevo);
            return true;
        });
        alMostrar();
    }
}
