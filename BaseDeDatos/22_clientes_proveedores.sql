-- =====================================================================
-- 22_clientes_proveedores.sql  -  Fichas completas de clientes y proveedores, zonas,
--                                 exoneración SAR, límite de crédito con autorización
-- Ejecutar UNA vez, con root, después de 21.
-- =====================================================================
USE hotel_sistema;

-- Zonas (se crean desde el catálogo o al vuelo desde la ficha de cliente / proveedor)
CREATE TABLE IF NOT EXISTS zonas (
  id_zona INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nombre  VARCHAR(60)  NOT NULL,
  activo  TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id_zona),
  UNIQUE KEY uk_zona_nombre (nombre)
) ENGINE=InnoDB;

-- Clientes (la tabla conserva el nombre clientes_credito; ahora es el catálogo general de clientes)
ALTER TABLE clientes_credito
  ADD COLUMN codigo            VARCHAR(20)  NULL,
  ADD COLUMN tipo_cliente      ENUM('PERSONA','EMPRESA','GOBIERNO','ONG','OTRO') NOT NULL DEFAULT 'PERSONA',
  ADD COLUMN id_zona           INT UNSIGNED NULL,
  ADD COLUMN ciudad            VARCHAR(60)  NULL,
  ADD COLUMN celular           VARCHAR(20)  NULL,
  ADD COLUMN notas             VARCHAR(255) NULL,
  ADD COLUMN es_exento         TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN exo_constancia    VARCHAR(40)  NULL,
  ADD COLUMN exo_registro_sag  VARCHAR(40)  NULL,
  ADD COLUMN exo_vence         DATE         NULL,
  ADD COLUMN bloquear_vencidos TINYINT(1)   NOT NULL DEFAULT 1,
  ADD COLUMN creado_en         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ADD UNIQUE KEY uk_cliente_codigo (codigo),
  ADD CONSTRAINT fk_cliente_zona FOREIGN KEY (id_zona) REFERENCES zonas (id_zona);

-- Proveedores
-- limite_credito: lo máximo que se le puede deber al proveedor (0 = sin límite)
ALTER TABLE proveedores
  ADD COLUMN codigo            VARCHAR(20)   NULL,
  ADD COLUMN id_zona           INT UNSIGNED  NULL,
  ADD COLUMN ciudad            VARCHAR(60)   NULL,
  ADD COLUMN celular           VARCHAR(20)   NULL,
  ADD COLUMN notas             VARCHAR(255)  NULL,
  ADD COLUMN limite_credito    DECIMAL(14,2) NOT NULL DEFAULT 0,
  ADD COLUMN es_exento         TINYINT(1)    NOT NULL DEFAULT 0,
  ADD COLUMN exo_constancia    VARCHAR(40)   NULL,
  ADD COLUMN exo_registro_sag  VARCHAR(40)   NULL,
  ADD COLUMN creado_en         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ADD UNIQUE KEY uk_proveedor_codigo (codigo),
  ADD CONSTRAINT fk_proveedor_zona FOREIGN KEY (id_zona) REFERENCES zonas (id_zona);

-- Datos de la exoneración que exige el SAR en cada factura exonerada
ALTER TABLE facturas
  ADD COLUMN orden_compra_exenta VARCHAR(40) NULL,
  ADD COLUMN registro_sag        VARCHAR(40) NULL;
-- (la columna constancia_exoneracion ya existente guarda el N.º correlativo de la constancia de registro exonerado)

-- El libro de ventas incluye ahora el cliente y los datos de la exoneración
CREATE OR REPLACE VIEW v_libro_ventas AS
SELECT f.id_factura, f.numero_factura, DATE(f.fecha_emision) AS fecha,
       f.cliente_nombre, f.cliente_rtn, f.origen, f.condicion_pago,
       f.importe_gravado_15, f.importe_gravado_18, f.importe_exento,
       f.importe_exonerado, f.isv_15, f.isv_18, f.tasa_turistica,
       f.total, f.estado,
       f.id_cliente_credito, f.orden_compra_exenta, f.constancia_exoneracion, f.registro_sag
FROM facturas f;

-- Límite de crédito: sigue siendo obligatorio salvo que un supervisor lo autorice
-- (el sistema activa @credito_autorizado = 1 solo en esa operación).
DROP TRIGGER IF EXISTS trg_facturas_credito_limite;
DELIMITER $$
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
    IF v_pendiente + NEW.total > v_limite AND COALESCE(@credito_autorizado, 0) = 0 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'La factura excede el límite de crédito del cliente';
    END IF;
  END IF;
END$$
DELIMITER ;

SELECT 'OK' AS resultado;
