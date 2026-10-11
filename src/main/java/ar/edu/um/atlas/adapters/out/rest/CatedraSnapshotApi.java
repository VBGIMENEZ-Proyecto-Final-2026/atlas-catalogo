package ar.edu.um.atlas.adapters.out.rest;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** Cliente declarativo de la API REST de la cátedra (ADR 0002, D10). Solo el snapshot. */
@HttpExchange
interface CatedraSnapshotApi {

    @GetExchange("/api/synchronization/snapshot")
    SnapshotResponse snapshot();
}
