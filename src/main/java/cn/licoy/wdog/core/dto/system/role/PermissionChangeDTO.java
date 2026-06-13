package cn.licoy.wdog.core.dto.system.role;

import lombok.Data;
import org.hibernate.validator.constraints.NotBlank;

import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 权限变更请求DTO
 */
@Data
public class PermissionChangeDTO {

    @NotBlank(message = "角色ID不能为空")
    private String roleId;

    @NotNull(message = "资源ID列表不能为空")
    private List<String> resourceIds;

}
