package apiworkflow.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class BatchHarImportRequest implements Serializable {
    private List<String> harFileUrl;
    private List<HarFileUpload> harFiles;
    private Integer swaggerMappingId;
    private Long tokenConfigId;
    private Integer entryIndex;
    private Integer maxEntries;
    private Integer dedup;
    private Integer importAsTemplate;
    private String operator;

    @Data
    public static class HarFileUpload {
        private String fileName;
        private String content;
    }
}