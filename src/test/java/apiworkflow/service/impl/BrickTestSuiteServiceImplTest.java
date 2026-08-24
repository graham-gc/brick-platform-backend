package apiworkflow.service.impl;

import apiworkflow.entity.BrickFlow;
import apiworkflow.entity.BrickFlowRun;
import apiworkflow.entity.BrickTestSuite;
import apiworkflow.entity.BrickTestSuiteFlowMapping;
import apiworkflow.entity.BrickTestSuiteRun;
import apiworkflow.mapper.BrickFlowMapper;
import apiworkflow.mapper.BrickFlowRunMapper;
import apiworkflow.mapper.BrickTestSuiteFlowMappingMapper;
import apiworkflow.mapper.BrickTestSuiteMapper;
import apiworkflow.mapper.BrickTestSuiteRunMapper;
import apiworkflow.service.IBrickFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrickTestSuiteServiceImplTest {

    @Mock private BrickTestSuiteMapper testSuiteMapper;
    @Mock private BrickTestSuiteFlowMappingMapper flowMappingMapper;
    @Mock private BrickTestSuiteRunMapper suiteRunMapper;
    @Mock private BrickFlowMapper flowMapper;
    @Mock private BrickFlowRunMapper flowRunMapper;
    @Mock private IBrickFlowService flowService;

    private BrickTestSuiteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BrickTestSuiteServiceImpl();
        ReflectionTestUtils.setField(service, "testSuiteMapper", testSuiteMapper);
        ReflectionTestUtils.setField(service, "flowMappingMapper", flowMappingMapper);
        ReflectionTestUtils.setField(service, "suiteRunMapper", suiteRunMapper);
        ReflectionTestUtils.setField(service, "flowMapper", flowMapper);
        ReflectionTestUtils.setField(service, "flowRunMapper", flowRunMapper);
        ReflectionTestUtils.setField(service, "flowService", flowService);
    }

    @Test
    void createsSuiteWithStableExecutionOrder() {
        BrickTestSuite suite = suite();
        when(flowMapper.selectByIds(any())).thenReturn(Arrays.asList(flow(10), flow(20)));
        when(testSuiteMapper.insert(suite)).thenAnswer(invocation -> {
            suite.setId(7);
            return 1;
        });
        List<BrickTestSuiteFlowMapping> mappings = Arrays.asList(mapping(20), mapping(10));

        service.createTestSuite(suite, mappings, "graham");

        ArgumentCaptor<List<BrickTestSuiteFlowMapping>> captor = mappingListCaptor();
        verify(flowMappingMapper).batchInsert(captor.capture());
        assertEquals(Integer.valueOf(1), captor.getValue().get(0).getExecutionOrder());
        assertEquals(Integer.valueOf(2), captor.getValue().get(1).getExecutionOrder());
        assertEquals(Integer.valueOf(7), captor.getValue().get(0).getSuiteId());
    }

    @Test
    void rejectsSuiteWithoutFlows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createTestSuite(suite(), Collections.emptyList(), "graham"));
    }

    @Test
    void aggregatesRequestAndBusinessResultsSeparately() {
        BrickTestSuite suite = suite();
        suite.setId(7);
        when(testSuiteMapper.selectById(7)).thenReturn(suite);
        when(flowMappingMapper.selectBySuiteId(7)).thenReturn(Arrays.asList(mapping(10), mapping(20), mapping(30)));
        when(suiteRunMapper.insert(any())).thenAnswer(invocation -> {
            ((BrickTestSuiteRun) invocation.getArgument(0)).setId(99L);
            return 1;
        });
        when(flowService.runFlow(eq(10), eq("graham"), isNull(), eq(1), isNull(), eq(99L)))
                .thenReturn(flowRun(10, "success", "passed"));
        when(flowService.runFlow(eq(20), eq("graham"), isNull(), eq(1), isNull(), eq(99L)))
                .thenReturn(flowRun(20, "success", "failed"));
        when(flowService.runFlow(eq(30), eq("graham"), isNull(), eq(1), isNull(), eq(99L)))
                .thenReturn(flowRun(30, "failed", "not_evaluated"));

        BrickTestSuiteRun result = service.runTestSuite(7, "graham", null);

        assertEquals("failed", result.getStatus());
        assertEquals("failed", result.getBusinessStatus());
        assertEquals(Integer.valueOf(2), result.getSuccessFlows());
        assertEquals(Integer.valueOf(1), result.getFailedFlows());
        assertEquals(Integer.valueOf(1), result.getBusinessPassedFlows());
        assertEquals(Integer.valueOf(1), result.getBusinessFailedFlows());
        assertEquals(Integer.valueOf(1), result.getBusinessNotEvaluatedFlows());
        verify(suiteRunMapper).updateById(result);
    }

    @Test
    void rerunsOnlyRequestOrBusinessFailures() {
        BrickTestSuiteRun originalSuiteRun = new BrickTestSuiteRun();
        originalSuiteRun.setId(91L);
        originalSuiteRun.setSuiteId(7);
        when(suiteRunMapper.selectById(91L)).thenReturn(originalSuiteRun);
        BrickTestSuite suite = suite();
        suite.setId(7);
        when(testSuiteMapper.selectById(7)).thenReturn(suite);
        when(flowRunMapper.selectBySuiteRunId(91L)).thenReturn(Arrays.asList(
                flowRun(10, "success", "passed"),
                flowRun(20, "success", "failed"),
                flowRun(30, "failed", "not_evaluated")
        ));
        when(flowMappingMapper.selectBySuiteId(7)).thenReturn(Arrays.asList(mapping(10), mapping(20), mapping(30)));
        when(suiteRunMapper.insert(any())).thenAnswer(invocation -> {
            ((BrickTestSuiteRun) invocation.getArgument(0)).setId(92L);
            return 1;
        });
        when(flowService.runFlow(eq(20), eq("graham"), isNull(), eq(1), isNull(), eq(92L)))
                .thenReturn(flowRun(20, "success", "passed"));
        when(flowService.runFlow(eq(30), eq("graham"), isNull(), eq(1), isNull(), eq(92L)))
                .thenReturn(flowRun(30, "success", "not_configured"));

        BrickTestSuiteRun result = service.rerunFailedFlows(91L, "graham");

        assertEquals(Integer.valueOf(2), result.getTotalFlows());
        assertEquals(Integer.valueOf(2), result.getSuccessFlows());
    }

    @Test
    void runDetailIncludesSuiteAndChildFlowRuns() {
        BrickTestSuiteRun suiteRun = new BrickTestSuiteRun();
        suiteRun.setId(91L);
        suiteRun.setSuiteId(7);
        BrickTestSuite suite = suite();
        suite.setId(7);
        List<Map<String, Object>> flowRuns = Collections.singletonList(Collections.singletonMap("flowId", 10));
        when(suiteRunMapper.selectById(91L)).thenReturn(suiteRun);
        when(testSuiteMapper.selectById(7)).thenReturn(suite);
        when(flowRunMapper.selectSummariesBySuiteRunId(91L)).thenReturn(flowRuns);

        Map<String, Object> detail = service.getRunDetail(91L);

        assertEquals(suiteRun, detail.get("suiteRun"));
        assertEquals(suite, detail.get("suite"));
        assertEquals(flowRuns, detail.get("flowRuns"));
    }

    private BrickTestSuite suite() {
        BrickTestSuite suite = new BrickTestSuite();
        suite.setName("Checkout regression");
        suite.setSwaggerMappingId(3);
        suite.setEnv("dev");
        return suite;
    }

    private BrickFlow flow(int id) {
        BrickFlow flow = new BrickFlow();
        flow.setId(id);
        flow.setSwaggerMappingId(3);
        return flow;
    }

    private BrickTestSuiteFlowMapping mapping(int flowId) {
        BrickTestSuiteFlowMapping mapping = new BrickTestSuiteFlowMapping();
        mapping.setFlowId(flowId);
        return mapping;
    }

    private BrickFlowRun flowRun(int flowId, String status, String businessStatus) {
        BrickFlowRun run = new BrickFlowRun();
        run.setFlowId(flowId);
        run.setStatus(status);
        run.setBusinessStatus(businessStatus);
        return run;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<BrickTestSuiteFlowMapping>> mappingListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
