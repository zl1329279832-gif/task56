package cn.licoy.wdog.core.dto.system.role;

import cn.licoy.wdog.core.entity.system.SysResource;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.hibernate.validator.constraints.NotBlank;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

/**
 * 角色资源变更请求体
 */
@Data
@ApiModel(value = "角色资源变更请求")
public class RoleResourceUpdateDTO implements Serializable {

    @NotBlank(message = "角色ID不能为空")
    @ApiModelProperty(value = "角色ID", required = true)
    private String roleId;

    @NotNull(message = "资源列表不能为空")
    @ApiModelProperty(value = "新的资源列表", required = true)
    private List<SysResource> resources;

    private static final long serialVersionUID = 1L;
}
