package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDate;
import java.util.Map;

/** Alta y edición de huéspedes. Devuelve el id guardado, o -1 si se canceló. */
public final class HuespedDialog {
    private HuespedDialog() {}

    public static int mostrar(Component padre, Integer idHuesped) {
        Map<String, Object> h = idHuesped == null ? null : Db.uno("SELECT * FROM huespedes WHERE id_huesped = ?", idHuesped);

        JComboBox<String> tipo = new JComboBox<>(new String[]{"IDENTIDAD", "PASAPORTE", "RESIDENCIA", "OTRO"});
        JTextField numero = new JTextField(20), nombre = new JTextField(24), rtn = new JTextField(14),
                nac = new JTextField(10), tel = new JTextField(14), email = new JTextField(24), proc = new JTextField(20);
        JCheckBox residente = new JCheckBox("Residente en Honduras", true);
        if (h != null) {
            tipo.setSelectedItem(Db.str(h.get("tipo_documento")));
            numero.setText(Db.str(h.get("numero_documento")));
            nombre.setText(Db.str(h.get("nombre")));
            rtn.setText(Db.str(h.get("rtn")));
            nac.setText(h.get("fecha_nacimiento") == null ? "" : Ui.texto(Ui.fecha(h.get("fecha_nacimiento"))));
            tel.setText(Db.str(h.get("telefono")));
            email.setText(Db.str(h.get("email")));
            proc.setText(Db.str(h.get("procedencia")));
            residente.setSelected(Db.ent(h.get("es_residente")) == 1);
        }

        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Tipo de documento:", tipo).agregar("Número de documento:", numero)
         .agregar("Nombre completo:", nombre).agregar("RTN (14 dígitos, opcional):", rtn)
         .agregar("Fecha de nacimiento (dd/mm/aaaa):", nac).agregar("Teléfono:", tel)
         .agregar("Correo:", email).agregar("Procedencia:", proc).agregar("", residente)
         .nota("La fecha de nacimiento sirve para aplicar el descuento de adulto mayor.");

        int[] resultado = {-1};
        boolean ok = Campos.dialogo(padre, h == null ? "Nuevo huésped" : "Editar huésped", f, () -> {
            if (numero.getText().isBlank() || nombre.getText().isBlank())
                throw new IllegalArgumentException("El número de documento y el nombre son obligatorios.");
            String r = rtn.getText().trim();
            if (!r.isEmpty() && !r.matches("\\d{14}")) throw new IllegalArgumentException("El RTN debe tener 14 dígitos (solo números).");
            LocalDate fn = nac.getText().isBlank() ? null : Ui.leerFecha(nac.getText(), "nacimiento");
            if (fn != null && (fn.isAfter(LocalDate.now()) || fn.isBefore(LocalDate.now().minusYears(120))))
                throw new IllegalArgumentException("La fecha de nacimiento no es razonable.");
            Object[] p = {tipo.getSelectedItem(), numero.getText().trim(), nombre.getText().trim(), r.isEmpty() ? null : r, fn,
                    residente.isSelected() ? 1 : 0, vacio(tel), vacio(email), vacio(proc)};
            if (h == null) {
                resultado[0] = (int) Db.insert("INSERT INTO huespedes (tipo_documento, numero_documento, nombre, rtn, fecha_nacimiento, "
                        + "es_residente, telefono, email, procedencia) VALUES (?,?,?,?,?,?,?,?,?)", p);
                Bitacora.registrar("CREAR", "huesped", resultado[0], nombre.getText().trim());
            } else {
                Db.exec("UPDATE huespedes SET tipo_documento=?, numero_documento=?, nombre=?, rtn=?, fecha_nacimiento=?, "
                        + "es_residente=?, telefono=?, email=?, procedencia=? WHERE id_huesped=?",
                        p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], idHuesped);
                resultado[0] = idHuesped;
                Bitacora.registrar("EDITAR", "huesped", idHuesped, nombre.getText().trim());
            }
            return true;
        });
        return ok ? resultado[0] : -1;
    }

    private static String vacio(JTextField t) { return t.getText().isBlank() ? null : t.getText().trim(); }
}
