package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Ficha completa de cliente: datos generales, crédito y exoneración (SAR). */
public final class ClienteForm {
    private ClienteForm() {}

    private static final String[] TIPOS = {"PERSONA", "EMPRESA", "GOBIERNO", "ONG", "OTRO"};

    /** Abre la ficha (id == null: cliente nuevo). Devuelve true si se guardó. */
    public static boolean editar(Component padre, Integer id) {
        Map<String, Object> c = id == null ? null : Db.uno("SELECT * FROM clientes_credito WHERE id_cliente_credito = ?", id);
        JTextField codigo = new JTextField(c == null ? "" : Db.str(c.get("codigo")), 12), nombre = new JTextField(c == null ? "" : Db.str(c.get("nombre")), 26),
                rtn = new JTextField(c == null ? "" : Db.str(c.get("rtn")), 16), contacto = new JTextField(c == null ? "" : Db.str(c.get("contacto")), 20),
                tel = new JTextField(c == null ? "" : Db.str(c.get("telefono")), 14), cel = new JTextField(c == null ? "" : Db.str(c.get("celular")), 14),
                email = new JTextField(c == null ? "" : Db.str(c.get("email")), 24), dir = new JTextField(c == null ? "" : Db.str(c.get("direccion")), 26),
                ciudad = new JTextField(c == null ? "" : Db.str(c.get("ciudad")), 18), notas = new JTextField(c == null ? "" : Db.str(c.get("notas")), 26),
                limite = new JTextField(c == null ? "0" : Db.dec(c.get("limite_credito")).toPlainString(), 12),
                constancia = new JTextField(c == null ? "" : Db.str(c.get("exo_constancia")), 18), sag = new JTextField(c == null ? "" : Db.str(c.get("exo_registro_sag")), 18),
                vence = new JTextField(c == null || c.get("exo_vence") == null ? "" : Ui.texto(Ui.fecha(c.get("exo_vence"))), 10);
        JComboBox<String> tipo = new JComboBox<>(TIPOS);
        if (c != null) tipo.setSelectedItem(Db.str(c.get("tipo_cliente")));
        ZonaCampo zona = new ZonaCampo(c == null || c.get("id_zona") == null ? null : Db.ent(c.get("id_zona")));
        JSpinner dias = Campos.entero(c == null ? 30 : Db.ent(c.get("dias_credito")), 1, 365);
        JCheckBox activo = new JCheckBox("Cliente activo", c == null || Db.ent(c.get("activo")) == 1);
        JCheckBox bloquear = new JCheckBox("No vender a crédito si tiene facturas vencidas", c == null || Db.ent(c.get("bloquear_vencidos")) == 1);
        JCheckBox exento = new JCheckBox("Cliente exento / exonerado de ISV", c != null && Db.ent(c.get("es_exento")) == 1);
        Runnable alternarExo = () -> { for (JTextField t : new JTextField[]{constancia, sag, vence}) t.setEnabled(exento.isSelected()); };
        exento.addActionListener(e -> alternarExo.run());
        alternarExo.run();

        Campos.Formulario general = new Campos.Formulario();
        general.agregar("Código (vacío = automático):", codigo).agregar("Nombre / razón social:", nombre).agregar("Tipo de cliente:", tipo)
               .agregar("RTN o identidad:", rtn).agregar("Persona de contacto:", contacto).agregar("Teléfono:", tel).agregar("Celular / WhatsApp:", cel)
               .agregar("Correo:", email).agregar("Dirección:", dir).agregar("Ciudad:", ciudad).agregar("Zona:", zona).agregar("Notas:", notas).agregar("", activo);

        Campos.Formulario credito = new Campos.Formulario();
        credito.agregar("Límite de crédito (L):", limite).agregar("Días de crédito:", dias).agregar("", bloquear)
               .nota("Con límite 0 el cliente no puede comprar a crédito. Para vender sobre el límite hace falta la autorización de un supervisor.");
        if (c != null) {
            BigDecimal saldo = Db.dec(Db.scalar("SELECT COALESCE(SUM(saldo),0) FROM cuentas_por_cobrar WHERE id_cliente_credito = ? AND estado IN ('PENDIENTE','PARCIAL')", id));
            Object venc = Db.scalar("SELECT COALESCE(SUM(saldo),0) FROM cuentas_por_cobrar WHERE id_cliente_credito = ? AND estado IN ('PENDIENTE','PARCIAL') AND fecha_vencimiento < CURDATE()", id);
            credito.nota("Saldo actual: " + Ui.lempiras(saldo) + "   |   Vencido: " + Ui.lempiras(Db.dec(venc)));
        }

        Campos.Formulario exo = new Campos.Formulario();
        exo.agregar("", exento).agregar("N.º de constancia de registro exonerado:", constancia).agregar("N.º identificativo del registro de la SAG:", sag)
           .agregar("Vigente hasta (dd/mm/aaaa):", vence)
           .nota("Al cobrarle, el punto de venta no calcula ISV y pide el N.º de orden de compra exenta de esa compra, que cambia en cada venta (el SAR lo exige en la factura).");

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", general);
        tabs.addTab("Crédito", credito);
        tabs.addTab("Exoneración (SAR)", exo);

        double limiteAntes = c == null ? 0 : Db.dec(c.get("limite_credito")).doubleValue();
        boolean exentoAntes = c != null && Db.ent(c.get("es_exento")) == 1;
        return Campos.dialogo(padre, id == null ? "Nuevo cliente" : "Ficha del cliente", tabs, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre del cliente.");
            String r = rtn.getText().trim();
            if (!r.isEmpty() && !r.matches("\\d{14}")) throw new IllegalArgumentException("El RTN debe tener 14 dígitos.");
            BigDecimal lim = Ui.leerDinero(limite.getText(), "límite de crédito");
            if (lim.signum() < 0) throw new IllegalArgumentException("El límite no puede ser negativo.");
            LocalDate fVence = vence.getText().isBlank() ? null : Ui.leerFecha(vence.getText(), "vigente hasta");
            if (exento.isSelected() && (constancia.getText().isBlank() || sag.getText().isBlank()))
                throw new IllegalArgumentException("Para un cliente exonerado escriba el N.º de constancia de registro exonerado y el N.º de registro de la SAG.");
            String cod = codigo.getText().isBlank() ? null : codigo.getText().trim();
            if (cod != null && Db.scalar("SELECT 1 FROM clientes_credito WHERE codigo = ? AND id_cliente_credito <> ?", cod, id == null ? 0 : id) != null)
                throw new IllegalArgumentException("Ese código ya lo tiene otro cliente.");
            // funciones delicadas: cambian solo con permiso o con la clave de un supervisor
            if (lim.doubleValue() != limiteAntes && (id != null || lim.signum() > 0)
                    && !Autorizacion.ejecutar(padre, "clientes.limite", "cambiar el límite de crédito", () -> { })) return false;
            if (exento.isSelected() != exentoAntes && !Autorizacion.ejecutar(padre, "clientes.exento", "cambiar la exoneración de ISV de un cliente", () -> { })) return false;
            Object[] v = {cod, nombre.getText().trim(), tipo.getSelectedItem(), nv(rtn), nv(contacto), nv(tel), nv(cel), nv(email), nv(dir), nv(ciudad), zona.id(), nv(notas),
                    lim, Campos.valorEntero(dias), bloquear.isSelected() ? 1 : 0, exento.isSelected() ? 1 : 0,
                    exento.isSelected() ? nv(constancia) : null, exento.isSelected() ? nv(sag) : null, exento.isSelected() ? fVence : null, activo.isSelected() ? 1 : 0};
            long idGuardado;
            if (id == null) {
                idGuardado = Db.insert("INSERT INTO clientes_credito (codigo, nombre, tipo_cliente, rtn, contacto, telefono, celular, email, direccion, ciudad, id_zona, notas, "
                        + "limite_credito, dias_credito, bloquear_vencidos, es_exento, exo_constancia, exo_registro_sag, exo_vence, activo) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", v);
                if (cod == null) Db.exec("UPDATE clientes_credito SET codigo = ? WHERE id_cliente_credito = ?", String.format("C%05d", idGuardado), idGuardado);
            } else {
                idGuardado = id;
                Object[] w = new Object[v.length + 1];
                System.arraycopy(v, 0, w, 0, v.length);
                w[v.length] = id;
                Db.exec("UPDATE clientes_credito SET codigo=?, nombre=?, tipo_cliente=?, rtn=?, contacto=?, telefono=?, celular=?, email=?, direccion=?, ciudad=?, id_zona=?, notas=?, "
                        + "limite_credito=?, dias_credito=?, bloquear_vencidos=?, es_exento=?, exo_constancia=?, exo_registro_sag=?, exo_vence=?, activo=? WHERE id_cliente_credito=?", w);
            }
            Bitacora.registrar(id == null ? "CREAR" : "EDITAR", "cliente", idGuardado, nombre.getText().trim() + " límite " + lim + (exento.isSelected() ? " EXENTO" : ""));
            return true;
        });
    }

    static String nv(JTextField t) { return t.getText().isBlank() ? null : t.getText().trim(); }
}
