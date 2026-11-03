package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.Map;

/** Ficha completa de proveedor: datos generales, crédito que nos dan y exoneración. */
public final class ProveedorForm {
    private ProveedorForm() {}

    public static boolean editar(Component padre, Integer id) {
        Map<String, Object> p = id == null ? null : Db.uno("SELECT * FROM proveedores WHERE id_proveedor = ?", id);
        JTextField codigo = new JTextField(p == null ? "" : Db.str(p.get("codigo")), 12), nombre = new JTextField(p == null ? "" : Db.str(p.get("nombre")), 26),
                rtn = new JTextField(p == null ? "" : Db.str(p.get("rtn")), 16), contacto = new JTextField(p == null ? "" : Db.str(p.get("contacto")), 20),
                tel = new JTextField(p == null ? "" : Db.str(p.get("telefono")), 14), cel = new JTextField(p == null ? "" : Db.str(p.get("celular")), 14),
                email = new JTextField(p == null ? "" : Db.str(p.get("email")), 24), dir = new JTextField(p == null ? "" : Db.str(p.get("direccion")), 26),
                ciudad = new JTextField(p == null ? "" : Db.str(p.get("ciudad")), 18), notas = new JTextField(p == null ? "" : Db.str(p.get("notas")), 26),
                limite = new JTextField(p == null ? "0" : Db.dec(p.get("limite_credito")).toPlainString(), 12),
                dias = new JTextField(p == null ? "0" : Db.str(p.get("dias_credito")), 6),
                constancia = new JTextField(p == null ? "" : Db.str(p.get("exo_constancia")), 18), sag = new JTextField(p == null ? "" : Db.str(p.get("exo_registro_sag")), 18);
        ZonaCampo zona = new ZonaCampo(p == null || p.get("id_zona") == null ? null : Db.ent(p.get("id_zona")));
        JCheckBox activo = new JCheckBox("Proveedor activo", p == null || Db.ent(p.get("activo")) == 1);
        JCheckBox exento = new JCheckBox("Proveedor exento / exonerado de ISV", p != null && Db.ent(p.get("es_exento")) == 1);
        Runnable alternarExo = () -> { constancia.setEnabled(exento.isSelected()); sag.setEnabled(exento.isSelected()); };
        exento.addActionListener(e -> alternarExo.run());
        alternarExo.run();

        Campos.Formulario general = new Campos.Formulario();
        general.agregar("Código (vacío = automático):", codigo).agregar("Nombre / razón social:", nombre).agregar("RTN:", rtn).agregar("Persona de contacto:", contacto)
               .agregar("Teléfono:", tel).agregar("Celular / WhatsApp:", cel).agregar("Correo:", email).agregar("Dirección:", dir).agregar("Ciudad:", ciudad)
               .agregar("Zona:", zona).agregar("Notas:", notas).agregar("", activo);
        Campos.Formulario credito = new Campos.Formulario();
        credito.agregar("Límite de crédito que nos da (L):", limite).agregar("Días de crédito:", dias)
               .nota("Límite 0 = sin límite. Al recibir una compra a crédito que lo pase, el sistema pide la autorización de un supervisor. Con 0 días la compra es de contado.");
        if (p != null) {
            BigDecimal saldo = Db.dec(Db.scalar("SELECT COALESCE(SUM(saldo),0) FROM cuentas_por_pagar WHERE id_proveedor = ? AND estado = 'PENDIENTE'", id));
            credito.nota("Saldo que se le debe actualmente: " + Ui.lempiras(saldo));
        }
        Campos.Formulario exo = new Campos.Formulario();
        exo.agregar("", exento).agregar("N.º de constancia de registro exonerado:", constancia).agregar("N.º identificativo del registro de la SAG:", sag)
           .nota("Se guarda como dato de la ficha y sale en los reportes de proveedores.");
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", general);
        tabs.addTab("Crédito", credito);
        tabs.addTab("Exoneración", exo);

        double limiteAntes = p == null ? 0 : Db.dec(p.get("limite_credito")).doubleValue();
        boolean exentoAntes = p != null && Db.ent(p.get("es_exento")) == 1;
        return Campos.dialogo(padre, id == null ? "Nuevo proveedor" : "Ficha del proveedor", tabs, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre del proveedor.");
            String r = rtn.getText().trim();
            if (!r.isEmpty() && !r.matches("\\d{14}")) throw new IllegalArgumentException("El RTN debe tener 14 dígitos.");
            if (!dias.getText().trim().matches("\\d{1,3}")) throw new IllegalArgumentException("Los días de crédito deben ser un número (0 a 999).");
            BigDecimal lim = Ui.leerDinero(limite.getText(), "límite de crédito");
            if (lim.signum() < 0) throw new IllegalArgumentException("El límite no puede ser negativo.");
            if (exento.isSelected() && (constancia.getText().isBlank() || sag.getText().isBlank()))
                throw new IllegalArgumentException("Para un proveedor exonerado escriba el N.º de constancia y el N.º de registro de la SAG.");
            String cod = codigo.getText().isBlank() ? null : codigo.getText().trim();
            if (cod != null && Db.scalar("SELECT 1 FROM proveedores WHERE codigo = ? AND id_proveedor <> ?", cod, id == null ? 0 : id) != null)
                throw new IllegalArgumentException("Ese código ya lo tiene otro proveedor.");
            if (lim.doubleValue() != limiteAntes && (id != null || lim.signum() > 0)
                    && !Autorizacion.ejecutar(padre, "clientes.limite", "cambiar el límite de crédito", () -> { })) return false;
            if (exento.isSelected() != exentoAntes && !Autorizacion.ejecutar(padre, "clientes.exento", "cambiar la exoneración de ISV de un proveedor", () -> { })) return false;
            Object[] v = {cod, nombre.getText().trim(), ClienteForm.nv(rtn), ClienteForm.nv(contacto), ClienteForm.nv(tel), ClienteForm.nv(cel), ClienteForm.nv(email), ClienteForm.nv(dir),
                    ClienteForm.nv(ciudad), zona.id(), ClienteForm.nv(notas), lim, Integer.parseInt(dias.getText().trim()), exento.isSelected() ? 1 : 0,
                    exento.isSelected() ? ClienteForm.nv(constancia) : null, exento.isSelected() ? ClienteForm.nv(sag) : null, activo.isSelected() ? 1 : 0};
            long idGuardado;
            if (id == null) {
                idGuardado = Db.insert("INSERT INTO proveedores (codigo, nombre, rtn, contacto, telefono, celular, email, direccion, ciudad, id_zona, notas, limite_credito, dias_credito, "
                        + "es_exento, exo_constancia, exo_registro_sag, activo) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", v);
                if (cod == null) Db.exec("UPDATE proveedores SET codigo = ? WHERE id_proveedor = ?", String.format("P%05d", idGuardado), idGuardado);
            } else {
                idGuardado = id;
                Object[] w = new Object[v.length + 1];
                System.arraycopy(v, 0, w, 0, v.length);
                w[v.length] = id;
                Db.exec("UPDATE proveedores SET codigo=?, nombre=?, rtn=?, contacto=?, telefono=?, celular=?, email=?, direccion=?, ciudad=?, id_zona=?, notas=?, limite_credito=?, dias_credito=?, "
                        + "es_exento=?, exo_constancia=?, exo_registro_sag=?, activo=? WHERE id_proveedor=?", w);
            }
            Bitacora.registrar(id == null ? "CREAR" : "EDITAR", "proveedor", idGuardado, nombre.getText().trim() + " límite " + lim);
            return true;
        });
    }
}
