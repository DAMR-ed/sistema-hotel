-- SISTEMA HOTEL Y RESTAURANTE - INSTALACION COMPLETA DE LA BASE DE DATOS (01 + 04 a 15)
-- ATENCION: borra y recrea hotel_sistema. Usar solo en una instalacion nueva.

-- ================= 01_schema_hotel_v2.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- SISTEMA HOTEL + RESTAURANTE - Esquema v2 (MySQL 8.0)
-- Hotel, restaurante (mesas dinámicas, recetas, inventario), crédito/contado,
-- alarmas y módulo fiscal para Honduras (SAR)
--
-- Ejecutar:
--   docker exec -i mysql8 mysql -uroot -proot < 01_schema_hotel_v2.sql
--
-- IMPORTANTE: la sección 0 BORRA las tablas si ya existen. Úsala solo
-- mientras desarrollas; con datos reales, quita esa sección.
--
-- Los valores fiscales (ISV, tasa turística, descuentos de adulto mayor,
-- código de tipo de documento) son PARÁMETROS que deben validarse con un
-- contador o con el SAR antes de usar el sistema en un hotel real.
-- =====================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS hotel_sistema
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE hotel_sistema;

-- ---------------------------------------------------------------------
-- 0. Limpieza (solo desarrollo)
-- ---------------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 0;
DROP VIEW IF EXISTS v_habitaciones;
DROP VIEW IF EXISTS v_libro_ventas;
DROP VIEW IF EXISTS v_alertas;
DROP VIEW IF EXISTS v_stock_insumos;
DROP VIEW IF EXISTS v_cuentas_por_cobrar;
DROP VIEW IF EXISTS v_ocupacion_hoy;
DROP VIEW IF EXISTS v_comandas_total;
DROP PROCEDURE IF EXISTS sp_descontar_inventario_comanda;
DROP PROCEDURE IF EXISTS sp_cargar_comanda_a_habitacion;
DROP PROCEDURE IF EXISTS sp_anular_detalle_comanda;
DROP PROCEDURE IF EXISTS sp_recibir_compra;
DROP PROCEDURE IF EXISTS sp_ajustar_inventario;
DROP PROCEDURE IF EXISTS sp_siguiente_numero_factura;
DROP TABLE IF EXISTS recordatorios, abonos, cuentas_por_cobrar, movimientos_inventario,
  receta_detalle, compra_detalle, compras, insumos, proveedores,
  comanda_detalle, comandas, productos_menu, categorias_menu, mesas, areas_restaurante,
  pagos, factura_detalle, facturas, cai_autorizaciones, clientes_credito,
  consumos, servicios, estancias, reservas, habitaciones, pisos, tipos_habitacion,
  huespedes, bitacora, usuarios, configuracion;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- 1. Configuración y seguridad
-- ---------------------------------------------------------------------
CREATE TABLE configuracion (
  clave          VARCHAR(50)  NOT NULL,
  valor          VARCHAR(255) NOT NULL,
  descripcion    VARCHAR(255) NULL,
  actualizado_en TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (clave)
) ENGINE=InnoDB;

CREATE TABLE usuarios (
  id_usuario        INT UNSIGNED NOT NULL AUTO_INCREMENT,
  username          VARCHAR(45)  NOT NULL,
  nombre            VARCHAR(80)  NOT NULL,
  password_hash     VARCHAR(100) NOT NULL,            -- BCrypt, nunca texto plano
  rol               ENUM('ADMIN','RECEPCION','AMA_LLAVES','CAJERO','MESERO','COCINA','BODEGA','CONTADOR')
                    NOT NULL DEFAULT 'RECEPCION',
  activo            TINYINT(1)   NOT NULL DEFAULT 1,  -- no se borran usuarios, se desactivan
  intentos_fallidos TINYINT UNSIGNED NOT NULL DEFAULT 0,
  bloqueado_hasta   DATETIME     NULL,
  ultimo_acceso     DATETIME     NULL,
  creado_en         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id_usuario),
  UNIQUE KEY uk_usuarios_username (username)
) ENGINE=InnoDB;

CREATE TABLE bitacora (
  id_bitacora BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  fecha       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_usuario  INT UNSIGNED NULL,
  accion      VARCHAR(40)  NOT NULL,   -- LOGIN, CREAR, EDITAR, ANULAR, CHECKIN...
  entidad     VARCHAR(40)  NOT NULL,   -- factura, reserva, huesped...
  id_entidad  VARCHAR(40)  NULL,
  detalle     VARCHAR(500) NULL,
  PRIMARY KEY (id_bitacora),
  KEY idx_bitacora_fecha (fecha),
  KEY idx_bitacora_entidad (entidad, id_entidad),
  CONSTRAINT fk_bitacora_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 2. Huéspedes
-- ---------------------------------------------------------------------
CREATE TABLE huespedes (
  id_huesped       INT UNSIGNED NOT NULL AUTO_INCREMENT,
  tipo_documento   ENUM('IDENTIDAD','PASAPORTE','RESIDENCIA','OTRO') NOT NULL DEFAULT 'IDENTIDAD',
  numero_documento VARCHAR(25)  NOT NULL,
  nombre           VARCHAR(100) NOT NULL,
  rtn              VARCHAR(14)  NULL,                -- 14 dígitos, opcional
  fecha_nacimiento DATE         NULL,                -- para calcular tercera/cuarta edad
  es_residente     TINYINT(1)   NOT NULL DEFAULT 1,  -- extranjeros no residentes: 0
  telefono         VARCHAR(20)  NULL,
  email            VARCHAR(100) NULL,
  procedencia      VARCHAR(60)  NULL,
  fecha_registro   DATE         NOT NULL DEFAULT (CURRENT_DATE),
  activo           TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id_huesped),
  UNIQUE KEY uk_huespedes_documento (tipo_documento, numero_documento),
  KEY idx_huespedes_nombre (nombre),
  CONSTRAINT ck_huespedes_rtn CHECK (rtn IS NULL OR rtn REGEXP '^[0-9]{14}$')
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 3. Habitaciones
-- ---------------------------------------------------------------------
CREATE TABLE tipos_habitacion (
  id_tipo      INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  nombre       VARCHAR(50)   NOT NULL,
  descripcion  VARCHAR(200)  NULL,
  precio_noche DECIMAL(10,2) NOT NULL,
  capacidad    TINYINT UNSIGNED NOT NULL,
  color        CHAR(7)       NOT NULL DEFAULT '#CCCCCC',
  activo       TINYINT(1)    NOT NULL DEFAULT 1,
  PRIMARY KEY (id_tipo),
  UNIQUE KEY uk_tipos_nombre (nombre),
  CONSTRAINT ck_tipos_precio    CHECK (precio_noche >= 0),
  CONSTRAINT ck_tipos_capacidad CHECK (capacidad BETWEEN 1 AND 20),
  CONSTRAINT ck_tipos_color     CHECK (color REGEXP '^#[0-9A-Fa-f]{6}$')
) ENGINE=InnoDB;

-- Plano del hotel: cada piso es un lienzo (cuadrícula) donde se acomodan
-- las habitaciones. Unidades = celdas de la cuadrícula.
CREATE TABLE pisos (
  id_piso  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre   VARCHAR(40)  NOT NULL,
  orden    TINYINT UNSIGNED NOT NULL DEFAULT 1,
  columnas SMALLINT UNSIGNED NOT NULL DEFAULT 20,
  filas    SMALLINT UNSIGNED NOT NULL DEFAULT 12,
  activo   TINYINT(1)    NOT NULL DEFAULT 1,
  PRIMARY KEY (id_piso),
  UNIQUE KEY uk_pisos_nombre (nombre)
) ENGINE=InnoDB;

CREATE TABLE habitaciones (
  id_habitacion   INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  numero          SMALLINT UNSIGNED NOT NULL,
  id_tipo         INT UNSIGNED  NOT NULL,
  id_piso         INT UNSIGNED  NOT NULL DEFAULT 1,
  pos_x           SMALLINT UNSIGNED NOT NULL DEFAULT 0,   -- posición en el plano
  pos_y           SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  ancho           TINYINT UNSIGNED NOT NULL DEFAULT 2,
  alto            TINYINT UNSIGNED NOT NULL DEFAULT 2,
  estado          ENUM('LIBRE','OCUPADA','RESERVADA','LIMPIEZA','REPARACION')
                  NOT NULL DEFAULT 'LIBRE',
  precio_especial DECIMAL(10,2) NULL,   -- si es NULL se usa el precio del tipo
  descripcion     VARCHAR(200)  NULL,
  activa          TINYINT(1)    NOT NULL DEFAULT 1,
  PRIMARY KEY (id_habitacion),
  UNIQUE KEY uk_habitaciones_numero (numero),
  KEY idx_habitaciones_tipo (id_tipo),
  KEY idx_habitaciones_estado (estado),
  CONSTRAINT ck_habitaciones_precio CHECK (precio_especial IS NULL OR precio_especial >= 0),
  KEY idx_habitaciones_piso (id_piso),
  CONSTRAINT fk_habitaciones_tipo FOREIGN KEY (id_tipo) REFERENCES tipos_habitacion (id_tipo),
  CONSTRAINT fk_habitaciones_piso FOREIGN KEY (id_piso) REFERENCES pisos (id_piso)
) ENGINE=InnoDB;

-- Clientes con crédito (empresas, agencias, instituciones)
CREATE TABLE clientes_credito (
  id_cliente_credito INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  nombre             VARCHAR(100)  NOT NULL,
  rtn                VARCHAR(14)   NULL,
  contacto           VARCHAR(80)   NULL,
  telefono           VARCHAR(20)   NULL,
  email              VARCHAR(100)  NULL,
  direccion          VARCHAR(200)  NULL,
  limite_credito     DECIMAL(12,2) NOT NULL DEFAULT 0,
  dias_credito       SMALLINT UNSIGNED NOT NULL DEFAULT 30,
  activo             TINYINT(1)    NOT NULL DEFAULT 1,
  PRIMARY KEY (id_cliente_credito),
  UNIQUE KEY uk_credito_nombre (nombre),
  CONSTRAINT ck_credito_limite CHECK (limite_credito >= 0),
  CONSTRAINT ck_credito_rtn CHECK (rtn IS NULL OR rtn REGEXP '^[0-9]{14}$')
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 4. Reservas y estadías
-- ---------------------------------------------------------------------
CREATE TABLE reservas (
  id_reserva    INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_huesped    INT UNSIGNED NOT NULL,
  id_habitacion INT UNSIGNED NOT NULL,
  fecha_reserva DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  fecha_llegada DATE         NOT NULL,
  fecha_salida  DATE         NOT NULL,
  noches        SMALLINT     GENERATED ALWAYS AS (DATEDIFF(fecha_salida, fecha_llegada)) STORED,
  num_personas  TINYINT UNSIGNED NOT NULL DEFAULT 1,
  recargo       DECIMAL(10,2) NOT NULL DEFAULT 0,
  anticipo      DECIMAL(10,2) NOT NULL DEFAULT 0,
  id_cliente_credito INT UNSIGNED NULL,   -- reserva a cargo de una empresa
  estado        ENUM('PENDIENTE','CONFIRMADA','CANCELADA','EN_CASA','FINALIZADA','NO_SHOW')
                NOT NULL DEFAULT 'PENDIENTE',
  observaciones VARCHAR(255) NULL,
  id_usuario    INT UNSIGNED NULL,
  PRIMARY KEY (id_reserva),
  KEY idx_reservas_habitacion_fechas (id_habitacion, fecha_llegada, fecha_salida),
  KEY idx_reservas_huesped (id_huesped),
  KEY idx_reservas_estado (estado),
  CONSTRAINT ck_reservas_fechas    CHECK (fecha_salida > fecha_llegada),
  CONSTRAINT ck_reservas_personas  CHECK (num_personas >= 1),
  CONSTRAINT ck_reservas_importes  CHECK (recargo >= 0 AND anticipo >= 0),
  CONSTRAINT fk_reservas_huesped    FOREIGN KEY (id_huesped)    REFERENCES huespedes (id_huesped),
  CONSTRAINT fk_reservas_habitacion FOREIGN KEY (id_habitacion) REFERENCES habitaciones (id_habitacion),
  CONSTRAINT fk_reservas_usuario    FOREIGN KEY (id_usuario)    REFERENCES usuarios (id_usuario),
  CONSTRAINT fk_reservas_credito    FOREIGN KEY (id_cliente_credito) REFERENCES clientes_credito (id_cliente_credito)
) ENGINE=InnoDB;

CREATE TABLE estancias (
  id_estancia     INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_reserva      INT UNSIGNED NULL,              -- NULL = huésped sin reserva (walk-in)
  id_huesped      INT UNSIGNED NOT NULL,
  id_habitacion   INT UNSIGNED NOT NULL,
  checkin_real    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  salida_prevista DATE         NOT NULL,
  checkout_real   DATETIME     NULL,
  precio_noche    DECIMAL(10,2) NOT NULL,         -- precio pactado en el check-in
  num_personas    TINYINT UNSIGNED NOT NULL DEFAULT 1,
  estado          ENUM('ACTIVA','CERRADA') NOT NULL DEFAULT 'ACTIVA',
  id_usuario      INT UNSIGNED NULL,
  PRIMARY KEY (id_estancia),
  KEY idx_estancias_habitacion (id_habitacion, estado),
  KEY idx_estancias_huesped (id_huesped),
  CONSTRAINT fk_estancias_reserva    FOREIGN KEY (id_reserva)    REFERENCES reservas (id_reserva),
  CONSTRAINT fk_estancias_huesped    FOREIGN KEY (id_huesped)    REFERENCES huespedes (id_huesped),
  CONSTRAINT fk_estancias_habitacion FOREIGN KEY (id_habitacion) REFERENCES habitaciones (id_habitacion),
  CONSTRAINT fk_estancias_usuario    FOREIGN KEY (id_usuario)    REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 5. Servicios y consumos
-- ---------------------------------------------------------------------
CREATE TABLE servicios (
  id_servicio        INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  nombre             VARCHAR(60)   NOT NULL,
  precio             DECIMAL(10,2) NOT NULL,
  tratamiento_fiscal ENUM('GRAVADO','EXENTO') NOT NULL DEFAULT 'GRAVADO',
  activo             TINYINT(1)    NOT NULL DEFAULT 1,
  PRIMARY KEY (id_servicio),
  UNIQUE KEY uk_servicios_nombre (nombre),
  CONSTRAINT ck_servicios_precio CHECK (precio >= 0)
) ENGINE=InnoDB;

CREATE TABLE consumos (
  id_consumo         INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_estancia        INT UNSIGNED  NOT NULL,
  id_servicio        INT UNSIGNED  NULL,
  descripcion        VARCHAR(100)  NOT NULL,
  cantidad           DECIMAL(10,2) NOT NULL DEFAULT 1,
  precio_unitario    DECIMAL(10,2) NOT NULL,
  tratamiento_fiscal ENUM('GRAVADO','EXENTO') NOT NULL DEFAULT 'GRAVADO',
  fecha              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  facturado          TINYINT(1)    NOT NULL DEFAULT 0,
  id_comanda         INT UNSIGNED  NULL,   -- cargo del restaurante a la habitación
  id_usuario         INT UNSIGNED  NULL,
  PRIMARY KEY (id_consumo),
  KEY idx_consumos_estancia (id_estancia),
  CONSTRAINT ck_consumos_cantidad CHECK (cantidad > 0),
  CONSTRAINT ck_consumos_precio   CHECK (precio_unitario >= 0),
  CONSTRAINT fk_consumos_estancia FOREIGN KEY (id_estancia) REFERENCES estancias (id_estancia),
  CONSTRAINT fk_consumos_servicio FOREIGN KEY (id_servicio) REFERENCES servicios (id_servicio),
  CONSTRAINT fk_consumos_usuario  FOREIGN KEY (id_usuario)  REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 6. Módulo fiscal: CAI, facturas, pagos
-- ---------------------------------------------------------------------
-- Cada fila es una autorización del SAR (CAI) con su rango y fecha límite.
-- Número de factura: establecimiento-punto de emisión-tipo de documento-correlativo
-- (3-3-2-8 dígitos). tipo_documento '01' = factura (confirmar códigos con el SAR).
CREATE TABLE cai_autorizaciones (
  id_cai                INT UNSIGNED NOT NULL AUTO_INCREMENT,
  cai                   VARCHAR(50)  NOT NULL,
  tipo_documento        CHAR(2)      NOT NULL DEFAULT '01',
  establecimiento       CHAR(3)      NOT NULL DEFAULT '000',
  punto_emision         CHAR(3)      NOT NULL DEFAULT '001',
  rango_inicial         INT UNSIGNED NOT NULL,
  rango_final           INT UNSIGNED NOT NULL,
  siguiente_correlativo INT UNSIGNED NOT NULL,   -- próximo número a emitir
  fecha_limite_emision  DATE         NOT NULL,
  activo                TINYINT(1)   NOT NULL DEFAULT 1,
  creado_en             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id_cai),
  UNIQUE KEY uk_cai (cai),
  CONSTRAINT ck_cai_rango     CHECK (rango_final >= rango_inicial),
  CONSTRAINT ck_cai_siguiente CHECK (siguiente_correlativo >= rango_inicial
                                     AND siguiente_correlativo <= rango_final + 1)
) ENGINE=InnoDB;

-- Los importes gravados ya vienen NETOS de descuento.
CREATE TABLE facturas (
  id_factura             INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_cai                 INT UNSIGNED  NOT NULL,
  numero_factura         CHAR(19)      NOT NULL,   -- 000-001-01-00000001
  fecha_emision          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_estancia            INT UNSIGNED  NULL,
  id_huesped             INT UNSIGNED  NULL,
  cliente_nombre         VARCHAR(100)  NOT NULL,   -- copia fiscal, no cambia si el huésped cambia
  cliente_rtn            VARCHAR(14)   NULL,
  importe_gravado_15     DECIMAL(12,2) NOT NULL DEFAULT 0,
  importe_gravado_18     DECIMAL(12,2) NOT NULL DEFAULT 0,
  importe_exento         DECIMAL(12,2) NOT NULL DEFAULT 0,
  importe_exonerado      DECIMAL(12,2) NOT NULL DEFAULT 0,
  descuento_total        DECIMAL(12,2) NOT NULL DEFAULT 0,
  isv_15                 DECIMAL(12,2) NOT NULL DEFAULT 0,
  isv_18                 DECIMAL(12,2) NOT NULL DEFAULT 0,
  tasa_turistica         DECIMAL(12,2) NOT NULL DEFAULT 0,
  total                  DECIMAL(12,2) NOT NULL,
  constancia_exoneracion VARCHAR(40)   NULL,
  origen                 ENUM('HOSPEDAJE','RESTAURANTE','MIXTA') NOT NULL DEFAULT 'HOSPEDAJE',
  condicion_pago         ENUM('CONTADO','CREDITO') NOT NULL DEFAULT 'CONTADO',
  id_cliente_credito     INT UNSIGNED  NULL,
  fecha_vencimiento      DATE          NULL,   -- solo crédito
  estado                 ENUM('EMITIDA','ANULADA') NOT NULL DEFAULT 'EMITIDA',
  motivo_anulacion       VARCHAR(255)  NULL,
  anulada_en             DATETIME      NULL,
  id_usuario             INT UNSIGNED  NULL,
  id_usuario_anula       INT UNSIGNED  NULL,
  PRIMARY KEY (id_factura),
  UNIQUE KEY uk_facturas_numero (numero_factura),
  KEY idx_facturas_fecha (fecha_emision),
  KEY idx_facturas_huesped (id_huesped),
  CONSTRAINT ck_facturas_total   CHECK (total >= 0),
  CONSTRAINT ck_facturas_credito CHECK (condicion_pago = 'CONTADO' OR (id_cliente_credito IS NOT NULL AND fecha_vencimiento IS NOT NULL)),
  CONSTRAINT fk_facturas_cai     FOREIGN KEY (id_cai)       REFERENCES cai_autorizaciones (id_cai),
  CONSTRAINT fk_facturas_estancia FOREIGN KEY (id_estancia) REFERENCES estancias (id_estancia),
  CONSTRAINT fk_facturas_huesped FOREIGN KEY (id_huesped)   REFERENCES huespedes (id_huesped),
  CONSTRAINT fk_facturas_credito FOREIGN KEY (id_cliente_credito) REFERENCES clientes_credito (id_cliente_credito),
  CONSTRAINT fk_facturas_usuario FOREIGN KEY (id_usuario)   REFERENCES usuarios (id_usuario),
  CONSTRAINT fk_facturas_anula   FOREIGN KEY (id_usuario_anula) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

CREATE TABLE factura_detalle (
  id_detalle      INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_factura      INT UNSIGNED  NOT NULL,
  tipo            ENUM('HOSPEDAJE','SERVICIO','RECARGO','TASA_TURISTICA','ALIMENTOS','BEBIDAS') NOT NULL,
  descripcion     VARCHAR(150)  NOT NULL,
  cantidad        DECIMAL(10,2) NOT NULL,
  precio_unitario DECIMAL(10,2) NOT NULL,
  descuento       DECIMAL(10,2) NOT NULL DEFAULT 0,
  tratamiento     ENUM('GRAVADO','EXENTO','EXONERADO') NOT NULL DEFAULT 'GRAVADO',
  tasa_isv        DECIMAL(4,2)  NOT NULL DEFAULT 15.00,
  subtotal        DECIMAL(12,2) NOT NULL,
  isv             DECIMAL(12,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (id_detalle),
  KEY idx_detalle_factura (id_factura),
  CONSTRAINT fk_detalle_factura FOREIGN KEY (id_factura) REFERENCES facturas (id_factura)
) ENGINE=InnoDB;

CREATE TABLE pagos (
  id_pago    INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_factura INT UNSIGNED  NOT NULL,
  metodo     ENUM('EFECTIVO','TARJETA','TRANSFERENCIA','CHEQUE','OTRO') NOT NULL,   -- facturas al CONTADO
  monto      DECIMAL(12,2) NOT NULL,
  referencia VARCHAR(60)   NULL,
  fecha_pago DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_usuario INT UNSIGNED  NULL,
  PRIMARY KEY (id_pago),
  KEY idx_pagos_factura (id_factura),
  KEY idx_pagos_fecha (fecha_pago),
  CONSTRAINT ck_pagos_monto CHECK (monto > 0),
  CONSTRAINT fk_pagos_factura FOREIGN KEY (id_factura) REFERENCES facturas (id_factura),
  CONSTRAINT fk_pagos_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 6B. RESTAURANTE: áreas, mesas dinámicas, menú y comandas
-- ---------------------------------------------------------------------
-- Cada área (salón, terraza, bar...) es un lienzo con cuadrícula; las mesas
-- se crean, mueven y eliminan desde el sistema sin tocar la base de datos.
CREATE TABLE areas_restaurante (
  id_area  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre   VARCHAR(40)  NOT NULL,
  columnas SMALLINT UNSIGNED NOT NULL DEFAULT 20,
  filas    SMALLINT UNSIGNED NOT NULL DEFAULT 12,
  activa   TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id_area),
  UNIQUE KEY uk_areas_nombre (nombre)
) ENGINE=InnoDB;

CREATE TABLE mesas (
  id_mesa   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_area   INT UNSIGNED NOT NULL,
  nombre    VARCHAR(20)  NOT NULL,                  -- "Mesa 1", "Barra 3"...
  capacidad TINYINT UNSIGNED NOT NULL DEFAULT 4,
  forma     ENUM('CUADRADA','REDONDA','RECTANGULAR') NOT NULL DEFAULT 'CUADRADA',
  pos_x     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  pos_y     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  ancho     TINYINT UNSIGNED NOT NULL DEFAULT 2,
  alto      TINYINT UNSIGNED NOT NULL DEFAULT 2,
  estado    ENUM('LIBRE','OCUPADA','RESERVADA','INACTIVA') NOT NULL DEFAULT 'LIBRE',
  PRIMARY KEY (id_mesa),
  UNIQUE KEY uk_mesas_nombre (id_area, nombre),
  CONSTRAINT ck_mesas_capacidad CHECK (capacidad BETWEEN 1 AND 50),
  CONSTRAINT fk_mesas_area FOREIGN KEY (id_area) REFERENCES areas_restaurante (id_area)
) ENGINE=InnoDB;

CREATE TABLE categorias_menu (
  id_categoria INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre       VARCHAR(50)  NOT NULL,
  orden        TINYINT UNSIGNED NOT NULL DEFAULT 1,
  tipo         ENUM('ALIMENTOS','BEBIDAS') NOT NULL DEFAULT 'ALIMENTOS',  -- para la línea de la factura
  activa       TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id_categoria),
  UNIQUE KEY uk_categorias_nombre (nombre)
) ENGINE=InnoDB;

CREATE TABLE productos_menu (
  id_producto        INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_categoria       INT UNSIGNED  NOT NULL,
  nombre             VARCHAR(80)   NOT NULL,
  descripcion        VARCHAR(200)  NULL,
  precio             DECIMAL(10,2) NOT NULL,        -- precio de venta (antes de ISV)
  tratamiento_fiscal ENUM('GRAVADO','EXENTO') NOT NULL DEFAULT 'GRAVADO',
  area_preparacion   ENUM('COCINA','BARRA','NINGUNA') NOT NULL DEFAULT 'COCINA',
  activo             TINYINT(1)    NOT NULL DEFAULT 1,
  PRIMARY KEY (id_producto),
  UNIQUE KEY uk_productos_nombre (nombre),
  KEY idx_productos_categoria (id_categoria),
  CONSTRAINT ck_productos_precio CHECK (precio >= 0),
  CONSTRAINT fk_productos_categoria FOREIGN KEY (id_categoria) REFERENCES categorias_menu (id_categoria)
) ENGINE=InnoDB;

-- tipo SALON = en mesa; LLEVAR = para llevar; HABITACION = servicio a la habitación
CREATE TABLE comandas (
  id_comanda         INT UNSIGNED NOT NULL AUTO_INCREMENT,
  tipo               ENUM('SALON','LLEVAR','HABITACION') NOT NULL DEFAULT 'SALON',
  id_mesa            INT UNSIGNED NULL,
  id_estancia        INT UNSIGNED NULL,           -- se llena al cargar a una habitación
  num_personas       TINYINT UNSIGNED NOT NULL DEFAULT 1,
  abierta_en         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  cerrada_en         DATETIME     NULL,
  estado             ENUM('ABIERTA','CERRADA','CARGADA_HABITACION','FACTURADA','ANULADA')
                     NOT NULL DEFAULT 'ABIERTA',
  propina            DECIMAL(10,2) NOT NULL DEFAULT 0,   -- voluntaria, fuera de la factura
  id_factura         INT UNSIGNED NULL,
  observaciones      VARCHAR(255) NULL,
  motivo_anulacion   VARCHAR(255) NULL,
  id_usuario         INT UNSIGNED NULL,           -- mesero
  PRIMARY KEY (id_comanda),
  KEY idx_comandas_mesa (id_mesa, estado),
  KEY idx_comandas_estancia (id_estancia),
  KEY idx_comandas_fecha (abierta_en),
  CONSTRAINT ck_comandas_propina CHECK (propina >= 0),
  CONSTRAINT fk_comandas_mesa     FOREIGN KEY (id_mesa)     REFERENCES mesas (id_mesa),
  CONSTRAINT fk_comandas_estancia FOREIGN KEY (id_estancia) REFERENCES estancias (id_estancia),
  CONSTRAINT fk_comandas_factura  FOREIGN KEY (id_factura)  REFERENCES facturas (id_factura),
  CONSTRAINT fk_comandas_usuario  FOREIGN KEY (id_usuario)  REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

CREATE TABLE comanda_detalle (
  id_detalle           INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_comanda           INT UNSIGNED  NOT NULL,
  id_producto          INT UNSIGNED  NOT NULL,
  descripcion          VARCHAR(80)   NOT NULL,        -- copia del nombre al momento de la venta
  cantidad             DECIMAL(10,2) NOT NULL DEFAULT 1,
  precio_unitario      DECIMAL(10,2) NOT NULL,        -- copia del precio al momento de la venta
  descuento            DECIMAL(10,2) NOT NULL DEFAULT 0,
  tratamiento_fiscal   ENUM('GRAVADO','EXENTO') NOT NULL DEFAULT 'GRAVADO',
  tipo_linea           ENUM('ALIMENTOS','BEBIDAS') NOT NULL DEFAULT 'ALIMENTOS',
  notas                VARCHAR(150)  NULL,            -- "sin cebolla", "término medio"
  estado               ENUM('PENDIENTE','ENVIADO','LISTO','SERVIDO','ANULADO') NOT NULL DEFAULT 'PENDIENTE',
  inventario_descontado TINYINT(1)   NOT NULL DEFAULT 0,
  enviado_en           DATETIME      NULL,
  id_usuario           INT UNSIGNED  NULL,
  PRIMARY KEY (id_detalle),
  KEY idx_cdet_comanda (id_comanda),
  KEY idx_cdet_estado (estado),
  CONSTRAINT ck_cdet_cantidad CHECK (cantidad > 0),
  CONSTRAINT ck_cdet_precio   CHECK (precio_unitario >= 0 AND descuento >= 0),
  CONSTRAINT fk_cdet_comanda  FOREIGN KEY (id_comanda)  REFERENCES comandas (id_comanda),
  CONSTRAINT fk_cdet_producto FOREIGN KEY (id_producto) REFERENCES productos_menu (id_producto),
  CONSTRAINT fk_cdet_usuario  FOREIGN KEY (id_usuario)  REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

ALTER TABLE consumos
  ADD KEY idx_consumos_comanda (id_comanda),
  ADD CONSTRAINT fk_consumos_comanda FOREIGN KEY (id_comanda) REFERENCES comandas (id_comanda);

-- ---------------------------------------------------------------------
-- 6C. INVENTARIO del restaurante: proveedores, insumos, compras, recetas
--     Todas las cantidades de insumos se guardan en su UNIDAD BASE
--     (G = gramos, ML = mililitros, UND = unidades). La pantalla convierte
--     (lb, kg, litro, galón, caja...) antes de guardar.
-- ---------------------------------------------------------------------
CREATE TABLE proveedores (
  id_proveedor INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre       VARCHAR(100) NOT NULL,
  rtn          VARCHAR(14)  NULL,
  contacto     VARCHAR(80)  NULL,
  telefono     VARCHAR(20)  NULL,
  email        VARCHAR(100) NULL,
  direccion    VARCHAR(200) NULL,
  activo       TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id_proveedor),
  UNIQUE KEY uk_proveedores_nombre (nombre),
  CONSTRAINT ck_proveedores_rtn CHECK (rtn IS NULL OR rtn REGEXP '^[0-9]{14}$')
) ENGINE=InnoDB;

CREATE TABLE insumos (
  id_insumo      INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  nombre         VARCHAR(80)   NOT NULL,
  unidad_base    ENUM('G','ML','UND') NOT NULL,
  stock_actual   DECIMAL(14,3) NOT NULL DEFAULT 0,   -- puede quedar negativo: indica ventas sin compra registrada
  stock_minimo   DECIMAL(14,3) NOT NULL DEFAULT 0,
  costo_promedio DECIMAL(14,6) NOT NULL DEFAULT 0,   -- por unidad base
  id_proveedor   INT UNSIGNED  NULL,                 -- proveedor habitual
  activo         TINYINT(1)    NOT NULL DEFAULT 1,
  PRIMARY KEY (id_insumo),
  UNIQUE KEY uk_insumos_nombre (nombre),
  CONSTRAINT ck_insumos_minimo CHECK (stock_minimo >= 0),
  CONSTRAINT ck_insumos_costo  CHECK (costo_promedio >= 0),
  CONSTRAINT fk_insumos_proveedor FOREIGN KEY (id_proveedor) REFERENCES proveedores (id_proveedor)
) ENGINE=InnoDB;

CREATE TABLE compras (
  id_compra       INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_proveedor    INT UNSIGNED NOT NULL,
  fecha           DATE         NOT NULL DEFAULT (CURRENT_DATE),
  numero_documento VARCHAR(40) NULL,                -- factura del proveedor
  estado          ENUM('BORRADOR','RECIBIDA','ANULADA') NOT NULL DEFAULT 'BORRADOR',
  total           DECIMAL(14,2) NOT NULL DEFAULT 0,
  observaciones   VARCHAR(255) NULL,
  id_usuario      INT UNSIGNED NULL,
  PRIMARY KEY (id_compra),
  KEY idx_compras_proveedor (id_proveedor),
  KEY idx_compras_fecha (fecha),
  CONSTRAINT fk_compras_proveedor FOREIGN KEY (id_proveedor) REFERENCES proveedores (id_proveedor),
  CONSTRAINT fk_compras_usuario   FOREIGN KEY (id_usuario)   REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

CREATE TABLE compra_detalle (
  id_detalle     INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_compra      INT UNSIGNED  NOT NULL,
  id_insumo      INT UNSIGNED  NOT NULL,
  cantidad       DECIMAL(14,3) NOT NULL,            -- en unidad base
  costo_unitario DECIMAL(14,6) NOT NULL,            -- por unidad base
  PRIMARY KEY (id_detalle),
  UNIQUE KEY uk_compra_insumo (id_compra, id_insumo),
  CONSTRAINT ck_compra_det CHECK (cantidad > 0 AND costo_unitario >= 0),
  CONSTRAINT fk_compra_det_compra FOREIGN KEY (id_compra) REFERENCES compras (id_compra),
  CONSTRAINT fk_compra_det_insumo FOREIGN KEY (id_insumo) REFERENCES insumos (id_insumo)
) ENGINE=InnoDB;

-- Receta: ingredientes que consume UNA unidad vendida del producto.
-- Un producto sin preparación (una gaseosa de lata) tiene una receta de
-- 1 ingrediente: el insumo "Gaseosa lata", 1 UND.
CREATE TABLE receta_detalle (
  id_producto INT UNSIGNED  NOT NULL,
  id_insumo   INT UNSIGNED  NOT NULL,
  cantidad    DECIMAL(14,3) NOT NULL,               -- en unidad base del insumo
  merma_pct   DECIMAL(5,2)  NOT NULL DEFAULT 0,     -- desperdicio esperado (%)
  PRIMARY KEY (id_producto, id_insumo),
  CONSTRAINT ck_receta CHECK (cantidad > 0 AND merma_pct >= 0 AND merma_pct < 100),
  CONSTRAINT fk_receta_producto FOREIGN KEY (id_producto) REFERENCES productos_menu (id_producto),
  CONSTRAINT fk_receta_insumo   FOREIGN KEY (id_insumo)   REFERENCES insumos (id_insumo)
) ENGINE=InnoDB;

-- Kardex: cada cambio de stock deja una fila. cantidad > 0 entra, < 0 sale.
CREATE TABLE movimientos_inventario (
  id_movimiento      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  fecha              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_insumo          INT UNSIGNED  NOT NULL,
  tipo               ENUM('COMPRA','SALIDA_VENTA','DEVOLUCION','AJUSTE','MERMA','INVENTARIO_INICIAL') NOT NULL,
  cantidad           DECIMAL(14,3) NOT NULL,
  costo_unitario     DECIMAL(14,6) NOT NULL DEFAULT 0,
  referencia         VARCHAR(80)   NULL,
  id_detalle_comanda INT UNSIGNED  NULL,
  id_compra          INT UNSIGNED  NULL,
  id_usuario         INT UNSIGNED  NULL,
  notas              VARCHAR(200)  NULL,
  PRIMARY KEY (id_movimiento),
  KEY idx_mov_insumo (id_insumo, fecha),
  CONSTRAINT fk_mov_insumo  FOREIGN KEY (id_insumo)  REFERENCES insumos (id_insumo),
  CONSTRAINT fk_mov_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 6D. CRÉDITO: cuentas por cobrar y abonos
--     La fila de cuentas_por_cobrar se crea sola (trigger) al emitir una
--     factura a CREDITO. El saldo baja solo (trigger) con cada abono.
-- ---------------------------------------------------------------------
CREATE TABLE cuentas_por_cobrar (
  id_cxc             INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_factura         INT UNSIGNED  NOT NULL,
  id_cliente_credito INT UNSIGNED  NOT NULL,
  monto              DECIMAL(12,2) NOT NULL,
  saldo              DECIMAL(12,2) NOT NULL,
  fecha_emision      DATE          NOT NULL,
  fecha_vencimiento  DATE          NOT NULL,
  estado             ENUM('PENDIENTE','PARCIAL','PAGADA','ANULADA') NOT NULL DEFAULT 'PENDIENTE',
  PRIMARY KEY (id_cxc),
  UNIQUE KEY uk_cxc_factura (id_factura),
  KEY idx_cxc_cliente (id_cliente_credito, estado),
  KEY idx_cxc_vence (fecha_vencimiento, estado),
  CONSTRAINT ck_cxc_saldo CHECK (saldo >= 0 AND saldo <= monto),
  CONSTRAINT fk_cxc_factura FOREIGN KEY (id_factura) REFERENCES facturas (id_factura),
  CONSTRAINT fk_cxc_cliente FOREIGN KEY (id_cliente_credito) REFERENCES clientes_credito (id_cliente_credito)
) ENGINE=InnoDB;

CREATE TABLE abonos (
  id_abono   INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_cxc     INT UNSIGNED  NOT NULL,
  metodo     ENUM('EFECTIVO','TARJETA','TRANSFERENCIA','CHEQUE','OTRO') NOT NULL,
  monto      DECIMAL(12,2) NOT NULL,
  referencia VARCHAR(60)   NULL,
  fecha      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_usuario INT UNSIGNED  NULL,
  PRIMARY KEY (id_abono),
  KEY idx_abonos_cxc (id_cxc),
  CONSTRAINT ck_abonos_monto CHECK (monto > 0),
  CONSTRAINT fk_abonos_cxc FOREIGN KEY (id_cxc) REFERENCES cuentas_por_cobrar (id_cxc),
  CONSTRAINT fk_abonos_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 6E. ALARMAS manuales (las automáticas salen de la vista v_alertas)
-- ---------------------------------------------------------------------
CREATE TABLE recordatorios (
  id_recordatorio INT UNSIGNED NOT NULL AUTO_INCREMENT,
  fecha_hora      DATETIME     NOT NULL,
  mensaje         VARCHAR(200) NOT NULL,
  id_habitacion   INT UNSIGNED NULL,
  id_reserva      INT UNSIGNED NULL,
  estado          ENUM('PENDIENTE','ATENDIDA','DESCARTADA') NOT NULL DEFAULT 'PENDIENTE',
  id_usuario      INT UNSIGNED NULL,
  creado_en       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id_recordatorio),
  KEY idx_recordatorios (estado, fecha_hora),
  CONSTRAINT fk_recordatorios_hab FOREIGN KEY (id_habitacion) REFERENCES habitaciones (id_habitacion),
  CONSTRAINT fk_recordatorios_res FOREIGN KEY (id_reserva) REFERENCES reservas (id_reserva),
  CONSTRAINT fk_recordatorios_usr FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 7. Vistas
-- ---------------------------------------------------------------------
CREATE VIEW v_habitaciones AS
SELECT h.id_habitacion, h.numero, h.id_piso, p.nombre AS piso, h.pos_x, h.pos_y, h.ancho, h.alto, h.estado,
       t.id_tipo, t.nombre AS tipo, t.color, t.capacidad,
       COALESCE(h.precio_especial, t.precio_noche) AS precio_noche,
       h.descripcion, h.activa
FROM habitaciones h
JOIN tipos_habitacion t ON t.id_tipo = h.id_tipo
JOIN pisos p ON p.id_piso = h.id_piso;

CREATE VIEW v_libro_ventas AS
SELECT f.id_factura, f.numero_factura, DATE(f.fecha_emision) AS fecha,
       f.cliente_nombre, f.cliente_rtn, f.origen, f.condicion_pago,
       f.importe_gravado_15, f.importe_gravado_18, f.importe_exento,
       f.importe_exonerado, f.isv_15, f.isv_18, f.tasa_turistica,
       f.total, f.estado
FROM facturas f;

CREATE VIEW v_ocupacion_hoy AS
SELECT estado, COUNT(*) AS habitaciones
FROM habitaciones WHERE activa = 1 GROUP BY estado;

CREATE VIEW v_comandas_total AS
SELECT c.id_comanda,
       COALESCE(SUM(CASE WHEN d.estado <> 'ANULADO'
                         THEN d.cantidad * d.precio_unitario - d.descuento END), 0) AS subtotal
FROM comandas c
LEFT JOIN comanda_detalle d ON d.id_comanda = c.id_comanda
GROUP BY c.id_comanda;

CREATE VIEW v_stock_insumos AS
SELECT i.id_insumo, i.nombre, i.unidad_base, i.stock_actual, i.stock_minimo,
       i.costo_promedio, ROUND(GREATEST(i.stock_actual, 0) * i.costo_promedio, 2) AS valor_inventario,
       CASE WHEN i.stock_actual <= 0 THEN 'AGOTADO'
            WHEN i.stock_actual <= i.stock_minimo THEN 'BAJO'
            ELSE 'OK' END AS situacion,
       p.nombre AS proveedor
FROM insumos i
LEFT JOIN proveedores p ON p.id_proveedor = i.id_proveedor
WHERE i.activo = 1;

CREATE VIEW v_cuentas_por_cobrar AS
SELECT x.id_cxc, x.id_factura, f.numero_factura, c.id_cliente_credito, c.nombre AS cliente,
       x.monto, x.saldo, x.fecha_emision, x.fecha_vencimiento, x.estado,
       GREATEST(DATEDIFF(CURDATE(), x.fecha_vencimiento), 0) AS dias_vencido
FROM cuentas_por_cobrar x
JOIN facturas f ON f.id_factura = x.id_factura
JOIN clientes_credito c ON c.id_cliente_credito = x.id_cliente_credito;

-- Alarmas automáticas: la pantalla de alarmas solo lee esta vista.
CREATE VIEW v_alertas AS
SELECT 'LLEGADA' AS tipo,
       IF(r.fecha_llegada = CURDATE(), 'ALTA', 'MEDIA') AS severidad,
       CAST(r.fecha_llegada AS DATETIME) AS fecha,
       CONCAT('Llega ', hu.nombre, ' - hab. ', h.numero) AS mensaje,
       'reserva' AS entidad, CAST(r.id_reserva AS CHAR) AS id_entidad
FROM reservas r
JOIN huespedes hu ON hu.id_huesped = r.id_huesped
JOIN habitaciones h ON h.id_habitacion = r.id_habitacion
WHERE r.estado IN ('PENDIENTE','CONFIRMADA')
  AND r.fecha_llegada BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 1 DAY)
UNION ALL
SELECT 'RESERVA_VENCIDA', 'ALTA', CAST(r.fecha_llegada AS DATETIME),
       CONCAT('No llegó: ', hu.nombre, ' - hab. ', h.numero, ' (era el ', DATE_FORMAT(r.fecha_llegada, '%d/%m/%Y'), ')'),
       'reserva', CAST(r.id_reserva AS CHAR)
FROM reservas r
JOIN huespedes hu ON hu.id_huesped = r.id_huesped
JOIN habitaciones h ON h.id_habitacion = r.id_habitacion
WHERE r.estado IN ('PENDIENTE','CONFIRMADA') AND r.fecha_llegada < CURDATE()
UNION ALL
SELECT 'SALIDA', IF(e.salida_prevista < CURDATE(), 'ALTA', 'MEDIA'), CAST(e.salida_prevista AS DATETIME),
       CONCAT(IF(e.salida_prevista < CURDATE(), 'Salida ATRASADA: ', 'Sale hoy: '), hu.nombre, ' - hab. ', h.numero),
       'estancia', CAST(e.id_estancia AS CHAR)
FROM estancias e
JOIN huespedes hu ON hu.id_huesped = e.id_huesped
JOIN habitaciones h ON h.id_habitacion = e.id_habitacion
WHERE e.estado = 'ACTIVA' AND e.salida_prevista <= CURDATE()
UNION ALL
SELECT 'CREDITO_VENCIDO', 'ALTA', CAST(x.fecha_vencimiento AS DATETIME),
       CONCAT('Factura ', x.numero_factura, ' de ', x.cliente, ' vencida hace ', x.dias_vencido, ' días. Saldo L ', x.saldo),
       'cxc', CAST(x.id_cxc AS CHAR)
FROM v_cuentas_por_cobrar x
WHERE x.estado IN ('PENDIENTE','PARCIAL') AND x.fecha_vencimiento < CURDATE()
UNION ALL
SELECT 'STOCK_BAJO', IF(s.stock_actual <= 0, 'ALTA', 'MEDIA'), CAST(NOW() AS DATETIME),
       CONCAT(s.nombre, ': quedan ', s.stock_actual, ' ', s.unidad_base, ' (mínimo ', s.stock_minimo, ')'),
       'insumo', CAST(s.id_insumo AS CHAR)
FROM v_stock_insumos s
WHERE s.situacion IN ('AGOTADO','BAJO')
UNION ALL
SELECT 'CAI_POR_VENCER', IF(c.fecha_limite_emision <= DATE_ADD(CURDATE(), INTERVAL 7 DAY), 'ALTA', 'MEDIA'),
       CAST(c.fecha_limite_emision AS DATETIME),
       CONCAT('El CAI ', c.cai, ' vence el ', DATE_FORMAT(c.fecha_limite_emision, '%d/%m/%Y')),
       'cai', CAST(c.id_cai AS CHAR)
FROM cai_autorizaciones c
WHERE c.activo = 1 AND c.fecha_limite_emision <= DATE_ADD(CURDATE(), INTERVAL 30 DAY)
UNION ALL
SELECT 'CAI_RANGO_AGOTANDO', 'ALTA', CAST(NOW() AS DATETIME),
       CONCAT('Quedan ', (c.rango_final - c.siguiente_correlativo + 1), ' números en el CAI ', c.cai),
       'cai', CAST(c.id_cai AS CHAR)
FROM cai_autorizaciones c
WHERE c.activo = 1 AND (c.rango_final - c.siguiente_correlativo + 1) <= 50
UNION ALL
SELECT 'RECORDATORIO', 'MEDIA', rc.fecha_hora, rc.mensaje, 'recordatorio', CAST(rc.id_recordatorio AS CHAR)
FROM recordatorios rc
WHERE rc.estado = 'PENDIENTE' AND rc.fecha_hora <= DATE_ADD(NOW(), INTERVAL 1 DAY);

-- ---------------------------------------------------------------------
-- 8. Reglas en la base de datos (triggers y procedimiento)
-- ---------------------------------------------------------------------
DELIMITER $$

-- 8.1 Una habitación no puede tener dos reservas activas que se traslapen.
--     (La aplicación además debe bloquear la fila de la habitación dentro de
--     una transacción para evitar que dos recepcionistas reserven a la vez.)
CREATE TRIGGER trg_reservas_traslape_ins BEFORE INSERT ON reservas
FOR EACH ROW
BEGIN
  IF NEW.estado IN ('PENDIENTE','CONFIRMADA','EN_CASA') THEN
    IF EXISTS (SELECT 1 FROM reservas r
                WHERE r.id_habitacion = NEW.id_habitacion
                  AND r.estado IN ('PENDIENTE','CONFIRMADA','EN_CASA')
                  AND NEW.fecha_llegada < r.fecha_salida
                  AND NEW.fecha_salida  > r.fecha_llegada) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'La habitación ya está reservada en esas fechas';
    END IF;
  END IF;
END$$

CREATE TRIGGER trg_reservas_traslape_upd BEFORE UPDATE ON reservas
FOR EACH ROW
BEGIN
  IF NEW.estado IN ('PENDIENTE','CONFIRMADA','EN_CASA') THEN
    IF EXISTS (SELECT 1 FROM reservas r
                WHERE r.id_habitacion = NEW.id_habitacion
                  AND r.id_reserva <> NEW.id_reserva
                  AND r.estado IN ('PENDIENTE','CONFIRMADA','EN_CASA')
                  AND NEW.fecha_llegada < r.fecha_salida
                  AND NEW.fecha_salida  > r.fecha_llegada) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'La habitación ya está reservada en esas fechas';
    END IF;
  END IF;
END$$

-- 8.2 Las facturas emitidas no se borran ni se modifican; solo se anulan.
CREATE TRIGGER trg_facturas_no_delete BEFORE DELETE ON facturas
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Las facturas no se pueden eliminar; use la anulación';
END$$

CREATE TRIGGER trg_facturas_solo_anular BEFORE UPDATE ON facturas
FOR EACH ROW
BEGIN
  IF OLD.estado = 'ANULADA' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Una factura anulada no se puede modificar';
  END IF;

  IF NOT (NEW.id_factura <=> OLD.id_factura
      AND NEW.id_cai <=> OLD.id_cai
      AND NEW.numero_factura <=> OLD.numero_factura
      AND NEW.fecha_emision <=> OLD.fecha_emision
      AND NEW.id_estancia <=> OLD.id_estancia
      AND NEW.id_huesped <=> OLD.id_huesped
      AND NEW.cliente_nombre <=> OLD.cliente_nombre
      AND NEW.cliente_rtn <=> OLD.cliente_rtn
      AND NEW.importe_gravado_15 <=> OLD.importe_gravado_15
      AND NEW.importe_gravado_18 <=> OLD.importe_gravado_18
      AND NEW.importe_exento <=> OLD.importe_exento
      AND NEW.importe_exonerado <=> OLD.importe_exonerado
      AND NEW.descuento_total <=> OLD.descuento_total
      AND NEW.isv_15 <=> OLD.isv_15
      AND NEW.isv_18 <=> OLD.isv_18
      AND NEW.tasa_turistica <=> OLD.tasa_turistica
      AND NEW.total <=> OLD.total
      AND NEW.constancia_exoneracion <=> OLD.constancia_exoneracion
      AND NEW.origen <=> OLD.origen
      AND NEW.condicion_pago <=> OLD.condicion_pago
      AND NEW.id_cliente_credito <=> OLD.id_cliente_credito
      AND NEW.fecha_vencimiento <=> OLD.fecha_vencimiento) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Los datos fiscales de una factura emitida no se pueden modificar';
  END IF;

  IF NEW.estado = 'ANULADA' THEN
    IF NEW.motivo_anulacion IS NULL OR CHAR_LENGTH(TRIM(NEW.motivo_anulacion)) < 5 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Debe indicar el motivo de la anulación';
    END IF;
    SET NEW.anulada_en = IFNULL(NEW.anulada_en, NOW());
  END IF;
END$$

CREATE TRIGGER trg_detalle_no_update BEFORE UPDATE ON factura_detalle
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'El detalle de una factura no se puede modificar';
END$$

CREATE TRIGGER trg_detalle_no_delete BEFORE DELETE ON factura_detalle
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'El detalle de una factura no se puede eliminar';
END$$

-- 8.3 Numeración correlativa sin saltos ni duplicados.
--     Debe llamarse DENTRO de la misma transacción que inserta la factura:
--     si la inserción falla, el número no se consume.
--     Usa el CAI activo, vigente y con números disponibles.
CREATE PROCEDURE sp_siguiente_numero_factura (
  IN  p_tipo_documento CHAR(2),
  OUT p_id_cai         INT UNSIGNED,
  OUT p_numero         CHAR(19)
)
BEGIN
  DECLARE v_est CHAR(3);
  DECLARE v_pto CHAR(3);
  DECLARE v_sig INT UNSIGNED;

  SET p_id_cai = NULL;
  SET p_numero = NULL;

  SELECT id_cai, establecimiento, punto_emision, siguiente_correlativo
    INTO p_id_cai, v_est, v_pto, v_sig
    FROM cai_autorizaciones
   WHERE activo = 1
     AND tipo_documento = p_tipo_documento
     AND siguiente_correlativo <= rango_final
     AND fecha_limite_emision >= CURDATE()
   ORDER BY fecha_limite_emision, id_cai
   LIMIT 1
   FOR UPDATE;

  IF p_id_cai IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'No hay un CAI vigente con números disponibles';
  END IF;

  SET p_numero = CONCAT(v_est, '-', v_pto, '-', p_tipo_documento, '-', LPAD(v_sig, 8, '0'));

  UPDATE cai_autorizaciones
     SET siguiente_correlativo = siguiente_correlativo + 1
   WHERE id_cai = p_id_cai;
END$$

-- 8.4 CRÉDITO: límite, cuenta por cobrar automática, abonos ----------------
CREATE TRIGGER trg_facturas_credito_limite BEFORE INSERT ON facturas
FOR EACH ROW
BEGIN
  DECLARE v_limite DECIMAL(12,2);
  DECLARE v_pendiente DECIMAL(12,2);
  IF NEW.condicion_pago = 'CREDITO' THEN
    SET v_limite = NULL;
    SELECT limite_credito INTO v_limite
      FROM clientes_credito
     WHERE id_cliente_credito = NEW.id_cliente_credito AND activo = 1;
    IF v_limite IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Cliente de crédito inexistente o inactivo';
    END IF;
    SELECT COALESCE(SUM(saldo), 0) INTO v_pendiente
      FROM cuentas_por_cobrar
     WHERE id_cliente_credito = NEW.id_cliente_credito
       AND estado IN ('PENDIENTE','PARCIAL');
    IF v_pendiente + NEW.total > v_limite THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'La factura excede el límite de crédito del cliente';
    END IF;
  END IF;
END$$

CREATE TRIGGER trg_facturas_crear_cxc AFTER INSERT ON facturas
FOR EACH ROW
BEGIN
  IF NEW.condicion_pago = 'CREDITO' THEN
    INSERT INTO cuentas_por_cobrar
      (id_factura, id_cliente_credito, monto, saldo, fecha_emision, fecha_vencimiento)
    VALUES
      (NEW.id_factura, NEW.id_cliente_credito, NEW.total, NEW.total,
       DATE(NEW.fecha_emision), NEW.fecha_vencimiento);
  END IF;
END$$

-- Al anular una factura a crédito se anula su cuenta, salvo que ya tenga abonos.
CREATE TRIGGER trg_facturas_anular_cxc AFTER UPDATE ON facturas
FOR EACH ROW
BEGIN
  IF OLD.estado = 'EMITIDA' AND NEW.estado = 'ANULADA' AND NEW.condicion_pago = 'CREDITO' THEN
    IF EXISTS (SELECT 1 FROM abonos a
                 JOIN cuentas_por_cobrar x ON x.id_cxc = a.id_cxc
                WHERE x.id_factura = NEW.id_factura) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'La factura tiene abonos; reviértalos antes de anular';
    END IF;
    UPDATE cuentas_por_cobrar SET estado = 'ANULADA' WHERE id_factura = NEW.id_factura;
  END IF;
END$$

CREATE TRIGGER trg_abonos_validar BEFORE INSERT ON abonos
FOR EACH ROW
BEGIN
  DECLARE v_saldo DECIMAL(12,2);
  DECLARE v_estado VARCHAR(10);
  SELECT saldo, estado INTO v_saldo, v_estado
    FROM cuentas_por_cobrar WHERE id_cxc = NEW.id_cxc FOR UPDATE;
  IF v_estado IS NULL OR v_estado IN ('PAGADA','ANULADA') THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'La cuenta no admite abonos (pagada, anulada o inexistente)';
  END IF;
  IF NEW.monto > v_saldo THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'El abono es mayor que el saldo pendiente';
  END IF;
END$$

CREATE TRIGGER trg_abonos_aplicar AFTER INSERT ON abonos
FOR EACH ROW
BEGIN
  -- OJO: "estado" va primero porque MySQL evalúa las asignaciones de izquierda a
  -- derecha y "saldo" debe leerse todavía sin restar.
  UPDATE cuentas_por_cobrar
     SET estado = IF(saldo - NEW.monto <= 0, 'PAGADA', 'PARCIAL'),
         saldo  = saldo - NEW.monto
   WHERE id_cxc = NEW.id_cxc;
END$$

-- 8.5 INVENTARIO ------------------------------------------------------------
-- Descuenta del inventario lo que consumen los platos de la comanda que aún no
-- se han descontado (según su receta). Es segura de llamar varias veces.
-- La app la llama al "enviar a cocina/barra" y otra vez al cerrar la cuenta.
CREATE PROCEDURE sp_descontar_inventario_comanda (IN p_id_comanda INT UNSIGNED)
BEGIN
  INSERT INTO movimientos_inventario
        (id_insumo, tipo, cantidad, costo_unitario, referencia, id_detalle_comanda)
  SELECT rd.id_insumo, 'SALIDA_VENTA',
         -(rd.cantidad * cd.cantidad * (1 + rd.merma_pct / 100)),
         i.costo_promedio, CONCAT('Comanda #', p_id_comanda), cd.id_detalle
    FROM comanda_detalle cd
    JOIN receta_detalle rd ON rd.id_producto = cd.id_producto
    JOIN insumos i ON i.id_insumo = rd.id_insumo
   WHERE cd.id_comanda = p_id_comanda
     AND cd.inventario_descontado = 0
     AND cd.estado <> 'ANULADO';

  UPDATE insumos i
    JOIN (SELECT rd.id_insumo,
                 SUM(rd.cantidad * cd.cantidad * (1 + rd.merma_pct / 100)) AS usado
            FROM comanda_detalle cd
            JOIN receta_detalle rd ON rd.id_producto = cd.id_producto
           WHERE cd.id_comanda = p_id_comanda
             AND cd.inventario_descontado = 0
             AND cd.estado <> 'ANULADO'
           GROUP BY rd.id_insumo) u ON u.id_insumo = i.id_insumo
     SET i.stock_actual = i.stock_actual - u.usado;

  UPDATE comanda_detalle
     SET inventario_descontado = 1
   WHERE id_comanda = p_id_comanda
     AND inventario_descontado = 0
     AND estado <> 'ANULADO';
END$$

-- Anula un plato de una comanda ABIERTA. p_devolver_stock = 1 regresa los
-- ingredientes al inventario (aún no se cocinó); 0 los deja descontados (merma).
CREATE PROCEDURE sp_anular_detalle_comanda (
  IN p_id_detalle INT UNSIGNED,
  IN p_motivo VARCHAR(100),
  IN p_devolver_stock TINYINT
)
BEGIN
  DECLARE v_descontado TINYINT;
  DECLARE v_estado VARCHAR(10);
  DECLARE v_comanda INT UNSIGNED;
  DECLARE v_estado_comanda VARCHAR(20);

  SET v_estado = NULL;
  SELECT inventario_descontado, estado, id_comanda
    INTO v_descontado, v_estado, v_comanda
    FROM comanda_detalle WHERE id_detalle = p_id_detalle FOR UPDATE;

  IF v_estado IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'El plato no existe';
  END IF;
  IF v_estado = 'ANULADO' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'El plato ya estaba anulado';
  END IF;
  SELECT estado INTO v_estado_comanda FROM comandas WHERE id_comanda = v_comanda;
  IF v_estado_comanda <> 'ABIERTA' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Solo se anulan platos de comandas abiertas';
  END IF;
  IF p_motivo IS NULL OR CHAR_LENGTH(TRIM(p_motivo)) < 3 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Indique el motivo de la anulación';
  END IF;

  IF v_descontado = 1 AND p_devolver_stock = 1 THEN
    INSERT INTO movimientos_inventario
          (id_insumo, tipo, cantidad, costo_unitario, referencia, id_detalle_comanda)
    SELECT rd.id_insumo, 'DEVOLUCION',
           rd.cantidad * cd.cantidad * (1 + rd.merma_pct / 100),
           i.costo_promedio, CONCAT('Anulación en comanda #', v_comanda), cd.id_detalle
      FROM comanda_detalle cd
      JOIN receta_detalle rd ON rd.id_producto = cd.id_producto
      JOIN insumos i ON i.id_insumo = rd.id_insumo
     WHERE cd.id_detalle = p_id_detalle;

    UPDATE insumos i
      JOIN (SELECT rd.id_insumo,
                   rd.cantidad * cd.cantidad * (1 + rd.merma_pct / 100) AS devuelto
              FROM comanda_detalle cd
              JOIN receta_detalle rd ON rd.id_producto = cd.id_producto
             WHERE cd.id_detalle = p_id_detalle) u ON u.id_insumo = i.id_insumo
       SET i.stock_actual = i.stock_actual + u.devuelto;
  END IF;

  UPDATE comanda_detalle
     SET estado = 'ANULADO',
         notas = LEFT(CONCAT(COALESCE(notas, ''), ' [ANULADO: ', p_motivo, ']'), 150)
   WHERE id_detalle = p_id_detalle;
END$$

-- Recibe una compra: suma al stock y recalcula el costo promedio ponderado.
CREATE PROCEDURE sp_recibir_compra (IN p_id_compra INT UNSIGNED, IN p_id_usuario INT UNSIGNED)
BEGIN
  DECLARE v_estado VARCHAR(10);
  SET v_estado = NULL;
  SELECT estado INTO v_estado FROM compras WHERE id_compra = p_id_compra FOR UPDATE;
  IF v_estado IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'La compra no existe';
  END IF;
  IF v_estado <> 'BORRADOR' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Solo se pueden recibir compras en borrador';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM compra_detalle WHERE id_compra = p_id_compra) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'La compra no tiene líneas';
  END IF;

  INSERT INTO movimientos_inventario
        (id_insumo, tipo, cantidad, costo_unitario, referencia, id_compra, id_usuario)
  SELECT d.id_insumo, 'COMPRA', d.cantidad, d.costo_unitario,
         CONCAT('Compra #', p_id_compra), p_id_compra, p_id_usuario
    FROM compra_detalle d WHERE d.id_compra = p_id_compra;

  -- Primero el costo promedio ponderado (con la existencia ANTES de la compra)...
  UPDATE insumos i
    JOIN compra_detalle d ON d.id_insumo = i.id_insumo AND d.id_compra = p_id_compra
     SET i.costo_promedio = (GREATEST(i.stock_actual, 0) * i.costo_promedio + d.cantidad * d.costo_unitario)
                            / (GREATEST(i.stock_actual, 0) + d.cantidad);

  -- ...y después la existencia.
  UPDATE insumos i
    JOIN compra_detalle d ON d.id_insumo = i.id_insumo AND d.id_compra = p_id_compra
     SET i.stock_actual = i.stock_actual + d.cantidad;

  UPDATE compras
     SET estado = 'RECIBIDA',
         total  = (SELECT ROUND(SUM(cantidad * costo_unitario), 2)
                     FROM compra_detalle WHERE id_compra = p_id_compra)
   WHERE id_compra = p_id_compra;
END$$

-- Ajuste manual de inventario (conteo físico, merma, inventario inicial).
-- p_cantidad es la DIFERENCIA: positiva suma, negativa resta.
CREATE PROCEDURE sp_ajustar_inventario (
  IN p_id_insumo INT UNSIGNED,
  IN p_cantidad DECIMAL(14,3),
  IN p_tipo VARCHAR(20),
  IN p_nota VARCHAR(200),
  IN p_id_usuario INT UNSIGNED
)
BEGIN
  DECLARE v_costo DECIMAL(14,6);
  SET v_costo = NULL;
  IF p_tipo NOT IN ('AJUSTE','MERMA','INVENTARIO_INICIAL') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Tipo de ajuste inválido';
  END IF;
  SELECT costo_promedio INTO v_costo FROM insumos WHERE id_insumo = p_id_insumo FOR UPDATE;
  IF v_costo IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'El insumo no existe';
  END IF;
  INSERT INTO movimientos_inventario
        (id_insumo, tipo, cantidad, costo_unitario, referencia, id_usuario, notas)
  VALUES (p_id_insumo, p_tipo, p_cantidad, v_costo, 'Ajuste manual', p_id_usuario, p_nota);
  UPDATE insumos SET stock_actual = stock_actual + p_cantidad WHERE id_insumo = p_id_insumo;
END$$

-- 8.6 CARGO A HABITACIÓN -----------------------------------------------------
-- Pasa el total de una comanda a la cuenta de una estadía activa. Crea un
-- consumo por tratamiento fiscal (gravado / exento). Se factura luego,
-- junto con el hospedaje, al hacer el check-out.
CREATE PROCEDURE sp_cargar_comanda_a_habitacion (
  IN p_id_comanda INT UNSIGNED,
  IN p_id_estancia INT UNSIGNED,
  IN p_id_usuario INT UNSIGNED
)
BEGIN
  DECLARE v_estado VARCHAR(20);
  DECLARE v_estado_estancia VARCHAR(10);
  DECLARE v_mesa INT UNSIGNED;

  SET v_estado = NULL;
  SELECT estado, id_mesa INTO v_estado, v_mesa
    FROM comandas WHERE id_comanda = p_id_comanda FOR UPDATE;
  IF v_estado IS NULL OR v_estado NOT IN ('ABIERTA','CERRADA') THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'La comanda no existe o ya fue cobrada, cargada o anulada';
  END IF;

  SET v_estado_estancia = NULL;
  SELECT estado INTO v_estado_estancia FROM estancias WHERE id_estancia = p_id_estancia FOR UPDATE;
  IF v_estado_estancia IS NULL OR v_estado_estancia <> 'ACTIVA' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'La estadía no existe o ya no está activa';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM comanda_detalle WHERE id_comanda = p_id_comanda AND estado <> 'ANULADO') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'La comanda no tiene platos para cargar';
  END IF;

  CALL sp_descontar_inventario_comanda(p_id_comanda);

  INSERT INTO consumos
        (id_estancia, descripcion, cantidad, precio_unitario, tratamiento_fiscal, id_comanda, id_usuario)
  SELECT p_id_estancia,
         CONCAT('Restaurante - comanda #', p_id_comanda,
                IF(d.tratamiento_fiscal = 'EXENTO', ' (exento)', '')),
         1,
         ROUND(SUM(d.cantidad * d.precio_unitario - d.descuento), 2),
         d.tratamiento_fiscal, p_id_comanda, p_id_usuario
    FROM comanda_detalle d
   WHERE d.id_comanda = p_id_comanda AND d.estado <> 'ANULADO'
   GROUP BY d.tratamiento_fiscal;

  UPDATE comandas
     SET estado = 'CARGADA_HABITACION', id_estancia = p_id_estancia,
         cerrada_en = NOW()
   WHERE id_comanda = p_id_comanda;

  IF v_mesa IS NOT NULL THEN
    UPDATE mesas SET estado = 'LIBRE'
     WHERE id_mesa = v_mesa
       AND NOT EXISTS (SELECT 1 FROM comandas c2
                        WHERE c2.id_mesa = v_mesa AND c2.estado IN ('ABIERTA','CERRADA'));
  END IF;
END$$

DELIMITER ;

-- ---------------------------------------------------------------------
-- 9. Parámetros iniciales (editables desde el sistema por el ADMIN)
-- ---------------------------------------------------------------------
INSERT INTO configuracion (clave, valor, descripcion) VALUES
 ('hotel_nombre',          'Hotel (configurar)', 'Nombre o razón social que aparece en la factura'),
 ('hotel_rtn',             '',                   'RTN del emisor (14 dígitos)'),
 ('hotel_direccion',       '',                   'Dirección del establecimiento'),
 ('hotel_telefono',        '',                   'Teléfono del establecimiento'),
 ('moneda',                'HNL',                'Moneda de facturación'),
 ('isv_general',           '15.00',              'ISV general (%). Verificar vigencia con el SAR'),
 ('isv_selectivo',         '18.00',              'ISV selectivo (%). No aplica a hospedaje; para bienes que lo requieran'),
 ('tasa_turistica',        '4.00',               'Tasa por servicios turísticos sobre alojamiento (%). Verificar con contador/IHT'),
 ('desc_tercera_edad',     '25.00',              'Descuento adulto mayor 60-79 años (%). Verificar con la ley vigente'),
 ('desc_cuarta_edad',      '35.00',              'Descuento adulto mayor 80+ años (%). Verificar con la ley vigente'),
 ('edad_tercera',          '60',                 'Edad desde la que aplica tercera edad'),
 ('edad_cuarta',           '80',                 'Edad desde la que aplica cuarta edad'),
 ('recargo_persona_extra', '100.00',             'Recargo por persona sobre la capacidad (valor del sistema anterior; definir si es por noche)'),
 ('hora_checkin',          '14:00',              'Hora estándar de entrada'),
 ('hora_checkout',         '12:00',              'Hora estándar de salida'),
 ('leyenda_factura',       'La factura es beneficio de todos, ¡exíjala!', 'Leyenda obligatoria al pie de la factura');

INSERT INTO configuracion (clave, valor, descripcion) VALUES
 ('restaurante_nombre',    'Restaurante (configurar)', 'Nombre comercial del restaurante'),
 ('propina_sugerida',      '10.00',  'Propina sugerida (%) en la cuenta. Es voluntaria y NO va en la factura'),
 ('dias_credito_default',  '30',     'Días de crédito por defecto para clientes nuevos'),
 ('alerta_cai_dias',       '30',     'Días antes del vencimiento del CAI para avisar'),
 ('alerta_cai_numeros',    '50',     'Cuando queden estos números en el rango del CAI, avisar'),
 ('desc_adulto_mayor_aplica', 'HOSPEDAJE', 'A qué se aplica el descuento de adulto mayor: HOSPEDAJE (solo alojamiento) o TODO. Confirmar con su contador'),
 ('tasa_turistica_incluye_recargo', 'SI', 'SI = la tasa turística también se calcula sobre el recargo por personas extra');

INSERT INTO pisos (nombre, orden) VALUES ('Piso 1', 1);
INSERT INTO areas_restaurante (nombre) VALUES ('Salón principal');
INSERT INTO categorias_menu (nombre, orden, tipo) VALUES
 ('Desayunos', 1, 'ALIMENTOS'),
 ('Entradas', 2, 'ALIMENTOS'),
 ('Platos fuertes', 3, 'ALIMENTOS'),
 ('Postres', 4, 'ALIMENTOS'),
 ('Bebidas sin alcohol', 5, 'BEBIDAS'),
 ('Cervezas y licores', 6, 'BEBIDAS');

-- ---------------------------------------------------------------------
-- 10. Usuario de la aplicación (NO usar root desde Java)
--     CAMBIA esta contraseña antes de usarla fuera de tu computadora.
-- ---------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'hotel_app'@'%' IDENTIFIED BY 'Hotel_App_2026!';
GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON hotel_sistema.* TO 'hotel_app'@'%';
FLUSH PRIVILEGES;

-- Verificación rápida:
--   SHOW TABLES;
--   SELECT clave, valor FROM configuracion;


-- ================= 04_pos_restaurante.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 04_pos_restaurante.sql
-- Migración INCREMENTAL sobre hotel_sistema (no borra datos).
-- Agrega: tickets de cocina/barra, subcuentas para dividir la cuenta,
-- rol SUPERVISOR y parámetros de impresoras.
-- Ejecutar UNA sola vez, con el usuario root, después de 01 (y 03 si lo usó).
-- =====================================================================
USE hotel_sistema;

-- 1) Rol de supervisor / gerente de restaurante
ALTER TABLE usuarios
  MODIFY rol ENUM('ADMIN','SUPERVISOR','RECEPCION','AMA_LLAVES','CAJERO','MESERO','COCINA','BODEGA','CONTADOR')
  NOT NULL DEFAULT 'RECEPCION';

-- 2) Tickets de cocina/barra (cada "Enviar" genera un ticket por destino)
CREATE TABLE IF NOT EXISTS tickets_cocina (
  id_ticket   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_comanda  INT UNSIGNED NOT NULL,
  destino     ENUM('COCINA','BARRA') NOT NULL,
  numero_dia  SMALLINT UNSIGNED NOT NULL,            -- 1,2,3... se reinicia cada día
  creado_en   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  estado      ENUM('NUEVO','PREPARANDO','LISTO','ENTREGADO','CANCELADO') NOT NULL DEFAULT 'NUEVO',
  listo_en    DATETIME NULL,
  impreso     TINYINT(1) NOT NULL DEFAULT 0,
  id_usuario  INT UNSIGNED NULL,                     -- quien lo envió (mesero)
  PRIMARY KEY (id_ticket),
  KEY idx_tk_estado (destino, estado),
  KEY idx_tk_comanda (id_comanda),
  CONSTRAINT fk_tk_comanda FOREIGN KEY (id_comanda) REFERENCES comandas (id_comanda),
  CONSTRAINT fk_tk_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

-- 3) Cada línea sabe a qué ticket pertenece y a qué subcuenta (para dividir)
ALTER TABLE comanda_detalle
  ADD COLUMN num_cuenta TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER id_comanda,
  ADD COLUMN id_ticket  INT UNSIGNED NULL AFTER estado,
  ADD KEY idx_cdet_ticket (id_ticket),
  ADD CONSTRAINT fk_cdet_ticket FOREIGN KEY (id_ticket) REFERENCES tickets_cocina (id_ticket);

-- 4) Subcuentas: una factura por subcuenta
CREATE TABLE IF NOT EXISTS comanda_cuentas (
  id_comanda  INT UNSIGNED NOT NULL,
  num_cuenta  TINYINT UNSIGNED NOT NULL,
  etiqueta    VARCHAR(40) NULL,                      -- "Juan", "Pareja", "Cuenta 2"
  id_factura  INT UNSIGNED NULL,                     -- se llena al cobrar esa subcuenta
  PRIMARY KEY (id_comanda, num_cuenta),
  CONSTRAINT fk_cc_comanda FOREIGN KEY (id_comanda) REFERENCES comandas (id_comanda),
  CONSTRAINT fk_cc_factura FOREIGN KEY (id_factura) REFERENCES facturas (id_factura)
) ENGINE=InnoDB;

-- 5) Parámetros de impresión (NO = desactivada; o IP:puerto, ej. 192.168.1.50:9100)
INSERT IGNORE INTO configuracion (clave, valor, descripcion) VALUES
  ('impresora_cocina',  'NO', 'Impresora térmica de cocina: NO o IP:puerto (ej. 192.168.1.50:9100)'),
  ('impresora_barra',   'NO', 'Impresora térmica de barra: NO o IP:puerto'),
  ('impresora_cuenta',  'NO', 'Impresora de pre-cuentas (caja): NO o IP:puerto'),
  ('ticket_caracteres', '42', 'Caracteres por línea del ticket (80 mm = 42 o 48)'),
  ('imprimir_auto',     'SI', 'Imprimir el ticket de cocina/barra al enviar la comanda: SI o NO'),
  ('mesero_ve_solo_suyas', 'SI', 'Un mesero solo ve y cobra sus propias mesas: SI o NO');

-- Verificación
SELECT 'OK' AS resultado, COUNT(*) AS parametros_impresion
FROM configuracion WHERE clave LIKE 'impresora_%';


-- ================= 05_vpos_banco.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 05_vpos_banco.sql  —  Integración vPOS bancario (Banco Atlántida, PinPad EDP)
-- Migración INCREMENTAL. Ejecutar UNA vez con root, después de 04.
-- =====================================================================
USE hotel_sistema;

CREATE TABLE IF NOT EXISTS vpos_cierres (
  id_cierre        INT UNSIGNED NOT NULL AUTO_INCREMENT,
  fecha            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_usuario       INT UNSIGNED NULL,
  num_ventas       INT UNSIGNED NOT NULL DEFAULT 0,
  monto_ventas     DECIMAL(12,2) NOT NULL DEFAULT 0,
  num_anuladas     INT UNSIGNED NOT NULL DEFAULT 0,
  monto_anuladas   DECIMAL(12,2) NOT NULL DEFAULT 0,
  exito            TINYINT(1) NOT NULL DEFAULT 0,
  respuesta_raw    VARCHAR(500) NULL,
  PRIMARY KEY (id_cierre),
  CONSTRAINT fk_vcierre_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS vpos_transacciones (
  id_vpos         INT UNSIGNED NOT NULL AUTO_INCREMENT,
  tipo            ENUM('VENTA','ANULACION') NOT NULL DEFAULT 'VENTA',
  fecha           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_usuario      INT UNSIGNED NULL,
  origen          ENUM('RESTAURANTE','HOTEL','OTRO') NOT NULL DEFAULT 'OTRO',
  id_comanda      INT UNSIGNED NULL,
  id_estancia     INT UNSIGNED NULL,
  id_factura      INT UNSIGNED NULL,             -- se llena cuando se emite la factura
  monto_total     DECIMAL(12,2) NOT NULL DEFAULT 0,   -- lo que paga el cliente (TOT + TAX)
  monto_tot       DECIMAL(12,2) NOT NULL DEFAULT 0,   -- valor enviado como TOT
  monto_tax       DECIMAL(12,2) NOT NULL DEFAULT 0,   -- valor enviado como TAX (ISV)
  rsp             VARCHAR(10) NULL,              -- RSP: 00 = aprobada
  tarjeta         VARCHAR(40) NULL,              -- CRD
  autorizacion    VARCHAR(20) NULL,              -- AUT
  fecha_lote      VARCHAR(12) NULL,              -- LTD
  lote            VARCHAR(12) NULL,              -- BAT
  hora_lote       VARCHAR(12) NULL,              -- LTT
  referencia      VARCHAR(30) NULL,              -- SYS
  estado          ENUM('PENDIENTE','APROBADA','RECHAZADA','ANULADA','ERROR') NOT NULL DEFAULT 'PENDIENTE',
  id_original     INT UNSIGNED NULL,             -- en una ANULACION, la venta anulada
  id_cierre       INT UNSIGNED NULL,
  respuesta_raw   VARCHAR(500) NULL,
  detalle_error   VARCHAR(255) NULL,
  PRIMARY KEY (id_vpos),
  KEY idx_vpos_fecha (fecha),
  KEY idx_vpos_estado (estado, id_cierre),
  KEY idx_vpos_factura (id_factura),
  CONSTRAINT fk_vpos_usuario  FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario),
  CONSTRAINT fk_vpos_factura  FOREIGN KEY (id_factura) REFERENCES facturas (id_factura),
  CONSTRAINT fk_vpos_cierre   FOREIGN KEY (id_cierre)  REFERENCES vpos_cierres (id_cierre),
  CONSTRAINT fk_vpos_original FOREIGN KEY (id_original) REFERENCES vpos_transacciones (id_vpos)
) ENGINE=InnoDB;

INSERT IGNORE INTO configuracion (clave, valor, descripcion) VALUES
  ('vpos_activo',      'NO',          'Activar cobro con PinPad (vPOS bancario): SI o NO'),
  ('vpos_modo',        'SIMULADOR',   'SIMULADOR (pruebas sin PinPad), LOCAL (DLL en esta PC con Windows) o PUENTE (DLL en la PC de caja)'),
  ('vpos_dll_ruta',    'C:\\EDPvPOS\\EDPvPOSLink.dll', 'Ruta del DLL del banco (modos LOCAL y PUENTE)'),
  ('vpos_url',         'http://192.168.1.20:8085', 'Dirección del puente vPOS (solo modo PUENTE)'),
  ('vpos_token',       'cambiar-esta-clave', 'Clave compartida con el puente vPOS (debe ser igual en ambos lados)'),
  ('vpos_modo_tot',    'SIN_ISV',     'TOT enviado al vPOS: SIN_ISV (subtotal; el vPOS suma TAX) o CON_ISV (total completo)'),
  ('vpos_timeout_seg', '150',         'Segundos máximos de espera al cliente en el PinPad'),
  ('vpos_pid_venta',     'VPSALE0002', 'PID de venta del vPOS'),
  ('vpos_pid_anulacion', 'VPREVER002', 'PID de anulación del vPOS'),
  ('vpos_pid_cierre',    'VPSETTL001', 'PID de cierre de lote del vPOS');

SELECT 'OK' AS resultado, COUNT(*) AS parametros_vpos FROM configuracion WHERE clave LIKE 'vpos_%';


-- ================= 06_estaciones_inventario.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 06_estaciones_inventario.sql
-- Migración INCREMENTAL (no borra datos). Ejecutar UNA vez, con root,
-- DESPUÉS de 04 y 05.
--   1) Estaciones de comanda (cocina, barra, postres...) con pantalla e impresora
--   2) Cada categoría del menú envía sus comandas a una estación
--   3) Categorías de inventario (insumos)
-- =====================================================================
USE hotel_sistema;

-- 1) Estaciones ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS estaciones (
  id_estacion   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre        VARCHAR(40)  NOT NULL,
  impresora     VARCHAR(80)  NULL,                 -- IP:puerto de la impresora térmica; vacío = sin impresora
  usa_pantalla  TINYINT(1)   NOT NULL DEFAULT 1,   -- aparece en la pantalla de cocina (KDS)
  imprime_auto  TINYINT(1)   NOT NULL DEFAULT 1,   -- imprime sola al enviar la comanda
  orden         TINYINT UNSIGNED NOT NULL DEFAULT 1,
  activa        TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id_estacion),
  UNIQUE KEY uk_estaciones_nombre (nombre)
) ENGINE=InnoDB;

INSERT IGNORE INTO estaciones (nombre, impresora, orden)
VALUES ('COCINA', (SELECT NULLIF(NULLIF(valor, 'NO'), '') FROM configuracion WHERE clave = 'impresora_cocina'), 1);
INSERT IGNORE INTO estaciones (nombre, impresora, orden)
VALUES ('BARRA',  (SELECT NULLIF(NULLIF(valor, 'NO'), '') FROM configuracion WHERE clave = 'impresora_barra'), 2);

-- 2) Categoría → estación; plato → estación propia (opcional) ---------------
ALTER TABLE categorias_menu
  ADD COLUMN id_estacion INT UNSIGNED NULL,
  ADD CONSTRAINT fk_categorias_estacion FOREIGN KEY (id_estacion) REFERENCES estaciones (id_estacion);

ALTER TABLE productos_menu
  ADD COLUMN id_estacion INT UNSIGNED NULL,      -- NULL = usa la estación de su categoría
  ADD CONSTRAINT fk_productos_estacion FOREIGN KEY (id_estacion) REFERENCES estaciones (id_estacion);

-- Valores iniciales según lo que ya tenían los platos
UPDATE categorias_menu c
   SET c.id_estacion = (SELECT e.id_estacion FROM estaciones e
                         WHERE e.nombre = IF(c.tipo = 'BEBIDAS', 'BARRA', 'COCINA'));

-- Platos que se preparaban en un lugar distinto al de su categoría conservan su estación
UPDATE productos_menu p
  JOIN categorias_menu c ON c.id_categoria = p.id_categoria
  JOIN estaciones e ON e.nombre = p.area_preparacion
   SET p.id_estacion = e.id_estacion
 WHERE p.area_preparacion IN ('COCINA','BARRA') AND (c.id_estacion IS NULL OR c.id_estacion <> e.id_estacion);

-- 2b) Foto del plato (miniatura JPEG guardada en la base de datos)
ALTER TABLE productos_menu ADD COLUMN foto MEDIUMBLOB NULL;

-- 3) Los tickets guardan la estación por nombre (ya no solo COCINA/BARRA)
ALTER TABLE tickets_cocina
  MODIFY destino VARCHAR(40) NOT NULL,
  ADD COLUMN id_estacion INT UNSIGNED NULL AFTER destino,
  ADD CONSTRAINT fk_tk_estacion FOREIGN KEY (id_estacion) REFERENCES estaciones (id_estacion);
UPDATE tickets_cocina t JOIN estaciones e ON e.nombre = t.destino SET t.id_estacion = e.id_estacion;

-- 4) Categorías de inventario -------------------------------------------
CREATE TABLE IF NOT EXISTS categorias_insumo (
  id_categoria_insumo INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre              VARCHAR(50) NOT NULL,
  activa              TINYINT(1)  NOT NULL DEFAULT 1,
  PRIMARY KEY (id_categoria_insumo),
  UNIQUE KEY uk_cat_insumo_nombre (nombre)
) ENGINE=InnoDB;

INSERT IGNORE INTO categorias_insumo (nombre) VALUES
  ('Carnes y embutidos'), ('Pescados y mariscos'), ('Lácteos y huevos'), ('Verduras y frutas'),
  ('Abarrotes y granos'), ('Panadería y repostería'), ('Salsas y condimentos'), ('Bebidas'),
  ('Licores y cervezas'), ('Desechables y empaques'), ('Limpieza');

ALTER TABLE insumos
  ADD COLUMN id_categoria_insumo INT UNSIGNED NULL,
  ADD CONSTRAINT fk_insumos_categoria FOREIGN KEY (id_categoria_insumo) REFERENCES categorias_insumo (id_categoria_insumo);

-- 5) Tipo de negocio: apague «modulo_hotel» si el cliente es solo restaurante (o «modulo_restaurante» si es solo hotel)
INSERT IGNORE INTO configuracion (clave, valor, descripcion) VALUES
  ('modulo_hotel',       'SI', 'Usar módulos de hotel (habitaciones, reservas, check-in, huéspedes): SI o NO'),
  ('modulo_restaurante', 'SI', 'Usar módulos de restaurante (punto de venta, cocina, menú): SI o NO');

SELECT 'OK' AS resultado,
       (SELECT COUNT(*) FROM estaciones) AS estaciones,
       (SELECT COUNT(*) FROM categorias_insumo) AS categorias_inventario;


-- ================= 07_manufactura_opciones.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 07_manufactura_opciones.sql
-- Migración INCREMENTAL (no borra datos). Ejecutar UNA vez, con root,
-- DESPUÉS de 04, 05 y 06.
--   1) Manufactura: preparaciones intermedias (salsas, masas...) y producción de lotes
--   2) Opciones del plato (variantes): término de la carne, acompañantes, extras...
-- =====================================================================
USE hotel_sistema;

-- 1) Manufactura ----------------------------------------------------------
ALTER TABLE movimientos_inventario
  MODIFY tipo ENUM('COMPRA','SALIDA_VENTA','DEVOLUCION','AJUSTE','MERMA','INVENTARIO_INICIAL','PRODUCCION_ENTRADA','PRODUCCION_SALIDA') NOT NULL;

-- Una preparación es una receta cuyo resultado es un insumo (por eso luego se usa en otras recetas)
CREATE TABLE IF NOT EXISTS preparaciones (
  id_preparacion INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_insumo      INT UNSIGNED NOT NULL,                 -- el insumo que se produce
  rendimiento    DECIMAL(14,3) NOT NULL,                -- cuánto rinde 1 lote, en la unidad base del insumo
  activa         TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id_preparacion),
  UNIQUE KEY uk_prep_insumo (id_insumo),
  CONSTRAINT ck_prep_rend CHECK (rendimiento > 0),
  CONSTRAINT fk_prep_insumo FOREIGN KEY (id_insumo) REFERENCES insumos (id_insumo)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS preparacion_detalle (
  id_preparacion INT UNSIGNED NOT NULL,
  id_insumo      INT UNSIGNED NOT NULL,
  cantidad       DECIMAL(14,3) NOT NULL,                -- por lote, en unidad base del ingrediente
  merma_pct      DECIMAL(5,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (id_preparacion, id_insumo),
  CONSTRAINT ck_pdet_cant CHECK (cantidad > 0),
  CONSTRAINT fk_pdet_prep FOREIGN KEY (id_preparacion) REFERENCES preparaciones (id_preparacion),
  CONSTRAINT fk_pdet_ins  FOREIGN KEY (id_insumo) REFERENCES insumos (id_insumo)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS producciones (
  id_produccion  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  fecha          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_preparacion INT UNSIGNED NOT NULL,
  lotes          DECIMAL(10,3) NOT NULL,
  cantidad       DECIMAL(14,3) NOT NULL,                -- total producido (unidad base)
  costo_total    DECIMAL(14,2) NOT NULL,
  id_usuario     INT UNSIGNED NULL,
  notas          VARCHAR(150) NULL,
  PRIMARY KEY (id_produccion),
  KEY idx_prod_fecha (fecha),
  CONSTRAINT fk_prod_prep FOREIGN KEY (id_preparacion) REFERENCES preparaciones (id_preparacion),
  CONSTRAINT fk_prod_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

-- 2) Opciones del plato (variantes) ---------------------------------------
CREATE TABLE IF NOT EXISTS grupos_opciones (
  id_grupo INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre   VARCHAR(60) NOT NULL,
  min_sel  TINYINT UNSIGNED NOT NULL DEFAULT 0,          -- 0 = opcional, 1 o más = obligatorio
  max_sel  TINYINT UNSIGNED NOT NULL DEFAULT 1,          -- 1 = escoger una; más = varias
  activo   TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id_grupo),
  UNIQUE KEY uk_grupo_nombre (nombre)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS opciones (
  id_opcion       INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_grupo        INT UNSIGNED NOT NULL,
  nombre          VARCHAR(60) NOT NULL,
  recargo         DECIMAL(10,2) NOT NULL DEFAULT 0,      -- precio extra (sin ISV)
  id_insumo       INT UNSIGNED NULL,                     -- ingrediente que gasta esta opción (opcional)
  cantidad_insumo DECIMAL(14,3) NULL,                    -- por unidad vendida, en unidad base
  orden           TINYINT UNSIGNED NOT NULL DEFAULT 1,
  activa          TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id_opcion),
  KEY idx_op_grupo (id_grupo),
  CONSTRAINT fk_op_grupo  FOREIGN KEY (id_grupo)  REFERENCES grupos_opciones (id_grupo),
  CONSTRAINT fk_op_insumo FOREIGN KEY (id_insumo) REFERENCES insumos (id_insumo)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS producto_grupos (
  id_producto INT UNSIGNED NOT NULL,
  id_grupo    INT UNSIGNED NOT NULL,
  orden       TINYINT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (id_producto, id_grupo),
  CONSTRAINT fk_pg_prod  FOREIGN KEY (id_producto) REFERENCES productos_menu (id_producto),
  CONSTRAINT fk_pg_grupo FOREIGN KEY (id_grupo)    REFERENCES grupos_opciones (id_grupo)
) ENGINE=InnoDB;

-- Lo que el cliente eligió en cada renglón (copia, para que cambiar el menú no altere cuentas viejas)
CREATE TABLE IF NOT EXISTS comanda_detalle_opciones (
  id_det_opcion   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_detalle      INT UNSIGNED NOT NULL,
  id_opcion       INT UNSIGNED NULL,
  nombre          VARCHAR(60) NOT NULL,
  recargo         DECIMAL(10,2) NOT NULL DEFAULT 0,
  id_insumo       INT UNSIGNED NULL,
  cantidad_insumo DECIMAL(14,3) NULL,
  descontado      TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id_det_opcion),
  KEY idx_cdo_detalle (id_detalle),
  CONSTRAINT fk_cdo_detalle FOREIGN KEY (id_detalle) REFERENCES comanda_detalle (id_detalle)
) ENGINE=InnoDB;

-- Ejemplos para empezar (puede cambiarlos o borrarlos desde Menú y recetas > Opciones)
INSERT IGNORE INTO grupos_opciones (nombre, min_sel, max_sel) VALUES
  ('Término de la carne', 1, 1), ('Extras', 0, 5), ('Acompañante', 1, 1);
INSERT INTO opciones (id_grupo, nombre, recargo, orden)
SELECT g.id_grupo, x.n, x.r, x.o FROM grupos_opciones g JOIN (
  SELECT 'Término de la carne' g, 'Término medio' n, 0 r, 1 o UNION ALL SELECT 'Término de la carne','Tres cuartos',0,2
  UNION ALL SELECT 'Término de la carne','Bien cocida',0,3
  UNION ALL SELECT 'Extras','Queso extra',15,1 UNION ALL SELECT 'Extras','Aguacate',20,2
  UNION ALL SELECT 'Acompañante','Papas fritas',0,1 UNION ALL SELECT 'Acompañante','Ensalada',0,2 UNION ALL SELECT 'Acompañante','Arroz y frijoles',0,3
) x ON x.g = g.nombre
WHERE NOT EXISTS (SELECT 1 FROM opciones o WHERE o.id_grupo = g.id_grupo);

SELECT 'OK' AS resultado, (SELECT COUNT(*) FROM grupos_opciones) AS grupos_de_opciones;


-- ================= 08_rtn_validados.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 08_rtn_validados.sql  -  RTN / identidad verificados en el SAR
-- Ejecutar UNA vez, con root, después de 07.
-- =====================================================================
USE hotel_sistema;

CREATE TABLE IF NOT EXISTS rtn_validados (
  numero       VARCHAR(20)  NOT NULL,            -- RTN (14 dígitos) o identidad (13 dígitos), solo números
  nombre       VARCHAR(150) NOT NULL,            -- nombre que mostró el SAR
  validado_en  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_usuario   INT UNSIGNED NULL,
  PRIMARY KEY (numero),
  CONSTRAINT fk_rtnv_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

SELECT 'OK' AS resultado;


-- ================= 09_permisos_roles.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 09_permisos_roles.sql  -  Roles y permisos editables
-- Ejecutar UNA vez, con root, después de 08.
-- =====================================================================
USE hotel_sistema;

CREATE TABLE IF NOT EXISTS permisos_rol (
  rol     VARCHAR(20) NOT NULL,
  permiso VARCHAR(40) NOT NULL,       -- id de pantalla (ej. pos_rest) o de acción (ej. pos.cobrar)
  PRIMARY KEY (rol, permiso)
) ENGINE=InnoDB;

-- Mientras un rol no tenga filas aquí, usa los permisos de fábrica del programa.
SELECT 'OK' AS resultado;


-- ================= 10_isv_por_producto.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 10_isv_por_producto.sql  -  ISV por plato/servicio (0 %, 15 %, 18 %)
-- Ejecutar UNA vez, con root, después de 09.
-- =====================================================================
USE hotel_sistema;

CREATE TABLE IF NOT EXISTS tasas_isv (
  id_tasa INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre  VARCHAR(40) NOT NULL,
  tasa    DECIMAL(5,2) NOT NULL,
  activa  TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id_tasa),
  UNIQUE KEY uk_tasa (tasa)
) ENGINE=InnoDB;
INSERT IGNORE INTO tasas_isv (nombre, tasa) VALUES ('Exento (0 %)', 0), ('ISV 15 %', 15), ('ISV 18 %', 18);

ALTER TABLE productos_menu ADD COLUMN tasa_isv DECIMAL(5,2) NOT NULL DEFAULT 15;
ALTER TABLE comanda_detalle ADD COLUMN tasa_isv DECIMAL(5,2) NOT NULL DEFAULT 15;
ALTER TABLE consumos        ADD COLUMN tasa_isv DECIMAL(5,2) NULL;      -- NULL = tasa general
ALTER TABLE servicios       ADD COLUMN tasa_isv DECIMAL(5,2) NOT NULL DEFAULT 15;

UPDATE productos_menu  SET tasa_isv = 0 WHERE tratamiento_fiscal = 'EXENTO';
UPDATE comanda_detalle SET tasa_isv = 0 WHERE tratamiento_fiscal = 'EXENTO';
UPDATE servicios       SET tasa_isv = 0 WHERE tratamiento_fiscal = 'EXENTO';

-- El cargo del restaurante a la habitación ahora agrupa también por tasa
DROP PROCEDURE IF EXISTS sp_cargar_comanda_a_habitacion;
DELIMITER $$
CREATE PROCEDURE sp_cargar_comanda_a_habitacion (
  IN p_id_comanda INT UNSIGNED,
  IN p_id_estancia INT UNSIGNED,
  IN p_id_usuario INT UNSIGNED
)
BEGIN
  DECLARE v_estado VARCHAR(20);
  DECLARE v_estado_estancia VARCHAR(10);
  DECLARE v_mesa INT UNSIGNED;

  SET v_estado = NULL;
  SELECT estado, id_mesa INTO v_estado, v_mesa
    FROM comandas WHERE id_comanda = p_id_comanda FOR UPDATE;
  IF v_estado IS NULL OR v_estado NOT IN ('ABIERTA','CERRADA') THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'La comanda no existe o ya fue cobrada, cargada o anulada';
  END IF;

  SET v_estado_estancia = NULL;
  SELECT estado INTO v_estado_estancia FROM estancias WHERE id_estancia = p_id_estancia FOR UPDATE;
  IF v_estado_estancia IS NULL OR v_estado_estancia <> 'ACTIVA' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'La estadía no existe o ya no está activa';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM comanda_detalle WHERE id_comanda = p_id_comanda AND estado <> 'ANULADO') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'La comanda no tiene platos para cargar';
  END IF;

  CALL sp_descontar_inventario_comanda(p_id_comanda);

  INSERT INTO consumos
        (id_estancia, descripcion, cantidad, precio_unitario, tratamiento_fiscal, tasa_isv, id_comanda, id_usuario)
  SELECT p_id_estancia,
         CONCAT('Restaurante - comanda #', p_id_comanda,
                IF(d.tasa_isv = 0, ' (exento)', IF(d.tasa_isv <> 15, CONCAT(' (ISV ', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM d.tasa_isv)), ' %)'), ''))),
         1,
         ROUND(SUM(d.cantidad * d.precio_unitario - d.descuento), 2),
         IF(d.tasa_isv = 0, 'EXENTO', 'GRAVADO'), d.tasa_isv, p_id_comanda, p_id_usuario
    FROM comanda_detalle d
   WHERE d.id_comanda = p_id_comanda AND d.estado <> 'ANULADO'
   GROUP BY d.tasa_isv;

  UPDATE comandas
     SET estado = 'CARGADA_HABITACION', id_estancia = p_id_estancia,
         cerrada_en = NOW()
   WHERE id_comanda = p_id_comanda;

  IF v_mesa IS NOT NULL THEN
    UPDATE mesas SET estado = 'LIBRE'
     WHERE id_mesa = v_mesa
       AND NOT EXISTS (SELECT 1 FROM comandas c2
                        WHERE c2.id_mesa = v_mesa AND c2.estado IN ('ABIERTA','CERRADA'));
  END IF;
END$$
DELIMITER ;

SELECT 'OK' AS resultado, (SELECT COUNT(*) FROM tasas_isv) AS tasas;


-- ================= 11_listas_precios.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 11_listas_precios.sql  -  Listas de precios para el menú
-- Ejecutar UNA vez, con root, después de 10.
-- =====================================================================
USE hotel_sistema;

CREATE TABLE IF NOT EXISTS listas_precios (
  id_lista INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre   VARCHAR(50) NOT NULL,
  activa   TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id_lista),
  UNIQUE KEY uk_lista_nombre (nombre)
) ENGINE=InnoDB;

-- El precio normal de cada plato (Menú y recetas) es la lista «General».
-- Una lista solo guarda los platos cuyo precio es distinto al general.
CREATE TABLE IF NOT EXISTS precios_lista (
  id_lista    INT UNSIGNED NOT NULL,
  id_producto INT UNSIGNED NOT NULL,
  precio      DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (id_lista, id_producto),
  CONSTRAINT ck_pl_precio CHECK (precio >= 0),
  CONSTRAINT fk_pl_lista FOREIGN KEY (id_lista) REFERENCES listas_precios (id_lista),
  CONSTRAINT fk_pl_prod  FOREIGN KEY (id_producto) REFERENCES productos_menu (id_producto)
) ENGINE=InnoDB;

ALTER TABLE comandas
  ADD COLUMN id_lista INT UNSIGNED NULL,
  ADD CONSTRAINT fk_comandas_lista FOREIGN KEY (id_lista) REFERENCES listas_precios (id_lista);

-- Cada zona del restaurante puede usar una lista por defecto (ej. Terraza, Bar)
ALTER TABLE areas_restaurante
  ADD COLUMN id_lista INT UNSIGNED NULL,
  ADD CONSTRAINT fk_areas_lista FOREIGN KEY (id_lista) REFERENCES listas_precios (id_lista);

INSERT IGNORE INTO listas_precios (nombre) VALUES ('Happy hour'), ('Delivery');

SELECT 'OK' AS resultado, (SELECT COUNT(*) FROM listas_precios) AS listas;


-- ================= 12_cuenta_pedida_precios.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 12_cuenta_pedida_precios.sql
--   * "Tirar la cuenta": la mesa pidió la cuenta (hora y color en el POS)
--   * Mostrar precios con ISV incluido o sin ISV en el POS
-- Ejecutar UNA vez, con root, después de 11.
-- =====================================================================
USE hotel_sistema;

ALTER TABLE comandas ADD COLUMN cuenta_pedida_en DATETIME NULL;

INSERT IGNORE INTO configuracion (clave, valor, descripcion) VALUES
  ('precios_con_isv', 'SI', 'Mostrar en el POS los precios con ISV incluido: SI o NO (los precios se guardan siempre sin ISV)');

SELECT 'OK' AS resultado;


-- ================= 13_cortes_caja.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 13_cortes_caja.sql  -  Corte X (cambio de turno) y Corte Z (cierre del día)
-- Ejecutar UNA vez, con root, después de 12.
-- =====================================================================
USE hotel_sistema;

CREATE TABLE IF NOT EXISTS cortes (
  id_corte      INT UNSIGNED NOT NULL AUTO_INCREMENT,
  tipo          ENUM('X','Z') NOT NULL,
  id_usuario    INT UNSIGNED NULL,                 -- quien hizo el corte
  creado_en     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  desde_fecha   DATETIME NULL,
  hasta_fecha   DATETIME NULL,
  desde_factura VARCHAR(19) NULL,
  hasta_factura VARCHAR(19) NULL,
  n_facturas    INT NOT NULL DEFAULT 0,
  n_anuladas    INT NOT NULL DEFAULT 0,
  n_anul_previas INT NOT NULL DEFAULT 0,
  mesas_abiertas INT NOT NULL DEFAULT 0,
  fondo_inicial DECIMAL(12,2) NOT NULL DEFAULT 0,
  ventas_exento DECIMAL(12,2) NOT NULL DEFAULT 0,
  ventas_exonerado DECIMAL(12,2) NOT NULL DEFAULT 0,
  ventas_grav15 DECIMAL(12,2) NOT NULL DEFAULT 0,
  ventas_grav18 DECIMAL(12,2) NOT NULL DEFAULT 0,
  isv15         DECIMAL(12,2) NOT NULL DEFAULT 0,
  isv18         DECIMAL(12,2) NOT NULL DEFAULT 0,
  descuentos    DECIMAL(12,2) NOT NULL DEFAULT 0,
  tasa_turistica DECIMAL(12,2) NOT NULL DEFAULT 0,
  total_ventas  DECIMAL(12,2) NOT NULL DEFAULT 0,
  ventas_contado DECIMAL(12,2) NOT NULL DEFAULT 0,
  ventas_credito DECIMAL(12,2) NOT NULL DEFAULT 0,
  pago_efectivo DECIMAL(12,2) NOT NULL DEFAULT 0,
  pago_tarjeta  DECIMAL(12,2) NOT NULL DEFAULT 0,
  pago_transferencia DECIMAL(12,2) NOT NULL DEFAULT 0,
  pago_cheque   DECIMAL(12,2) NOT NULL DEFAULT 0,
  pago_otro     DECIMAL(12,2) NOT NULL DEFAULT 0,
  propinas      DECIMAL(12,2) NOT NULL DEFAULT 0,
  anulado_total DECIMAL(12,2) NOT NULL DEFAULT 0,
  anul_previas_total DECIMAL(12,2) NOT NULL DEFAULT 0,
  efectivo_esperado DECIMAL(12,2) NOT NULL DEFAULT 0,
  efectivo_contado  DECIMAL(12,2) NOT NULL DEFAULT 0,
  diferencia    DECIMAL(12,2) NOT NULL DEFAULT 0,
  notas         VARCHAR(255) NULL,
  id_z          INT UNSIGNED NULL,                 -- en qué corte Z quedó incluido este X
  PRIMARY KEY (id_corte),
  KEY idx_cortes_tipo (tipo, id_z),
  CONSTRAINT fk_cortes_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

ALTER TABLE facturas
  ADD COLUMN id_corte INT UNSIGNED NULL,           -- corte X que incluyó esta factura (NULL = turno abierto)
  ADD COLUMN anulada_corte INT UNSIGNED NULL;      -- corte X que reportó la anulación

SELECT 'OK' AS resultado;


-- ================= 14_tipo_negocio_retail.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 14_tipo_negocio_retail.sql  -  Tipo de negocio: hotel / restaurante / tienda (retail)
-- Ejecutar UNA vez, con root, después de 13.
-- =====================================================================
USE hotel_sistema;

INSERT IGNORE INTO configuracion (clave, valor, descripcion) VALUES
  ('modulo_retail', 'NO', 'Usar caja de tienda (retail: venta rápida con código de barras, sin mesas): SI o NO');

-- Código de barras del producto (opcional, no se repite)
ALTER TABLE productos_menu ADD COLUMN codigo_barras VARCHAR(40) NULL, ADD UNIQUE KEY uk_productos_barras (codigo_barras);

SELECT 'OK' AS resultado;


-- ================= 15_autoconsumo.sql =================
SET NAMES utf8mb4;
-- =====================================================================
-- 15_autoconsumo.sql  -  Autoconsumos: salida de inventario SIN venta, con motivo y reporte
-- Ejecutar UNA vez, con root, después de 14.
-- =====================================================================
USE hotel_sistema;

ALTER TABLE comandas
  MODIFY estado ENUM('ABIERTA','CERRADA','CARGADA_HABITACION','FACTURADA','ANULADA','AUTOCONSUMO') NOT NULL DEFAULT 'ABIERTA';

ALTER TABLE movimientos_inventario
  MODIFY tipo ENUM('COMPRA','SALIDA_VENTA','DEVOLUCION','AJUSTE','MERMA','INVENTARIO_INICIAL','PRODUCCION_ENTRADA','PRODUCCION_SALIDA','AUTOCONSUMO') NOT NULL;

CREATE TABLE IF NOT EXISTS motivos_autoconsumo (
  id_motivo INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre    VARCHAR(60) NOT NULL,
  activo    TINYINT(1)  NOT NULL DEFAULT 1,
  PRIMARY KEY (id_motivo),
  UNIQUE KEY uk_motivo_nombre (nombre)
) ENGINE=InnoDB;

INSERT IGNORE INTO motivos_autoconsumo (nombre) VALUES
  ('Consumo de empleados'), ('Cortesía de la gerencia'), ('Degustación / prueba de platos'), ('Error de preparación');

CREATE TABLE IF NOT EXISTS autoconsumos (
  id_autoconsumo INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_comanda     INT UNSIGNED NOT NULL,
  id_motivo      INT UNSIGNED NOT NULL,
  fecha          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  id_usuario     INT UNSIGNED NULL,          -- quien lo registró
  id_autoriza    INT UNSIGNED NULL,          -- supervisor que lo autorizó con su clave (si hizo falta)
  costo_total    DECIMAL(14,2) NOT NULL DEFAULT 0,   -- costo del inventario descargado
  notas          VARCHAR(200) NULL,
  PRIMARY KEY (id_autoconsumo),
  UNIQUE KEY uk_auto_comanda (id_comanda),
  KEY idx_auto_fecha (fecha),
  CONSTRAINT fk_auto_comanda FOREIGN KEY (id_comanda) REFERENCES comandas (id_comanda),
  CONSTRAINT fk_auto_motivo  FOREIGN KEY (id_motivo)  REFERENCES motivos_autoconsumo (id_motivo),
  CONSTRAINT fk_auto_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario)
) ENGINE=InnoDB;

SELECT 'OK' AS resultado, (SELECT COUNT(*) FROM motivos_autoconsumo) AS motivos;


-- ===== 16_compras_cxp =====
-- =====================================================================
-- 16_compras_cxp.sql  -  Compras: condición de pago, método, vencimiento y Cuentas por Pagar
-- Ejecutar UNA vez, con root, después de 15.
-- =====================================================================

ALTER TABLE proveedores
  ADD COLUMN dias_credito SMALLINT UNSIGNED NOT NULL DEFAULT 0;

ALTER TABLE compras
  ADD COLUMN condicion_pago     ENUM('CONTADO','CREDITO') NOT NULL DEFAULT 'CONTADO',
  ADD COLUMN metodo_pago        ENUM('EFECTIVO','TARJETA','TRANSFERENCIA','CHEQUE') NULL,
  ADD COLUMN referencia_pago    VARCHAR(60) NULL,
  ADD COLUMN fecha_vencimiento  DATE NULL;

CREATE TABLE IF NOT EXISTS cuentas_por_pagar (
  id_cxp            INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_compra         INT UNSIGNED  NOT NULL,
  id_proveedor      INT UNSIGNED  NOT NULL,
  fecha_emision     DATE          NOT NULL,
  fecha_vencimiento DATE          NOT NULL,
  monto             DECIMAL(14,2) NOT NULL,
  saldo             DECIMAL(14,2) NOT NULL,
  estado            ENUM('PENDIENTE','PAGADA') NOT NULL DEFAULT 'PENDIENTE',
  PRIMARY KEY (id_cxp),
  UNIQUE KEY uk_cxp_compra (id_compra),
  KEY idx_cxp_estado_venc (estado, fecha_vencimiento),
  CONSTRAINT fk_cxp_compra    FOREIGN KEY (id_compra)    REFERENCES compras (id_compra),
  CONSTRAINT fk_cxp_proveedor FOREIGN KEY (id_proveedor) REFERENCES proveedores (id_proveedor)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS pagos_proveedor (
  id_pago     INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  id_cxp      INT UNSIGNED  NOT NULL,
  fecha       DATE          NOT NULL,
  monto       DECIMAL(14,2) NOT NULL,
  metodo      ENUM('EFECTIVO','TARJETA','TRANSFERENCIA','CHEQUE') NOT NULL,
  referencia  VARCHAR(60)   NULL,
  id_usuario  INT UNSIGNED  NULL,
  creado_en   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id_pago),
  KEY idx_pagos_cxp (id_cxp),
  CONSTRAINT ck_pago_monto CHECK (monto > 0),
  CONSTRAINT fk_pago_cxp FOREIGN KEY (id_cxp) REFERENCES cuentas_por_pagar (id_cxp)
) ENGINE=InnoDB;

-- Al recibir una compra a crédito se crea su cuenta por pagar.
DROP PROCEDURE IF EXISTS sp_recibir_compra;
DELIMITER $$
CREATE PROCEDURE sp_recibir_compra (IN p_id_compra INT UNSIGNED, IN p_id_usuario INT UNSIGNED)
BEGIN
  DECLARE v_estado VARCHAR(10);
  DECLARE v_cond VARCHAR(10);
  SET v_estado = NULL;
  SELECT estado, condicion_pago INTO v_estado, v_cond FROM compras WHERE id_compra = p_id_compra FOR UPDATE;
  IF v_estado IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'La compra no existe';
  END IF;
  IF v_estado <> 'BORRADOR' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Solo se pueden recibir compras en borrador';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM compra_detalle WHERE id_compra = p_id_compra) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'La compra no tiene líneas';
  END IF;

  INSERT INTO movimientos_inventario
        (id_insumo, tipo, cantidad, costo_unitario, referencia, id_compra, id_usuario)
  SELECT d.id_insumo, 'COMPRA', d.cantidad, d.costo_unitario,
         CONCAT('Compra #', p_id_compra), p_id_compra, p_id_usuario
    FROM compra_detalle d WHERE d.id_compra = p_id_compra;

  UPDATE insumos i
    JOIN compra_detalle d ON d.id_insumo = i.id_insumo AND d.id_compra = p_id_compra
     SET i.costo_promedio = (GREATEST(i.stock_actual, 0) * i.costo_promedio + d.cantidad * d.costo_unitario)
                            / (GREATEST(i.stock_actual, 0) + d.cantidad);

  UPDATE insumos i
    JOIN compra_detalle d ON d.id_insumo = i.id_insumo AND d.id_compra = p_id_compra
     SET i.stock_actual = i.stock_actual + d.cantidad;

  UPDATE compras
     SET estado = 'RECIBIDA',
         total  = (SELECT ROUND(SUM(cantidad * costo_unitario), 2)
                     FROM compra_detalle WHERE id_compra = p_id_compra)
   WHERE id_compra = p_id_compra;

  IF v_cond = 'CREDITO' THEN
    INSERT INTO cuentas_por_pagar (id_compra, id_proveedor, fecha_emision, fecha_vencimiento, monto, saldo)
    SELECT c.id_compra, c.id_proveedor, c.fecha, COALESCE(c.fecha_vencimiento, c.fecha), c.total, c.total
      FROM compras c WHERE c.id_compra = p_id_compra;
  END IF;
END$$
DELIMITER ;

-- ===== 17_permisos_sin_clave =====
-- =====================================================================
-- 17_permisos_sin_clave.sql  -  Por rol: acciones bloqueadas que NO se pueden autorizar con clave de supervisor
-- Ejecutar UNA vez, con root, después de 16.
-- Si un rol no tiene filas aquí, toda acción bloqueada pide clave de supervisor (como hasta ahora).
-- =====================================================================

CREATE TABLE IF NOT EXISTS permisos_sin_clave (
  rol     VARCHAR(20) NOT NULL,
  permiso VARCHAR(40) NOT NULL,
  PRIMARY KEY (rol, permiso)
) ENGINE=InnoDB;

SELECT 'OK' AS resultado;

-- ===== 18_almacenes =====
-- =====================================================================
-- 18_almacenes.sql  -  Varios almacenes, puntos de venta y traspasos
-- Ejecutar UNA vez, con root, después de 17.
--   * insumos.stock_actual sigue siendo la existencia TOTAL (suma de todos los almacenes).
--   * La existencia por almacén vive en la tabla "existencias" y la mantiene un trigger.
--   * Todo lo que ya existe queda en el "Almacén principal".
-- =====================================================================

CREATE TABLE IF NOT EXISTS almacenes (
  id_almacen   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre       VARCHAR(60)  NOT NULL,
  descripcion  VARCHAR(120) NULL,
  es_principal TINYINT(1)   NOT NULL DEFAULT 0,
  activo       TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id_almacen),
  UNIQUE KEY uk_almacen_nombre (nombre)
) ENGINE=InnoDB;

INSERT INTO almacenes (id_almacen, nombre, descripcion, es_principal) VALUES (1, 'Almacén principal', 'Almacén por defecto', 1);

CREATE TABLE IF NOT EXISTS existencias (
  id_almacen INT UNSIGNED  NOT NULL,
  id_insumo  INT UNSIGNED  NOT NULL,
  stock      DECIMAL(14,3) NOT NULL DEFAULT 0,
  PRIMARY KEY (id_almacen, id_insumo),
  KEY idx_exist_insumo (id_insumo),
  CONSTRAINT fk_exist_almacen FOREIGN KEY (id_almacen) REFERENCES almacenes (id_almacen),
  CONSTRAINT fk_exist_insumo  FOREIGN KEY (id_insumo)  REFERENCES insumos (id_insumo)
) ENGINE=InnoDB;

INSERT INTO existencias (id_almacen, id_insumo, stock) SELECT 1, id_insumo, stock_actual FROM insumos;

ALTER TABLE movimientos_inventario
  MODIFY tipo ENUM('COMPRA','SALIDA_VENTA','DEVOLUCION','AJUSTE','MERMA','INVENTARIO_INICIAL','PRODUCCION_ENTRADA','PRODUCCION_SALIDA','AUTOCONSUMO','TRASPASO_SALIDA','TRASPASO_ENTRADA') NOT NULL,
  ADD COLUMN id_almacen INT UNSIGNED NULL,
  ADD KEY idx_mov_almacen (id_almacen),
  ADD CONSTRAINT fk_mov_almacen FOREIGN KEY (id_almacen) REFERENCES almacenes (id_almacen);

UPDATE movimientos_inventario SET id_almacen = 1 WHERE id_almacen IS NULL;

-- Código del insumo (para la carga masiva desde Excel).
ALTER TABLE insumos
  ADD COLUMN codigo VARCHAR(30) NULL,
  ADD UNIQUE KEY uk_insumo_codigo (codigo);

CREATE TABLE IF NOT EXISTS puntos_venta (
  id_punto   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre     VARCHAR(60)  NOT NULL,
  tipo       ENUM('RESTAURANTE','TIENDA','BAR','OTRO') NOT NULL DEFAULT 'RESTAURANTE',
  id_almacen INT UNSIGNED NOT NULL,
  activo     TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id_punto),
  UNIQUE KEY uk_punto_nombre (nombre),
  CONSTRAINT fk_punto_almacen FOREIGN KEY (id_almacen) REFERENCES almacenes (id_almacen)
) ENGINE=InnoDB;

INSERT INTO puntos_venta (nombre, tipo, id_almacen) VALUES ('Restaurante', 'RESTAURANTE', 1), ('Tienda', 'TIENDA', 1);

ALTER TABLE comandas
  ADD COLUMN id_punto_venta INT UNSIGNED NULL,
  ADD CONSTRAINT fk_comanda_punto FOREIGN KEY (id_punto_venta) REFERENCES puntos_venta (id_punto);

ALTER TABLE compras
  ADD COLUMN id_almacen INT UNSIGNED NOT NULL DEFAULT 1,
  ADD CONSTRAINT fk_compra_almacen FOREIGN KEY (id_almacen) REFERENCES almacenes (id_almacen);

DROP TRIGGER IF EXISTS trg_insumos_existencias;
DROP TRIGGER IF EXISTS trg_mov_almacen;
DROP PROCEDURE IF EXISTS sp_traspasar;
DELIMITER $$

-- Cada cambio en la existencia total se aplica al almacén indicado por @almacen_id (o al principal).
CREATE TRIGGER trg_insumos_existencias AFTER UPDATE ON insumos FOR EACH ROW
BEGIN
  DECLARE v_alm INT UNSIGNED;
  IF NEW.stock_actual <> OLD.stock_actual THEN
    SET v_alm = COALESCE(@almacen_id, (SELECT id_almacen FROM almacenes WHERE es_principal = 1 LIMIT 1), 1);
    INSERT INTO existencias (id_almacen, id_insumo, stock)
    VALUES (v_alm, NEW.id_insumo, NEW.stock_actual - OLD.stock_actual)
    ON DUPLICATE KEY UPDATE stock = stock + (NEW.stock_actual - OLD.stock_actual);
  END IF;
END$$

CREATE TRIGGER trg_mov_almacen BEFORE INSERT ON movimientos_inventario FOR EACH ROW
BEGIN
  IF NEW.id_almacen IS NULL THEN
    SET NEW.id_almacen = COALESCE(@almacen_id, (SELECT id_almacen FROM almacenes WHERE es_principal = 1 LIMIT 1), 1);
  END IF;
END$$

-- Traspaso de un insumo entre almacenes (la existencia total no cambia).
CREATE PROCEDURE sp_traspasar (IN p_insumo INT UNSIGNED, IN p_origen INT UNSIGNED, IN p_destino INT UNSIGNED,
                               IN p_cant DECIMAL(14,3), IN p_usuario INT UNSIGNED, IN p_nota VARCHAR(150))
BEGIN
  DECLARE v_disp DECIMAL(14,3);
  DECLARE v_cu DECIMAL(14,6);
  DECLARE v_no VARCHAR(60);
  DECLARE v_nd VARCHAR(60);
  IF p_origen = p_destino THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'El almacén de origen y el de destino son el mismo';
  END IF;
  IF p_cant IS NULL OR p_cant <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'La cantidad debe ser mayor que cero';
  END IF;
  SET v_disp = NULL;
  SELECT stock INTO v_disp FROM existencias WHERE id_almacen = p_origen AND id_insumo = p_insumo FOR UPDATE;
  IF COALESCE(v_disp, 0) < p_cant THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'No hay suficiente existencia en el almacén de origen';
  END IF;
  SELECT costo_promedio INTO v_cu FROM insumos WHERE id_insumo = p_insumo;
  SELECT nombre INTO v_no FROM almacenes WHERE id_almacen = p_origen;
  SELECT nombre INTO v_nd FROM almacenes WHERE id_almacen = p_destino;
  UPDATE existencias SET stock = stock - p_cant WHERE id_almacen = p_origen AND id_insumo = p_insumo;
  INSERT INTO existencias (id_almacen, id_insumo, stock) VALUES (p_destino, p_insumo, p_cant)
    ON DUPLICATE KEY UPDATE stock = stock + p_cant;
  INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, costo_unitario, referencia, id_usuario, notas, id_almacen)
  VALUES (p_insumo, 'TRASPASO_SALIDA', -p_cant, v_cu, CONCAT('Traspaso a ', v_nd), p_usuario, p_nota, p_origen),
         (p_insumo, 'TRASPASO_ENTRADA', p_cant, v_cu, CONCAT('Traspaso desde ', v_no), p_usuario, p_nota, p_destino);
END$$
DELIMITER ;

SELECT 'OK' AS resultado, (SELECT COUNT(*) FROM existencias) AS existencias, (SELECT COUNT(*) FROM puntos_venta) AS puntos_venta;

-- ===== 19_cai_por_punto =====
-- =====================================================================
-- 19_cai_por_punto.sql  -  CAI por punto de venta, alarmas configurables y bloqueo al vencer
-- Ejecutar UNA vez, con root, después de 18.
--   * Cada CAI puede pertenecer a un punto de venta (sucursal/caja). Sin punto = CAI general.
--   * Al facturar se usa primero el CAI del punto de venta de esa computadora; si no tiene, el general.
--   * Vencido o agotado = no se puede facturar (el sistema lo bloquea y avisa).
-- =====================================================================

ALTER TABLE puntos_venta
  MODIFY tipo ENUM('RESTAURANTE','TIENDA','BAR','HOTEL','OTRO') NOT NULL DEFAULT 'RESTAURANTE',
  ADD COLUMN direccion VARCHAR(150) NULL,
  ADD COLUMN telefono  VARCHAR(20)  NULL;

ALTER TABLE cai_autorizaciones
  ADD COLUMN id_punto_venta  INT UNSIGNED NULL,
  ADD COLUMN alerta_dias     SMALLINT UNSIGNED NOT NULL DEFAULT 30,
  ADD COLUMN alerta_numeros  INT UNSIGNED NOT NULL DEFAULT 50,
  ADD KEY idx_cai_punto (id_punto_venta),
  ADD CONSTRAINT fk_cai_punto FOREIGN KEY (id_punto_venta) REFERENCES puntos_venta (id_punto);

DROP PROCEDURE IF EXISTS sp_siguiente_numero_factura;
DROP VIEW IF EXISTS v_alertas;
DELIMITER $$
CREATE PROCEDURE sp_siguiente_numero_factura (
  IN  p_tipo_documento CHAR(2),
  IN  p_punto          INT UNSIGNED,
  OUT p_id_cai         INT UNSIGNED,
  OUT p_numero         CHAR(19)
)
BEGIN
  DECLARE v_est CHAR(3);
  DECLARE v_pto CHAR(3);
  DECLARE v_sig INT UNSIGNED;

  SET p_id_cai = NULL;
  SET p_numero = NULL;

  -- Primero el CAI propio del punto de venta; si no tiene, el CAI general (sin punto).
  SELECT id_cai, establecimiento, punto_emision, siguiente_correlativo
    INTO p_id_cai, v_est, v_pto, v_sig
    FROM cai_autorizaciones
   WHERE activo = 1
     AND tipo_documento = p_tipo_documento
     AND siguiente_correlativo <= rango_final
     AND fecha_limite_emision >= CURDATE()
     AND (id_punto_venta = p_punto OR id_punto_venta IS NULL)
   ORDER BY (id_punto_venta IS NULL), fecha_limite_emision, id_cai
   LIMIT 1
   FOR UPDATE;

  IF p_id_cai IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'No hay un CAI vigente con números disponibles para este punto de venta';
  END IF;

  SET p_numero = CONCAT(v_est, '-', v_pto, '-', p_tipo_documento, '-', LPAD(v_sig, 8, '0'));

  UPDATE cai_autorizaciones
     SET siguiente_correlativo = siguiente_correlativo + 1
   WHERE id_cai = p_id_cai;
END$$
DELIMITER ;

CREATE VIEW v_alertas AS
SELECT 'LLEGADA' AS tipo,
       IF(r.fecha_llegada = CURDATE(), 'ALTA', 'MEDIA') AS severidad,
       CAST(r.fecha_llegada AS DATETIME) AS fecha,
       CONCAT('Llega ', hu.nombre, ' - hab. ', h.numero) AS mensaje,
       'reserva' AS entidad, CAST(r.id_reserva AS CHAR) AS id_entidad
FROM reservas r
JOIN huespedes hu ON hu.id_huesped = r.id_huesped
JOIN habitaciones h ON h.id_habitacion = r.id_habitacion
WHERE r.estado IN ('PENDIENTE','CONFIRMADA')
  AND r.fecha_llegada BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 1 DAY)
UNION ALL
SELECT 'RESERVA_VENCIDA', 'ALTA', CAST(r.fecha_llegada AS DATETIME),
       CONCAT('No llegó: ', hu.nombre, ' - hab. ', h.numero, ' (era el ', DATE_FORMAT(r.fecha_llegada, '%d/%m/%Y'), ')'),
       'reserva', CAST(r.id_reserva AS CHAR)
FROM reservas r
JOIN huespedes hu ON hu.id_huesped = r.id_huesped
JOIN habitaciones h ON h.id_habitacion = r.id_habitacion
WHERE r.estado IN ('PENDIENTE','CONFIRMADA') AND r.fecha_llegada < CURDATE()
UNION ALL
SELECT 'SALIDA', IF(e.salida_prevista < CURDATE(), 'ALTA', 'MEDIA'), CAST(e.salida_prevista AS DATETIME),
       CONCAT(IF(e.salida_prevista < CURDATE(), 'Salida ATRASADA: ', 'Sale hoy: '), hu.nombre, ' - hab. ', h.numero),
       'estancia', CAST(e.id_estancia AS CHAR)
FROM estancias e
JOIN huespedes hu ON hu.id_huesped = e.id_huesped
JOIN habitaciones h ON h.id_habitacion = e.id_habitacion
WHERE e.estado = 'ACTIVA' AND e.salida_prevista <= CURDATE()
UNION ALL
SELECT 'CREDITO_VENCIDO', 'ALTA', CAST(x.fecha_vencimiento AS DATETIME),
       CONCAT('Factura ', x.numero_factura, ' de ', x.cliente, ' vencida hace ', x.dias_vencido, ' días. Saldo L ', x.saldo),
       'cxc', CAST(x.id_cxc AS CHAR)
FROM v_cuentas_por_cobrar x
WHERE x.estado IN ('PENDIENTE','PARCIAL') AND x.fecha_vencimiento < CURDATE()
UNION ALL
SELECT 'STOCK_BAJO', IF(s.stock_actual <= 0, 'ALTA', 'MEDIA'), CAST(NOW() AS DATETIME),
       CONCAT(s.nombre, ': quedan ', s.stock_actual, ' ', s.unidad_base, ' (mínimo ', s.stock_minimo, ')'),
       'insumo', CAST(s.id_insumo AS CHAR)
FROM v_stock_insumos s
WHERE s.situacion IN ('AGOTADO','BAJO')
UNION ALL
SELECT 'CAI_POR_VENCER', IF(DATEDIFF(c.fecha_limite_emision, CURDATE()) <= 7, 'ALTA', 'MEDIA'), CAST(c.fecha_limite_emision AS DATETIME),
       CONCAT('El CAI ', c.cai, ' (', COALESCE(pv.nombre, 'general'), ') vence el ', DATE_FORMAT(c.fecha_limite_emision, '%d/%m/%Y'),
              ' - faltan ', DATEDIFF(c.fecha_limite_emision, CURDATE()), ' días'),
       'cai', CAST(c.id_cai AS CHAR)
FROM cai_autorizaciones c LEFT JOIN puntos_venta pv ON pv.id_punto = c.id_punto_venta
WHERE c.activo = 1 AND c.siguiente_correlativo <= c.rango_final AND c.fecha_limite_emision >= CURDATE()
  AND c.fecha_limite_emision <= DATE_ADD(CURDATE(), INTERVAL c.alerta_dias DAY)
UNION ALL
SELECT 'CAI_VENCIDO', 'ALTA', CAST(c.fecha_limite_emision AS DATETIME),
       CONCAT('VENTAS BLOQUEADAS: el CAI ', c.cai, ' (', COALESCE(pv.nombre, 'general'), ') venció el ', DATE_FORMAT(c.fecha_limite_emision, '%d/%m/%Y'), '. Registre el nuevo CAI.'),
       'cai', CAST(c.id_cai AS CHAR)
FROM cai_autorizaciones c LEFT JOIN puntos_venta pv ON pv.id_punto = c.id_punto_venta
WHERE c.activo = 1 AND c.siguiente_correlativo <= c.rango_final AND c.fecha_limite_emision < CURDATE()
UNION ALL
SELECT 'CAI_RANGO_AGOTANDO', IF((c.rango_final - c.siguiente_correlativo + 1) <= 10, 'ALTA', 'MEDIA'), CAST(NOW() AS DATETIME),
       CONCAT('Quedan ', (c.rango_final - c.siguiente_correlativo + 1), ' números en el CAI ', c.cai, ' (', COALESCE(pv.nombre, 'general'), ')'),
       'cai', CAST(c.id_cai AS CHAR)
FROM cai_autorizaciones c LEFT JOIN puntos_venta pv ON pv.id_punto = c.id_punto_venta
WHERE c.activo = 1 AND c.siguiente_correlativo <= c.rango_final AND c.fecha_limite_emision >= CURDATE()
  AND (c.rango_final - c.siguiente_correlativo + 1) <= c.alerta_numeros
UNION ALL
SELECT 'CAI_AGOTADO', 'ALTA', CAST(NOW() AS DATETIME),
       CONCAT('VENTAS BLOQUEADAS: se agotó el rango del CAI ', c.cai, ' (', COALESCE(pv.nombre, 'general'), '). Registre el nuevo CAI.'),
       'cai', CAST(c.id_cai AS CHAR)
FROM cai_autorizaciones c LEFT JOIN puntos_venta pv ON pv.id_punto = c.id_punto_venta
WHERE c.activo = 1 AND c.siguiente_correlativo > c.rango_final
  AND NOT EXISTS (SELECT 1 FROM cai_autorizaciones n WHERE n.activo = 1 AND n.tipo_documento = c.tipo_documento AND n.id_cai <> c.id_cai
                  AND n.siguiente_correlativo <= n.rango_final AND n.fecha_limite_emision >= CURDATE()
                  AND (n.id_punto_venta <=> c.id_punto_venta))
UNION ALL
SELECT 'PUNTO_SIN_CAI', 'ALTA', CAST(NOW() AS DATETIME),
       CONCAT('El punto de venta ', p.nombre, ' no puede facturar: no tiene un CAI vigente con números disponibles.'),
       'punto_venta', CAST(p.id_punto AS CHAR)
FROM puntos_venta p
WHERE p.activo = 1
  AND NOT EXISTS (SELECT 1 FROM cai_autorizaciones n WHERE n.activo = 1 AND n.tipo_documento = '01'
                  AND n.siguiente_correlativo <= n.rango_final AND n.fecha_limite_emision >= CURDATE()
                  AND (n.id_punto_venta = p.id_punto OR n.id_punto_venta IS NULL))
UNION ALL
SELECT 'RECORDATORIO', 'MEDIA', rc.fecha_hora, rc.mensaje, 'recordatorio', CAST(rc.id_recordatorio AS CHAR)
FROM recordatorios rc
WHERE rc.estado = 'PENDIENTE' AND rc.fecha_hora <= DATE_ADD(NOW(), INTERVAL 1 DAY);

SELECT 'OK' AS resultado;

-- ===== 20_usuario_punto_venta =====
-- =====================================================================
-- 20_usuario_punto_venta.sql  -  Punto de venta asignado a cada usuario
-- Ejecutar UNA vez, con root, después de 19.
-- Cada usuario puede tener uno o varios puntos de venta. Al iniciar sesión usa el predeterminado y puede
-- cambiar a otro de los suyos desde la barra superior. Sin puntos asignados se usa el punto de la computadora.
-- =====================================================================

ALTER TABLE usuarios
  ADD COLUMN id_punto_venta INT UNSIGNED NULL,
  ADD CONSTRAINT fk_usuario_punto FOREIGN KEY (id_punto_venta) REFERENCES puntos_venta (id_punto);

CREATE TABLE IF NOT EXISTS usuario_puntos (
  id_usuario INT UNSIGNED NOT NULL,
  id_punto   INT UNSIGNED NOT NULL,
  PRIMARY KEY (id_usuario, id_punto),
  CONSTRAINT fk_up_usuario FOREIGN KEY (id_usuario) REFERENCES usuarios (id_usuario),
  CONSTRAINT fk_up_punto   FOREIGN KEY (id_punto)   REFERENCES puntos_venta (id_punto)
) ENGINE=InnoDB;

-- Los usuarios que ya tenían un punto predeterminado lo conservan como permitido.
INSERT IGNORE INTO usuario_puntos (id_usuario, id_punto) SELECT id_usuario, id_punto_venta FROM usuarios WHERE id_punto_venta IS NOT NULL;

-- ===== 21_permisos_pantalla_clave =====
CREATE TABLE IF NOT EXISTS permisos_con_clave (
  rol     VARCHAR(20) NOT NULL,
  permiso VARCHAR(40) NOT NULL,
  PRIMARY KEY (rol, permiso)
) ENGINE=InnoDB;

SELECT 'OK' AS resultado;
