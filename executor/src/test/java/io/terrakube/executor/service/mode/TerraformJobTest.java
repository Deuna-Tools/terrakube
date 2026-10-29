package io.terrakube.executor.service.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TerraformJobTest {

    @Test
    void legacyPayloadDoesNotRequireSavedPlan() throws Exception {
        TerraformJob job = new ObjectMapper().readValue("{\"type\":\"terraformApply\"}", TerraformJob.class);

        assertFalse(job.isRequireSavedPlan());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readsRequireSavedPlanFromExecutorPayload(boolean required) throws Exception {
        TerraformJob job = new ObjectMapper().readValue(
                "{\"type\":\"terraformApply\",\"requireSavedPlan\":" + required + "}", TerraformJob.class);

        assertEquals(required, job.isRequireSavedPlan());
    }

}
