package apiworkflow.mapper;

import apiworkflow.entity.AppSwaggerMapping;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AppSwaggerMappingMapper {

    AppSwaggerMapping selectById(Integer id);

    AppSwaggerMapping selectByEnvAndAppConfigId(@Param("env") String env, @Param("appConfigId") String appConfigId);

    AppSwaggerMapping selectByEnvAppConfigAndVersion(@Param("env") String env, @Param("appConfigId") String appConfigId, @Param("versionTag") String versionTag);

    List<AppSwaggerMapping> selectVersionsByEnvAndAppConfig(@Param("env") String env, @Param("appConfigId") String appConfigId);

    int insert(AppSwaggerMapping record);

    int updateById(AppSwaggerMapping record);

    int softDeleteById(@Param("id") Integer id, @Param("updateBy") String updateBy);

    List<AppSwaggerMapping> selectList(@Param("query") AppSwaggerMapping query);
}
