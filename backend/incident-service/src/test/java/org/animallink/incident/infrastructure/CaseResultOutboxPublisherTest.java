package org.animallink.incident.infrastructure;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CaseResultOutboxPublisherTest {
    @Test
    void retriesOuterScanOnceAfterTransientConnectionFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenThrow(new CannotGetJdbcConnectionException("transient failure"))
                .thenReturn(List.of());

        CaseResultOutboxPublisher publisher = new CaseResultOutboxPublisher(jdbc, rabbit, transactions);

        assertEquals(0, publisher.publishBatch());
        verify(jdbc, times(2)).queryForList(anyString(), eq(String.class));
        verifyNoInteractions(rabbit, transactions);
    }

    @Test
    void propagatesSecondOuterScanConnectionFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        CannotGetJdbcConnectionException first = new CannotGetJdbcConnectionException("first failure");
        CannotGetJdbcConnectionException second = new CannotGetJdbcConnectionException("second failure");
        when(jdbc.queryForList(anyString(), eq(String.class))).thenThrow(first, second);
        CaseResultOutboxPublisher publisher = new CaseResultOutboxPublisher(jdbc, rabbit, transactions);

        CannotGetJdbcConnectionException thrown = assertThrows(
                CannotGetJdbcConnectionException.class,
                publisher::publishBatch);

        assertSame(second, thrown);
        verify(jdbc, times(2)).queryForList(anyString(), eq(String.class));
        verifyNoInteractions(rabbit, transactions);
    }

    @Test
    void scansOnlyOnceWhenInitialScanSucceeds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        CaseResultOutboxPublisher publisher = new CaseResultOutboxPublisher(jdbc, rabbit, transactions);

        assertEquals(0, publisher.publishBatch());

        verify(jdbc).queryForList(anyString(), eq(String.class));
        verifyNoInteractions(rabbit, transactions);
    }
}
