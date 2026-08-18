package apiworkflow.execution;

import apiworkflow.entity.BrickFlowNodeAssertion;
import apiworkflow.entity.BrickFlowRunNodeAssertion;
import apiworkflow.mapper.BrickFlowNodeAssertionMapper;
import apiworkflow.mapper.BrickFlowRunNodeAssertionMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class AssertionExecutor {

    @Autowired
    private BrickFlowNodeAssertionMapper assertionMapper;

    @Autowired
    private BrickFlowRunNodeAssertionMapper runAssertionMapper;

    public static class AssertionResult {
        public int total;
        public int passed;
        public int failed;
        public List<BrickFlowRunNodeAssertion> runAssertions = new ArrayList<>();

        public AssertionResult(int total, int passed, int failed, List<BrickFlowRunNodeAssertion> runAssertions) {
            this.total = total;
            this.passed = passed;
            this.failed = failed;
            this.runAssertions = runAssertions;
        }
    }

    @Transactional
    public AssertionResult executeAssertions(Long runNodeId, Long nodeId,
            Integer httpStatus, String responseBody,
            Map<String, List<String>> responseHeaders, Long durationMs) {

        List<BrickFlowNodeAssertion> assertions = assertionMapper.selectByNodeId(nodeId);
        List<BrickFlowRunNodeAssertion> runAssertions = new ArrayList<>();
        int passed = 0;
        int failed = 0;

        for (BrickFlowNodeAssertion assertion : assertions) {
            if (assertion.getIsEnabled() == null || assertion.getIsEnabled() != 1) {
                continue;
            }

            BrickFlowRunNodeAssertion runAssertion = executeAssertion(runNodeId, assertion,
                    httpStatus, responseBody, responseHeaders, durationMs);
            runAssertion.setCreateTime(new Date());
            runAssertions.add(runAssertion);

            if ("passed".equals(runAssertion.getStatus())) {
                passed++;
            } else {
                failed++;
            }
        }

        if (!runAssertions.isEmpty()) {
            runAssertionMapper.batchInsert(runAssertions);
        }

        return new AssertionResult(runAssertions.size(), passed, failed, runAssertions);
    }

    private BrickFlowRunNodeAssertion executeAssertion(Long runNodeId, BrickFlowNodeAssertion assertion,
            Integer httpStatus, String responseBody,
            Map<String, List<String>> responseHeaders, Long durationMs) {

        BrickFlowRunNodeAssertion runAssertion = new BrickFlowRunNodeAssertion();
        runAssertion.setRunNodeId(runNodeId);
        runAssertion.setAssertionId(assertion.getId());
        runAssertion.setExpectedValue(assertion.getExpectedValue());

        String assertionType = assertion.getAssertionType();
        String operator = assertion.getOperator();
        String fieldPath = assertion.getFieldPath();
        String expectedValue = assertion.getExpectedValue();

        runAssertion.setAssertionType(assertionType);
        runAssertion.setFieldPath(fieldPath);
        runAssertion.setOperator(operator);

        String actualValue = null;
        boolean passed = false;

        try {
            switch (assertionType) {
                case "status_code":
                    actualValue = String.valueOf(httpStatus);
                    passed = compareByType(httpStatus, operator, expectedValue);
                    break;

                case "json_path":
                    Object extracted = JsonPath.read(responseBody, fieldPath);
                    if (extracted == null) {
                        runAssertion.setErrorMsg("JSONPath '" + fieldPath + "' returned null");
                    } else {
                        passed = compareByType(extracted, operator, expectedValue);
                        actualValue = String.valueOf(extracted);
                    }
                    break;

                case "header":
                    List<String> headerValues = responseHeaders.get(fieldPath);
                    if (headerValues != null && !headerValues.isEmpty()) {
                        actualValue = headerValues.get(0);
                        passed = compareString(actualValue, operator, expectedValue);
                    } else {
                        runAssertion.setErrorMsg("Header '" + fieldPath + "' not found in response");
                    }
                    break;

                case "response_time":
                    actualValue = String.valueOf(durationMs);
                    passed = compareByType(durationMs, operator, expectedValue);
                    break;

                default:
                    runAssertion.setErrorMsg("Unknown assertion type: " + assertionType);
            }
        } catch (Exception e) {
            runAssertion.setErrorMsg(e.getMessage());
            actualValue = null;
        }

        runAssertion.setActualValue(actualValue);
        runAssertion.setStatus(passed ? "passed" : "failed");

        return runAssertion;
    }

    private boolean compareByType(Object actual, String operator, String expected) {
        if (actual == null) {
            return false;
        }

        // Determine actual value type and parse expected accordingly
        if (actual instanceof Number) {
            // Numeric comparison
            Number actualNum = (Number) actual;
            Number expectedNum;
            if (expected != null) {
                try {
                    expectedNum = Double.parseDouble(expected);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else {
                return false;
            }
            return compareNumeric(actualNum, operator, expectedNum);
        } else if (actual instanceof Boolean) {
            // Boolean comparison
            Boolean actualBool = (Boolean) actual;
            Boolean expectedBool = Boolean.parseBoolean(expected);
            return compareBoolean(actualBool, operator, expectedBool);
        } else {
            // String comparison
            String actualStr = String.valueOf(actual);
            return compareString(actualStr, operator, expected);
        }
    }

    private boolean compareString(String actual, String operator, String expected) {
        if (actual == null) {
            return false;
        }
        switch (operator) {
            case "equals":
                return actual.equals(expected);
            case "not_equals":
                return !actual.equals(expected);
            case "contains":
                return actual.contains(expected);
            case "not_contains":
                return !actual.contains(expected);
            case "regex":
                try {
                    return Pattern.matches(expected, actual);
                } catch (PatternSyntaxException e) {
                    return false;
                }
            default:
                // For gt/lt/gte/lte on strings, compare lexicographically
                return false;
        }
    }

    private boolean compareNumeric(Number actual, String operator, Number expected) {
        int cmp = Double.compare(actual.doubleValue(), expected.doubleValue());
        switch (operator) {
            case "equals":
                return cmp == 0;
            case "not_equals":
                return cmp != 0;
            case "gt":
                return cmp > 0;
            case "lt":
                return cmp < 0;
            case "gte":
                return cmp >= 0;
            case "lte":
                return cmp <= 0;
            default:
                return false;
        }
    }

    private boolean compareBoolean(Boolean actual, String operator, Boolean expected) {
        switch (operator) {
            case "equals":
                return actual.equals(expected);
            case "not_equals":
                return !actual.equals(expected);
            default:
                return false;
        }
    }
}
