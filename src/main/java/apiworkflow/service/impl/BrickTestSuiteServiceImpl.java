package apiworkflow.service.impl;

import apiworkflow.entity.*;
import apiworkflow.exception.ResourceNotFoundException;
import apiworkflow.mapper.*;
import apiworkflow.service.IBrickFlowService;
import apiworkflow.service.IBrickTestSuiteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class BrickTestSuiteServiceImpl implements IBrickTestSuiteService {

    @Autowired
    private BrickTestSuiteMapper testSuiteMapper;

    @Autowired
    private BrickTestSuiteFlowMappingMapper flowMappingMapper;

    @Autowired
    private BrickTestSuiteRunMapper suiteRunMapper;

    @Autowired
    private BrickFlowMapper flowMapper;

    @Autowired
    private BrickFlowRunMapper flowRunMapper;

    @Autowired
    private IBrickFlowService flowService;

    @Override
    @Transactional
    public int createTestSuite(BrickTestSuite suite, List<BrickTestSuiteFlowMapping> flowMappings, String operator) {
        validateSuite(suite, flowMappings);
        String effectiveOperator = operator != null ? operator : "system";
        suite.setIsDeleted(0);
        suite.setCreateBy(effectiveOperator);
        int result = testSuiteMapper.insert(suite);

        prepareMappings(suite.getId(), flowMappings, effectiveOperator);
        flowMappingMapper.batchInsert(flowMappings);

        return result;
    }

    @Override
    @Transactional
    public int updateTestSuite(BrickTestSuite suite, List<BrickTestSuiteFlowMapping> flowMappings, String operator) {
        validateSuite(suite, flowMappings);
        String effectiveOperator = operator != null ? operator : "system";
        suite.setUpdateBy(effectiveOperator);
        int result = testSuiteMapper.updateById(suite);

        flowMappingMapper.deleteBySuiteId(suite.getId(), effectiveOperator);
        prepareMappings(suite.getId(), flowMappings, effectiveOperator);
        flowMappingMapper.batchInsert(flowMappings);

        return result;
    }

    @Override
    public int deleteTestSuite(Integer id, String operator) {
        return testSuiteMapper.softDeleteById(id, operator);
    }

    @Override
    public List<BrickTestSuite> listTestSuitesBySwaggerMappingId(Integer swaggerMappingId, String keyword, int offset, int pageSize) {
        return testSuiteMapper.selectBySwaggerMappingId(swaggerMappingId, keyword, offset, pageSize);
    }

    @Override
    public int countTestSuitesBySwaggerMappingId(Integer swaggerMappingId, String keyword) {
        return testSuiteMapper.countBySwaggerMappingId(swaggerMappingId, keyword);
    }

    @Override
    public Map<String, Object> getTestSuiteDetail(Integer id) {
        BrickTestSuite suite = testSuiteMapper.selectById(id);
        if (suite == null) {
            throw new ResourceNotFoundException("Test suite not found: " + id);
        }
        List<BrickTestSuiteFlowMapping> flowMappings = flowMappingMapper.selectBySuiteId(id);

        List<Integer> flowIds = flowMappings.stream()
                .map(BrickTestSuiteFlowMapping::getFlowId)
                .collect(java.util.stream.Collectors.toList());

        List<BrickFlow> flows = new ArrayList<>();
        if (!flowIds.isEmpty()) {
            Map<Integer, BrickFlow> flowsById = flowMapper.selectByIds(flowIds).stream()
                    .collect(java.util.stream.Collectors.toMap(BrickFlow::getId, flow -> flow));
            for (BrickTestSuiteFlowMapping mapping : flowMappings) {
                BrickFlow flow = flowsById.get(mapping.getFlowId());
                if (flow != null) {
                    flows.add(flow);
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("suite", suite);
        result.put("flows", flows);
        return result;
    }

    @Override
    public BrickTestSuiteRun runTestSuite(Integer id, String operator, Map<String, String> customHeaders) {
        BrickTestSuite suite = testSuiteMapper.selectById(id);
        if (suite == null) {
            throw new ResourceNotFoundException("Test suite not found: " + id);
        }

        List<BrickTestSuiteFlowMapping> flowMappings = flowMappingMapper.selectBySuiteId(id);

        if (flowMappings.isEmpty()) {
            throw new IllegalStateException("Test suite contains no flows: " + id);
        }

        return executeSuite(suite, flowMappings, operator, customHeaders);
    }

    private BrickTestSuiteRun executeSuite(BrickTestSuite suite,
                                            List<BrickTestSuiteFlowMapping> flowMappings,
                                            String operator,
                                            Map<String, String> customHeaders) {
        String effectiveOperator = operator != null ? operator : "system";

        BrickTestSuiteRun suiteRun = new BrickTestSuiteRun();
        suiteRun.setSuiteId(suite.getId());
        suiteRun.setStatus("running");
        suiteRun.setBusinessStatus("not_evaluated");
        suiteRun.setOperator(effectiveOperator);
        suiteRun.setTotalFlows(flowMappings.size());
        suiteRun.setSuccessFlows(0);
        suiteRun.setFailedFlows(0);
        suiteRun.setBusinessPassedFlows(0);
        suiteRun.setBusinessFailedFlows(0);
        suiteRun.setBusinessNotConfiguredFlows(0);
        suiteRun.setBusinessNotEvaluatedFlows(0);
        suiteRun.setStartTime(new Date());
        suiteRunMapper.insert(suiteRun);

        List<String> errors = new ArrayList<>();
        for (BrickTestSuiteFlowMapping mapping : flowMappings) {
            try {
                BrickFlowRun flowRun = flowService.runFlow(
                        mapping.getFlowId(), effectiveOperator, null, 1, customHeaders, suiteRun.getId());
                countFlowResult(suiteRun, flowRun);
                if (!"success".equalsIgnoreCase(flowRun.getStatus()) && flowRun.getErrorMsg() != null) {
                    errors.add("Flow " + mapping.getFlowId() + ": " + flowRun.getErrorMsg());
                }
            } catch (Exception e) {
                suiteRun.setFailedFlows(suiteRun.getFailedFlows() + 1);
                suiteRun.setBusinessNotEvaluatedFlows(suiteRun.getBusinessNotEvaluatedFlows() + 1);
                errors.add("Flow " + mapping.getFlowId() + ": " + rootMessage(e));
            }
        }

        suiteRun.setStatus(suiteRun.getFailedFlows() == 0 ? "success" : "failed");
        suiteRun.setBusinessStatus(resolveSuiteBusinessStatus(suiteRun));
        suiteRun.setEndTime(new Date());
        suiteRun.setDurationMs(suiteRun.getEndTime().getTime() - suiteRun.getStartTime().getTime());
        suiteRun.setErrorMsg(errors.isEmpty() ? null : String.join("; ", errors));
        suiteRunMapper.updateById(suiteRun);

        return suiteRun;
    }

    @Override
    public BrickTestSuiteRun rerunFailedFlows(Long suiteRunId, String operator) {
        BrickTestSuiteRun suiteRun = suiteRunMapper.selectById(suiteRunId);
        if (suiteRun == null) {
            throw new ResourceNotFoundException("Test suite run not found: " + suiteRunId);
        }

        BrickTestSuite suite = testSuiteMapper.selectById(suiteRun.getSuiteId());
        if (suite == null) {
            throw new ResourceNotFoundException("Test suite not found: " + suiteRun.getSuiteId());
        }

        Set<Integer> failedFlowIds = flowRunMapper.selectBySuiteRunId(suiteRunId).stream()
                .filter(this::requiresRerun)
                .map(BrickFlowRun::getFlowId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (failedFlowIds.isEmpty()) {
            throw new IllegalStateException("This test suite run has no failed flows to rerun");
        }

        List<BrickTestSuiteFlowMapping> failedMappings = flowMappingMapper.selectBySuiteId(suite.getId()).stream()
                .filter(mapping -> failedFlowIds.contains(mapping.getFlowId()))
                .collect(java.util.stream.Collectors.toList());
        return executeSuite(suite, failedMappings, operator, null);
    }

    @Override
    public List<Map<String, Object>> listRunsBySwaggerMappingId(Integer testSuiteId, Integer swaggerMappingId,
                                                                   String suiteName, String status,
                                                                   String startDate, String endDate,
                                                                   int offset, int pageSize) {
        return suiteRunMapper.selectRunsByCondition(
                testSuiteId, swaggerMappingId, suiteName, status, startDate, endDate, offset, pageSize);
    }

    @Override
    public int countRunsBySwaggerMappingId(Integer testSuiteId, Integer swaggerMappingId,
                                            String suiteName, String status, String startDate, String endDate) {
        return suiteRunMapper.countRunsByCondition(
                testSuiteId, swaggerMappingId, suiteName, status, startDate, endDate);
    }

    @Override
    public Map<String, Object> getRunDetail(Long runId) {
        BrickTestSuiteRun suiteRun = suiteRunMapper.selectById(runId);
        if (suiteRun == null) {
            throw new ResourceNotFoundException("Test suite run not found: " + runId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("suiteRun", suiteRun);
        result.put("suite", testSuiteMapper.selectById(suiteRun.getSuiteId()));
        result.put("flowRuns", flowRunMapper.selectSummariesBySuiteRunId(runId));
        return result;
    }

    private void validateSuite(BrickTestSuite suite, List<BrickTestSuiteFlowMapping> mappings) {
        if (suite == null) {
            throw new IllegalArgumentException("Test suite is required");
        }
        if (suite.getName() == null || suite.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Test suite name is required");
        }
        if (suite.getSwaggerMappingId() == null) {
            throw new IllegalArgumentException("Swagger mapping is required");
        }
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("Select at least one flow for the test suite");
        }

        Set<Integer> uniqueFlowIds = new HashSet<>();
        for (BrickTestSuiteFlowMapping mapping : mappings) {
            if (mapping == null || mapping.getFlowId() == null) {
                throw new IllegalArgumentException("Every test suite entry must reference a flow");
            }
            if (!uniqueFlowIds.add(mapping.getFlowId())) {
                throw new IllegalArgumentException("A flow can only appear once in a test suite");
            }
        }

        List<BrickFlow> flows = flowMapper.selectByIds(new ArrayList<>(uniqueFlowIds));
        if (flows.size() != uniqueFlowIds.size()) {
            throw new IllegalArgumentException("One or more selected flows do not exist");
        }
        for (BrickFlow flow : flows) {
            if (!suite.getSwaggerMappingId().equals(flow.getSwaggerMappingId())) {
                throw new IllegalArgumentException("All flows must belong to the selected application version");
            }
        }
    }

    private void prepareMappings(Integer suiteId, List<BrickTestSuiteFlowMapping> mappings, String operator) {
        for (int index = 0; index < mappings.size(); index++) {
            BrickTestSuiteFlowMapping mapping = mappings.get(index);
            mapping.setSuiteId(suiteId);
            mapping.setExecutionOrder(index + 1);
            mapping.setIsDeleted(0);
            mapping.setCreateBy(operator);
        }
    }

    private void countFlowResult(BrickTestSuiteRun suiteRun, BrickFlowRun flowRun) {
        if ("success".equalsIgnoreCase(flowRun.getStatus())) {
            suiteRun.setSuccessFlows(suiteRun.getSuccessFlows() + 1);
        } else {
            suiteRun.setFailedFlows(suiteRun.getFailedFlows() + 1);
        }

        String businessStatus = flowRun.getBusinessStatus();
        if ("passed".equalsIgnoreCase(businessStatus)) {
            suiteRun.setBusinessPassedFlows(suiteRun.getBusinessPassedFlows() + 1);
        } else if ("failed".equalsIgnoreCase(businessStatus)) {
            suiteRun.setBusinessFailedFlows(suiteRun.getBusinessFailedFlows() + 1);
        } else if ("not_configured".equalsIgnoreCase(businessStatus)) {
            suiteRun.setBusinessNotConfiguredFlows(suiteRun.getBusinessNotConfiguredFlows() + 1);
        } else {
            suiteRun.setBusinessNotEvaluatedFlows(suiteRun.getBusinessNotEvaluatedFlows() + 1);
        }
    }

    private String resolveSuiteBusinessStatus(BrickTestSuiteRun suiteRun) {
        if (suiteRun.getBusinessFailedFlows() > 0) {
            return "failed";
        }
        if (suiteRun.getBusinessNotEvaluatedFlows() > 0) {
            return "not_evaluated";
        }
        if (suiteRun.getBusinessPassedFlows() > 0) {
            return "passed";
        }
        return "not_configured";
    }

    private boolean requiresRerun(BrickFlowRun run) {
        return !"success".equalsIgnoreCase(run.getStatus())
                || "failed".equalsIgnoreCase(run.getBusinessStatus());
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }
}
