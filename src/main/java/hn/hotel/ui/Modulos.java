package hn.hotel.ui;

import javax.swing.*;
import java.util.List;
import java.util.function.Supplier;

/** Lista de pantallas del sistema. Para agregar una pantalla nueva, se agrega una línea aquí. */
public final class Modulos {
    private Modulos() {}

    public record Modulo(String id, String titulo, String grupo, Supplier<JPanel> fabrica) {}

    public static List<Modulo> todos() {
        return List.of(
                new Modulo("inicio", "Inicio", "GENERAL", InicioPanel::new),

                new Modulo("plano", "Habitaciones (plano)", "HOTEL", PlanoHabitacionesPanel::new),
                new Modulo("reservas", "Reservas", "HOTEL", ReservasPanel::new),
                new Modulo("estancias", "Check-in / Check-out", "HOTEL", EstanciasPanel::new),
                new Modulo("huespedes", "Huéspedes", "HOTEL", HuespedesPanel::new),

                new Modulo("pos_rest", "Punto de venta", "RESTAURANTE", PosRestaurantePanel::new),
                new Modulo("cocina", "Cocina y barra", "RESTAURANTE", CocinaPanel::new),
                new Modulo("restaurante", "Plano de mesas", "RESTAURANTE", RestaurantePanel::new),
                new Modulo("pos_retail", "Caja de tienda", "TIENDA", PosRetailPanel::new),
                new Modulo("menu", hn.hotel.app.Sesion.restauranteActivo ? "Menú y recetas" : "Productos y recetas", hn.hotel.app.Sesion.restauranteActivo ? "RESTAURANTE" : "TIENDA", MenuPanel::new),
                new Modulo("autoconsumo", "Autoconsumos", "RESTAURANTE", AutoconsumoPanel::new),
                new Modulo("estaciones", "Estaciones de comanda", "RESTAURANTE", EstacionesPanel::new),

                new Modulo("inventario", "Inventario", "INVENTARIO", InventarioPanel::new),
                new Modulo("manufactura", "Manufactura (recetas)", "INVENTARIO", ManufacturaPanel::new),
                new Modulo("compras", "Compras y proveedores", "INVENTARIO", ComprasPanel::new),

                new Modulo("facturacion", "Facturación", "FACTURACIÓN", FacturacionPanel::new),
                new Modulo("credito", "Crédito y cobros", "FACTURACIÓN", CreditoPanel::new),
                new Modulo("cortes", "Cortes de caja (X y Z)", "FACTURACIÓN", CortesPanel::new),
                new Modulo("vpos", "Pagos con tarjeta", "FACTURACIÓN", VposPanel::new),

                new Modulo("alarmas", "Alarmas", "CONTROL", AlarmasPanel::new),
                new Modulo("reportes", "Reportes", "CONTROL", ReportesPanel::new),
                new Modulo("impuestos", "Impuestos (ISV)", "CONTROL", ImpuestosPanel::new),
                new Modulo("fiscal", "CAI y libro de ventas", "CONTROL", FiscalPanel::new),
                new Modulo("config", "Configuración", "CONTROL", ConfigPanel::new),
                new Modulo("permisos", "Roles y permisos", "CONTROL", PermisosPanel::new),
                new Modulo("usuarios", "Usuarios", "CONTROL", UsuariosPanel::new)
        );
    }
}
