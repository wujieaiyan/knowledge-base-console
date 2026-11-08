package com.kbconsole.series;

import com.kbconsole.config.ConfigPayload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateSeriesRequest {
    @NotBlank(message = "请输入知识库名称")
    @Size(min = 2, max = 50, message = "名称需为 2-50 个字符")
    private String name;

    private Long deptId;

    @Size(max = 200, message = "简介不能超过 200 个字符")
    private String description;

    private String indexStrategy;
    private Boolean draft;
    private ConfigPayload config;
}
