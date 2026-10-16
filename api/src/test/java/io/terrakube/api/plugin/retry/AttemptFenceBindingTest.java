package io.terrakube.api.plugin.retry;

import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.dictionary.Injector;
import io.terrakube.api.rs.job.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AttemptFenceBindingTest {
    @Test
    void exposesTheTransientAttemptForExecutorCallbacks() {
        EntityDictionary dictionary = EntityDictionary.builder().injector(new Injector() {
            public void inject(Object entity) {}
            public <T> T instantiate(Class<T> type) { return mock(type); }
        }).build();
        dictionary.bindEntity(Job.class);
        Job job = new Job();
        assertTrue(dictionary.getAttributes(job).contains("executionAttempt"));
        dictionary.setValue(job, "executionAttempt", 2);
        assertEquals(2, job.getExecutionAttempt());
    }
}
