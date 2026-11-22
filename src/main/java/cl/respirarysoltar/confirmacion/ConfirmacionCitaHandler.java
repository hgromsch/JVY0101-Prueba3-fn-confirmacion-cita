package cl.respirarysoltar.confirmacion;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Punto de entrada de la Lambda fn-confirmacion-cita (handler:
 * cl.respirarysoltar.confirmacion.ConfirmacionCitaHandler::handleRequest).
 *
 * La invoca el event source mapping de SQS con lotes de hasta 5 mensajes. Devuelve
 * "batchItemFailures" (respuesta parcial): solo los mensajes que fallaron vuelven a la cola,
 * los que salieron bien no se reprocesan.
 */
public class ConfirmacionCitaHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final ProcesadorEvento procesador;

    /** Constructor que usa Lambda: los clientes se crean una vez y se reutilizan entre invocaciones. */
    public ConfirmacionCitaHandler() {
        this(new ProcesadorEvento(
                new BackendHttp(variable("API_URL"), variable("API_KEY"), JSON),
                new AlmacenS3(S3Client.builder()
                        .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                        .httpClient(UrlConnectionHttpClient.create())
                        .build(), variable("BUCKET_COMPROBANTES"))));
    }

    ConfirmacionCitaHandler(ProcesadorEvento procesador) {
        this.procesador = procesador;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent evento, Context contexto) {
        List<SQSBatchResponse.BatchItemFailure> fallidos = new ArrayList<>();
        List<SQSEvent.SQSMessage> mensajes = evento.getRecords() == null ? List.of() : evento.getRecords();
        for (SQSEvent.SQSMessage mensaje : mensajes) {
            long inicio = System.currentTimeMillis();
            try {
                EventoCita cita = JSON.readValue(mensaje.getBody(), EventoCita.class);
                ProcesadorEvento.Resultado resultado = procesador.procesar(cita);
                log("INFO", mensaje, Map.of("resultado", resultado.name(), "tipo", cita.tipo(),
                        "citaId", cita.citaId().toString(), "ms", System.currentTimeMillis() - inicio));
            } catch (Exception e) {
                fallidos.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(mensaje.getMessageId()).build());
                log("ERROR", mensaje, Map.of("error", String.valueOf(e.getMessage()),
                        "intento", String.valueOf(mensaje.getAttributes() == null ? "?" :
                                mensaje.getAttributes().getOrDefault("ApproximateReceiveCount", "?"))));
            }
        }
        return SQSBatchResponse.builder().withBatchItemFailures(fallidos).build();
    }

    /** Logs en JSON (una línea por mensaje) para filtrarlos en CloudWatch Logs Insights. */
    private static void log(String nivel, SQSEvent.SQSMessage mensaje, Map<String, Object> datos) {
        Map<String, Object> linea = new LinkedHashMap<>();
        linea.put("nivel", nivel);
        linea.put("messageId", mensaje.getMessageId());
        linea.putAll(datos);
        try {
            System.out.println(JSON.writeValueAsString(linea));
        } catch (Exception e) {
            System.out.println(linea);
        }
    }

    private static String variable(String nombre) {
        String valor = System.getenv(nombre);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno " + nombre);
        }
        return valor;
    }
}
