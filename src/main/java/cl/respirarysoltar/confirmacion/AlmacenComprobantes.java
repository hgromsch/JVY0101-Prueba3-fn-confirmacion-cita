package cl.respirarysoltar.confirmacion;

/** Almacenamiento de comprobantes (Amazon S3 en la nube). */
public interface AlmacenComprobantes {

    boolean existe(String clave);

    void guardar(String clave, String html, java.util.Map<String, String> metadatos);
}
