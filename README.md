# DB-Diff-Force

![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.9+-C71A36?logo=apachemaven&logoColor=white)
![MariaDB](https://img.shields.io/badge/MariaDB-compatible-003545?logo=mariadb&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-compatible-4479A1?logo=mysql&logoColor=white)

DB-Diff-Force es una aplicación de escritorio para comparar esquemas de MariaDB y MySQL y exportar SQL para revisar. Se conecta a dos bases, permite elegir tablas, vistas y rutinas, muestra objetos faltantes o modificados y genera SQL para los elementos seleccionados. La aplicación nunca ejecuta el SQL generado.

## Índice

- [Requisitos](#requisitos)
- [Ejecución](#ejecución)
- [Alcance actual](#alcance-actual)
- [Diseño](#diseño)

## Requisitos

- JDK 21 o posterior
- Apache Maven 3.9 o posterior
- Acceso a internet en la primera compilación para que Maven descargue las dependencias; después quedan guardadas en la caché local
- Dos instancias accesibles del motor seleccionado para realizar una comparación

## Ejecución

En Windows, ejecutá `run.bat`. El script compila el JAR ejecutable y abre la aplicación en una ventana independiente. La terminal vuelve a mostrar el prompt mientras la aplicación queda abierta. En macOS o Linux, ejecutá `./run.sh`; el script compila y abre la aplicación. Para compilar sin abrirla, ejecutá `mvn package`: la aplicación queda en `target/db-diff-force-0.2.0.jar`, con los controladores JDBC en `target/lib`. Maven descarga los controladores y sus dependencias a la caché local. Las credenciales no se guardan allí.

En la ventana de conexión, elegí el motor y completá los datos de cada extremo. Al pulsar **Probar conexión**, se verifica el servidor y se cargan las bases visibles para esa cuenta; después elegí una base de la lista. En esta versión, ambos extremos usan el mismo motor. Los perfiles guardados se pueden elegir por separado para el origen y el destino. Guardan el motor, servidor, puerto, base, usuario y configuración TLS en `~/.db-diff-studio/connections.properties` para conservar los perfiles existentes. Las contraseñas nunca se guardan; hay que ingresarlas en cada sesión. TLS se puede habilitar por conexión.

Al conectar, la lista de objetos queda marcada como **sin comparar** hasta que se ejecute el análisis. Usá Ctrl+clic o Mayús+clic para seleccionar objetos. **Comparar** analiza los objetos seleccionados; si no seleccionás ninguno, analiza todos los tipos habilitados. Para comparar filas, seleccioná una tabla que ya se haya comparado. **Exportar SQL** solo incluye los resultados seleccionados; si no seleccionaste ninguno, la aplicación te pide que elijas en vez de exportarlo todo.

## Alcance actual

- Conexiones JDBC a MariaDB y MySQL. MySQL usa el controlador oficial Connector/J y es compatible con MySQL Server 8.0 o posterior.
- Listado y selección de tablas, vistas, procedimientos y funciones.
- Comparación de columnas e índices de tablas; comparación de definiciones de vistas y rutinas.
- Comparación de filas de tablas seleccionadas mediante claves primarias. Las filas se clasifican como exclusivas del origen, exclusivas del destino o diferentes. Se puede exportar `INSERT` y `UPDATE`; `DELETE` requiere habilitarse explícitamente.
- Diferencias de definiciones línea por línea y estado de objetos faltantes.
- DDL basado en `SHOW CREATE` para tablas, vistas, procedimientos y funciones, si la cuenta tiene permisos. Las tablas y vistas que solo existen en el origen se exportan con `CREATE`; las vistas modificadas, con `CREATE OR REPLACE`; las rutinas modificadas, con scripts revisables de `DROP` y `CREATE`. Cada exportación selecciona la base de destino y la aplicación nunca la ejecuta.
- Los cambios en definiciones de tablas quedan para revisión: la aplicación exporta el `CREATE TABLE` completo del origen, pero no intenta generar una migración `ALTER`.

Se pueden agregar SQL Server y otros motores mediante la interfaz `DatabaseConnector`. Por ahora, las comparaciones entre MySQL y MariaDB solo se admiten dentro del mismo motor; no se admiten comparaciones entre motores distintos. Los permisos o la versión del servidor pueden impedir `SHOW CREATE`; en ese caso, la aplicación muestra el error y exporta una nota para revisión, en vez de un DDL incompleto. Las exportaciones de rutinas usan la directiva `DELIMITER` del cliente de la familia MySQL y deben revisarse con un cliente SQL compatible. La comparación de filas omite tablas sin clave primaria, tablas cuyas firmas de columnas no coinciden (incluye `COLUMN_TYPE`, juego de caracteres, intercalación, nulabilidad y precisión de fecha y hora) y tipos que no se pueden ordenar o comparar de forma segura. Lee las filas en orden de clave primaria con cursores del servidor y conserva como máximo 10.000 filas modificadas por comparación; la exportación indica si se alcanzó ese límite e incluye solo las filas conservadas. Se admiten tipos numéricos, texto, fecha y hora, `ENUM` y `SET` habituales. Se omiten tipos binarios, JSON, espaciales y otros tipos no compatibles. El SQL de filas califica cada objeto con la base de destino y la aplicación nunca lo ejecuta.

## Diseño

La aplicación usa Java 21 y Swing. Maven administra las dependencias JDBC y prepara un JAR ejecutable con sus bibliotecas en `target/lib`. El acceso JDBC y la extracción de objetos se implementan detrás de `DatabaseConnector`; `MariaDbConnector` y `MySqlConnector` comparten una implementación para la familia MySQL. La interfaz ejecuta las operaciones de base de datos fuera del hilo de eventos y administra las contraseñas temporales usadas para iniciar sesión.
