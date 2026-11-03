-- =====================================================================
-- 21_permisos_pantalla_clave.sql  -  Pantallas del menú que se abren con clave de supervisor
-- Ejecutar UNA vez, con root, después de 20.
-- =====================================================================
USE hotel_sistema;

CREATE TABLE IF NOT EXISTS permisos_con_clave (
  rol     VARCHAR(20) NOT NULL,
  permiso VARCHAR(40) NOT NULL,
  PRIMARY KEY (rol, permiso)
) ENGINE=InnoDB;

SELECT 'OK' AS resultado;
