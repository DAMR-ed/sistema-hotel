-- =====================================================================
-- DATOS DE DEMOSTRACIÓN del restaurante (opcional, solo para probar)
-- Ejecutar DESPUÉS de 01_schema_hotel_v2.sql:
--   docker exec -i mysql8 mysql -uroot -proot < 03_datos_demo_restaurante.sql
-- Precios, costos y recetas son inventados para probar el sistema.
-- Cantidades en unidad base: G (gramos), ML (mililitros), UND.
-- =====================================================================
SET NAMES utf8mb4;
USE hotel_sistema;

INSERT INTO proveedores (nombre, contacto, telefono) VALUES
 ('Distribuidora de Alimentos (demo)', 'Contacto demo', '9999-0001'),
 ('Bebidas y Licores (demo)',          'Contacto demo', '9999-0002');

INSERT INTO insumos (nombre, unidad_base, stock_minimo, costo_promedio, id_proveedor) VALUES
 ('Pechuga de pollo',   'G',   2000, 0.1200, 1),
 ('Carne de res',       'G',   2000, 0.2000, 1),
 ('Arroz',              'G',   3000, 0.0300, 1),
 ('Frijoles fritos',    'G',   2000, 0.0400, 1),
 ('Huevo',              'UND',   30, 4.0000, 1),
 ('Tortilla',           'UND',   50, 1.0000, 1),
 ('Plátano maduro',     'G',   1500, 0.0300, 1),
 ('Queso fresco',       'G',   1000, 0.1000, 1),
 ('Café molido',        'G',    500, 0.2500, 1),
 ('Leche',              'ML',  3000, 0.0300, 1),
 ('Gaseosa lata',       'UND',   24, 12.0000, 2),
 ('Cerveza botella',    'UND',   24, 22.0000, 2),
 ('Agua embotellada',   'UND',   24, 6.0000, 2),
 ('Jugo de naranja',    'ML',  3000, 0.0400, 1);

INSERT INTO productos_menu (id_categoria, nombre, precio, area_preparacion, tratamiento_fiscal)
SELECT c.id_categoria, x.nombre, x.precio, x.area, 'GRAVADO'
FROM (
  SELECT 'Desayunos' cat, 'Desayuno típico' nombre, 120.00 precio, 'COCINA' area UNION ALL
  SELECT 'Platos fuertes', 'Pollo a la plancha', 180.00, 'COCINA' UNION ALL
  SELECT 'Platos fuertes', 'Churrasco', 260.00, 'COCINA' UNION ALL
  SELECT 'Bebidas sin alcohol', 'Café', 35.00, 'BARRA' UNION ALL
  SELECT 'Bebidas sin alcohol', 'Gaseosa', 40.00, 'BARRA' UNION ALL
  SELECT 'Bebidas sin alcohol', 'Agua embotellada', 30.00, 'BARRA' UNION ALL
  SELECT 'Bebidas sin alcohol', 'Jugo de naranja', 55.00, 'BARRA' UNION ALL
  SELECT 'Cervezas y licores', 'Cerveza nacional', 65.00, 'BARRA'
) x JOIN categorias_menu c ON c.nombre = x.cat;

INSERT INTO receta_detalle (id_producto, id_insumo, cantidad, merma_pct)
SELECT p.id_producto, i.id_insumo, r.cant, r.merma
FROM (
  SELECT 'Desayuno típico' prod, 'Huevo' ins, 2 cant, 0 merma UNION ALL
  SELECT 'Desayuno típico', 'Frijoles fritos', 100, 0 UNION ALL
  SELECT 'Desayuno típico', 'Queso fresco', 50, 0 UNION ALL
  SELECT 'Desayuno típico', 'Plátano maduro', 80, 5 UNION ALL
  SELECT 'Desayuno típico', 'Tortilla', 2, 0 UNION ALL
  SELECT 'Pollo a la plancha', 'Pechuga de pollo', 250, 8 UNION ALL
  SELECT 'Pollo a la plancha', 'Arroz', 150, 0 UNION ALL
  SELECT 'Churrasco', 'Carne de res', 300, 10 UNION ALL
  SELECT 'Churrasco', 'Arroz', 150, 0 UNION ALL
  SELECT 'Café', 'Café molido', 15, 0 UNION ALL
  SELECT 'Café', 'Leche', 60, 0 UNION ALL
  SELECT 'Gaseosa', 'Gaseosa lata', 1, 0 UNION ALL
  SELECT 'Agua embotellada', 'Agua embotellada', 1, 0 UNION ALL
  SELECT 'Jugo de naranja', 'Jugo de naranja', 300, 0 UNION ALL
  SELECT 'Cerveza nacional', 'Cerveza botella', 1, 0
) r
JOIN productos_menu p ON p.nombre = r.prod
JOIN insumos i ON i.nombre = r.ins;

-- Existencias iniciales (con kardex), con su registro en el kardex
INSERT INTO usuarios (username, nombre, password_hash, rol)
VALUES ('sistema', 'Usuario del sistema', 'NO_LOGIN', 'ADMIN')
ON DUPLICATE KEY UPDATE nombre = nombre;
SET @u = (SELECT id_usuario FROM usuarios WHERE username = 'sistema');
UPDATE usuarios SET activo = 0 WHERE id_usuario = @u;   -- no puede iniciar sesión

INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, costo_unitario, referencia, id_usuario, notas)
SELECT i.id_insumo, 'INVENTARIO_INICIAL', e.cant, i.costo_promedio, 'Existencia inicial', @u, 'Demo'
FROM (
  SELECT 'Pechuga de pollo' ins, 10000 cant UNION ALL
  SELECT 'Carne de res' ins, 10000 cant UNION ALL
  SELECT 'Arroz' ins, 20000 cant UNION ALL
  SELECT 'Frijoles fritos' ins, 10000 cant UNION ALL
  SELECT 'Huevo' ins, 120 cant UNION ALL
  SELECT 'Tortilla' ins, 200 cant UNION ALL
  SELECT 'Plátano maduro' ins, 8000 cant UNION ALL
  SELECT 'Queso fresco' ins, 5000 cant UNION ALL
  SELECT 'Café molido' ins, 2000 cant UNION ALL
  SELECT 'Leche' ins, 10000 cant UNION ALL
  SELECT 'Gaseosa lata' ins, 48 cant UNION ALL
  SELECT 'Cerveza botella' ins, 48 cant UNION ALL
  SELECT 'Agua embotellada' ins, 48 cant UNION ALL
  SELECT 'Jugo de naranja' ins, 12000 cant
) e JOIN insumos i ON i.nombre = e.ins;

UPDATE insumos i
  JOIN (SELECT id_insumo, SUM(cantidad) AS c FROM movimientos_inventario
         WHERE tipo = 'INVENTARIO_INICIAL' GROUP BY id_insumo) m ON m.id_insumo = i.id_insumo
   SET i.stock_actual = m.c;

-- Mesas de ejemplo (en el sistema se crean y mueven desde el editor)
INSERT INTO mesas (id_area, nombre, capacidad, forma, pos_x, pos_y)
SELECT a.id_area, CONCAT('Mesa ', n.i), 4, 'CUADRADA', ((n.i - 1) MOD 4) * 4, ((n.i - 1) DIV 4) * 4
FROM areas_restaurante a
JOIN (SELECT 1 i UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8) n
WHERE a.nombre = 'Salón principal';

INSERT INTO clientes_credito (nombre, limite_credito, dias_credito)
VALUES ('Empresa de ejemplo S. de R.L.', 50000.00, 30);

SELECT 'Demo cargada' AS resultado;
SELECT * FROM v_stock_insumos;
