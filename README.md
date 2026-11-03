# Sistema Hotel y Restaurante (versión 2)

Reconstrucción y ampliación del sistema de hotel de 2014: ahora une **control hotelero** y **control de restaurante**
en un solo programa (Java 17 + Swing + MySQL 8).

## Qué hace

**Hotel**
- Plano de habitaciones por piso. En *modo diseño* (administrador) se crean, mueven (arrastrando), cambian de tamaño y se eliminan habitaciones; también tipos de habitación, precios y pisos.
- Reservas con disponibilidad real por fechas (no permite empalmes), anticipo, reservas a cargo de una empresa.
- Check-in con reserva o directo, extensión de estadía, consumos a la cuenta, check-out con factura.
- Alarmas automáticas: llegadas de hoy/mañana, salidas del día o atrasadas, reservas que no llegaron, créditos vencidos, stock bajo, CAI por vencer o por agotarse, y recordatorios propios.

**Restaurante**
- Salones y mesas creados por el usuario (modo diseño: una mesa, varias a la vez, redondas/cuadradas, arrastrar).
- Comandas por mesa, para llevar o a la habitación; envío a cocina/barra; pantalla de cocina.
- **Cargo a habitación**: el total pasa a la cuenta del huésped y se factura en su check-out.
- Menú por categorías, **recetas** con ingredientes y merma, costo y margen de cada plato.
- Inventario de insumos con kardex; cada venta descuenta del inventario según la receta; compras a proveedores con costo promedio; ajustes y mermas.

**Facturación (Honduras)**
- Numeración con CAI, rango y fecha límite; número correlativo sin saltos.
- ISV, tasa turística sobre hospedaje, descuento de adulto mayor por edad, exoneración con constancia, importe exento/exonerado.
- Contado (efectivo/tarjeta/transferencia, pago mixto) y **crédito** (clientes con límite, cuentas por cobrar, abonos, antigüedad de saldos).
- Facturas no editables ni borrables: se anulan con motivo y se pueden re-emitir corregidas. Libro de ventas exportable a Excel.
- Bitácora de auditoría de todo lo importante. Usuarios con roles (ADMIN, RECEPCION, CAJERO, MESERO, COCINA, BODEGA, AMA_LLAVES, CONTADOR).

## Puesta en marcha

1. **Base de datos** (Docker Desktop encendido). En Terminal:
   ```
   docker run --name mysql8 -e MYSQL_ROOT_PASSWORD=root -p 3307:3306 -d mysql:8.0
   ```
   Espere ~30 segundos y cree el esquema (desde la carpeta `BaseDeDatos`):
   ```
   docker exec -i mysql8 mysql -uroot -proot < 01_schema_hotel_v2.sql
   ```
2. *(Opcional)* Datos de ejemplo del restaurante: `docker exec -i mysql8 mysql -uroot -proot < 03_datos_demo_restaurante.sql`
3. *(Opcional)* Traer los datos viejos de 2014 (con el contenedor `mysql57` encendido):
   ```
   docker exec mysql57 mysqldump -uroot -proot --databases datoshotel | docker exec -i mysql8 mysql -uroot -proot
   docker exec -i mysql8 mysql -uroot -proot < 02_migracion_datoshotel.sql
   ```
4. **Programa**: en IntelliJ, *File > Open* y elija la carpeta `SistemaHotel2` (donde está `pom.xml`); acepte *Load Maven Project*. Ejecute `hn.hotel.app.Main`.
   La primera vez se crea `hotel.properties` (conexión a `localhost:3307`, usuario `hotel_app`).
5. En el primer inicio el sistema pide crear el **administrador**. Luego:
   Configuración (datos del hotel y RTN) → CAI y libro de ventas (registrar el CAI) → Habitaciones (modo diseño) → Menú y recetas → Inventario.

## Importante antes de usarlo en un negocio real
- Porcentajes (ISV 15 %, tasa turística 4 %, descuentos de 25 %/35 %), a qué aplica el descuento y el formato del número de factura son **parámetros** puestos según lo investigado; deben validarse con un contador o con el SAR. Se cambian en *Configuración*.
- Los precios del menú se guardan **sin ISV** (el impuesto se suma al facturar).
- Cambie la clave de `hotel_app` (en el script SQL y en `hotel.properties`) fuera de su computadora.
- La facturación electrónica del SAR no está integrada: el sistema emite facturas con CAI tradicional e imprime desde el diálogo de impresión (en macOS: *PDF > Guardar como PDF*).
- Haga respaldos: `docker exec mysql8 mysqldump -uroot -proot hotel_sistema > respaldo.sql`.
