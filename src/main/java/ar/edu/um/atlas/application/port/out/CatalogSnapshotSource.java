package ar.edu.um.atlas.application.port.out;

/** Origen del snapshot completo del catálogo (hoy, la API REST de la cátedra). */
public interface CatalogSnapshotSource {

    /**
     * Pide el snapshot completo.
     *
     * @throws CatalogSourceException si la cátedra no está disponible, rechaza la credencial
     *                                o responde algo que no se puede interpretar
     */
    CatalogSnapshot fetchSnapshot();
}
