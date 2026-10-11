# Instrucciones para agentes

## Criterios de trabajo

- Preservá el comportamiento existente salvo que el pedido requiera cambiarlo; mantené los cambios acotados y señalá los cambios de lógica relevantes.
- Mantené responsabilidades cohesionadas y respetá la arquitectura existente. Evitá mezclar interfaz, acceso a datos y lógica de comparación.
- Evitá duplicación, pero centralizá solo reglas o valores realmente compartidos; no agregues abstracciones genéricas para casos aislados.
- Comentá decisiones no obvias, invariantes y motivos útiles para mantenimiento; evitá comentarios que solo repitan lo que hace el código.
- Reutilizá las bibliotecas y convenciones visuales existentes antes de implementar estilos manuales.

## Mensajes de commit

- Todos los commits deben seguir el formato Conventional Commits: `<tipo>[alcance opcional]: <descripción>`.
- El tipo puede ser el que corresponda al cambio; no hay una lista cerrada de tipos.
- Para cambios incompatibles, usá `!` después del tipo o alcance y agregá un pie `BREAKING CHANGE:` cuando haga falta explicar el impacto.

Ejemplos:

- `feat(search): add SQL preview search`
- `fix: clear stale object selection`
- `docs: document GPL-3.0-only license`
