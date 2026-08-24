package apiworkflow.execution;

import apiworkflow.entity.BrickFlowNodeAssertion;
import apiworkflow.entity.BrickFlowRunNodeAssertion;
import apiworkflow.mapper.BrickFlowNodeAssertionMapper;
import apiworkflow.mapper.BrickFlowRunNodeAssertionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssertionExecutorTest {

    @Mock
    private BrickFlowNodeAssertionMapper assertionMapper;

    @Mock
    private BrickFlowRunNodeAssertionMapper runAssertionMapper;

    private AssertionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AssertionExecutor();
        ReflectionTestUtils.setField(executor, "assertionMapper", assertionMapper);
        ReflectionTestUtils.setField(executor, "runAssertionMapper", runAssertionMapper);
    }

    @Test
    void persistsAssertionConfigurationSnapshotAndMatchesHeaderNamesCaseInsensitively() {
        BrickFlowNodeAssertion assertion = new BrickFlowNodeAssertion();
        assertion.setId(401L);
        assertion.setNodeId(101L);
        assertion.setAssertionType("header");
        assertion.setFieldPath("X-Trace-Id");
        assertion.setOperator("equals");
        assertion.setExpectedValue("trace-123");
        assertion.setIsEnabled(1);
        when(assertionMapper.selectByNodeId(101L)).thenReturn(Collections.singletonList(assertion));
        when(runAssertionMapper.batchInsert(anyList())).thenReturn(1);

        Map<String, List<String>> responseHeaders = Collections.singletonMap(
                "x-trace-id", Collections.singletonList("trace-123"));
        AssertionExecutor.AssertionResult result = executor.executeAssertions(
                901L, 101L, 200, "{}", responseHeaders, 35L);

        assertEquals(1, result.total);
        assertEquals(1, result.passed);
        assertEquals(0, result.failed);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<BrickFlowRunNodeAssertion>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(runAssertionMapper).batchInsert(captor.capture());
        BrickFlowRunNodeAssertion snapshot = captor.getValue().get(0);
        assertEquals("header", snapshot.getAssertionType());
        assertEquals("X-Trace-Id", snapshot.getFieldPath());
        assertEquals("equals", snapshot.getOperator());
        assertEquals("passed", snapshot.getStatus());
        assertEquals("trace-123", snapshot.getActualValue());
    }
}
