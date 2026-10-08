# DB-Diff-Force

![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.9+-C71A36?logo=apachemaven&logoColor=white)
![MariaDB](https://img.shields.io/badge/MariaDB-compatible-003545?logo=mariadb&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-compatible-4479A1?logo=mysql&logoColor=white)
![SQL Server](https://img.shields.io/badge/SQL%20Server-compatible-CC2927?logo=microsoftsqlserver&logoColor=white)

DB-Diff-Force es una aplicación de escritorio para comparar esquemas de MariaDB, MySQL y SQL Server, y exportar SQL para revisar. Se conecta a dos bases del mismo motor, permite elegir tablas, vistas y rutinas, muestra objetos faltantes o modificados y genera SQL para los elementos seleccionados. La aplicación nunca ejecuta el SQL generado.

## Índice

- [Requisitos](#requisitos)
- [Ejecución](#ejecución)
- [Alcance actual](#alcance-actual)
- [Diseño](#diseño)

## Requisitos

- JDK 21 o posterior
- Apache Maven 3.9 o posterior
- Acceso a internet en la primera compilación para que Maven descargue las dependencias; después quedan guardadas en la caché local
- Acceso a dos bases de datos del mismo motor; pueden estar en la misma instancia o en instancias distintas

## Ejecución

En Windows, ejecutá `run.bat`. El script compila el JAR ejecutable y abre la aplicación en una ventana independiente. La terminal vuelve a mostrar el prompt mientras la aplicación queda abierta. En macOS o Linux, ejecutá `./run.sh`; el script compila y abre la aplicación. Para compilar sin abrirla, ejecutá `mvn package`: la aplicación queda en `target/db-diff-force-0.2.0.jar`, con los controladores JDBC en `target/lib`. Maven descarga los controladores y sus dependencias a la caché local. Las credenciales no se guardan allí.

En la ventana de conexión, elegí el motor y completá los datos de cada extremo. Origen y destino se conectan por separado, así que pueden apuntar al mismo servidor con dos bases distintas o a servidores diferentes. Al pulsar **Probar conexión**, se verifica el servidor y se cargan las bases visibles para esa cuenta; después elegí una base para cada lado. En esta versión, ambos extremos usan el mismo motor. Los perfiles guardados se pueden elegir por separado para el origen y el destino. Guardan el motor, servidor, puerto, base, usuario y configuración TLS en `~/.db-diff-studio/connections.properties` para conservar los perfiles existentes. Si activás **Recordar contraseña**, se guarda en el almacén seguro del sistema operativo; si no, hay que ingresarla en cada sesión. TLS se puede habilitar por conexión.

Al conectar, la lista de objetos queda marcada como **sin comparar** hasta que se ejecute el análisis. Usá Ctrl+clic o Mayús+clic para seleccionar objetos. **Comparar** analiza los objetos seleccionados; si no seleccionás ninguno, analiza todos los tipos habilitados. Para comparar filas, seleccioná una tabla que ya se haya comparado. **Exportar SQL** solo incluye los resultados seleccionados; si no seleccionaste ninguno, la aplicación te pide que elijas en vez de exportarlo todo.

## Alcance actual

- Conexiones JDBC a MariaDB, MySQL y SQL Server. SQL Server usa el controlador JDBC oficial de Microsoft y autenticación SQL con usuario y contraseña. Ambos extremos deben usar el mismo motor.
- Listado y selección de tablas, vistas, procedimientos y funciones.
- Comparación de columnas e índices de tablas; comparación de definiciones de vistas y rutinas. En SQL Server, los objetos se distinguen por esquema y nombre.
- Comparación de filas de tablas seleccionadas mediante claves primarias. Las filas se clasifican como exclusivas del origen, exclusivas del destino o diferentes. Se puede exportar `INSERT` y `UPDATE`; `DELETE` requiere habilitarse explícitamente.
- Diferencias de definiciones línea por línea y estado de objetos faltantes.
- DDL basado en `SHOW CREATE` para tablas, vistas, procedimientos y funciones, si la cuenta tiene permisos. Las tablas y vistas que solo existen en el origen se exportan con `CREATE`; las vistas modificadas, con `CREATE OR REPLACE`; las rutinas modificadas, con scripts revisables de `DROP` y `CREATE`. Cada exportación selecciona la base de destino y la aplicación nunca la ejecuta.
- Los cambios en definiciones de tablas quedan para revisión: la aplicación exporta el `CREATE TABLE` completo del origen, pero no intenta generar una migración `ALTER`.
- Para SQL Server, las definiciones de tablas se reconstruyen desde las vistas de catálogo; los cambios de tabla se dejan para revisión manual. Las vistas, procedimientos y funciones modificados se pueden exportar con `CREATE OR ALTER` (SQL Server 2016 SP1 o posterior) o como `DROP` + `CREATE`. El script usa `GO` para separar lotes y puede incluir una transacción explícita.
- La comparación de filas de SQL Server requiere una clave primaria y tipos comparables. Exporta T-SQL con nombres calificados por base y esquema; inserta columnas `IDENTITY` con `SET IDENTITY_INSERT` cuando hace falta. Los tipos binarios y varios tipos especiales se omiten de la comparación automática.

Los permisos o la versión del servidor pueden impedir leer metadatos o definiciones; en ese caso, la aplicación marca el resultado para revisión en vez de generar DDL ejecutable incompleto. En SQL Server, procedimientos cifrados o sin permisos `VIEW DEFINITION` no muestran su definición. La reconstrucción inicial de tablas no incluye triggers ni funciones definidas por el usuario asociadas a columnas, y las características avanzadas de índices pueden requerir revisión manual. Las exportaciones de rutinas MySQL/MariaDB usan `DELIMITER` y deben revisarse con un cliente compatible. En ambos dialectos, la comparación de filas omite tablas sin clave primaria, esquemas incompatibles y tipos que no se pueden comparar de forma segura. Conserva como máximo 10.000 filas modificadas por comparación; si alcanza ese límite, la exportación lo indica e incluye solo las filas conservadas. La aplicación nunca ejecuta el SQL generado.

## Diseño

La aplicación usa Java 21 y Swing. Maven administra las dependencias JDBC y prepara un JAR ejecutable con sus bibliotecas en `target/lib`. El acceso JDBC y la extracción de objetos se implementan detrás de `DatabaseConnector`; MariaDB y MySQL comparten una implementación y SQL Server tiene su propio conector. La interfaz ejecuta las operaciones de base de datos fuera del hilo de eventos y administra las contraseñas temporales usadas para iniciar sesión.
