# DB-Diff-Force

![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.9+-C71A36?logo=apachemaven&logoColor=white)
![MariaDB](https://img.shields.io/badge/MariaDB-compatible-003545?logo=mariadb&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-compatible-4479A1?logo=mysql&logoColor=white)
![SQL Server](https://img.shields.io/badge/SQL%20Server-compatible-CC2927?logo=microsoftsqlserver&logoColor=white)

DB-Diff-Force es una aplicación de escritorio para comparar esquemas de MariaDB, MySQL y SQL Server, y exportar SQL para revisar. Al iniciar una comparación se puede elegir entre dos bases, una base con definiciones SQL completas de una carpeta o una base con un esquema reconstruido desde migraciones Flyway. Permite elegir tablas, vistas, rutinas y triggers, muestra objetos faltantes o modificados y genera SQL para los elementos seleccionados. La aplicación nunca ejecuta el SQL generado.

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

En **Nueva comparación**, elegí el motor y el tipo de origen. Si elegís **Base de datos**, completá origen y destino; pueden apuntar al mismo servidor con dos bases distintas o a servidores diferentes. Al pulsar **Probar conexión**, se cargan las bases visibles para esa cuenta y luego elegís una para cada lado. En Snapshot SQL y Flyway, el panel de conexión del origen se reemplaza por la selección de una carpeta; se conecta solo la base destino. En esta versión, ambos extremos usan el mismo motor. Los perfiles guardados se pueden elegir por separado para origen y destino en el modo de bases. Guardan el motor, servidor, puerto, base, usuario y configuración TLS en `~/.db-diff-studio/connections.properties` para conservar los perfiles existentes. Si activás **Recordar contraseña**, se guarda en el almacén seguro del sistema operativo; si no, hay que ingresarla en cada sesión. TLS se puede habilitar por conexión.

En **Nueva comparación**, elegí el tipo de origen. **Base de datos** muestra los datos de conexión para origen y destino. **Snapshot SQL** oculta la conexión del origen y permite elegir una carpeta con archivos `.sql`; cada archivo debe contener una definición `CREATE` completa de un solo objeto. No es un dump arbitrario ni se ejecuta el SQL. Se leen tablas, vistas, procedimientos, funciones y triggers. Los archivos se ordenan por ruta, y una segunda definición del mismo objeto se reporta como ambigua.

Para reconstruir el esquema de migraciones Flyway, elegí **Migraciones Flyway** en **Nueva comparación** y seleccioná la carpeta de migraciones versionadas (`V1__...sql`, `V2__...sql`), por ejemplo `src/main/resources/db/migration`; no selecciones la raíz del proyecto si también contiene una copia compilada en `target/classes`. Antes de crear nada, la aplicación pide autorización. Flyway se ejecuta en una base temporal de nombre único creada en el servidor de destino; la aplicación lee el esquema resultante y elimina la base temporal antes de mostrar la comparación. Luego compara ese esquema reconstruido (origen) con la base destino. No modifica directamente la base destino. Revisá las migraciones: SQL que haga referencia explícita a otras bases o servidores podría afectarlos. La cuenta del destino debe tener permisos para crear y eliminar bases de datos. Si la limpieza falla, el error informa el nombre de la base temporal para permitir su revisión manual.

Al conectar, la lista de objetos queda marcada como **sin comparar** hasta que se ejecute el análisis. Usá Ctrl+clic o Mayús+clic para seleccionar objetos. **Comparar** analiza los objetos seleccionados; si no seleccionás ninguno, analiza todos los tipos habilitados. Para comparar filas, seleccioná una tabla que ya se haya comparado. **Exportar SQL** solo incluye los resultados seleccionados; si no seleccionaste ninguno, la aplicación te pide que elijas en vez de exportarlo todo.

## Alcance actual

- Conexiones JDBC a MariaDB, MySQL y SQL Server. SQL Server usa el controlador JDBC oficial de Microsoft y autenticación SQL con usuario y contraseña. Ambos extremos deben usar el mismo motor.
- Listado y selección de tablas, vistas, procedimientos, funciones y triggers DML.
- Comparación de columnas e índices de tablas; comparación de definiciones de vistas y rutinas. En SQL Server, los objetos se distinguen por esquema y nombre.
- Comparación de filas de tablas seleccionadas mediante claves primarias. Las filas se clasifican como exclusivas del origen, exclusivas del destino o diferentes. Se puede exportar `INSERT` y `UPDATE`; `DELETE` requiere habilitarse explícitamente.
- Diferencias de definiciones línea por línea y estado de objetos faltantes.
- DDL basado en `SHOW CREATE` o catálogos del servidor para tablas, vistas, procedimientos, funciones y triggers, si la cuenta tiene permisos. Las tablas y vistas que solo existen en el origen se exportan con `CREATE`; las vistas modificadas, con `CREATE OR REPLACE`; las rutinas y triggers modificados, con scripts revisables de `DROP` y `CREATE`. El exportador ordena por dependencias de claves foráneas, vistas, referencias SQL y triggers cuando el motor expone esos metadatos; los ciclos y referencias no visibles quedan para revisión. Cada exportación selecciona la base de destino y la aplicación nunca la ejecuta.
- La vista previa de exportación lista el orden propuesto y advierte si faltan prerrequisitos, si un objeto destino depende de algo que se va a eliminar, si hay ciclos, o si una sentencia requiere revisión manual. También informa cuántas relaciones encontró cada catálogo; ese número es un recuento, no un porcentaje de cobertura. En MariaDB/MySQL se leen claves foráneas, vistas y triggers de la base seleccionada; referencias de vistas a otras bases se cuentan aparte cuando el catálogo las expone, pero no se usan para ordenar. La visibilidad puede depender de los permisos sobre las vistas y sus objetos, y las referencias de procedimientos y funciones no se consideran completas. En SQL Server se leen claves foráneas, triggers y referencias locales resueltas de `sys.sql_expression_dependencies`; las referencias con `object_id` no resuelto se cuentan aparte, pero no se usan para ordenar. Para consultar todas las dependencias, SQL Server requiere `VIEW DEFINITION` y `SELECT` sobre `sys.sql_expression_dependencies`; la visibilidad de metadatos también depende de los permisos del usuario.
- Los cambios en definiciones de tablas quedan para revisión: la aplicación exporta el `CREATE TABLE` completo del origen, pero no intenta generar una migración `ALTER`.
- Para SQL Server, las definiciones de tablas se reconstruyen desde las vistas de catálogo; los cambios de tabla se dejan para revisión manual. Las vistas, procedimientos y funciones modificados se pueden exportar con `CREATE OR ALTER` (SQL Server 2016 SP1 o posterior) o como `DROP` + `CREATE`. El script usa `GO` para separar lotes y puede incluir una transacción explícita.
- La comparación de filas de SQL Server requiere una clave primaria y tipos comparables. Exporta T-SQL con nombres calificados por base y esquema; inserta columnas `IDENTITY` con `SET IDENTITY_INSERT` cuando hace falta. Los tipos binarios y varios tipos especiales se omiten de la comparación automática.

Los permisos o la versión del servidor pueden impedir leer metadatos o definiciones; en ese caso, la aplicación marca el resultado para revisión en vez de generar DDL ejecutable incompleto. En SQL Server, procedimientos cifrados o sin permisos `VIEW DEFINITION` no muestran su definición. La reconstrucción inicial de tablas no incluye triggers ni funciones definidas por el usuario asociadas a columnas, y las características avanzadas de índices pueden requerir revisión manual. Las exportaciones de rutinas y triggers MySQL/MariaDB usan `DELIMITER` y deben revisarse con un cliente compatible. La carpeta snapshot debe contener definiciones completas, no historiales de migración que requieran reproducir sentencias incrementales. En ambos dialectos, la comparación de filas omite tablas sin clave primaria, esquemas incompatibles y tipos que no se pueden comparar de forma segura. Conserva como máximo 10.000 filas modificadas por comparación; si alcanza ese límite, la exportación lo indica e incluye solo las filas conservadas. La aplicación nunca ejecuta el SQL generado.

## Diseño

La aplicación usa Java 21 y Swing. Maven administra las dependencias JDBC y prepara un JAR ejecutable con sus bibliotecas en `target/lib`. El acceso JDBC y la extracción de objetos se implementan detrás de `DatabaseConnector`; MariaDB y MySQL comparten una implementación y SQL Server tiene su propio conector. La interfaz ejecuta las operaciones de base de datos fuera del hilo de eventos y administra las contraseñas temporales usadas para iniciar sesión.
