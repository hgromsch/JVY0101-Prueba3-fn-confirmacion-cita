# fn-confirmacion-cita — función serverless (AWS Lambda)

Función **FaaS** de Respirar y Soltar (EP3 · JVY0101). Consume la cola **Amazon SQS**
`rys-citas-eventos` y, por cada evento de una cita, **notifica al paciente** y **guarda el comprobante**
en Amazon S3. Autor: Heinrich Gromsch Sanz.

## Requerimiento funcional que resuelve

**RF-04 + RF-10 — confirmación asíncrona de citas.** Cuando un paciente reserva, reprograma o cancela una
cita, debe recibir la confirmación con un comprobante. Hacerlo dentro de `ms-agenda` obligaría a la reserva
a esperar (o fallar) si el envío está lento o caído. Por eso:

1. `ms-agenda` (**productor**) guarda la cita y, *después del commit*, publica el evento en SQS.
2. Esta función (**consumidor**) se activa sola con cada lote de mensajes (event source mapping):
   - busca al paciente en `ms-identidad` y a la psicóloga en `ms-psicologos` (vía API Gateway, con su propia API key),
   - registra la notificación en `ms-notificaciones` (`POST /api/notificaciones/eventos`),
   - guarda el comprobante HTML en `s3://rys-comprobantes-<cuenta>/comprobantes/<citaId>/<tipo>-<eventoId>.html`.

```
ms-agenda ──SendMessage──► SQS rys-citas-eventos ──(lotes de 5)──► Lambda fn-confirmacion-cita
                                 │ 3 intentos fallidos                 │            │
                                 ▼                                     ▼            ▼
                        SQS rys-citas-eventos-dlq            API Gateway → ms-*   Amazon S3
                        (+ alarma CloudWatch)
```

## Buenas prácticas FaaS aplicadas

| Práctica | Cómo |
|---|---|
| Función pequeña y por evento | Solo confirma citas; no reemplaza ningún CRUD |
| **Idempotencia** (SQS entrega "al menos una vez") | Si el comprobante del `eventoId` ya existe en S3, el mensaje se descarta sin notificar de nuevo |
| **Respuesta parcial por lote** (`ReportBatchItemFailures`) | Solo los mensajes que fallan vuelven a la cola |
| Reintentos + **DLQ** | 3 intentos (`maxReceiveCount`) y luego `rys-citas-eventos-dlq` (14 días) con alarma de CloudWatch |
| Concurrencia acotada | `ScalingConfig.MaximumConcurrency: 5` protege al backend de picos |
| Arranque en frío reducido | Clientes creados una vez y reutilizados; cliente HTTP liviano; `TieredStopAtLevel=1` |
| Sin secretos en el código | `API_KEY`, `API_URL` y `BUCKET_COMPROBANTES` llegan como variables de entorno desde CloudFormation |
| Logs estructurados | Una línea JSON por mensaje (resultado, tipo, citaId, ms) en CloudWatch Logs (retención 14 días) |

## Estructura

```
src/main/java/cl/respirarysoltar/confirmacion/
  ConfirmacionCitaHandler.java   ← handler de Lambda (SQSEvent → SQSBatchResponse)
  ProcesadorEvento.java          ← lógica (idempotencia, notificación, comprobante) sin dependencias de AWS
  BackendHttp.java / Backend     ← llamadas a los microservicios por el API Gateway
  AlmacenS3.java / AlmacenComprobantes ← comprobantes en S3
cloudformation/funcion.yaml      ← función, disparador SQS, log group y alarma de la DLQ
scripts/desplegar.sh             ← empaqueta, sube el jar a S3 y despliega con CloudFormation
eventos/                         ← eventos de ejemplo para probar
.github/workflows/ci-cd.yml      ← build → test → despliegue → prueba de humo
```

## Compilar y probar en local

```bash
mvn clean verify          # 6 pruebas unitarias + target/fn-confirmacion-cita-1.0.0.jar (~11 MB)
```

## Desplegar

Requisitos: credenciales de AWS y los stacks `rys-servicios` (cola, S3) y `rys-api` (API Gateway) creados
por el repositorio de infraestructura.

```bash
./scripts/desplegar.sh
```

| Configuración | Valor |
|---|---|
| Runtime / handler | `java21` · `cl.respirarysoltar.confirmacion.ConfirmacionCitaHandler::handleRequest` |
| Memoria / timeout | 512 MB · 20 s (la cola tiene visibilidad de 120 s, ≥ 6 × timeout) |
| Rol | `LabRole` (AWS Academy no permite crear roles) |
| Disparador | SQS `rys-citas-eventos`, lote 5, ventana 1 s, concurrencia máx. 5 |

## Probar la función desplegada

1. **De punta a punta (recomendado):** reservar una cita por el API Gateway y ver el resultado:
   ```bash
   ../rys-infraestructura/scripts/prueba-e2e.sh
   ```
2. **Enviando un evento directo a la cola** (con un `pacienteId` existente):
   ```bash
   COLA=$(aws sqs get-queue-url --queue-name rys-citas-eventos --query QueueUrl --output text)
   aws sqs send-message --queue-url "$COLA" --message-body file://eventos/cita-reservada.json
   aws logs tail /aws/lambda/rys-fn-confirmacion-cita --since 5m --follow
   aws s3 ls s3://rys-comprobantes-<cuenta>/comprobantes/ --recursive
   ```
3. **Invocando la función con un lote SQS simulado:**
   ```bash
   aws lambda invoke --function-name rys-fn-confirmacion-cita --cli-binary-format raw-in-base64-out \
     --payload file://eventos/lote-sqs.json respuesta.json && cat respuesta.json
   ```
   La respuesta `{"batchItemFailures":[...]}` lista los mensajes que volverían a la cola.

## Pipeline CI/CD

| Etapa | Qué hace |
|---|---|
| 1 · Build y pruebas | `mvn verify` y publica el jar como artefacto |
| 2 · Despliegue | Sube el jar a `s3://rys-artefactos-<cuenta>/fn-confirmacion-cita/<commit>.jar` y aplica `cloudformation/funcion.yaml` |
| 3 · Prueba de humo | Invoca la función con un lote vacío y con un evento inválido (debe devolverlo como fallido) |

Secrets: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` (se renuevan en cada sesión del Learner Lab).
