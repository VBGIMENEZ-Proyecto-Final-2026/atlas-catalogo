package ar.edu.um.atlas.application.repository;

import ar.edu.um.atlas.domain.SyncState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SyncStateRepository extends JpaRepository<SyncState, Short> {

    /**
     * Lee la fila única con {@code SELECT ... FOR UPDATE}: la corrida de sync es exclusiva hasta
     * que termina la transacción (ADR 0003). Debe llamarse dentro de una transacción.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SyncState s where s.id = 1")
    SyncState findForUpdate();
}
