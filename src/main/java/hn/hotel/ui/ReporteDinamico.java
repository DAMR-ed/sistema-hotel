package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Visor de reportes estilo Odoo: período rápido, filtros dinámicos sobre cualquier columna, agrupar por cualquier columna,
 * búsqueda, totales, gráfico y exportación. Sirve para todos los reportes del catálogo.
 */
public final class ReporteDinamico extends JPanel {
    private record Filtro(int col, String op, String valor) {}

    private static final int TEXTO = 0, NUMERO = 1, FECHA = 2;
    /** Columnas numéricas que no tiene sentido sumar (porcentajes, días, horas, promedios, números de documento). */
    private static final Pattern NO_SUMAR = Pattern.compile("(?i)(%|días vencida|dias vencida|días del|hora|promedio|unit|costo/|n\\.º|\\bid\\b|disponible|ticket|noches)");
    private static final String[] PERIODOS = {"Hoy", "Ayer", "Esta semana", "Este mes", "Mes anterior", "Últimos 30 días", "Este año", "Año anterior", "Personalizado"};

    private ReporteDef def;
    private List<String> cols = List.of();
    private List<Object[]> filas = List.of();
    private int[] tipos = new int[0];
    private boolean[] sumable = new boolean[0];
    private boolean[] sumableVista = new boolean[0];
    private final List<Filtro> filtros = new ArrayList<>();

    private final JLabel lblTitulo = new JLabel(" ");
    private final JLabel lblDescripcion = new JLabel(" ");
    private final JComboBox<String> cmbPeriodo = new JComboBox<>(PERIODOS);
    private final JSpinner desde = Campos.fecha(LocalDate.now().withDayOfMonth(1)), hasta = Campos.fecha(LocalDate.now());
    private final JComboBox<AccionesHotel.Opcion> cmbCliente = new JComboBox<>(), cmbProveedor = new JComboBox<>();
    private final JLabel lblPeriodo = new JLabel("Período:"), lblDesde = new JLabel("Desde:"), lblHasta = new JLabel("hasta:"), lblCliente = new JLabel("Cliente:"), lblProv = new JLabel("Proveedor:");
    private final JButton btnActualizar = Ui.botonPrimario("Actualizar", this::consultar);
    private final JPanel chips = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
    private final JComboBox<String> cmbAgrupar = new JComboBox<>();
    private final JTextField buscar = new JTextField(16);
    private final DefaultTableModel modelo = new DefaultTableModel() {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable tabla = new JTable(modelo);
    private final JLabel lblTotales = new JLabel(" "), lblInfo = new JLabel(" ");
    private boolean cargando = false;

    private static final Comparator<Object> COMPARADOR = (a, b) -> {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        if (a instanceof Number && b instanceof Number) return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        return a.toString().compareToIgnoreCase(b.toString());
    };

    public ReporteDinamico() {
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
        lblTitulo.setFont(lblTitulo.getFont().deriveFont(Font.BOLD, 18f));
        lblTitulo.setForeground(Tema.TEXTO);
        lblDescripcion.setForeground(Tema.TEXTO_SUAVE);

        // fila 1: período y parámetros
        cmbPeriodo.setSelectedItem("Este mes");
        cmbPeriodo.addActionListener(e -> { if (!cargando) aplicarPeriodo(); });
        JPanel f1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        f1.add(lblPeriodo); f1.add(cmbPeriodo); f1.add(lblDesde); f1.add(desde); f1.add(lblHasta); f1.add(hasta);
        f1.add(lblCliente); f1.add(cmbCliente); f1.add(lblProv); f1.add(cmbProveedor); f1.add(btnActualizar);
        desde.addChangeListener(e -> { if (!cargando) cmbPeriodo.setSelectedItem("Personalizado"); });
        hasta.addChangeListener(e -> { if (!cargando) cmbPeriodo.setSelectedItem("Personalizado"); });

        // fila 2: filtros dinámicos, agrupar y buscar
        JButton btnFiltro = Ui.boton("+ Agregar filtro", this::agregarFiltro);
        cmbAgrupar.addActionListener(e -> { if (!cargando) aplicar(); });
        buscar.putClientProperty("JTextField.placeholderText", "Buscar en el reporte...");
        buscar.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { if (!cargando) aplicar(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { if (!cargando) aplicar(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { if (!cargando) aplicar(); }
        });
        JPanel f2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        f2.add(btnFiltro); f2.add(new JLabel("Agrupar por:")); f2.add(cmbAgrupar); f2.add(buscar);
        f2.add(Ui.boton("Gráfico", this::grafico));
        f2.add(Ui.boton("Exportar a Excel (CSV)", () -> Exportar.csv(this, modelo, "reporte_" + LocalDate.now())));

        chips.setOpaque(false);
        JPanel norte = new JPanel();
        norte.setLayout(new BoxLayout(norte, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{lblTitulo, lblDescripcion, f1, f2, chips}) { c.setAlignmentX(Component.LEFT_ALIGNMENT); norte.add(c); }
        add(norte, BorderLayout.NORTH);

        tabla.setRowHeight(30);
        tabla.setShowVerticalLines(false);
        tabla.setIntercellSpacing(new Dimension(0, 1));
        tabla.setFillsViewportHeight(true);
        tabla.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tabla.getTableHeader().setReorderingAllowed(true);
        tabla.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override protected void setValue(Object v) {
                if (v == null) { setText(""); setHorizontalAlignment(SwingConstants.LEFT); return; }
                Object t = Ui.formatear(v);
                setText(t.toString());
                setHorizontalAlignment(v instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
            }
        });
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        JPanel sur = new JPanel(new GridLayout(0, 1));
        lblTotales.setFont(lblTotales.getFont().deriveFont(Font.BOLD));
        sur.add(lblTotales); sur.add(lblInfo);
        add(sur, BorderLayout.SOUTH);
        cargarListas();
    }

    private void cargarListas() {
        cmbCliente.addItem(new AccionesHotel.Opcion(0, "(Todos)"));
        cmbProveedor.addItem(new AccionesHotel.Opcion(0, "(Todos)"));
        try {
            for (var c : Db.query("SELECT id_cliente_credito, nombre FROM clientes_credito ORDER BY nombre")) cmbCliente.addItem(new AccionesHotel.Opcion(Db.ent(c.get("id_cliente_credito")), Db.str(c.get("nombre"))));
            for (var p : Db.query("SELECT id_proveedor, nombre FROM proveedores ORDER BY nombre")) cmbProveedor.addItem(new AccionesHotel.Opcion(Db.ent(p.get("id_proveedor")), Db.str(p.get("nombre"))));
        } catch (RuntimeException e) { /* aún sin las tablas nuevas: quedan solo «(Todos)» */ }
    }

    /** Abre un reporte. presets: "cliente" / "proveedor" con el id a seleccionar. */
    public void abrir(ReporteDef d, Map<String, Integer> presets) {
        this.def = d;
        cargando = true;
        lblTitulo.setText(d.nombre());
        lblDescripcion.setText(d.categoria());
        boolean per = d.usaPeriodo();
        for (JComponent c : new JComponent[]{lblPeriodo, cmbPeriodo, lblDesde, desde, lblHasta, hasta}) c.setVisible(per);
        lblCliente.setVisible(d.usaCliente()); cmbCliente.setVisible(d.usaCliente());
        lblProv.setVisible(d.usaProveedor()); cmbProveedor.setVisible(d.usaProveedor());
        cmbCliente.setSelectedIndex(0); cmbProveedor.setSelectedIndex(0);
        if (presets != null) {
            if (presets.get("cliente") != null) AccionesHotel.seleccionar(cmbCliente, presets.get("cliente"));
            if (presets.get("proveedor") != null) AccionesHotel.seleccionar(cmbProveedor, presets.get("proveedor"));
        }
        filtros.clear();
        buscar.setText("");
        // los estados de cuenta se ven completos por defecto (todo el año)
        cmbPeriodo.setSelectedItem(d.nombre().startsWith("Estado de cuenta") ? "Este año" : "Este mes");
        cargando = false;
        aplicarPeriodo();   // fija las fechas y consulta
    }

    private void aplicarPeriodo() {
        String p = (String) cmbPeriodo.getSelectedItem();
        LocalDate hoy = LocalDate.now(), d = hoy, h = hoy;
        switch (p) {
            case "Ayer" -> { d = hoy.minusDays(1); h = d; }
            case "Esta semana" -> d = hoy.with(DayOfWeek.MONDAY);
            case "Este mes" -> d = hoy.withDayOfMonth(1);
            case "Mes anterior" -> { d = hoy.minusMonths(1).withDayOfMonth(1); h = d.withDayOfMonth(d.lengthOfMonth()); }
            case "Últimos 30 días" -> d = hoy.minusDays(29);
            case "Este año" -> d = hoy.withDayOfYear(1);
            case "Año anterior" -> { d = hoy.minusYears(1).withDayOfYear(1); h = d.withDayOfYear(d.lengthOfYear()); }
            case "Personalizado" -> { return; }
            default -> { }
        }
        cargando = true;
        Campos.poner(desde, d);
        Campos.poner(hasta, h);
        cargando = false;
        if (def != null && !cargando) consultar();
    }

    private void consultar() {
        if (def == null) return;
        LocalDate d = Campos.valor(desde), h = Campos.valor(hasta);
        if (h.isBefore(d)) { Ui.error(this, "La fecha final es anterior a la inicial."); return; }
        Integer cli = cmbCliente.isVisible() && AccionesHotel.idDe(cmbCliente) > 0 ? AccionesHotel.idDe(cmbCliente) : null;
        Integer prov = cmbProveedor.isVisible() && AccionesHotel.idDe(cmbProveedor) > 0 ? AccionesHotel.idDe(cmbProveedor) : null;
        ReporteDef r = def;
        lblInfo.setText("Consultando...");
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> {
            tx.exec("SET @d = ?, @h = ?, @cliente = ?, @proveedor = ?", d, h, cli, prov);
            return tx.query(r.sql());
        }), datos -> {
            if (def != r) return;   // el usuario ya cambió de reporte
            cols = datos.isEmpty() ? List.of() : new ArrayList<>(datos.get(0).keySet());
            int limite = 50_000;
            List<Object[]> f = new ArrayList<>();
            for (Map<String, Object> m : datos) {
                if (f.size() >= limite) break;
                Object[] fila = new Object[cols.size()];
                for (int i = 0; i < fila.length; i++) fila[i] = m.get(cols.get(i));
                f.add(fila);
            }
            filas = f;
            tipos = new int[cols.size()];
            sumable = new boolean[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                Object v = null;
                for (Object[] fila : filas) if (fila[i] != null) { v = fila[i]; break; }
                tipos[i] = v instanceof Number ? NUMERO : (v instanceof java.sql.Date || v instanceof java.sql.Timestamp || v instanceof LocalDate || v instanceof java.time.LocalDateTime) ? FECHA : TEXTO;
                sumable[i] = tipos[i] == NUMERO && !NO_SUMAR.matcher(cols.get(i)).find();
            }
            cargando = true;
            cmbAgrupar.removeAllItems();
            cmbAgrupar.addItem("(sin agrupar)");
            for (int i = 0; i < cols.size(); i++) if (tipos[i] != NUMERO) cmbAgrupar.addItem(cols.get(i));
            cargando = false;
            filtros.removeIf(x -> x.col() >= cols.size());
            repintarChips();
            aplicar();
            lblInfo.setText(datos.size() > limite ? "Se muestran las primeras " + limite + " filas de " + datos.size() + ". Use los filtros para acotar." : " ");
        });
    }

    // ------------------------------------------------------------------ filtros
    private void agregarFiltro() {
        if (cols.isEmpty()) { Ui.info(this, "El reporte no tiene datos para filtrar."); return; }
        JComboBox<String> cmbCol = new JComboBox<>(cols.toArray(new String[0]));
        JComboBox<String> cmbOp = new JComboBox<>();
        JComboBox<String> cmbValor = new JComboBox<>();
        cmbValor.setEditable(true);
        Runnable alCambiarColumna = () -> {
            int c = cmbCol.getSelectedIndex();
            cmbOp.removeAllItems();
            String[] ops = tipos[c] == NUMERO ? new String[]{"=", "≠", ">", "≥", "<", "≤"}
                    : tipos[c] == FECHA ? new String[]{"es igual a", "desde", "hasta"}
                    : new String[]{"contiene", "no contiene", "es igual a", "no es igual a", "empieza con"};
            for (String o : ops) cmbOp.addItem(o);
            cmbValor.removeAllItems();
            if (tipos[c] == TEXTO) {
                TreeSet<String> distintos = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (Object[] f : filas) { if (f[c] != null && distintos.size() < 300) distintos.add(f[c].toString()); }
                for (String s : distintos) cmbValor.addItem(s);
            }
            cmbValor.setSelectedItem("");
        };
        cmbCol.addActionListener(e -> alCambiarColumna.run());
        alCambiarColumna.run();
        Campos.Formulario fm = new Campos.Formulario().agregar("Columna:", cmbCol).agregar("Condición:", cmbOp).agregar("Valor:", cmbValor)
                .nota("Las fechas se escriben dd/mm/aaaa. Puede agregar varios filtros; todos se aplican juntos.");
        Campos.dialogo(this, "Agregar filtro", fm, () -> {
            int c = cmbCol.getSelectedIndex();
            String v = cmbValor.getEditor().getItem() == null ? "" : cmbValor.getEditor().getItem().toString().trim();
            if (v.isEmpty()) throw new IllegalArgumentException("Escriba el valor del filtro.");
            if (tipos[c] == NUMERO) Ui.leerDinero(v, cols.get(c));
            if (tipos[c] == FECHA) v = Ui.leerFecha(v, cols.get(c)).toString();
            filtros.add(new Filtro(c, (String) cmbOp.getSelectedItem(), v));
            repintarChips();
            aplicar();
            return true;
        });
    }

    private void repintarChips() {
        chips.removeAll();
        for (Filtro f : new ArrayList<>(filtros)) {
            JButton b = new JButton(cols.get(f.col()) + " " + f.op() + " «" + f.valor() + "»  ✕");
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.setToolTipText("Clic para quitar este filtro");
            b.addActionListener(e -> { filtros.remove(f); repintarChips(); aplicar(); });
            chips.add(b);
        }
        chips.revalidate();
        chips.repaint();
    }

    private boolean cumple(Object[] fila, Filtro f) {
        Object v = fila[f.col()];
        int t = tipos[f.col()];
        if (t == NUMERO) {
            if (v == null) return false;
            int c = new BigDecimal(v.toString()).compareTo(new BigDecimal(f.valor()));
            return switch (f.op()) { case "=" -> c == 0; case "≠" -> c != 0; case ">" -> c > 0; case "≥" -> c >= 0; case "<" -> c < 0; default -> c <= 0; };
        }
        String s = v == null ? "" : v.toString();
        if (t == FECHA) {
            if (s.length() >= 10) s = s.substring(0, 10);
            int c = s.compareTo(f.valor());
            return switch (f.op()) { case "desde" -> c >= 0; case "hasta" -> c <= 0; default -> c == 0; };
        }
        String a = s.toLowerCase(), b = f.valor().toLowerCase();
        return switch (f.op()) {
            case "contiene" -> a.contains(b);
            case "no contiene" -> !a.contains(b);
            case "es igual a" -> a.equals(b);
            case "no es igual a" -> !a.equals(b);
            default -> a.startsWith(b);
        };
    }

    // ------------------------------------------------------------------ tabla (con o sin agrupar)
    private void aplicar() {
        String q = buscar.getText().trim().toLowerCase();
        List<Object[]> vis = new ArrayList<>();
        for (Object[] f : filas) {
            boolean ok = true;
            for (Filtro x : filtros) if (!cumple(f, x)) { ok = false; break; }
            if (ok && !q.isEmpty()) {
                boolean hay = false;
                for (Object v : f) if (v != null && Ui.formatear(v).toString().toLowerCase().contains(q)) { hay = true; break; }
                ok = hay;
            }
            if (ok) vis.add(f);
        }
        String grupo = cmbAgrupar.getSelectedIndex() <= 0 ? null : (String) cmbAgrupar.getSelectedItem();
        List<String> nombres = new ArrayList<>();
        List<Object[]> datos = new ArrayList<>();
        if (grupo == null) {
            nombres.addAll(cols);
            datos = vis;
            sumableVista = sumable;
        } else {
            int g = cols.indexOf(grupo);
            nombres.add(grupo);
            nombres.add("Registros");
            List<Integer> ms = new ArrayList<>();
            for (int i = 0; i < cols.size(); i++) if (sumable[i]) { ms.add(i); nombres.add(cols.get(i)); }
            Map<String, Object[]> acum = new LinkedHashMap<>();
            for (Object[] f : vis) {
                String k = f[g] == null ? "(vacío)" : Ui.formatear(f[g]).toString();
                Object[] a = acum.computeIfAbsent(k, kk -> {
                    Object[] r = new Object[2 + ms.size()];
                    r[0] = kk; r[1] = 0L;
                    for (int i = 0; i < ms.size(); i++) r[2 + i] = BigDecimal.ZERO;
                    return r;
                });
                a[1] = (Long) a[1] + 1;
                for (int i = 0; i < ms.size(); i++) {
                    Object v = f[ms.get(i)];
                    if (v != null) a[2 + i] = ((BigDecimal) a[2 + i]).add(new BigDecimal(v.toString()));
                }
            }
            datos = new ArrayList<>(acum.values());
            if (!ms.isEmpty()) datos.sort((x, y) -> ((BigDecimal) y[2]).compareTo((BigDecimal) x[2]));
            sumableVista = new boolean[nombres.size()];
            for (int i = 1; i < nombres.size(); i++) sumableVista[i] = true;
        }
        modelo.setDataVector(datos.toArray(new Object[0][]), nombres.toArray());
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(modelo);
        for (int i = 0; i < nombres.size(); i++) sorter.setComparator(i, COMPARADOR);
        tabla.setRowSorter(sorter);
        for (int i = 0; i < tabla.getColumnCount(); i++)
            tabla.getColumnModel().getColumn(i).setPreferredWidth(Math.max(90, Math.min(300, nombres.get(i).length() * 10 + 40)));
        mostrarTotales(datos, nombres);
        lblInfo.setText(lblInfo.getText().startsWith("Se muestran") ? lblInfo.getText()
                : datos.isEmpty() ? "Sin datos para ese período y esos filtros." : datos.size() + " fila(s)" + (grupo != null ? " (agrupadas por " + grupo + ")" : "") + ".");
    }

    private void mostrarTotales(List<Object[]> datos, List<String> nombres) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nombres.size(); i++) {
            if (i >= sumableVista.length || !sumableVista[i]) continue;
            BigDecimal s = BigDecimal.ZERO;
            for (Object[] f : datos) if (f[i] != null) s = s.add(new BigDecimal(f[i].toString()));
            if (sb.length() > 0) sb.append("   |   ");
            sb.append(nombres.get(i)).append(": ").append(nombres.get(i).equals("Registros") ? String.valueOf(s.longValue()) : Ui.numero(s));
        }
        lblTotales.setText(sb.length() == 0 ? " " : "Totales:  " + sb);
    }

    // ------------------------------------------------------------------ gráfico
    private void grafico() {
        if (modelo.getRowCount() == 0) { Ui.info(this, "No hay datos para graficar."); return; }
        List<String> medidas = new ArrayList<>();
        for (int i = 0; i < modelo.getColumnCount(); i++) if (i < sumableVista.length && sumableVista[i]) medidas.add(modelo.getColumnName(i));
        if (medidas.isEmpty()) { Ui.info(this, "Este reporte no tiene columnas de importes para graficar."); return; }
        int etiqueta = 0;
        for (int i = 0; i < modelo.getColumnCount(); i++) if (!(i < sumableVista.length && sumableVista[i])) { etiqueta = i; break; }
        final int colEtiqueta = etiqueta;
        JComboBox<String> cmbMedida = new JComboBox<>(medidas.toArray(new String[0]));
        JComboBox<String> cmbTipo = new JComboBox<>(new String[]{"Barras horizontales", "Barras verticales", "Dona"});
        Graficos.Barras barrasH = new Graficos.Barras(true, v -> String.format("%,.2f", v), Graficos.PALETA[0]);
        Graficos.Barras barrasV = new Graficos.Barras(false, v -> String.format("%,.2f", v), Graficos.PALETA[0]);
        Graficos.Dona dona = new Graficos.Dona(v -> String.format("%,.2f", v));
        JPanel area = new JPanel(new CardLayout());
        area.add(barrasH, "H"); area.add(barrasV, "V"); area.add(dona, "D");
        Runnable pintar = () -> {
            int m = -1;
            String nm = (String) cmbMedida.getSelectedItem();
            for (int i = 0; i < modelo.getColumnCount(); i++) if (modelo.getColumnName(i).equals(nm)) m = i;
            if (m < 0) return;
            Map<String, Double> acum = new LinkedHashMap<>();
            for (int r = 0; r < modelo.getRowCount(); r++) {
                Object e = modelo.getValueAt(r, colEtiqueta), v = modelo.getValueAt(r, m);
                String k = e == null ? "(vacío)" : Ui.formatear(e).toString();
                acum.merge(k, v == null ? 0.0 : new BigDecimal(v.toString()).doubleValue(), Double::sum);
            }
            List<Map.Entry<String, Double>> orden = new ArrayList<>(acum.entrySet());
            orden.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
            if (orden.size() > 12) orden = orden.subList(0, 12);
            List<String> et = new ArrayList<>(); List<Double> val = new ArrayList<>();
            for (var x : orden) { et.add(x.getKey()); val.add(x.getValue()); }
            barrasH.datos(et, val); barrasV.datos(et, val); dona.datos(et, val);
            String t = (String) cmbTipo.getSelectedItem();
            ((CardLayout) area.getLayout()).show(area, t.equals("Dona") ? "D" : t.endsWith("verticales") ? "V" : "H");
        };
        cmbMedida.addActionListener(e -> pintar.run());
        cmbTipo.addActionListener(e -> pintar.run());
        pintar.run();
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(Ui.fila(new JLabel("Medida:"), cmbMedida, new JLabel("Tipo:"), cmbTipo, new JLabel("(los 12 mayores de «" + modelo.getColumnName(colEtiqueta) + "»)")), BorderLayout.NORTH);
        area.setPreferredSize(new Dimension(760, 420));
        p.add(area, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, p, def.nombre(), JOptionPane.PLAIN_MESSAGE);
    }
}
