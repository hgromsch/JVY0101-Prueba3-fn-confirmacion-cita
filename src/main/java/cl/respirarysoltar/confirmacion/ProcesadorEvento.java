package cl.respirarysoltar.confirmacion;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lógica de la función, sin dependencias de AWS (se prueba con dobles):
 *  1. Idempotencia: SQS entrega "al menos una vez"; si el comprobante del evento ya existe en S3,
 *     el evento ya se procesó y se descarta sin notificar de nuevo.
 *  2. Busca al paciente (ms-identidad) y al psicólogo (ms-psicologos).
 *  3. Notifica al paciente (ms-notificaciones).
 *  4. Guarda el comprobante HTML en S3, que además marca el evento como procesado.
 * Si algo falla se lanza la excepción: el mensaje vuelve a la cola y, tras 3 intentos, a la DLQ.
 */
public class ProcesadorEvento {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", new Locale("es", "CL"));
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    public enum Resultado { PROCESADO, DUPLICADO }

    private final Backend backend;
    private final AlmacenComprobantes almacen;

    public ProcesadorEvento(Backend backend, AlmacenComprobantes almacen) {
        this.backend = backend;
        this.almacen = almacen;
    }

    public static String claveComprobante(EventoCita e) {
        return "comprobantes/%s/%s-%s.html".formatted(e.citaId(), e.tipo(), e.eventoId());
    }

    public Resultado procesar(EventoCita evento) {
        validar(evento);
        String clave = claveComprobante(evento);
        if (almacen.existe(clave)) {
            return Resultado.DUPLICADO;
        }

        Backend.Paciente paciente = backend.buscarPaciente(evento.pacienteId())
                .orElseThrow(() -> new IllegalStateException("Paciente inexistente: " + evento.pacienteId()));
        String psicologo = backend.buscarPsicologo(evento.psicologoId())
                .map(Backend.Psicologo::nombre).orElse("tu psicólogo/a");

        String mensaje = mensaje(evento, paciente, psicologo);
        String notificacionId = backend.notificar(evento.tipo(), paciente.email(), mensaje);

        Map<String, String> metadatos = new LinkedHashMap<>();
        metadatos.put("cita-id", evento.citaId().toString());
        metadatos.put("evento-id", evento.eventoId().toString());
        metadatos.put("tipo", evento.tipo());
        metadatos.put("notificacion-id", notificacionId);
        almacen.guardar(clave, html(evento, paciente, psicologo, mensaje, notificacionId), metadatos);
        return Resultado.PROCESADO;
    }

    private static void validar(EventoCita e) {
        if (e == null || e.eventoId() == null || e.citaId() == null || e.pacienteId() == null
                || e.tipo() == null || e.horario() == null) {
            throw new IllegalArgumentException("Evento de cita incompleto: " + e);
        }
    }

    static String mensaje(EventoCita e, Backend.Paciente p, String psicologo) {
        String cuando = "el " + FECHA.format(e.horario()) + " a las " + HORA.format(e.horario());
        String texto = switch (e.tipo()) {
            case "CITA_RESERVADA" -> "Hola %s: tu cita con %s quedó confirmada para %s.".formatted(p.nombre(), psicologo, cuando);
            case "CITA_REPROGRAMADA" -> "Hola %s: tu cita con %s fue reprogramada para %s.".formatted(p.nombre(), psicologo, cuando);
            case "CITA_CANCELADA" -> "Hola %s: tu cita con %s de %s fue cancelada%s.".formatted(p.nombre(), psicologo, cuando,
                    e.motivo() == null ? "" : " (motivo: " + e.motivo() + ")");
            default -> "Hola %s: hay una actualización de tu cita con %s (%s).".formatted(p.nombre(), psicologo, e.tipo());
        };
        texto += " Comprobante N° " + e.citaId().toString().substring(0, 8).toUpperCase() + ". Respirar y Soltar.";
        return texto.length() > 500 ? texto.substring(0, 497) + "..." : texto;
    }

    static String html(EventoCita e, Backend.Paciente p, String psicologo, String mensaje, String notificacionId) {
        return """
                <!doctype html>
                <html lang="es"><head><meta charset="utf-8"><title>Comprobante de cita</title>
                <style>body{font-family:Arial,sans-serif;max-width:560px;margin:40px auto;color:#2d2a3e}
                h1{color:#7b5ea7;font-size:22px}td{padding:6px 10px;border-bottom:1px solid #e6e0f0}
                .pie{color:#888;font-size:12px;margin-top:24px}</style></head>
                <body><h1>Respirar y Soltar · Comprobante de cita</h1>
                <p>%s</p>
                <table>
                <tr><td>Evento</td><td>%s</td></tr>
                <tr><td>Paciente</td><td>%s</td></tr>
                <tr><td>Psicólogo/a</td><td>%s</td></tr>
                <tr><td>Fecha y hora</td><td>%s %s</td></tr>
                <tr><td>Cita</td><td>%s</td></tr>
                <tr><td>Notificación</td><td>%s</td></tr>
                </table>
                <p class="pie">Generado por fn-confirmacion-cita (AWS Lambda) el %s · evento %s</p>
                </body></html>
                """.formatted(esc(mensaje), esc(e.tipo()), esc(p.nombre()), esc(psicologo),
                FECHA.format(e.horario()), HORA.format(e.horario()), e.citaId(), esc(notificacionId),
                Instant.now(), e.eventoId());
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
