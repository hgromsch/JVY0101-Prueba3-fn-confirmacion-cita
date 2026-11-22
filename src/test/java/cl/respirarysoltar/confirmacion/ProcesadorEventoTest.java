package cl.respirarysoltar.confirmacion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcesadorEventoTest {

    /** Dobles en memoria del backend y de S3. */
    static class BackendFalso implements Backend {
        final Map<UUID, Paciente> pacientes = new HashMap<>();
        final List<String[]> notificaciones = new ArrayList<>();
        boolean caido;

        public Optional<Paciente> buscarPaciente(UUID id) { return Optional.ofNullable(pacientes.get(id)); }
        public Optional<Psicologo> buscarPsicologo(UUID id) { return Optional.of(new Psicologo(id, "Ps. Silvana Varela", "Ansiedad")); }
        public String notificar(String evento, String destinatario, String mensaje) {
            if (caido) throw new BackendHttp.ErrorBackend("503");
            notificaciones.add(new String[]{evento, destinatario, mensaje});
            return "notif-" + notificaciones.size();
        }
    }

    static class AlmacenFalso implements AlmacenComprobantes {
        final Map<String, String> objetos = new HashMap<>();
        public boolean existe(String clave) { return objetos.containsKey(clave); }
        public void guardar(String clave, String html, Map<String, String> metadatos) { objetos.put(clave, html); }
    }

    private final BackendFalso backend = new BackendFalso();
    private final AlmacenFalso almacen = new AlmacenFalso();
    private final ProcesadorEvento procesador = new ProcesadorEvento(backend, almacen);
    private final UUID pacienteId = UUID.randomUUID();

    private EventoCita evento(String tipo) {
        return new EventoCita(UUID.randomUUID(), tipo, UUID.randomUUID(), pacienteId, UUID.randomUUID(),
                LocalDateTime.of(2026, 12, 3, 10, 30), null, null, Instant.now());
    }

    @Test
    void citaReservadaNotificaAlPacienteYGuardaElComprobante() {
        backend.pacientes.put(pacienteId, new Backend.Paciente(pacienteId, "Ana Pérez", "ana@correo.cl"));
        EventoCita e = evento("CITA_RESERVADA");

        assertEquals(ProcesadorEvento.Resultado.PROCESADO, procesador.procesar(e));

        assertEquals(1, backend.notificaciones.size());
        assertEquals("ana@correo.cl", backend.notificaciones.get(0)[1]);
        assertTrue(backend.notificaciones.get(0)[2].contains("quedó confirmada para el jueves 3 de diciembre de 2026 a las 10:30"));
        assertTrue(almacen.objetos.get(ProcesadorEvento.claveComprobante(e)).contains("Ana Pérez"));
    }

    @Test
    void unMensajeRepetidoNoNotificaDosVeces() {
        backend.pacientes.put(pacienteId, new Backend.Paciente(pacienteId, "Ana", "ana@correo.cl"));
        EventoCita e = evento("CITA_RESERVADA");

        procesador.procesar(e);
        assertEquals(ProcesadorEvento.Resultado.DUPLICADO, procesador.procesar(e));
        assertEquals(1, backend.notificaciones.size());
    }

    @Test
    void siNotificacionesFallaNoSeMarcaComoProcesadoYSeReintenta() {
        backend.pacientes.put(pacienteId, new Backend.Paciente(pacienteId, "Ana", "ana@correo.cl"));
        backend.caido = true;
        EventoCita e = evento("CITA_RESERVADA");

        assertThrows(BackendHttp.ErrorBackend.class, () -> procesador.procesar(e));
        assertTrue(almacen.objetos.isEmpty());

        backend.caido = false;
        assertEquals(ProcesadorEvento.Resultado.PROCESADO, procesador.procesar(e));
    }

    @Test
    void pacienteInexistenteFallaParaQueElMensajeTermineEnLaDlq() {
        assertThrows(IllegalStateException.class, () -> procesador.procesar(evento("CITA_RESERVADA")));
    }

    @Test
    void cancelacionIncluyeElMotivo() {
        Backend.Paciente p = new Backend.Paciente(pacienteId, "Ana", "ana@correo.cl");
        EventoCita e = new EventoCita(UUID.randomUUID(), "CITA_CANCELADA", UUID.randomUUID(), pacienteId,
                UUID.randomUUID(), LocalDateTime.of(2026, 12, 3, 10, 30), null, "viaje", Instant.now());
        assertTrue(ProcesadorEvento.mensaje(e, p, "Ps. X").contains("fue cancelada (motivo: viaje)"));
    }
}
