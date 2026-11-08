package com.kbconsole.series;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UpdateSeriesRequest {
    private String name;
    private String description;
    private Long deptId;
    private Long rowVersion;
}
