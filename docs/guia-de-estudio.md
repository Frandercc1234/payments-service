# Guía de estudio: payments-service

Guía para entender y poder **explicar en una entrevista** cada pieza del proyecto. Para cada una: qué hace, dónde está,
cómo se compara con NestJS y preguntas de entrevista con respuesta.

> Convención: las rutas son relativas a `src/main/java/cr/portfolio/payments/` salvo que se indique otra cosa.
> Los números de línea son del momento de escribir esta guía; si el archivo cambió, buscá el nombre del método.

## Mapa mental rápido (NestJS → Spring)

| NestJS | Spring Boot | Idea |
|---|---|---|
| `@Controller`, `@Get`, `@Post` | `@RestController`, `@GetMapping`, `@PostMapping` | recibir HTTP |
| DTO + `class-validator` + `ValidationPipe` | `record` + Jakarta Validation + `@Valid` | validar entrada |
| `@Injectable()` + módulos | `@Service`/`@Component` + *component scan* (no hay módulos) | inyección de dependencias |
| `Repository<T>` de TypeORM / Prisma client | interfaz `JpaRepository` (Spring la implementa en ejecución) | acceso a datos |
| `ExceptionFilter` | `@RestControllerAdvice` | errores uniformes |
| `@nestjs/config` | `application.yml` + `@ConfigurationProperties` | configuración |
| `Test.createTestingModule` + supertest | `@SpringBootTest` + `TestRestTemplate` | pruebas de integración |
| `package.json` + npm | `pom.xml` + Maven (`mvnw`) | dependencias y build |

Diferencia de fondo: Nest corre en un solo hilo de Node con `async/await`; Spring (Tomcat) usa **un hilo por petición**,
con código bloqueante. Por eso aquí la concurrencia real (varios hilos tocando la misma fila) es un problema central y en Node
se manifiesta distinto (varias peticiones intercaladas en el mismo proceso, o varias instancias).

---

## 1. Controladores

**Qué hacen.** Reciben la petición HTTP, validan la forma de la entrada, delegan al servicio y devuelven la respuesta.
No contienen reglas de negocio.

**Dónde.**
- `web/PaymentController.java`: `POST /payments` (exige el header `Idempotency-Key`) y `GET /payments/{id}`.
- `web/AccountController.java`: `POST /accounts`, `GET /accounts/{id}`.
- `web/WebhookController.java`: `POST /webhooks/provider` (recibe el cuerpo como `byte[]`, ver sección 8).
- `web/dto/`: los DTO son `record` de Java (inmutables, con constructor y getters generados).

**Cómo se compara con NestJS.**
- `@RequestBody` ≈ `@Body()`; `@RequestHeader` ≈ `@Headers()`; `@PathVariable` ≈ `@Param()`.
- `@Valid` sobre el cuerpo dispara las anotaciones del `record` (`@NotNull`, `@Positive`…) igual que `ValidationPipe`
  con `class-validator`. El error se convierte en 400 con la lista de campos (`web/ApiExceptionHandler.java`).
- En Nest, `ResponseEntity` no existe: devolvés el objeto y el status por decorador (`@HttpCode`). En Spring `ResponseEntity`
  permite controlar status y headers (acá `Location` e `Idempotent-Replayed`).

**Preguntas de entrevista.**
1. *¿Por qué el controlador no calcula saldos ni abre transacciones?*
   Porque es la capa de transporte: traduce HTTP a llamadas de negocio. Si tuviera lógica, no se podría reutilizar ni probar sin
   levantar HTTP, y la transacción abarcaría el trabajo de serialización. La transacción vive en el servicio.
2. *¿Qué pasa si el JSON trae `"amount": 10.5` y el campo es `long`?*
   Por defecto Jackson lo trunca a `10` sin avisar. En dinero es inaceptable, así que `application.yml` pone
   `accept-float-as-int: false` y la petición se rechaza con 400. Hay una prueba (`decimalAmountIsRejectedInsteadOfTruncated`).
3. *¿Qué hace `@Valid` y qué pasa si falla?*
   Valida el objeto con Jakarta Validation antes de entrar al método; si falla lanza `MethodArgumentNotValidException`, que
   `ApiExceptionHandler` convierte en un `ProblemDetail` 400 con `errors[]`.

---

## 2. Servicios

**Qué hacen.** Contienen los casos de uso: orquestan dominio, repositorios y transacciones.

**Dónde.**
- `service/PaymentService.java`: crea el pago, mueve saldos y escribe el ledger.
- `service/IdempotentPaymentService.java`: envuelve la creación con la idempotencia.
- `service/WebhookService.java`: firma → evento → efecto sobre el pago.
- `service/AccountService.java`, `service/AccountLocks.java`, `service/SignatureVerifier.java`.

**Cómo se compara con NestJS.** `@Service` ≈ `@Injectable()`. La inyección por constructor es idéntica en espíritu; la diferencia es
que Spring descubre los beans escaneando paquetes (no declarás `providers` en un módulo). Las reglas de negocio puras
(`Account.debit`, `PaymentStatus`) están en `domain/`, no en el servicio, para poder probarlas sin Spring.

**Preguntas de entrevista.**
1. *¿Dónde pusiste las reglas de negocio y por qué?*
   Las invariantes (saldo no negativo, moneda igual, transiciones de estado) están en el dominio (`domain/Account`,
   `domain/PaymentStatus`) y se prueban con tests unitarios que corren en milisegundos. El servicio orquesta el orden y la transacción.
2. *¿Por qué `IdempotentPaymentService` llama a `PaymentService` en vez de duplicar la lógica?*
   Separa dos responsabilidades: "cómo se hace un pago" y "cómo se evita repetirlo". Como ambos métodos son `@Transactional` y el
   segundo se une a la transacción del primero, o queda todo (clave + pago + ledger) o no queda nada.
3. *¿Hay una dependencia que no te gusta?*
   Sí, honestamente: `IdempotentPaymentService` importa `CreatePaymentRequest` de `web.dto`, o sea que `service` depende de `web`.
   Lo correcto sería un comando propio del servicio. Es un compromiso de tamaño que reconozco.

---

## 3. Repositorios (Spring Data JPA)

**Qué hacen.** Acceso a datos. Escribís una **interfaz** y Spring genera la implementación en tiempo de ejecución.

**Dónde.** `repository/` (`AccountRepository`, `PaymentRepository`, `LedgerEntryRepository`, `IdempotencyRepository`,
`WebhookEventRepository`). Las entidades JPA están en `domain/` (`Account`, `Payment`, `LedgerEntry`…).

**Cómo se compara con NestJS.**
- `JpaRepository<Account, UUID>` da `save`, `findById`, etc. sin escribir nada, parecido al `Repository<T>` de TypeORM o al
  cliente de Prisma.
- Métodos derivados del nombre (`findByPaymentId`) ≈ `find({ where: { paymentId } })`.
- `@Query` con JPQL o SQL nativo ≈ `createQueryBuilder` / `$queryRaw`.
- Hibernate (la implementación de JPA) lleva un **contexto de persistencia**: las entidades que cargás dentro de una transacción
  se "vigilan" y los cambios se escriben solos al terminar (*dirty checking*). Por eso `account.debit(...)` no llama a `save()`.
  TypeORM/Prisma no hacen esto: ahí siempre hay que guardar explícitamente.

**Preguntas de entrevista.**
1. *Si no llamás a `save()` después de `debit()`, ¿cómo se guarda el saldo?*
   La cuenta se cargó dentro de una transacción y Hibernate la tiene en el contexto de persistencia. Al hacer commit compara
   el estado con el original y emite el `UPDATE`. Fuera de una transacción no pasaría.
2. *¿Por qué `spring.jpa.open-in-view: false`?*
   Con `true` (el valor por defecto) la sesión de Hibernate queda abierta durante toda la petición HTTP, permitiendo cargas
   perezosas desde el controlador y ocultando consultas N+1 y conexiones retenidas. En un servicio de pagos prefiero que todo el
   acceso a datos ocurra, de forma explícita, dentro del servicio transaccional.
3. *¿Por qué `ddl-auto: validate`?*
   Hibernate no crea ni modifica tablas: solo comprueba que las entidades coincidan con el esquema que construyó Flyway. Si no
   coinciden, la aplicación no arranca. Es como tener `synchronize: false` en TypeORM, con una verificación extra.

---

## 4. Transacciones con `@Transactional`

**Qué hace.** Abre una transacción de base de datos al entrar al método y la confirma (commit) al salir; si el método lanza una
`RuntimeException`, la deshace (rollback). Todo lo que ocurra dentro, aunque pase por varios repositorios, es atómico.

**Dónde.**
- `service/PaymentService.java:32`: crear pago (débito, crédito, pago, 2 asientos: todo o nada).
- `service/IdempotentPaymentService.java:41`: reclamar la clave + crear el pago en la misma transacción.
- `service/WebhookService.java:52`: registrar el evento + cambiar el estado + reversión.
- `service/AccountLocks.java:28`: `Propagation.MANDATORY`, exige que ya exista una transacción abierta.

**Cómo se compara con NestJS.** En Nest no hay un decorador estándar equivalente: hacés `dataSource.transaction(async manager => …)`
o un `QueryRunner` y pasás el `manager` a cada llamada a mano. En Spring la transacción se asocia al **hilo actual** y todos los
repositorios la usan automáticamente, sin pasar nada. Se implementa con un **proxy**: Spring envuelve tu bean en un objeto que abre
y cierra la transacción alrededor de tu método.

**Trampas clásicas (muy preguntadas).**
- *Auto-invocación:* si un método de la clase llama a otro método `@Transactional` **de la misma clase**, la llamada no pasa por el
  proxy y no se abre transacción. (Lo evité en `WebhookService`: el único método público transaccional es `handle`.)
- *Qué revierte:* por defecto solo `RuntimeException` y `Error`. Una excepción comprobada (*checked*) hace commit.
- *`readOnly = true`:* permite optimizaciones y comunica la intención, pero no impide escribir por sí solo.

**Preguntas de entrevista.**
1. *¿Qué pasa con el saldo si el crédito al destino falla después de haber debitado al origen?*
   La `RuntimeException` hace rollback de toda la transacción: el débito no queda. Se prueba en
   `PaymentFlowIntegrationTest` (saldo insuficiente / moneda distinta dejan los saldos y el ledger intactos).
2. *¿Por qué la clave de idempotencia y el pago comparten transacción?*
   Para que un intento fallido (por ejemplo, saldo insuficiente) no deje la clave "consumida". Si fueran transacciones separadas,
   el cliente no podría reintentar con la misma clave tras corregir el problema (`failedAttemptDoesNotConsumeTheKey`).
3. *¿Qué es `Propagation.MANDATORY` y por qué lo usás en `AccountLocks`?*
   Obliga a que ya exista una transacción; si no, lanza excepción. Un `SELECT … FOR UPDATE` fuera de una transacción libera el
   bloqueo de inmediato y no protege nada, así que prefiero fallar ruidosamente que tener una protección falsa.

---

## 5. Bloqueo `PESSIMISTIC_WRITE`

**Qué hace.** Traduce a `SELECT … FOR UPDATE`: la fila queda bloqueada hasta que termina la transacción; otra transacción que pida
esa misma fila **espera**. Así dos pagos simultáneos no leen el mismo saldo viejo (el *lost update*).

**Dónde.**
- `repository/AccountRepository.java:22-24`: `findByIdForUpdate` con `@Lock(LockModeType.PESSIMISTIC_WRITE)`.
- `service/AccountLocks.java`: bloquea las dos cuentas **en orden de ID** para evitar deadlocks.
- `repository/PaymentRepository.java:15-17`: mismo bloqueo sobre el pago al procesar webhooks.
- Prueba: `src/test/java/cr/portfolio/payments/ConcurrencyIntegrationTest.java`.

**Cómo se compara con NestJS.** TypeORM: `findOne({ where, lock: { mode: 'pessimistic_write' } })` dentro de una transacción
(o `.setLock('pessimistic_write')`). Prisma no tiene bloqueo de filas nativo: hay que usar `$queryRaw` con `FOR UPDATE` dentro de
`$transaction`. Con una sola instancia de Node podrías creer que no hay carreras por ser un único hilo, pero en cuanto hay dos
instancias (o `await` entre lectura y escritura) el problema es exactamente el mismo.

**Preguntas de entrevista.**
1. *¿Por qué pesimista y no optimista?*
   El optimista (`@Version` + reintentos) asume pocos conflictos. Aquí la prueba de 50 pagos contra una cuenta es contención pura:
   con optimista casi todos fallarían y reintentarían en cascada. Con pesimista cada pago espera su turno y el resultado es
   determinista. El costo: los pagos de una misma cuenta se serializan (una cuenta muy activa es un cuello de botella).
2. *¿Qué es un deadlock y cómo lo evitás?*
   Pago A→B bloquea A y espera B; pago B→A bloquea B y espera A: se esperan para siempre y PostgreSQL aborta uno. Se evita bloqueando
   siempre en el mismo orden global (ID ascendente): ambos piden primero la misma cuenta y uno espera al otro. Hay una prueba con
   40 pagos opuestos simultáneos.
3. *¿Cómo sabés que la prueba de concurrencia de verdad prueba algo?*
   La validé con una mutación: quité `@Lock` y las 6 pruebas de esa clase fallaron; al restaurarlo, pasaron. Una prueba que
   nunca puede fallar no demuestra nada.

---

## 6. Idempotencia

**Qué hace.** `POST /payments` exige `Idempotency-Key`. Misma clave + mismo cuerpo → mismo resultado, sin cobrar de nuevo
(responde con `Idempotent-Replayed: true`). Misma clave + cuerpo distinto → 409.

**Dónde.**
- `service/IdempotentPaymentService.java`: `create` (línea 41), reclamo en la línea 45.
- `repository/IdempotencyRepository.java:22-24`: `INSERT … ON CONFLICT DO NOTHING` (`claim`).
- Migración `src/main/resources/db/migration/V1__create_core_schema.sql`: tabla `idempotency_keys` con la clave como PK.
- Prueba: `src/test/java/cr/portfolio/payments/IdempotencyIntegrationTest.java`.

**Cómo se compara con NestJS.** En Nest suele hacerse con un interceptor y Redis (o una caché en memoria). Aquí la unicidad la
garantiza **PostgreSQL** (PK) dentro de la misma transacción del pago: no hace falta otra pieza de infraestructura y no hay ventana
entre "comprobar" y "guardar".

**Preguntas de entrevista.**
1. *Dos peticiones idénticas llegan al mismo tiempo. ¿Qué pasa?*
   Ambas hacen `INSERT … ON CONFLICT DO NOTHING`. PostgreSQL deja pasar a una y hace esperar a la otra hasta que la primera
   termine: si confirma, la segunda recibe 0 filas y devuelve la respuesta guardada; si hace rollback, la segunda inserta. Hay
   una prueba con 10 peticiones simultáneas: se crea un solo pago.
2. *¿Por qué no `if (existe) … else insertar`?*
   Tiene una carrera entre el `SELECT` y el `INSERT`: dos hilos pueden ver "no existe" a la vez. La restricción única de la base de
   datos es la única fuente de verdad.
3. *¿Cómo decidís que "es el mismo cuerpo"?*
   Con un hash SHA-256 de los campos ya interpretados (origen, destino, monto, moneda), no del texto JSON. Así, reordenar campos o
   cambiar espacios no cuenta como cuerpo distinto (`jsonFieldOrderDoesNotChangeTheRequestIdentity`).
4. *¿Qué limitaciones tiene?* Las claves no caducan y no están ligadas a un cliente; solo se guardan los resultados exitosos
   (ver "Límites" en el README).

---

## 7. Firma HMAC del webhook

**Qué hace.** El proveedor firma el cuerpo con un secreto compartido (HMAC-SHA256) y manda `X-Signature: sha256=<hex>`.
El servicio recalcula la firma y rechaza con 401 si no coincide; nada del cuerpo se interpreta antes de validarla.

**Dónde.**
- `service/SignatureVerifier.java`: cálculo del HMAC (`Mac`) y comparación en tiempo constante (línea 40, `MessageDigest.isEqual`).
- `web/WebhookController.java`: recibe `@RequestBody byte[]` (bytes crudos).
- `service/WebhookService.java:52-`: orden firma → parseo → transacción.
- `config/WebhookProperties.java`: el secreto, desde `app.webhook.secret` / variable `WEBHOOK_SECRET`.
- Prueba: `src/test/java/cr/portfolio/payments/WebhookIntegrationTest.java` (calcula la firma con `javax.crypto` por su cuenta,
  sin reutilizar el código bajo prueba).

**Cómo se compara con NestJS.** `crypto.createHmac('sha256', secret).update(body).digest('hex')` y `crypto.timingSafeEqual` (que exige
buffers del mismo largo o lanza). En Nest necesitás activar `rawBody: true` en `NestFactory.create` para conservar los bytes crudos;
si no, el body ya parseado cambia espacios/orden y la firma deja de coincidir. Aquí lo resolvemos pidiendo `byte[]` desde el inicio.

**Preguntas de entrevista.**
1. *¿Por qué firmar los bytes crudos y no el objeto parseado?*
   Porque la firma cubre el texto exacto que mandó el proveedor. Si lo parseás y volvés a serializar, cualquier diferencia de
   espacios o de orden de campos invalida una firma legítima (o, peor, hace que valides algo distinto de lo que se firmó).
2. *¿Qué es la comparación en tiempo constante y por qué importa?*
   Con `equals`, la comparación termina en el primer byte distinto; midiendo el tiempo de respuesta un atacante puede adivinar la
   firma byte a byte (*timing attack*). `MessageDigest.isEqual` recorre siempre todo.
3. *El proveedor reenvía el mismo evento. ¿Cómo evitás procesarlo dos veces?*
   Cada evento trae un `eventId` que se guarda en `webhook_events` (PK) con `INSERT … ON CONFLICT DO NOTHING`. Si ya existía, responde
   200 `duplicate` sin efectos. Además se bloquea la fila del pago, de modo que dos eventos distintos sobre el mismo pago se procesan
   uno después del otro.
4. *¿Esto protege contra un atacante que reenvía un webhook válido capturado?* Solo parcialmente: el `eventId` repetido no hace nada,
   pero no hay marca de tiempo firmada ni rotación de secretos (está en "Límites").

---

## 8. Flyway

**Qué hace.** Versiona el esquema de la base como archivos SQL numerados que se aplican una sola vez y en orden. Guarda en la tabla
`flyway_schema_history` qué se aplicó y el *checksum* de cada archivo.

**Dónde.** `src/main/resources/db/migration/V1__create_core_schema.sql` (tablas, restricciones `CHECK`, claves únicas e índices).
Configuración en `application.yml` (`spring.flyway.enabled`, `jpa.hibernate.ddl-auto: validate`).

**Cómo se compara con NestJS.** Equivale a las migraciones de TypeORM o a `prisma migrate`. La diferencia cultural: aquí el SQL se
escribe a mano y es la fuente de verdad; Hibernate solo valida. No se usa el equivalente de `synchronize: true`.

**Preguntas de entrevista.**
1. *¿Por qué no editar una migración ya aplicada?*
   Flyway guarda su checksum y, al arrancar, si el archivo cambió, falla. Los cambios van en una migración nueva (`V2__…`). Mientras el
   proyecto no estaba desplegado en ningún lado reemplacé el `V1` provisional; después de eso ya no se toca.
2. *¿Por qué poner `CHECK (balance >= 0)` en la base si ya lo valida el código?*
   Defensa en profundidad: aunque haya un bug, un script manual o un acceso por otra vía, la base no admite saldos negativos.
   Se prueba directamente con SQL en `SchemaIntegrationTest`.
3. *¿Qué pasó con el `V1__baseline.sql` y los dos "V1"?*
   Al reemplazarlo, `mvn verify` sin `clean` dejó el archivo viejo en `target/classes` y Flyway falló con "more than one migration with
   version 1". Es un buen ejemplo de por qué CI siempre compila desde limpio.

---

## 9. Testcontainers

**Qué hace.** Levanta contenedores Docker reales (aquí PostgreSQL 16) durante las pruebas y los elimina al terminar.

**Dónde.** `src/test/java/cr/portfolio/payments/AbstractIntegrationTest.java`: contenedor único compartido, propiedades de conexión con
`@DynamicPropertySource`, `TestRestTemplate` y los helpers `postPayment` / `paymentBody`. Todas las pruebas de integración la extienden.

**Cómo se compara con NestJS.** Existe `testcontainers` para Node con la misma idea. En Spring, `@SpringBootTest` levanta toda la
aplicación (como `Test.createTestingModule(...).compile()` + `app.init()`), y `TestRestTemplate` cumple el papel de `supertest`.

**Preguntas de entrevista.**
1. *¿Por qué PostgreSQL real y no H2?*
   H2 no implementa igual `SELECT … FOR UPDATE`, `ON CONFLICT` ni el aislamiento transaccional: justo lo que este proyecto necesita
   probar. Una prueba verde contra H2 podría ser falsa contra producción.
2. *¿Por qué el contenedor se arranca en un bloque `static` y no con `@Container`?*
   Spring guarda en caché el contexto entre clases de prueba. Con `@Container` el contenedor se detenía al terminar cada clase y el
   contexto cacheado apuntaba a un puerto que ya no existía (me falló así: `CannotGetJdbcConnection`). Un contenedor único para toda
   la ejecución evita eso; Testcontainers lo limpia al terminar la JVM.
3. *¿Cómo se prueba la concurrencia sin que sea inestable?*
   Todos los hilos esperan en un `CountDownLatch` y arrancan a la vez; el resultado esperado es determinista (exactamente 10 de 50),
   no depende del orden. Además se repite 5 veces y un hilo vigía comprueba que el saldo nunca se vea negativo.

---

## Piezas complementarias

- **Libro mayor de doble entrada** (`domain/LedgerEntry.java`, tabla `ledger_entries`): cada pago escribe un débito y un crédito; una
  reversión escribe dos asientos nuevos. Invariante: por pago, `SUM(CREDIT) − SUM(DEBIT) = 0` (verificado en las pruebas con SQL).
- **Máquina de estados** (`domain/PaymentStatus.java`): `PENDING → CONFIRMED | FAILED`; los dos últimos son terminales.
- **Errores RFC 7807** (`web/ApiExceptionHandler.java`): extiende `ResponseEntityExceptionHandler` para que también los errores propios
  de Spring (JSON ilegible, método no permitido, 415…) salgan como `application/problem+json`. El 500 genérico nunca filtra detalles internos.
- **Montos** (`domain/Money.java`): `long` en centavos, positivo; `Math.addExact` evita el desbordamiento silencioso.

## Cómo estudiarlo (sugerencia)

1. Corré `./mvnw verify` y mirá qué prueba corresponde a cada sección de esta guía.
2. Rompé algo a propósito (quitá `@Lock`, quitá `ON CONFLICT`, cambiá `isEqual` por `equals`) y observá qué prueba falla.
3. Explicá en voz alta el recorrido de `POST /payments` desde el controlador hasta el commit, mencionando la transacción y el bloqueo.
