package apiworkflow.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class SwaggerSyncLog implements Serializable {
    private Long id;
    private Integer swaggerMappingId;
    private String syncType;
    private String syncStatus;
    private Date startTime;
    private Date endTime;
    private Long durationMs;
    private Integer interfacesBefore;
    private Integer interfacesAfter;
    private Integer interfacesAdded;
    private Integer interfacesUpdated;
    private Integer interfacesDeleted;
    private String gitMergeBranches;
    private String createBy;
    private Date createTime;
}
