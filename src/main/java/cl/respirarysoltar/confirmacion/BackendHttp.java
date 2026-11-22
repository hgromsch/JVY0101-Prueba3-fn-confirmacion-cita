package cl.respirarysoltar.confirmacion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cliente HTTP hacia los microservicios por el API Gateway (con su propia API key).
 * La función no entra directo al clúster: pasa por la misma puerta protegida que cualquier cliente.
 */
public class BackendHttp implements Backend {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String apiUrl;
    private final String apiKey;

    public BackendHttp(String apiUrl, String apiKey, ObjectMapper json) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.apiKey = apiKey;
        this.json = json;
    }

    @Override
    public Optional<Paciente> buscarPaciente(UUID id) {
        return get("/identidad/api/usuarios/" + id)
                .map(n -> new Paciente(id, n.path("nombre").asText(), n.path("email").asText()));
    }

    @Override
    public Optional<Psicologo> buscarPsicologo(UUID id) {
        return get("/psicologos/api/psicologos/" + id)
                .map(n -> new Psicologo(id, n.path("nombre").asText(), n.path("especialidad").asText()));
    }

    @Override
    public String notificar(String evento, String destinatario, String mensaje) {
        try {
            String cuerpo = json.writeValueAsString(Map.of("evento", evento, "destinatario", destinatario, "mensaje", mensaje));
            HttpResponse<String> r = http.send(peticion("/notificaciones/api/notificaciones/eventos")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new ErrorBackend("ms-notificaciones respondió " + r.statusCode() + ": " + r.body());
            }
            return json.readTree(r.body()).path("id").asText();
        } catch (IOException e) {
            throw new ErrorBackend("No se pudo contactar a ms-notificaciones: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrorBackend("Interrumpido", e);
        }
    }

    private Optional<JsonNode> get(String ruta) {
        try {
            HttpResponse<String> r = http.send(peticion(ruta).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                return Optional.empty();
            }
            if (r.statusCode() / 100 != 2) {
                throw new ErrorBackend("GET " + ruta + " respondió " + r.statusCode());
            }
            return Optional.of(json.readTree(r.body()));
        } catch (IOException e) {
            throw new ErrorBackend("No se pudo contactar " + ruta + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrorBackend("Interrumpido", e);
        }
    }

    private HttpRequest.Builder peticion(String ruta) {
        return HttpRequest.newBuilder(URI.create(apiUrl + ruta))
                .timeout(Duration.ofSeconds(8))
                .header("x-api-key", apiKey)
                .header("User-Agent", "fn-confirmacion-cita");
    }

    /** Error transitorio del backend: el mensaje vuelve a la cola y se reintenta. */
    public static class ErrorBackend extends RuntimeException {
        public ErrorBackend(String mensaje) {
            super(mensaje);
        }

        public ErrorBackend(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
