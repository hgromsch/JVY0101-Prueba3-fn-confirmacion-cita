package cl.respirarysoltar.confirmacion;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfirmacionCitaHandlerTest {

    @Test
    void devuelveSoloLosMensajesFallidosParaQueVuelvanALaCola() {
        ProcesadorEventoTest.BackendFalso backend = new ProcesadorEventoTest.BackendFalso();
        UUID pacienteId = UUID.randomUUID();
        backend.pacientes.put(pacienteId, new Backend.Paciente(pacienteId, "Ana", "ana@correo.cl"));
        ConfirmacionCitaHandler handler = new ConfirmacionCitaHandler(
                new ProcesadorEvento(backend, new ProcesadorEventoTest.AlmacenFalso()));

        SQSEvent lote = new SQSEvent();
        lote.setRecords(List.of(
                mensaje("ok-1", """
                        {"eventoId":"%s","tipo":"CITA_RESERVADA","citaId":"%s","pacienteId":"%s",
                         "psicologoId":"%s","horario":"2026-12-03T10:30:00","ocurridoEn":"2026-11-22T20:00:00Z"}
                        """.formatted(UUID.randomUUID(), UUID.randomUUID(), pacienteId, UUID.randomUUID())),
                mensaje("malo-1", "{esto no es json")));

        SQSBatchResponse respuesta = handler.handleRequest(lote, null);

        assertEquals(1, respuesta.getBatchItemFailures().size());
        assertEquals("malo-1", respuesta.getBatchItemFailures().get(0).getItemIdentifier());
        assertEquals(1, backend.notificaciones.size());
    }

    private static SQSEvent.SQSMessage mensaje(String id, String cuerpo) {
        SQSEvent.SQSMessage m = new SQSEvent.SQSMessage();
        m.setMessageId(id);
        m.setBody(cuerpo);
        return m;
    }
}
