package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class AppSwaggerMapping implements Serializable {
    private Integer id;
    private String appName;
    private String appConfigId;
    private String realName;
    private String swaggerUrl;
    private String env;
    private Integer active;
    private String owner;
    private String extJson;
    private String versionTag;
    private String branchName;
    private String projectUrl;
    private Integer isDeleted;
    private BigDecimal coverageRate;
    private BigDecimal coverageRateNumerator;
    private BigDecimal coverageRateDenominator;
    private BigDecimal last30DaysCoverageRate;
    private Integer coverageRateType;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}