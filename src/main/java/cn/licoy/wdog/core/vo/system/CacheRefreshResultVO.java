package cn.licoy.wdog.core.vo.system;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 权限缓存刷新结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ApiModel(value = "权限缓存刷新结果")
public class CacheRefreshResultVO implements Serializable {

    @ApiModelProperty(value = "角色ID")
    private String roleId;

    @ApiModelProperty(value = "角色名称")
    private String roleName;

    @ApiModelProperty(value = "已刷新缓存的用户ID列表")
    private List<String> refreshedUserIds;

    @ApiModelProperty(value = "已刷新缓存的用户数量")
    private int refreshedUserCount;

    @ApiModelProperty(value = "Shiro过滤链是否已重载")
    private boolean filterChainReloaded;

    @ApiModelProperty(value = "刷新时间")
    private Date refreshTime;

    @ApiModelProperty(value = "状态：SUCCESS-成功, NO_IMPACT-无影响变更")
    private String status;

    private static final long serialVersionUID = 1L;
}
