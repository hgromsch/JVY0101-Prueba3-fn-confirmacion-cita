package cl.respirarysoltar.confirmacion;

import java.util.Optional;
import java.util.UUID;

/** Lo que la función necesita de los microservicios (siempre a través del API Gateway). */
public interface Backend {

    record Paciente(UUID id, String nombre, String email) {
    }

    record Psicologo(UUID id, String nombre, String especialidad) {
    }

    /** GET /identidad/api/usuarios/{id}; vacío si el paciente no existe (404). */
    Optional<Paciente> buscarPaciente(UUID id);

    /** GET /psicologos/api/psicologos/{id}; vacío si no existe. */
    Optional<Psicologo> buscarPsicologo(UUID id);

    /** POST /notificaciones/api/notificaciones/eventos; devuelve el id de la notificación creada. */
    String notificar(String evento, String destinatario, String mensaje);
}
