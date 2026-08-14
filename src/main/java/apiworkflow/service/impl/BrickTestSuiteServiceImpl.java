package apiworkflow.service.impl;

import apiworkflow.entity.*;
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
    private IBrickFlowService flowService;

    @Override
    @Transactional
    public int createTestSuite(BrickTestSuite suite, List<BrickTestSuiteFlowMapping> flowMappings, String operator) {
        suite.setIsDeleted(0);
        suite.setCreateBy(operator);
        int result = testSuiteMapper.insert(suite);

        if (flowMappings != null && !flowMappings.isEmpty()) {
            for (BrickTestSuiteFlowMapping mapping : flowMappings) {
                mapping.setSuiteId(suite.getId());
                mapping.setIsDeleted(0);
                mapping.setCreateBy(operator);
            }
            flowMappingMapper.batchInsert(flowMappings);
        }

        return result;
    }

    @Override
    @Transactional
    public int updateTestSuite(BrickTestSuite suite, List<BrickTestSuiteFlowMapping> flowMappings, String operator) {
        suite.setUpdateBy(operator);
        int result = testSuiteMapper.updateById(suite);

        flowMappingMapper.deleteBySuiteId(suite.getId(), operator);
        if (flowMappings != null && !flowMappings.isEmpty()) {
            for (BrickTestSuiteFlowMapping mapping : flowMappings) {
                mapping.setSuiteId(suite.getId());
                mapping.setIsDeleted(0);
                mapping.setCreateBy(operator);
            }
            flowMappingMapper.batchInsert(flowMappings);
        }

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
        List<BrickTestSuiteFlowMapping> flowMappings = flowMappingMapper.selectBySuiteId(id);

        List<Integer> flowIds = flowMappings.stream()
                .map(BrickTestSuiteFlowMapping::getFlowId)
                .collect(java.util.stream.Collectors.toList());

        List<BrickFlow> flows = new ArrayList<>();
        if (!flowIds.isEmpty()) {
            flows = new ArrayList<>();
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
            throw new RuntimeException("Test suite not found: " + id);
        }

        List<BrickTestSuiteFlowMapping> flowMappings = flowMappingMapper.selectBySuiteId(id);

        BrickTestSuiteRun suiteRun = new BrickTestSuiteRun();
        suiteRun.setSuiteId(id);
        suiteRun.setStatus("running");
        suiteRun.setOperator(operator);
        suiteRun.setTotalFlows(flowMappings.size());
        suiteRun.setSuccessFlows(0);
        suiteRun.setFailedFlows(0);
        suiteRun.setStartTime(new Date());
        suiteRunMapper.insert(suiteRun);

        for (BrickTestSuiteFlowMapping mapping : flowMappings) {
            try {
                flowService.runFlow(mapping.getFlowId(), operator, null, 0, customHeaders, suiteRun.getId());
                suiteRun.setSuccessFlows(suiteRun.getSuccessFlows() + 1);
            } catch (Exception e) {
                suiteRun.setFailedFlows(suiteRun.getFailedFlows() + 1);
            }
        }

        suiteRun.setStatus(suiteRun.getFailedFlows() == 0 ? "success" : "failed");
        suiteRun.setEndTime(new Date());
        suiteRunMapper.updateById(suiteRun);

        return suiteRun;
    }

    @Override
    public BrickTestSuiteRun rerunFailedFlows(Long suiteRunId, String operator) {
        BrickTestSuiteRun suiteRun = suiteRunMapper.selectById(suiteRunId);
        if (suiteRun == null) {
            throw new RuntimeException("Test suite run not found: " + suiteRunId);
        }

        return runTestSuite(suiteRun.getSuiteId(), operator, null);
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
        Map<String, Object> result = new HashMap<>();
        result.put("suiteRun", suiteRun);
        return result;
    }

    @Override
    public Map<String, Object> checkAndFixInterruption(Long runId) {
        Map<String, Object> result = new HashMap<>();
        result.put("interrupted", false);
        return result;
    }

    @Override
    public Map<String, Object> getFlowResultsSummary(Long runId) {
        Map<String, Object> result = new HashMap<>();
        result.put("total", 0);
        result.put("success", 0);
        result.put("failed", 0);
        return result;
    }
}