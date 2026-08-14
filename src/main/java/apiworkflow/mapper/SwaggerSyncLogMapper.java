package apiworkflow.mapper;

import apiworkflow.entity.SwaggerSyncLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SwaggerSyncLogMapper {
    int insert(SwaggerSyncLog record);
}
