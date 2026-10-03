# atlas-catalogo

Servicio backend (Java + Spring Boot) responsable de mantener la copia local autoritativa de categorías, profesionales y horarios semanales, sincronizada contra el servicio central de la cátedra mediante snapshot REST y actualizaciones incrementales vía Redis y Kafka. Es la única fuente local vigente para búsqueda y filtrado de profesionales, consumida por hermes-app y cronos-turnos mediante un contrato propio protegido con JWT, nunca por acceso directo a su base de datos.

Documentación, contratos y plantillas: [alejandria-docs](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria-/tree/main/alejandria-docs).
