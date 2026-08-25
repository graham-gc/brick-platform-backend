package apiworkflow.execution;

import apiworkflow.dto.GlobalVariableDataSourceDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GlobalVariableDataSourceRegistry {

    private final Map<String, DataSource> dataSources;

    @Autowired
    public GlobalVariableDataSourceRegistry(DataSource primaryDataSource,
                                            Map<String, DataSource> registeredDataSources) {
        this.dataSources = new LinkedHashMap<>();
        this.dataSources.put("primary", primaryDataSource);
        if (registeredDataSources != null) {
            for (Map.Entry<String, DataSource> entry : registeredDataSources.entrySet()) {
                if (entry.getValue() != primaryDataSource && StringUtils.hasText(entry.getKey())) {
                    this.dataSources.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    public DataSource require(String id) {
        String effectiveId = StringUtils.hasText(id) ? id.trim() : "primary";
        DataSource dataSource = dataSources.get(effectiveId);
        if (dataSource == null) {
            throw new IllegalArgumentException("Configured database source does not exist: " + effectiveId);
        }
        return dataSource;
    }

    public List<GlobalVariableDataSourceDescriptor> list() {
        List<GlobalVariableDataSourceDescriptor> result = new ArrayList<>();
        for (String id : dataSources.keySet()) {
            result.add(new GlobalVariableDataSourceDescriptor(
                    id, "primary".equals(id) ? "Primary application database" : id));
        }
        result.sort(Comparator.comparing(GlobalVariableDataSourceDescriptor::getId));
        return result;
    }
}
