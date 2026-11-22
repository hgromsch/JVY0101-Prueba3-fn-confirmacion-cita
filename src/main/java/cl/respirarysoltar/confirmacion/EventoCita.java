package cl.respirarysoltar.confirmacion;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Mensaje que publica ms-agenda en la cola rys-citas-eventos (mismo contrato JSON). */
public record EventoCita(
        UUID eventoId,
        String tipo,
        UUID citaId,
        UUID pacienteId,
        UUID psicologoId,
        LocalDateTime horario,
        LocalDateTime horarioAnterior,
        String motivo,
        Instant ocurridoEn
) {
}
